/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_PROGRAM_H
#define HNU_KBL_PROGRAM_H
#include <stddef.h>
#include <stdint.h>
#include "kbl_file.h"
#define KBL_PROGRAM_ABI 1u
#define KBL_PROGRAM_NOTE_TYPE 0x4b424c01u
/* ABI1 cooperative profile: GPR-only, X18 reserved. All program code,
 * including handwritten assembly, must avoid FP/SIMD and FP control/status
 * registers. Runtime/firmware FP state is left untouched; SIMD scheduling is
 * not a capability of this profile. ELF structure is not fault isolation. */
#define KBL_PROGRAM_GPR_ONLY 1u

struct kbl_program_api {
    uint32_t abi_version;
    uint32_t struct_bytes;
    void *host;
    /* Zero means the entire span was accepted; nonzero reports failure. */
    int (*write)(void *host, const void *data, size_t bytes);
    /* Cooperative checkpoint: budget_us>0 respects the current dispatch
     * deadline, without extending it; budget_us=0 forces suspension. Without
     * a real clock every checkpoint suspends. Zero means accepted/resumed. */
    int (*yield)(void *host, unsigned budget_us);
    unsigned (*current_el)(void *host);
    /* Optional ABI1 tail. Check struct_bytes before access. argv includes argv[0]
     * and is borrowed for the invocation. Future VFS capabilities extend this
     * table; no Linux fd/syscall ABI or placeholder VFS methods are implied.
     */
    int argc;
    const char *const *argv;
    /* Optional real firmware capability, absent on host/emulator fixtures.
     * The provider owns these borrowed handles; no fake system table is valid.
     */
    int (*uefi_environment)(void *host, void **image_handle, void **system_table);
    /* ABI1 VFS tail. Every callback returns 0 or negative KBL_E*. Handles are
     * invocation-owned and automatically closed before the job completes.
     * No callback changes EL. A program must check struct_bytes first. */
    int (*file_open)(void *,const char *,unsigned,kbl_file_handle *);
    int (*file_read)(void *,kbl_file_handle,void *,size_t,size_t *);
    int (*file_write)(void *,kbl_file_handle,const void *,size_t,size_t *);
    int (*file_seek)(void *,kbl_file_handle,int64_t,unsigned,uint64_t *);
    int (*file_close)(void *,kbl_file_handle);
    int (*file_stat)(void *,const char *,struct kbl_file_stat *);
    int (*file_mkdir)(void *,const char *);
    int (*file_unlink)(void *,const char *);
    /* handle=0 flushes writable handles owned by this invocation. */
    int (*file_sync)(void *,kbl_file_handle);
    /* Optional memory tail. Returned storage is 16-byte aligned, owned by this
     * invocation and valid across cooperative yields. Release requires the exact
     * pointer and requested byte count; outstanding blocks are reclaimed on job
     * completion/cancellation. Host allocation failure is the capacity limit. */
    void *(*memory_allocate)(void *,size_t);
    int (*memory_release)(void *,void *,size_t);
};
#define KBL_PROGRAM_API_BASE_BYTES ((uint32_t)offsetof(struct kbl_program_api, argc))
#define KBL_PROGRAM_API_ARGV_BYTES ((uint32_t)offsetof(struct kbl_program_api, uefi_environment))
#define KBL_PROGRAM_API_UEFI_BYTES ((uint32_t)offsetof(struct kbl_program_api, file_open))
#define KBL_PROGRAM_API_FILE_BYTES ((uint32_t)offsetof(struct kbl_program_api, memory_allocate))
#define KBL_PROGRAM_API_MEMORY_BYTES ((uint32_t)sizeof(struct kbl_program_api))
_Static_assert(KBL_PROGRAM_API_FILE_BYTES==136,"ABI1 file prefix remains stable");
typedef int (*kbl_program_entry)(const struct kbl_program_api *, void *opaque);

/* The profile is deliberately different from Linux/Android ELF ABI. */
#define KBL_DECLARE_PROGRAM_WITH_API_BYTES(required_bytes) \
    static const struct { uint32_t namesz, descsz, type; char owner[4]; \
        uint32_t abi, api_bytes, flags, reserved; } \
    kbl_program_note __attribute__((section(".note.kbl"), used, aligned(4))) = \
        {4, 16, KBL_PROGRAM_NOTE_TYPE, {'K','B','L',0}, KBL_PROGRAM_ABI, \
         (uint32_t)(required_bytes), 0, 0}
#define KBL_DECLARE_PROGRAM() KBL_DECLARE_PROGRAM_WITH_API_BYTES(KBL_PROGRAM_API_BASE_BYTES)

int kbl_main(const struct kbl_program_api *, void *opaque);
#endif
