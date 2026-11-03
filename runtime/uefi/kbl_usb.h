#ifndef HNU_KBL_USB_H
#define HNU_KBL_USB_H
#include <stddef.h>
#include <stdint.h>

/* Normalized binding for the existing Qualcomm USB device interface. This
 * module neither locates a firmware protocol nor starts/stops a controller.
 * The platform binding must prove the interface identity and ABI first.
 */
enum { KBL_USB_EVENT_NONE, KBL_USB_EVENT_STATE, KBL_USB_EVENT_TRANSFER, KBL_USB_EVENT_OEM };
enum { KBL_USB_ACTIVE, KBL_USB_COMPLETE, KBL_USB_CANCELLED, KBL_USB_FAILED };
enum { KBL_USB_CONNECTED, KBL_USB_DISCONNECTED };
enum { KBL_USB_INTERMEDIATE = 0, KBL_USB_FINAL_RESPONSE = 1 };
enum kbl_usb_result {
    KBL_USB_OK = 0, KBL_USB_ARGUMENT = -1, KBL_USB_UNAVAILABLE = -2,
    KBL_USB_STATE = -3, KBL_USB_IO = -4, KBL_USB_TIMEOUT = -5,
    KBL_USB_TRANSFER = -6, KBL_USB_FORWARD = -7, KBL_USB_RETAINED = -8,
    KBL_USB_FOREIGN_TX = -9, KBL_USB_BAD_EVENT = -10, KBL_USB_DISCONNECT = -11
};
struct kbl_usb_outcome {
    uint32_t status;
    uint8_t endpoint;
    uint8_t reserved[3];
    size_t completed;
    void *buffer;
};
/* On the verified AArch64 provider, outcome is 24 bytes and the largest
 * event union is 40 bytes. Preserve opaque OEM bytes for the original owner. */
struct kbl_usb_event {
    uint32_t kind;
    size_t bytes;
    union {
        uint32_t state;
        struct kbl_usb_outcome transfer;
        unsigned char opaque[40];
    } data;
};
struct kbl_usb_ops {
    void *opaque;
    /* claim_tx must establish exclusive response ownership AND that EP0x81
     * has no earlier transfer pending. No public GetTxIdle exists in this
     * protocol; a missing/unproved ownership fence is not assumed safe. */
    int (*claim_tx)(void *);
    void (*release_tx)(void *);
    int (*allocate)(void *, size_t, void **); /* matching protocol DMA allocator */
    void (*free)(void *, void *); /* matching free; binding must handle errors */
    int (*send)(void *, uint8_t endpoint, size_t bytes, void *buffer);
    int (*event)(void *, struct kbl_usb_event *);
    int (*abort)(void *, uint8_t endpoint); /* queued abort, NOT proof of completion */
    uint64_t (*now_us)(void *); /* bounded passive monotonic provider */
    void (*relax)(void *); /* optional bounded pause, not kernel scheduling */
    /* Non-TX events go to the native owner's decoded-event path/deferred
     * queue. Successful final TX completion must also restore native RX
     * rearm exactly once. Do not call a poll-new-event routine here. */
    int (*forward)(void *, const struct kbl_usb_event *);
};
struct kbl_usb_session {
    struct kbl_usb_ops ops;
    void *buffer;
    size_t size, max_bytes;
    uint64_t timeout_us, abort_timeout_us;
    unsigned poll_limit;
    int initialized, claimed, pending, abort_requested, final_response, last_error;
};

/* Caller supplies a zeroed session. init has zero provider side effects. */
int kbl_usb_init(struct kbl_usb_session *, const struct kbl_usb_ops *,
                 size_t max_bytes, uint64_t timeout_us, uint64_t abort_timeout_us,
                 unsigned poll_limit);
int kbl_usb_begin(struct kbl_usb_session *);
/* Input is copied to a protocol-allocated persistent TX buffer. It is freed
 * only after a matching terminal outcome, never just because Send/Abort=0.
 * FINAL_RESPONSE is only for the transaction's final OKAY/FAIL, not DATA or
 * intermediate INFO/payload. It forwards the completion to restore RX. */
int kbl_usb_send(struct kbl_usb_session *, const void *, size_t, int flags);
/* After RETAINED, continue bounded event handling without another Send or
 * duplicate Abort. Caller must retain this session and buffer until terminal
 * completion. It is invalid to free them on timeout/kbloff. */
int kbl_usb_reap(struct kbl_usb_session *);
int kbl_usb_buffer_pending(const struct kbl_usb_session *);
#endif
