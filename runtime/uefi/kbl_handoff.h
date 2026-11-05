/* SPDX-License-Identifier: MIT -- private ABI between two separate EFI images. */
#ifndef KBL_HANDOFF_H
#define KBL_HANDOFF_H
#include "kbl_efi_run.h"
#define KBL_HANDOFF_MAGIC UINT64_C(0x4353544152543031)
struct kbl_handoff {
    uint64_t magic;
    struct kbl_efi_launcher target;
    struct kbl_efi_run_result result;
    int outcome;
    unsigned entered;
};
#endif
