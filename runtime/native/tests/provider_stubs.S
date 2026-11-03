/* SPDX-License-Identifier: MIT */
.text
.macro hook name
.global \name
.type \name,%function
\name:
    ret
.endm
hook test_allocate
hook test_release
hook test_elf_allocate
hook test_elf_release
hook test_protect
hook test_icache
hook test_live_allocations
hook test_fail_after
hook test_fail_clear
.global memset
.type memset,%function
memset:
    mov x3,x0
    cbz x2,2f
1:
    strb w1,[x3],#1
    subs x2,x2,#1
    b.ne 1b
2:
    ret
.global test_current_el
.type test_current_el,%function
test_current_el:
    mrs x0, CurrentEL
    lsr x0,x0,#2
    and x0,x0,#3
    ret
.section .note.GNU-stack,"",%progbits
