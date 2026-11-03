/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_ELF_H
#define HNU_KBL_ELF_H
#include <stddef.h>
#include <stdint.h>
#include "sdk/kbl_program.h"

#define KBL_ELF_R 1u
#define KBL_ELF_W 2u
#define KBL_ELF_X 4u
#define KBL_ELF_DEFAULT_MAX_BYTES SIZE_MAX

enum kbl_elf_error {
    KBL_ELF_OK = 0, KBL_ELF_FORMAT = -1, KBL_ELF_UNSUPPORTED = -2,
    KBL_ELF_BOUNDS = -3, KBL_ELF_MEMORY = -4, KBL_ELF_PERMISSION = -5,
    KBL_ELF_ABI = -6, KBL_ELF_RELOCATION = -7, KBL_ELF_STATE = -8,
    KBL_ELF_EL_CHANGED = -9
};
struct kbl_elf_allocation { void *base; size_t bytes; };
struct kbl_elf_host {
    void *opaque;
    size_t page_bytes;       /* Power of two, 4096..65536. */
    size_t max_image_bytes;  /* Zero means no runtime cap; nonzero is an explicit host quota. */
    /* Provider owns the platform implementation. allocate returns private,
     * initially writable and NONEXECUTABLE memory. If it fails with a nonnull
     * allocation, release is still called. release accepts protected pages.
     * No UEFI, Linux, allocator, timer or USB implementation is implied here.
     */
    int (*allocate)(void *, size_t bytes, size_t alignment, struct kbl_elf_allocation *);
    void (*release)(void *, struct kbl_elf_allocation *);
    int (*protect)(void *, struct kbl_elf_allocation *, size_t offset, size_t bytes, unsigned flags);
    int (*icache)(void *, struct kbl_elf_allocation *, size_t offset, size_t bytes);
};
struct kbl_elf_image {
    struct kbl_elf_allocation memory;
    size_t mapped_bytes;
    uint64_t virtual_min;
    void *entry;
    uint32_t required_api_bytes;
    struct kbl_elf_host host;
    uint64_t cookie;
};

/* Caller initializes image to zero; a live image cannot be overwritten.
 * Format: ET_DYN ELF64LE/EM_AARCH64 + explicit KBL ABI note. Only self-image
 * R_AARCH64_RELATIVE relocations, no interpreter or dynamic dependencies.
 */
int kbl_elf_load(const void *file, size_t bytes, const struct kbl_elf_host *, struct kbl_elf_image *);
void kbl_elf_unload(struct kbl_elf_image *);
/* Native AArch64 only. Must be called on the runtime's cooperatively scheduled
 * execution stack. It does not preempt faults/hangs, sandbox privileged code,
 * create a shell process, or integrate ./ execution into an existing runtime.
 */
int kbl_elf_call(struct kbl_elf_image *, const struct kbl_program_api *, void *opaque, int *program_rc);
#endif
