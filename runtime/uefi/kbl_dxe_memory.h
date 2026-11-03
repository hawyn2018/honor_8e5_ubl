/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_DXE_MEMORY_H
#define HNU_KBL_DXE_MEMORY_H
#include "kbl_efi_types.h"
#include "kbl_arch_memory.h"

/* PI DXE Services fallback when MemoryAttribute protocol is absent. This is
 * only for pages privately owned by the caller. It never allocates memory,
 * changes capabilities, installs a protocol, or changes cacheability bits. */
struct kbl_gcd_descriptor {
    uint64_t base,length,capabilities,attributes;
    uint32_t type,reserved;
    void *image,*device;
};
struct kbl_dxe_services {
    struct kefi_header header;
    void *add,*allocate,*free,*remove;
    kefi_status (*get)(uint64_t,struct kbl_gcd_descriptor *);
    kefi_status (*set)(uint64_t,uint64_t,uint64_t);
};
struct kbl_dxe_memory {
    struct kefi_memory_attributes api;
    struct kbl_dxe_services *services;
    struct kefi_memory_attributes *native;
    struct kefi_boot_services *bs;
    void *cpu;
    kefi_status (*cpu_set)(void *,uint64_t,uint64_t,uint64_t);
    struct kbl_arch_memory arch;
    unsigned strict,cpu_active;
    const char *provider,*inner_phase,*fallback_reason;
    uint64_t diagnostic_address,diagnostic_caps,diagnostic_before,diagnostic_requested;
    kefi_status diagnostic_status;
    unsigned diagnostic_caps_valid;
};
/* Zeroed output; only queries the real SystemTable configuration entries. */
int kbl_dxe_memory_init(struct kbl_dxe_memory *,const struct kefi_system_table *);
/* Driver-only compatibility wrapper. Native protocol remains preferred;
 * only UNSUPPORTED may switch to standard CPU_ARCH plus real stage-1 getter.
 * The ownership callback covers the allocation ledger, including restoration
 * of released pages retained after failed cleanup. No capabilities are set. */
int kbl_dxe_memory_compat_init(struct kbl_dxe_memory *,const struct kefi_system_table *,
    struct kefi_memory_attributes *,void *,int(*)(void *,uint64_t,uint64_t));
_Static_assert(sizeof(struct kbl_gcd_descriptor)==56,"PI GCD descriptor ABI");
_Static_assert(offsetof(struct kbl_dxe_services,get)==0x38,"DXE GetMemorySpaceDescriptor ABI");
_Static_assert(offsetof(struct kbl_dxe_services,set)==0x40,"DXE SetMemorySpaceAttributes ABI");
#endif
