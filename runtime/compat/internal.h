/* SPDX-License-Identifier: MIT */
#ifndef KBL_COMPAT_INTERNAL_H
#define KBL_COMPAT_INTERNAL_H
#include "include/kbl_port.h"
#include "include/errno.h"
#include "include/stdio.h"
#include "include/string.h"
#include "include/stdlib.h"
#include <stdint.h>
#include <limits.h>
extern const struct kbl_program_api *kbl_api;
void kbl_tick(void);int kbl_check(void);int kbl_has_files(void);
void kbl_heap_reset(void);void kbl_io_reset(void);int kbl_io_close_all(void);
struct kbl_compat_file {int fd,used,error,eof;};
#endif
