/* SPDX-License-Identifier: MIT -- synchronous, explicit native filesystem ABI. */
#ifndef HNU_KBL_FS_H
#define HNU_KBL_FS_H
#include <stddef.h>
#include <stdint.h>
#include "../sdk/kbl_file.h"

#ifndef KBL_EXDEV
#define KBL_EXDEV 18
#endif
#define KBL_FS_READ_ONLY 1u

/* All functions return zero or negative KBL_E*. No callback yields, starts a
 * driver, changes EL, or retries an uncertain failed write. The caller checks
 * EL and cooperatively checkpoints only after the callback has returned. */
struct kbl_fs_host {
    void *opaque;
    void *(*allocate)(void *,size_t,size_t alignment);
    void (*release)(void *,void *,size_t);
};
/* A fixed-capacity byte device owned by the mount, not a per-job file token.
 * sector_bytes is the backing IO unit (512 for RAM images), not a filesystem
 * block-size promise. Byte callbacks already handle backing alignment/RMW.
 * write_at returns confirmed bytes even on error; no failed span is replayed.
 * The mount owner closes/releases the backing device after successful unmount. */
struct kbl_fs_device {
    void *opaque;
    uint64_t bytes;
    uint32_t sector_bytes,flags;
    int (*read_at)(void *,uint64_t,void *,size_t,size_t *);
    int (*write_at)(void *,uint64_t,const void *,size_t,size_t *);
    int (*flush)(void *);
};
struct kbl_fs_ops {
    /* Failure retains the volume/file object for explicit cleanup. A backend
     * remembers uncertain write failures: close/sync must not replay those
     * writes. Successful close consumes the file object exactly once. */
    int (*unmount)(void *volume);
    int (*open)(void *volume,const char *absolute_path,unsigned flags,void **file);
    int (*close)(void *volume,void *file);
    int (*read_at)(void *volume,void *file,uint64_t,void *,size_t,size_t *);
    int (*write_at)(void *volume,void *file,uint64_t,const void *,size_t,size_t *);
    int (*fstat)(void *volume,void *file,struct kbl_file_stat *);
    int (*stat)(void *volume,const char *,struct kbl_file_stat *);
    /* index starts at zero; name is UTF-8, with no slash or embedded NUL.
     * Exactly one entry is returned, or end=1. No synthesized dot entries. */
    int (*list)(void *volume,const char *,uint64_t index,char *name,size_t,
                struct kbl_file_stat *,int *end);
    int (*mkdir)(void *volume,const char *);
    int (*unlink)(void *volume,const char *);
    int (*rename)(void *volume,const char *,const char *);
    /* file=NULL synchronizes the volume; never invoked implicitly by reads. */
    int (*sync)(void *volume,void *file);
};
struct kbl_fs {
    const struct kbl_fs_ops *ops;
    void *opaque;
    unsigned flags;
    const char *type;
};
/* type: auto, fat, exfat, ext4, f2fs, erofs. Only actually implemented formats
 * are accepted. Unknown/unsupported formats return -KBL_ENOSYS. A read-only
 * mount and a read-only backing device are both enforced by the native router
 * and backend. Mount failures leave out zeroed and release backend ownership;
 * media is never formatted, repaired, or implicitly mounted on START. */
int kbl_fs_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,
                 const char *type,unsigned flags,struct kbl_fs *out);
#endif
