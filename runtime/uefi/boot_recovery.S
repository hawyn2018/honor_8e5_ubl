/* SPDX-License-Identifier: GPL-3.0-only */
#include "boot_recovery.h"
.macro BR_LOCATE
    adr x10, br_config
    adr x17, br_entry
    ldr x9, [x10, #BR_CFG_SELF_RVA]
    sub x17, x17, x9
    ldr x16, [x10, #BR_CFG_STATE_RVA]
    add x16, x17, x16
.endm

.section .text.br_entry,"ax"
.balign 16
.global br_entry
.hidden br_entry
br_entry:
    bti c
    BR_LOCATE
    stp x0, x1, [x16, #BR_STATE_HANDLE]
    str x17, [x16, #BR_STATE_BASE]
    mov x9, sp
    str x9, [x16, #BR_STATE_SP]
    mrs x9, nzcv
    str x9, [x16, #BR_STATE_NZCV]
    stp x18, x19, [x16, #96]
    stp x20, x21, [x16, #112]
    stp x22, x23, [x16, #128]
    stp x24, x25, [x16, #144]
    stp x26, x27, [x16, #160]
    stp x28, x29, [x16, #176]
    str x30, [x16, #192]
    stp d8, d9, [x16, #208]
    stp d10, d11, [x16, #224]
    stp d12, d13, [x16, #240]
    stp d14, d15, [x16, #256]
    mov x0, x16
    mov x1, x10
    bl br_initialize
    b br_loop

.section .text.br_control,"ax"
.balign 16
.global br_request
.hidden br_request
/* x0=reason, x1=status, x2=site. Nonlocal restart uses only our root context. */
br_request:
    bti c
    BR_LOCATE
    str x0, [x16, #BR_STATE_REASON]
    str x1, [x16, #BR_STATE_STATUS]
    str x2, [x16, #BR_STATE_SITE]
    mov x0, x16
    mov x1, x10
    bl br_cleanup_event
    b br_restore_and_record

br_restore_and_record:
    BR_LOCATE
    ldr x9, [x16, #BR_STATE_SP]
    mov sp, x9
    ldp x18, x19, [x16, #96]
    ldp x20, x21, [x16, #112]
    ldp x22, x23, [x16, #128]
    ldp x24, x25, [x16, #144]
    ldp x26, x27, [x16, #160]
    ldp x28, x29, [x16, #176]
    ldr x30, [x16, #192]
    ldp d8, d9, [x16, #208]
    ldp d10, d11, [x16, #224]
    ldp d12, d13, [x16, #240]
    ldp d14, d15, [x16, #256]
    ldr x9, [x16, #BR_STATE_NZCV]
    msr nzcv, x9
    mov x0, x16
    mov x1, x10
    bl br_note_failure
    b br_loop

br_loop:
    BR_LOCATE
    mov x0, x16
    mov x1, x10
    bl br_prepare_retry
    BR_LOCATE
    ldp x0, x1, [x16, #BR_STATE_HANDLE]
    ldr x9, [x16, #BR_STATE_NZCV]
    msr nzcv, x9
    /* Genuine BL/RET: the relocated original prefix signs this return LR. */
    bl br_original_entry
    mov x1, x0
    mov x0, #BR_REASON_ENTRY_RETURN
    adr x2, br_original_entry
    b br_request

.balign 16
.global br_forced
.hidden br_forced
br_forced:
    bti c
    BR_LOCATE
    ldr x0, [x16, #BR_STATE_FORCE]
    ret

.balign 16
.global br_mark_only
.hidden br_mark_only
/* Normal ABI returning helper: records a blocked default-slot initialization. */
br_mark_only:
    bti c
    stp x29, x30, [sp, #-16]!
    BR_LOCATE
    str x0, [x16, #BR_STATE_REASON]
    str x1, [x16, #BR_STATE_STATUS]
    str x2, [x16, #BR_STATE_SITE]
    mov x0, x16
    mov x1, x10
    bl br_note_failure
    ldp x29, x30, [sp], #16
    ret

.balign 16
.global br_manual_allowed
.hidden br_manual_allowed
br_manual_allowed:
    bti c
    BR_LOCATE
    ldr x0, [x16, #BR_STATE_MANUAL_DEPTH]
    ret

.balign 16
.global br_boot_scope
.hidden br_boot_scope
br_boot_scope:
    bti c
    BR_LOCATE
    ldr x0, [x16, #BR_STATE_BOOT_SCOPE]
    ret

.balign 16
.global br_fastboot_enter
.hidden br_fastboot_enter
br_fastboot_enter:
    bti c
    BR_LOCATE
    str xzr, [x16, #BR_STATE_BOOT_SCOPE]
    ret

.balign 16
.global br_defer
.hidden br_defer
/* Event callback path: never unwind a firmware callback, change TPL, or access NV. */
br_defer:
    bti c
    BR_LOCATE
    str x0, [x16, #BR_STATE_REASON]
    str x1, [x16, #BR_STATE_STATUS]
    str x2, [x16, #BR_STATE_SITE]
    mov x9, #1
    str x9, [x16, #BR_STATE_PENDING]
    str x9, [x16, #BR_STATE_FORCE]
    ret

.balign 16
.global br_pending_value
.hidden br_pending_value
br_pending_value:
    bti c
    BR_LOCATE
    ldr x0, [x16, #BR_STATE_PENDING]
    ret

.balign 16
.global br_pending
.hidden br_pending
/* Called only from an ordinary application-TPL checkpoint, never a callback. */
br_pending:
    bti c
    BR_LOCATE
    ldr x9, [x16, #BR_STATE_PENDING]
    cbz x9, 1f
    mov x0, x16
    mov x1, x10
    bl br_cleanup_event
    b br_restore_and_record
1:  ret

.balign 16
.global br_bootlinux
.hidden br_bootlinux
br_bootlinux:
    bti c
    sub sp, sp, #128
    stp x0, x1, [sp]
    stp x2, x3, [sp, #16]
    stp x4, x5, [sp, #32]
    stp x6, x7, [sp, #48]
    stp x29, x30, [sp, #64]
    /* Native Fastboot clears this scope; AUTH restores the caller's scope.
     * Save it before before_bootlinux temporarily marks the native handoff.
     */
    BR_LOCATE
    ldr x9, [x16, #BR_STATE_BOOT_SCOPE]
    str x9, [sp, #80]
    bl br_pending
    BR_LOCATE
    mov x0, x16
    mov x1, x10
    bl br_before_bootlinux
    BR_LOCATE
    ldr x16, [x10, #BR_CFG_BOOTLINUX_RVA]
    add x16, x17, x16
    ldp x0, x1, [sp]
    ldp x2, x3, [sp, #16]
    ldp x4, x5, [sp, #32]
    ldp x6, x7, [sp, #48]
    blr x16
    stp x0, x1, [sp, #96]
    ldr x9, [sp, #80]
    cbnz x9, .Lbr_automatic_bootlinux_return
    /* A manual continue has a live native command frame and exit/cleanup
     * path. Record the returned status without restarting main, changing
     * failure counters, closing firmware events, or touching its exit flag.
     */
    BR_LOCATE
    ldr x9, [sp, #96]
    str x9, [x16, #BR_STATE_STATUS]
    str x9, [x16, #BR_STATE_MANUAL_BOOTLINUX_STATUS]
    ldr x9, [x16, #BR_STATE_MANUAL_BOOTLINUX_COUNT]
    add x9, x9, #1
    str x9, [x16, #BR_STATE_MANUAL_BOOTLINUX_COUNT]
    mov x9, #BR_REASON_BOOTLINUX_RETURN
    str x9, [x16, #BR_STATE_REASON]
    adr x9, br_bootlinux
    str x9, [x16, #BR_STATE_SITE]
    str x9, [x16, #BR_STATE_MANUAL_BOOTLINUX_SITE]
    ldr x9, [sp, #80]
    str x9, [x16, #BR_STATE_BOOT_SCOPE]
    ldp x0, x1, [sp, #96]
    ldp x29, x30, [sp, #64]
    add sp, sp, #128
    ret
.Lbr_automatic_bootlinux_return:
    ldp x0, x1, [sp, #96]
    mov x1, x0
    mov x0, #BR_REASON_BOOTLINUX_RETURN
    adr x2, br_bootlinux
    b br_request

.balign 16
.global br_set_active
.hidden br_set_active
br_set_active:
    bti c
    sub sp, sp, #96
    stp x0, x1, [sp]
    stp x2, x3, [sp, #16]
    stp x4, x5, [sp, #32]
    stp x6, x7, [sp, #48]
    stp x29, x30, [sp, #64]
    BR_LOCATE
    ldr x9, [x16, #BR_STATE_MANUAL_DEPTH]
    add x9, x9, #1
    str x9, [x16, #BR_STATE_MANUAL_DEPTH]
    ldr x16, [x10, #BR_CFG_SET_ACTIVE_RVA]
    add x16, x17, x16
    ldp x0, x1, [sp]
    ldp x2, x3, [sp, #16]
    ldp x4, x5, [sp, #32]
    ldp x6, x7, [sp, #48]
    blr x16
    stp x0, x1, [sp, #80]
    BR_LOCATE
    ldr x9, [x16, #BR_STATE_MANUAL_DEPTH]
    cbz x9, 1f
    sub x9, x9, #1
    str x9, [x16, #BR_STATE_MANUAL_DEPTH]
1:  ldp x0, x1, [sp, #80]
    ldp x29, x30, [sp, #64]
    add sp, sp, #96
    ret

.balign 16
.global br_auth
.hidden br_auth
/* The same native loader serves ordinary boot and manual Fastboot continue.
 * Its caller, not this scope wrapper, decides which returned statuses block
 * boot. Preserve warnings, native failure reports and their early returns.
 */
br_auth:
    bti c
    sub sp, sp, #128
    stp x0, x1, [sp]
    stp x2, x3, [sp, #16]
    stp x4, x5, [sp, #32]
    stp x6, x7, [sp, #48]
    stp x29, x30, [sp, #64]
    BR_LOCATE
    ldr x9, [x16, #BR_STATE_BOOT_SCOPE]
    str x9, [sp, #80]
    mov x9, #1
    str x9, [x16, #BR_STATE_BOOT_SCOPE]
    ldr x16, [x10, #BR_CFG_AUTH_RVA]
    add x16, x17, x16
    ldp x0, x1, [sp]
    ldp x2, x3, [sp, #16]
    ldp x4, x5, [sp, #32]
    ldp x6, x7, [sp, #48]
    blr x16
    stp x0, x1, [sp, #96]
    BR_LOCATE
    ldr x9, [sp, #80]
    str x9, [x16, #BR_STATE_BOOT_SCOPE]
    ldp x0, x1, [sp, #96]
    ldp x29, x30, [sp, #64]
    add sp, sp, #128
    ret

.balign 16
.global br_original_entry
.hidden br_original_entry
/* Filled by the Kotlin emitter: original BTI/PAC prefix, displaced instruction,
 * then a genuine branch to the remainder of the original entry function. */
br_original_entry:
    .rept 16
    nop
    .endr

.section .br.config,"a"
.balign 16
.global br_config
.hidden br_config
br_config:
    .zero BR_CFG_BYTES
