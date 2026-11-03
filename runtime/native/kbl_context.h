/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_CONTEXT_H
#define HNU_KBL_CONTEXT_H
#include <stdint.h>
/* Keep offsets synchronized with kbl_context_switch.S. */
struct kbl_cpu_context {
    uint64_t x19_x30[12];
    uint64_t sp, nzcv;
} __attribute__((aligned(16)));
_Static_assert(sizeof(struct kbl_cpu_context) == 112, "context size");
_Static_assert(__builtin_offsetof(struct kbl_cpu_context, sp) == 96, "SP offset");
/* The first SDK profile is general-registers-only. Runtime and programs never
 * alter FP/SIMD state or interrupt masks; no FP permission is assumed/enabled. */
void kbl_context_switch(struct kbl_cpu_context *from, const struct kbl_cpu_context *to);
void kbl_context_seed(struct kbl_cpu_context *);
void kbl_fiber_trampoline(void);
#endif
