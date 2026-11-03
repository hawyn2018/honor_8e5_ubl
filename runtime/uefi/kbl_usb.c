#include "kbl_usb.h"

_Static_assert(sizeof(size_t) == 8, "KBL USB binding requires 64-bit UINTN");
_Static_assert(offsetof(struct kbl_usb_outcome, endpoint) == 4, "USB endpoint ABI");
_Static_assert(offsetof(struct kbl_usb_outcome, completed) == 8, "USB length ABI");
_Static_assert(offsetof(struct kbl_usb_outcome, buffer) == 16, "USB pointer ABI");
_Static_assert(sizeof(struct kbl_usb_outcome) == 24, "USB outcome ABI");

static void zero(void *p, size_t n) {
    volatile unsigned char *d = p;
    while (n--) *d++ = 0;
}
static void release(struct kbl_usb_session *s) {
    if (s->claimed) s->ops.release_tx(s->ops.opaque);
    s->claimed = 0;
}
static void failed(struct kbl_usb_session *s, int error) {
    if (!s->last_error) s->last_error = error;
}
int kbl_usb_init(struct kbl_usb_session *s, const struct kbl_usb_ops *o,
                 size_t max_bytes, uint64_t timeout, uint64_t abort_timeout, unsigned limit) {
    if (!s || !o || !max_bytes || !timeout || !abort_timeout || !limit) return KBL_USB_ARGUMENT;
    if (s->initialized || s->pending || s->claimed) return KBL_USB_STATE;
    if (!o->claim_tx || !o->release_tx || !o->allocate || !o->free ||
        !o->send || !o->event || !o->now_us || !o->forward) return KBL_USB_UNAVAILABLE;
    s->ops = *o;
    s->max_bytes = max_bytes;
    s->timeout_us = timeout;
    s->abort_timeout_us = abort_timeout;
    s->poll_limit = limit;
    s->initialized = 1;
    return KBL_USB_OK;
}
int kbl_usb_begin(struct kbl_usb_session *s) {
    if (!s || !s->initialized) return KBL_USB_UNAVAILABLE;
    if (s->pending || s->claimed) return KBL_USB_STATE;
    if (s->ops.claim_tx(s->ops.opaque) != 0) return KBL_USB_STATE;
    s->claimed = 1;
    return KBL_USB_OK;
}
static int terminal(struct kbl_usb_session *s, const struct kbl_usb_event *e) {
    const struct kbl_usb_outcome *t = &e->data.transfer;
    if (t->status != KBL_USB_COMPLETE) failed(s, KBL_USB_TRANSFER);
    if (t->status == KBL_USB_COMPLETE && t->completed != s->size) failed(s, KBL_USB_TRANSFER);
    /* Completion itself proves buffer ownership has ended, even when status
     * is CANCELLED/error/short. Forward while the pointer is still valid. */
    if (s->final_response || s->last_error) {
        if (s->ops.forward(s->ops.opaque, e) != 0) failed(s, KBL_USB_FORWARD);
    }
    s->pending = 0;
    s->ops.free(s->ops.opaque, s->buffer);
    s->buffer = 0;
    s->size = 0;
    if (s->final_response || s->last_error) release(s);
    return s->last_error;
}
static int wait_pending(struct kbl_usb_session *s, uint64_t timeout) {
    unsigned poll;
    uint64_t start = s->ops.now_us(s->ops.opaque);
    for (poll = 0; poll < s->poll_limit; ++poll) {
        struct kbl_usb_event e;
        zero(&e, sizeof(e));
        if (s->ops.event(s->ops.opaque, &e) != 0) {
            failed(s, KBL_USB_IO); return KBL_USB_RETAINED;
        }
        if (e.kind == KBL_USB_EVENT_TRANSFER) {
            if (e.bytes < sizeof(struct kbl_usb_outcome) || e.bytes > sizeof(e.data)) {
                failed(s, KBL_USB_BAD_EVENT); return KBL_USB_RETAINED;
            }
            if (e.data.transfer.endpoint == 0x81 && e.data.transfer.buffer == s->buffer) {
                unsigned status = e.data.transfer.status;
                if (status == KBL_USB_COMPLETE || status == KBL_USB_CANCELLED || status == KBL_USB_FAILED)
                    return terminal(s, &e);
                if (status != KBL_USB_ACTIVE) {
                    failed(s, KBL_USB_BAD_EVENT); return KBL_USB_RETAINED;
                }
            } else {
                if (s->ops.forward(s->ops.opaque, &e) != 0) {
                    failed(s, KBL_USB_FORWARD); return KBL_USB_RETAINED;
                }
                if (e.data.transfer.endpoint == 0x81) {
                    failed(s, KBL_USB_FOREIGN_TX); return KBL_USB_RETAINED;
                }
            }
        } else if (e.kind != KBL_USB_EVENT_NONE) {
            if (e.bytes > sizeof(e.data) || (e.kind == KBL_USB_EVENT_STATE && e.bytes < sizeof(uint32_t))) {
                failed(s, KBL_USB_BAD_EVENT); return KBL_USB_RETAINED;
            }
            if (s->ops.forward(s->ops.opaque, &e) != 0) {
                failed(s, KBL_USB_FORWARD); return KBL_USB_RETAINED;
            }
            if (e.kind == KBL_USB_EVENT_STATE && e.data.state == KBL_USB_DISCONNECTED) {
                failed(s, KBL_USB_DISCONNECT); return KBL_USB_RETAINED;
            }
        }
        if ((uint64_t)(s->ops.now_us(s->ops.opaque) - start) >= timeout) {
            failed(s, KBL_USB_TIMEOUT); return KBL_USB_RETAINED;
        }
        if (s->ops.relax) s->ops.relax(s->ops.opaque);
    }
    failed(s, KBL_USB_TIMEOUT);
    return KBL_USB_RETAINED;
}
int kbl_usb_send(struct kbl_usb_session *s, const void *data, size_t bytes, int flags) {
    int result; size_t i;
    if (!s || !s->initialized) return KBL_USB_UNAVAILABLE;
    if (!data || !bytes || bytes > s->max_bytes ||
        (flags != KBL_USB_INTERMEDIATE && flags != KBL_USB_FINAL_RESPONSE)) return KBL_USB_ARGUMENT;
    if (!s->claimed || s->pending) return KBL_USB_STATE;
    s->last_error = 0; s->abort_requested = 0; s->final_response = flags;
    s->buffer = 0; s->size = bytes;
    if (s->ops.allocate(s->ops.opaque, bytes, &s->buffer) != 0 || !s->buffer) {
        s->size = 0; release(s); return KBL_USB_IO;
    }
    for (i = 0; i < bytes; ++i) ((volatile unsigned char *)s->buffer)[i] = ((const unsigned char *)data)[i];
    if (s->ops.send(s->ops.opaque, 0x81, bytes, s->buffer) != 0) {
        /* Binding contract: rejected submission did not queue or retain buffer. */
        s->ops.free(s->ops.opaque, s->buffer); s->buffer = 0; s->size = 0;
        release(s); return KBL_USB_IO;
    }
    s->pending = 1;
    result = wait_pending(s, s->timeout_us);
    if (!s->pending) return result;
    if (s->ops.abort) {
        s->abort_requested = 1;
        (void)s->ops.abort(s->ops.opaque, 0x81);
        /* Abort success only queues a request. Keep pumping until the actual
         * matching terminal outcome, or retain this session and DMA buffer. */
        result = wait_pending(s, s->abort_timeout_us);
        if (!s->pending) return result;
    }
    return KBL_USB_RETAINED;
}
int kbl_usb_reap(struct kbl_usb_session *s) {
    if (!s || !s->initialized) return KBL_USB_UNAVAILABLE;
    if (!s->pending) return KBL_USB_STATE;
    return wait_pending(s, s->abort_timeout_us);
}
int kbl_usb_buffer_pending(const struct kbl_usb_session *s) { return s && s->pending; }
