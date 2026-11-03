/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_PARENT_SHIM_H
#define HNU_KBL_PARENT_SHIM_H
#include "kbl_service.h"
#define KBL_PARENT_MAGIC UINT64_C(0x31544e524150424b)
#define KBL_PARENT_ABI 1u
#define KBL_PARENT_STORE_ABI UINT64_C(1)
/* Serialized little-endian data. Every address is an RVA in the parent PE.
 * No pointer requires a PE base relocation. Never use firmware version tables. */
struct kbl_parent_config {
 uint64_t magic;
 uint32_t abi,bytes;
 uint64_t image_global,system_global,boot_global,runtime_global;
 uint64_t okay_rva,fail_rva,driver_rva,driver_bytes,state_rva,flags;
 uint64_t usb_facts_rva,generic_getvar_rva,generic_fetch_rva;
};
struct kbl_parent_state {
 uint64_t phase; /* 0 empty, 1 own verified protocol, 2 cleanup required */
 void *image;
 struct kbl_service_protocol *service;
 uint32_t usb_bound,started; /* started only after a successful START final */
 uint32_t registered_mask,registering; /* touched only by exact explicit runtime/display commands */
};
_Static_assert(sizeof(struct kbl_parent_config)==120,"parent config ABI");
_Static_assert(offsetof(struct kbl_parent_config,usb_facts_rva)==96,"USB facts ABI");
_Static_assert(sizeof(struct kbl_parent_state)==40,"parent state ABI");
_Static_assert(sizeof(struct kbl_usb_parent_facts)==88,"USB descriptor ABI");
/* Ordinary commands never call this entry. Only registered KBL wrappers do.
 * X0 cfg, X1 parentBase, W2 op, X3 native suffix, X4/X5 proven download args.
 * A tail-branch wrapper preserves the original native dispatcher return LR. */
void kbl_parent_dispatch(const struct kbl_parent_config *,uintptr_t,unsigned,
                         const char *,const void *,size_t);
/* Private lazy-registration metadata. All addresses are parent-relative;
 * fields are independently proved from NativeRegister and its consumer. */
#define KBL_LAZY_MAGIC UINT64_C(0x31595a414c4c424b)
struct kbl_parent_lazy_config {
 uint64_t magic;
 uint32_t bytes,count;
 uint64_t native_register_rva,head_rva,entries_rva;
 uint32_t next_offset,prefix_offset,length_offset,handler_offset;
};
_Static_assert(sizeof(struct kbl_parent_lazy_config)==56,"lazy metadata ABI");
void kbl_parent_lazy_start(const struct kbl_parent_config *,uintptr_t,
                          const struct kbl_parent_lazy_config *,const char *,unsigned);
#endif
