/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef HNU_KBL_FS_F2FS_H
#define HNU_KBL_FS_F2FS_H
#include "kbl_fs.h"
int kbl_f2fs_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,
                   const char *type,unsigned flags,struct kbl_fs *);
#endif
