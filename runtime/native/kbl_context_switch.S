/* SPDX-License-Identifier: MIT */
#ifdef _WIN32
#define KBL_TYPE(name)
#define KBL_SIZE(name)
#else
#define KBL_TYPE(name) .type name, %function
#define KBL_SIZE(name) .size name, .-name
#endif
.text
.balign 4
.global kbl_context_switch
KBL_TYPE(kbl_context_switch)
kbl_context_switch:
    stp x19, x20, [x0, #0]
    stp x21, x22, [x0, #16]
    stp x23, x24, [x0, #32]
    stp x25, x26, [x0, #48]
    stp x27, x28, [x0, #64]
    stp x29, x30, [x0, #80]
    mov x2, sp
    str x2, [x0, #96]
    mrs x2, nzcv
    str x2, [x0, #104]
    ldr x2, [x1, #104]
    msr nzcv, x2
    ldr x2, [x1, #96]
    mov sp, x2
    ldp x19, x20, [x1, #0]
    ldp x21, x22, [x1, #16]
    ldp x23, x24, [x1, #32]
    ldp x25, x26, [x1, #48]
    ldp x27, x28, [x1, #64]
    ldp x29, x30, [x1, #80]
    ret
KBL_SIZE(kbl_context_switch)

.balign 4
.global kbl_context_seed
KBL_TYPE(kbl_context_seed)
kbl_context_seed:
    mrs x1, nzcv
    str x1, [x0, #104]
    ret
KBL_SIZE(kbl_context_seed)

.balign 4
.global kbl_fiber_trampoline
KBL_TYPE(kbl_fiber_trampoline)
kbl_fiber_trampoline:
    mov x0, x19
    bl kbl_native_job_entry
    /* The C job entry never returns. */
    brk #0
KBL_SIZE(kbl_fiber_trampoline)
#ifndef _WIN32
.section .note.GNU-stack,"",%progbits
#endif
