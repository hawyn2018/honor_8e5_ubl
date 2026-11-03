/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef HNU_KBL_F2FS_RW_H
#define HNU_KBL_F2FS_RW_H
#include "kbl_fs.h"
/* Existing regular files, existing size and allocated data/inline ranges.
 * No implicit allocation, truncation, directory mutation or checkpoint write. */
int kbl_f2fs_rw_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,const char *,unsigned,struct kbl_fs *);
#endif
