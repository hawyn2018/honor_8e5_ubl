/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_FS_FAT_H
#define HNU_KBL_FS_FAT_H
#include "kbl_fs.h"
int kbl_fat_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,
                  const char *type,unsigned flags,struct kbl_fs *);
#if defined(KBL_FATFS_TEST_MKFS) && KBL_FATFS_TEST_MKFS
/* Host fixture generator only. Absent from production objects and CLI. */
int kbl_fat_test_format(const struct kbl_fs_host *,const struct kbl_fs_device *,
                        const char *type);
#endif
#endif
