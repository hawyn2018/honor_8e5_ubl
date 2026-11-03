/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_DISPLAY_H
#define HNU_KBL_DISPLAY_H
#include "kbl_efi_types.h"
/* Microsoft EFI_DISPLAY_POWER_PROTOCOL, a published backward-compatible ABI.
 * GUID/function offsets are protocol identifiers, never device addresses.
 * https://learn.microsoft.com/windows-hardware/drivers/bringup/efi-display-power-protocol
 */
#define KBL_DISPLAY_POWER_GUID {0xf352021d,0x9593,0x4432,{0xbf,0x04,0x67,0xb9,0xf3,0xb7,0x60,0x08}}
#define KBL_DISPLAY_STATUS 0u
#define KBL_DISPLAY_OFF 1u
#define KBL_DISPLAY_ON 2u
struct kbl_display_power_protocol {
    uint32_t revision;
    kefi_status (*set)(struct kbl_display_power_protocol *,unsigned);
    kefi_status (*get)(struct kbl_display_power_protocol *,unsigned *);
};
struct kbl_display {
    struct kefi_system_table *st;
    unsigned provider,revision,state,saved,saved_valid,observed,set_attempted;
    kefi_status locate_status,get_status,set_status;
};
/* These functions are called only for an explicit screen operation. */
kefi_status kbl_display_request(struct kbl_display *,struct kefi_system_table *,unsigned operation);
int kbl_display_describe(const struct kbl_display *,char *,size_t capacity);
/* Runtime-services ResetSystem(EfiResetShutdown), only explicit poweroff. */
kefi_status kbl_power_shutdown(struct kefi_system_table *);
_Static_assert(offsetof(struct kbl_display_power_protocol,set)==8,"display power set ABI");
_Static_assert(offsetof(struct kbl_display_power_protocol,get)==16,"display power get ABI");
#endif
