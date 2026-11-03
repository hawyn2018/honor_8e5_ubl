/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_USB_NATIVE_H
#define HNU_KBL_USB_NATIVE_H
#include "kbl_efi_types.h"
#include "kbl_usb.h"
#include "kbl_service.h"

/* Standard verified Qualcomm EFI_USB_DEVICE_PROTOCOL ABI (no This argument).
 * The addresses below are runtime inputs resolved from the actual firmware,
 * never fixed source-image offsets or a model/version lookup table. */
struct kbl_usb_device_protocol {
    uint64_t revision;
    void *start;
    kefi_status (*send)(uint8_t,size_t,void *);
    kefi_status (*handle_event)(uint32_t *,size_t *,void *);
    kefi_status (*allocate)(size_t,void **);
    kefi_status (*free)(void *);
    void *stop;
    kefi_status (*abort)(uint8_t);
    void *stall,*start_ex;
};
struct kbl_native_usb_environment {
    void *opaque;
    struct kbl_usb_device_protocol **fbd_protocol_slot;
    void *native_ack_buffer;
    /* Required exact-handler identity/exclusive-owner check. Actual TX idle
     * is established separately by the live observer, never this callback. */
    int (*enter_fetch_fence)(void *);
    void (*leave_fetch_fence)(void *);
    /* Proven normal-command RX classification using the original command
     * buffer and native command/download state. No guessed state values. */
    int (*is_command_rx)(void *,const struct kbl_usb_outcome *);
    /* Calls existing native Okay/Fail, whose verified shared TX buffer and
     * Send behavior must match native_ack_buffer and the Fbd slot above. */
    void (*native_final)(void *,int,const char *);
    uint64_t (*now_us)(void *); /* passive monotonic, no new timer/kernel */
    void (*relax)(void *);
};
#define KBL_NATIVE_USB_QUEUE 32u
struct kbl_native_usb_extensions {
    void *opaque;
    /* Exact private endpoint events only; zero leaves the event unchanged. */
    int (*event)(void *,const struct kbl_usb_event *);
    /* Called only on a no-event return to the native Fastboot loop. */
    void (*idle)(void *);
};
struct kbl_native_usb_binding {
    struct kbl_native_usb_environment env;
    struct kbl_usb_device_protocol *original,clone;
    struct kbl_usb_session sender;
    struct kbl_firmware_link link;
    struct kbl_usb_event events[KBL_NATIVE_USB_QUEUE],injected;
    unsigned head,count,initialized,attached,in_call,have_injected,aborted,fault;
    unsigned final_call,final_queued,final_observed,native_sends;
    unsigned private_active,detach_requested,native_pending,ambiguous,idle_witness,command_ticket;
    void *native_pending_buffer;
    size_t native_pending_bytes;
    uint64_t rx_epoch,used_rx_epoch;
    uint64_t connection_epoch,native_tx_epoch;
    size_t final_bytes;
    void *dma;
    unsigned dma_released;
    uint64_t final_status;
    struct kbl_native_usb_extensions extensions;
    unsigned start_ack_wait,start_ack_queued,start_ack_complete,start_ack_forwarded;
    unsigned native_rx_pending,native_rx_abort;
    void *native_rx_buffer;size_t native_rx_bytes;
    kefi_status provider_event_status;
    kefi_status first_event_error;
    uint32_t provider_errors;
};
/* Single native owner: original protocol has no This parameter. A zeroed
 * binding is required. init has no provider side effects. observe is installed
 * only on explicit KBL start and initially has NO idle witness; the start's
 * native ACK and exact terminal completion establish it. Normal Send/events
 * are passed through, without running the kernel. A fresh command-RX epoch and
 * the exact FETCH wrapper are required before begin can send private DATA.
 * No USB Start/Stop/Locate or kernel call is made. */
int kbl_native_usb_init(struct kbl_native_usb_binding *,const struct kbl_native_usb_environment *,
                        size_t max_send,uint64_t timeout_us,uint64_t abort_timeout_us,unsigned poll_limit);
int kbl_native_usb_observe(struct kbl_native_usb_binding *);
int kbl_native_usb_begin(struct kbl_native_usb_binding *);
int kbl_native_usb_stop_observing(struct kbl_native_usb_binding *);
const struct kbl_firmware_link *kbl_native_usb_link(struct kbl_native_usb_binding *);
int kbl_native_usb_can_release(const struct kbl_native_usb_binding *);
int kbl_native_usb_extensions(struct kbl_native_usb_binding *,const struct kbl_native_usb_extensions *);
/* Arm before the explicit START final reply. Ready is based on its exact
 * successful native ACK terminal event, forwarded to the original loop.
 * First composite activation also aborts and awaits native EP1 RX; a resident
 * restart does not touch the existing Fastboot RX or USB configuration. */
int kbl_native_usb_arm_start_ack(struct kbl_native_usb_binding *);
int kbl_native_usb_start_ack_ready(struct kbl_native_usb_binding *,int quiesce_rx);
int kbl_native_usb_resume_rx(struct kbl_native_usb_binding *);

_Static_assert(offsetof(struct kbl_usb_device_protocol,send)==0x10,"UsbDevice.Send ABI");
_Static_assert(offsetof(struct kbl_usb_device_protocol,handle_event)==0x18,"UsbDevice.HandleEvent ABI");
_Static_assert(offsetof(struct kbl_usb_device_protocol,allocate)==0x20,"UsbDevice.Allocate ABI");
_Static_assert(offsetof(struct kbl_usb_device_protocol,free)==0x28,"UsbDevice.Free ABI");
_Static_assert(offsetof(struct kbl_usb_device_protocol,abort)==0x38,"UsbDevice.Abort ABI");
#endif
