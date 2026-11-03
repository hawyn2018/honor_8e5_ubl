/* SPDX-License-Identifier: MIT -- explicit RAM-only bootloader selection. */
#ifndef HNU_KBL_EFI_BOOTLOADER_H
#define HNU_KBL_EFI_BOOTLOADER_H
#include <stddef.h>
#include <stdint.h>
enum kbl_efi_bootloader_status {
    KBL_EFI_BL_OK = 0,
    KBL_EFI_BL_BAD_PE = -1,
    KBL_EFI_BL_NO_TARGET = -2,
    KBL_EFI_BL_AMBIGUOUS = -3
};
struct kbl_efi_bootloader_result {
    size_t file_offset;
    uint32_t load_rva, before, after;
    unsigned already_forced;
};
/* Caller owns a private RAM copy. Every validation/match completes before the
 * sole four-byte commit. Failure does not modify the supplied image. File size
 * is size_t without an artificial cap; RVA/image fields retain PE32+ width. */
int kbl_efi_force_bootloader(void *, size_t, struct kbl_efi_bootloader_result *);
const char *kbl_efi_bootloader_error(int);
#endif
