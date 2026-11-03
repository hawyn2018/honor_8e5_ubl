/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_FILE_H
#define HNU_KBL_FILE_H
#include <stddef.h>
#include <stdint.h>
/* Status is 0 on success or negative KBL_E*. Partial I/O reports confirmed
 * bytes even with an error; EOF is success with zero bytes. Handles are opaque
 * generation tokens, never Linux descriptors or firmware pointers. A valid
 * close consumes its token even on error; cleanup ownership remains with the
 * runtime/provider. Raw/RAMFS close does not flush. Mounted filesystems may
 * commit filesystem metadata on close; explicit sync confirms durability.
 * An uncertain write is not retried by close or sync.
 * Read/write may succeed with a short span (currently at most 64 KiB/call), so
 * callers loop over done. A failed write is never automatically replayed. */
typedef uint64_t kbl_file_handle;
#define KBL_FILE_READ 1u
#define KBL_FILE_WRITE 2u
#define KBL_FILE_CREATE 4u
#define KBL_FILE_TRUNC 8u
#define KBL_FILE_APPEND 16u
#define KBL_FILE_EXCL 32u
#define KBL_SEEK_SET 0u
#define KBL_SEEK_CUR 1u
#define KBL_SEEK_END 2u
#define KBL_FILE_REGULAR 1u
#define KBL_FILE_DIRECTORY 2u
#define KBL_FILE_BLOCK 3u
#define KBL_FILE_READ_ONLY 1u
#define KBL_EPERM 1
#define KBL_ENOENT 2
#define KBL_EIO 5
#define KBL_EBADF 9
#define KBL_ENOMEM 12
#define KBL_EACCES 13
#define KBL_EBUSY 16
#define KBL_EEXIST 17
#define KBL_ENODEV 19
#define KBL_ENOTDIR 20
#define KBL_EISDIR 21
#define KBL_EINVAL 22
#define KBL_EMFILE 24
#define KBL_EFBIG 27
#define KBL_ENOSPC 28
#define KBL_EROFS 30
#define KBL_ENAMETOOLONG 36
#define KBL_ENOSYS 38
#define KBL_ENOTEMPTY 39
#define KBL_ELOOP 40
#define KBL_EOVERFLOW 75
struct kbl_file_stat { uint64_t bytes;uint32_t type,flags,block_bytes,reserved; };
#endif
