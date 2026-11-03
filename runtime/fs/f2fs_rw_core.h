/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef HNU_KBL_F2FS_RW_CORE_H
#define HNU_KBL_F2FS_RW_CORE_H
#include "kbl_fs.h"
/* Read-only parser instance used privately by the RW wrapper. Source retains
 * its true RW byte callbacks; READ_ONLY here limits only reader operations. */
int kbl_f2fs_rw_reader_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,
                            const char *,unsigned,struct kbl_fs *);
int kbl_f2fs_existing_writable(void *reader_volume);
int kbl_f2fs_refresh_existing(void *reader_volume,void *reader_file);
/* Actual in-place writes into existing allocated extents, bounded to 64 KiB.
 * A range requiring allocation/size change returns ENOSYS before any write.
 * Entire range is preflighted. No flush, retry, checkpoint, NAT or SIT update
 * is performed; pointers/ownership/file size remain unchanged. Caller latches
 * any uncertain device write/flush error and owns explicit synchronization.
 * done counts only confirmed user-data bytes, including partial failures. */
int kbl_f2fs_write_existing(void *reader_volume,void *reader_file,uint64_t,
                            const void *,size_t,size_t *done);
#endif
