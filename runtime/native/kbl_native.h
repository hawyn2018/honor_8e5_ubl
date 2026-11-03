/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_NATIVE_H
#define HNU_KBL_NATIVE_H
#include "../kbl_api.h"
#include "../kbl_elf.h"
#include "../kbl_block.h"
#include "../kbl_file.h"
#include "../kbl_efi.h"

/* The provider owns real platform allocation, EL measurement and executable
 * page permissions/cache maintenance. No fallback allocator or fabricated EL
 * is supplied. Pool memory is private RW data; allocate must honor alignment.
 * No constructors, UEFI variables, timers, drivers or MMU changes are made.
 */
struct kbl_native_platform {
    void *opaque;
    void *(*allocate)(void *opaque, size_t bytes, size_t alignment);
    void (*release)(void *opaque, void *memory, size_t bytes);
    unsigned (*current_el)(void *opaque);
    /* Optional monotonic clock. Without it, every explicit checkpoint yields;
     * this is cooperative execution, not timer-enforced fault isolation. */
    uint64_t (*now_us)(void *opaque);
    struct kbl_elf_host elf;
    size_t stack_bytes;       /* 0 -> 64 KiB; >=16 KiB, multiple of 16. */
    size_t output_bytes;      /* 0 -> 4096; >=256. Backpressure, no truncation. */
    size_t max_file_bytes;    /* 0 -> selected total quota (SIZE_MAX by default). */
    size_t max_ramfs_bytes;   /* 0 -> SIZE_MAX, file contents only. */
    unsigned max_nodes;      /* 0 -> allocation capacity, includes RAMFS root. */
    /* Optional actual caller firmware environment; no synthetic handles. */
    int (*uefi_environment)(void *opaque, void **image_handle, void **system_table);
    int (*block_open)(void *opaque,const char *label,unsigned access,struct kbl_block *);
    int (*block_list)(void *opaque,struct kbl_block_info *,size_t capacity,size_t *count);
    /* Explicit synchronous UEFI application transaction. Called only by the
     * efi command after start, never by controller or firmware initialization.
     * options is NUL-terminated UTF-16 and options_bytes includes that NUL. */
    int (*efi_run)(void *opaque,const void *image,size_t image_bytes,
                   const uint16_t *options,size_t options_bytes,struct kbl_efi_run_result *result);
    /* Explicit screen commands only. operation: 0 status, 1 off, 2 on. */
    int (*display)(void *opaque,unsigned operation,char *reply,size_t capacity);
    /* Explicit poweroff only. A real shutdown never returns. */
    int (*poweroff)(void *opaque);
    /* Optional extension; old efi_run/result prefix remain usable unchanged. */
    int (*efi_run_full)(void *opaque,const void *image,size_t image_bytes,
                   const uint16_t *options,size_t options_bytes,struct kbl_efi_run_full_result *result);
};

/* Fill a backend descriptor, without allocating or initializing a runtime.
 * The platform descriptor must outlive the controller using this backend.
 * start is supported on native AArch64 only; other architectures fail clearly.
 */
int kbl_native_backend(struct kbl_backend *, const struct kbl_native_platform *);

/* Explicit private-RAMFS byte operations. put is atomic at file granularity;
 * external mutation while a job is queued/running returns KBL_AGAIN. Direct
 * get copies current bytes; use open_snapshot for consistent multi-fetch data.
 * A positive-length NULL buffer, invalid path, absent file or bounds error fails.
 */
int kbl_native_put(void *context, const char *path, const void *data, size_t bytes);
int kbl_native_get(void *context, const char *path, uint64_t offset, void *buffer,
                   size_t capacity, size_t *read_bytes, uint64_t *file_bytes);
int kbl_native_open_snapshot(void *context, const char *path, struct kbl_reader *);
/* open_snapshot requires a zeroed reader; it never overwrites a live handle.
 * Reader.close consumes its opaque snapshot exactly once; caller then clears
 * its reader descriptor. Snapshots are caller-owned until close; stop returns KBL_AGAIN while any
 * snapshot is open, so the caller must close/invalidate fetch handles first.
 * Never call stop from a job stack or free a suspended context behind a reader.
 */

struct kbl_native_stats {
    size_t owned_allocations, owned_bytes, ramfs_bytes;
    /* Legacy diagnostic counters saturate; internal resource counts are size_t. */
    unsigned nodes, open_snapshots, el;
    unsigned job_active, in_job;
    uint64_t last_job;
    unsigned open_files;
};
int kbl_native_get_stats(void *context, struct kbl_native_stats *);

#endif
