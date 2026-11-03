/* SPDX-License-Identifier: MIT */
#ifndef KBL_PORT_H
#define KBL_PORT_H
#include "kbl_program.h"
int kbl_compat_enter(const struct kbl_program_api *);
int kbl_compat_leave(void);
int kbl_checkpoint(void);
unsigned kbl_current_el(void);
#endif
