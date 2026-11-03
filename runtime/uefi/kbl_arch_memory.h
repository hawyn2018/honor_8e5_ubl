/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_ARCH_MEMORY_H
#define HNU_KBL_ARCH_MEMORY_H
#include "kbl_efi_types.h"
struct kbl_arch_config {
    uint64_t sctlr,tcr,ttbr0,mair,mmfr3,tcr2,mmfr1,pan;
    unsigned el;
};
struct kbl_arch_page {
    uint64_t physical,attributes,cache,signature;
    unsigned forced_ro,forced_xp,bti;
};
struct kbl_arch_memory {
    void *opaque;
    int (*configuration)(void *,struct kbl_arch_config *);
    int (*read_table)(void *,uint64_t,uint64_t *);
    int (*owns)(void *,uint64_t,uint64_t);
    const char *failure;
    uint64_t last_address,last_before,last_requested;
    unsigned tainted;
};
/* Native callbacks read real EL1 registers and real table entries. read_table
 * saves/restores PAR and DAIF, verifies Normal identity-readable table memory
 * with AT, and performs no firmware callback while interrupts are masked. */
void kbl_arch_memory_native(struct kbl_arch_memory *,void *,int(*)(void *,uint64_t,uint64_t));
int kbl_arch_memory_profile(struct kbl_arch_memory *);
int kbl_arch_memory_page(struct kbl_arch_memory *,uint64_t,struct kbl_arch_page *);
int kbl_arch_memory_get(struct kbl_arch_memory *,uint64_t,uint64_t,uint64_t *);
kefi_status kbl_arch_memory_change(struct kbl_arch_memory *,uint64_t,uint64_t,uint64_t,int,
    void *,kefi_status(*)(void *,uint64_t,uint64_t,uint64_t));
#endif
