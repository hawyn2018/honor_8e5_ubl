/* SPDX-License-Identifier: MIT */
#ifndef KBL_EXT4_H
#define KBL_EXT4_H
#include "kbl_fs.h"
int kbl_ext4_mount(const struct kbl_fs_host *, const struct kbl_fs_device *,
                   const char *, unsigned, struct kbl_fs *);
/* Host fixture formatter only: production never links ext4_mkfs.c. */
#ifdef KBL_EXT4_HOST_TEST
void kbl_ext4_test_allocator(const struct kbl_fs_host *);
#endif
#endif
