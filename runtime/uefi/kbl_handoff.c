/* SPDX-License-Identifier: MIT
 * Independent EFI application. No KBL native stack, runtime, USB hook or
 * callbacks are needed to start the already prepared target. The driver and
 * parent image remain mapped because their outer firmware call frames exist.
 * Firmware owns this application's code/stack for the whole StartImage call.
 */
#include "kbl_handoff.h"
static unsigned handoff_el(void *unused){
    (void)unused;
#if defined(KBL_HANDOFF_TEST)
    extern unsigned kbl_handoff_test_el(void);return kbl_handoff_test_el();
#elif defined(__aarch64__)
    uint64_t el;__asm__ volatile("mrs %0, CurrentEL":"=r"(el));return(unsigned)((el>>2)&3);
#else
    return 0;
#endif
}
kefi_status KblHandoffMain(kefi_handle image,struct kefi_system_table *st){
    static const struct kefi_guid guid=KEFI_LOADED_GUID;
    struct kefi_loaded_image *loaded=0;
    if(!image||!st||st->header.signature!=KEFI_SYSTEM_SIGNATURE||!st->boot_services||
       !st->boot_services->handle_protocol||st->boot_services->handle_protocol(image,&guid,(void**)&loaded)||
       !loaded||loaded->options_bytes!=sizeof(struct kbl_handoff)||!loaded->options)return KEFI_INVALID;
    struct kbl_handoff *h=loaded->options;
    if(h->magic!=KBL_HANDOFF_MAGIC||h->entered||h->target.system!=st||!h->target.prepared||
       !h->target.child||h->target.active||handoff_el(0)!=h->target.expected_el)return KEFI_ACCESS_DENIED;
    h->entered=1;
    h->target.current_el=handoff_el;h->target.opaque=0;
    h->outcome=kbl_efi_start_prepared(&h->target,&h->result);
    return h->outcome?KEFI_ERROR(7):h->result.status;
}
