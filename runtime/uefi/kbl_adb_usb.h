/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_ADB_USB_H
#define HNU_KBL_ADB_USB_H
#include "kbl_usb_native.h"
#include "../adb/kbl_adb.h"
#define KBL_ADB_USB_CONFIGS 4u
#define KBL_ADB_USB_CONFIG_BYTES 1024u
#define KBL_ADB_USB_STRINGS 32u
#define KBL_ADB_USB_STRING_BYTES 256u
#define KBL_ADB_USB_DMA_BYTES 65536u
/* Composite debug identity only; original/pre-START/rollback IDs are retained.
 * Keep these stable for a descriptor schema, and bump the revision when that
 * schema changes so Windows can query fresh Microsoft OS descriptors. */
#ifndef KBL_ADB_USB_DEBUG_PID
#define KBL_ADB_USB_DEBUG_PID 0xd0d1u
#endif
#ifndef KBL_ADB_USB_DEBUG_BCD_DEVICE
#define KBL_ADB_USB_DEBUG_BCD_DEVICE 0x0102u
#endif
#if KBL_ADB_USB_DEBUG_PID > 0xffffu || KBL_ADB_USB_DEBUG_BCD_DEVICE > 0xffffu
#error "USB debug PID/revision must fit a 16-bit descriptor field"
#endif
struct kbl_usb_descriptor_set {
    void *device;void **configs;void *ss_device;void **ss_configs;
    void *qualifier;void *bos;uint8_t string_count;void **strings;
};
struct kbl_adb_usb_descriptors {
    struct kbl_usb_descriptor_set original,composite;
    unsigned configs,strings,has_ss;uint8_t adb_interface,adb_out,adb_in,fastboot_interface;
    unsigned char device[18],ss_device[18],qualifier[10],bos[512];
    unsigned char old_device[18],old_ss_device[18],old_qualifier[10],old_bos[512];
    unsigned char config[KBL_ADB_USB_CONFIGS][KBL_ADB_USB_CONFIG_BYTES];
    unsigned char ss_config[KBL_ADB_USB_CONFIGS][KBL_ADB_USB_CONFIG_BYTES];
    unsigned char old_config[KBL_ADB_USB_CONFIGS][KBL_ADB_USB_CONFIG_BYTES];
    unsigned char old_ss_config[KBL_ADB_USB_CONFIGS][KBL_ADB_USB_CONFIG_BYTES];
    unsigned char string[KBL_ADB_USB_STRINGS][KBL_ADB_USB_STRING_BYTES];
    void *config_ptr[KBL_ADB_USB_CONFIGS],*ss_config_ptr[KBL_ADB_USB_CONFIGS];
    void *old_config_ptr[KBL_ADB_USB_CONFIGS],*old_ss_config_ptr[KBL_ADB_USB_CONFIGS];
    void *string_ptr[KBL_ADB_USB_STRINGS];
};
/* The span callback proves each source range before it is dereferenced.
 * No USB/provider call occurs in this builder. Original descriptors are deep
 * copied for failure-only rollback. Successful STOP retains composite storage. */
int kbl_adb_usb_descriptors(struct kbl_adb_usb_descriptors *,const struct kbl_usb_descriptor_set *,
                           void *,int(*span)(void *,const void *,size_t));

/* Standard Qualcomm UsbfnIo ABI. Values are interface layout, not addresses. */
struct kbl_usbfn_protocol {
    uint32_t revision;
    void *detect,*configure,*max_packet,*get_info,*get_vid_pid;
    kefi_status (*abort_transfer)(struct kbl_usbfn_protocol *,uint8_t,unsigned);
    void *get_stall,*set_stall;
    kefi_status (*event)(struct kbl_usbfn_protocol *,uint32_t *,size_t *,void *);
    kefi_status (*transfer)(struct kbl_usbfn_protocol *,uint8_t,unsigned,size_t *,void *);
    kefi_status (*max_transfer)(struct kbl_usbfn_protocol *,size_t *);
    void *allocate,*free,*start_controller,*stop_controller,*set_policy,*get_policy,*configure_ex,*update_ss;
};
struct kbl_usbfn_result {size_t completed;uint32_t status;uint8_t endpoint;uint32_t direction;void *buffer;};
enum {KBL_ADB_USB_IDLE,KBL_ADB_USB_ARMED,KBL_ADB_USB_ENUMERATING,KBL_ADB_USB_ACTIVE,
      KBL_ADB_USB_STOPPING,KBL_ADB_USB_RESIDENT,KBL_ADB_USB_ROLLBACK,KBL_ADB_USB_FAILED};
struct kbl_adb_usb_environment {
    void *opaque;struct kbl_usb_device_protocol *device;struct kbl_usbfn_protocol *usbfn;
    /* Returns one only after successful explicit START and its original
     * native ACK completion has been forwarded, with exact Fastboot RX/DMA
     * ownership quiesced. May defer; it must not guess based on Send success. */
    int (*activation_fence)(void *);
    /* Install/remove only the EP0 control router, after activation_fence.
     * Caller validates/restores the shared provider EventHandler hook/slot.
     * Successful composite activation keeps this hook resident until reboot. */
    int (*control_router)(void *,int enable);
    /* Single owner invokes these adapters; no other queue consumer. */
    int (*feed)(void *,const void *,size_t,size_t *);
    int (*poll)(void *,unsigned);
    int (*output)(void *,const void **,size_t *);
    int (*output_done)(void *,size_t,int);
    int (*disconnect)(void *);
};
struct kbl_adb_usb {
    struct kbl_adb_usb_environment env;
    struct kbl_adb_usb_descriptors descriptors;
    unsigned phase,initialized,composite_started,router_installed,connected,stop_requested,fault;
    unsigned configuration_uncertain,stop_stalled;
    void *rx,*tx,*control;size_t rx_used,rx_at,tx_bytes,control_bytes,transfer_limit,packet_bytes,frame_remaining;
    unsigned rx_pending,tx_pending,rx_abort,tx_abort,control_pending,control_direction,control_abort,control_stage;
    unsigned control_cancelled,control_queued;unsigned char control_setup[8];
    unsigned disconnected,rx_held,reconfig_calls;
    kefi_status last_status;
};
/* Called only by the successful explicit START path. init/arm have no
 * controller/DMA side effects; step waits for activation_fence before StartEx. */
int kbl_adb_usb_init(struct kbl_adb_usb *,const struct kbl_adb_usb_environment *,
    const struct kbl_usb_descriptor_set *,void *,int(*span)(void *,const void *,size_t));
int kbl_adb_usb_arm(struct kbl_adb_usb *);
int kbl_adb_usb_step(struct kbl_adb_usb *,unsigned budget_us);
/* 1 consumed an exact owned event, 0 not owned, negative malformed/error. State
 * events always remain available to Fastboot (return0), never swallowed. */
int kbl_adb_usb_event(struct kbl_adb_usb *,const struct kbl_usb_event *);
/* Lower UsbfnIo event hook, after calling the saved original EventHandler
 * once. SETUP accepts standalone8 or a payload union >=8 and reads only the
 * first8 bytes; caller proves returned bytes <= original buffer capacity.
 * Only exact MS OS requests or owned EP0 DMA completions are consumed. */
int kbl_adb_usb_fn_event(struct kbl_adb_usb *,uint32_t,size_t,const void *);
void kbl_adb_usb_request_stop(struct kbl_adb_usb *);
int kbl_adb_usb_dma_quiet(const struct kbl_adb_usb *);
/* A live composite config references this driver even after ADB stops. */
int kbl_adb_usb_can_release(const struct kbl_adb_usb *);
_Static_assert(sizeof(struct kbl_usb_descriptor_set)==64,"UsbDevice descriptor-set ABI");
_Static_assert(offsetof(struct kbl_usb_descriptor_set,strings)==56,"descriptor strings ABI");
_Static_assert(offsetof(struct kbl_usbfn_protocol,event)==0x48,"UsbfnIo EventHandler ABI");
_Static_assert(offsetof(struct kbl_usbfn_protocol,transfer)==0x50,"UsbfnIo Transfer ABI");
_Static_assert(sizeof(struct kbl_usbfn_result)==32,"Usbfn transfer outcome ABI");
#endif
