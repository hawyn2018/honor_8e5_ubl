/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_PARTITION_H
#define HNU_KBL_PARTITION_H
#include "kbl_efi_types.h"
#include "../kbl_api.h"
#include "../kbl_block.h"

/* Minimal standard UEFI BlockIo/PartitionInfo ABI. These structures describe
 * protocol contracts, not device addresses or a model-specific partition map. */
struct kbl_efi_block_media {
    uint32_t media_id;
    uint8_t removable,present,logical,read_only,write_caching;
    uint32_t block_size,io_align;
    uint64_t last_block;
};
struct kbl_efi_block_io {
    uint64_t revision;
    struct kbl_efi_block_media *media;
    void *reset;
    kefi_status (*read_blocks)(struct kbl_efi_block_io *,uint32_t,uint64_t,size_t,void *);
    kefi_status (*write_blocks)(struct kbl_efi_block_io *,uint32_t,uint64_t,size_t,void *);
    kefi_status (*flush_blocks)(struct kbl_efi_block_io *);
};
struct kbl_efi_gpt_entry {
    struct kefi_guid type,unique;
    uint64_t starting_lba,ending_lba,attributes;
    uint16_t name[36];
};
struct kbl_efi_partition_info {
    uint32_t revision,type;
    uint8_t system,reserved[7];
    struct kbl_efi_gpt_entry gpt;
};
/* allocate/release own a tracked allocation ledger. release takes cleanup
 * ownership even when EFI FreePool fails, as in the driver's pool tracker. */
struct kbl_partition_provider {
    struct kefi_boot_services *bs;
    void *opaque;
    void *(*allocate)(void *,size_t,size_t alignment);
    void (*release)(void *,void *,size_t);
    size_t readers;
    void *pending_handles; /* retained if firmware FreePool reports failure */
    unsigned initialized;
};
int kbl_partition_init(struct kbl_partition_provider *,struct kefi_boot_services *,void *,
                       void *(*allocate)(void *,size_t,size_t),void (*release)(void *,void *,size_t));
/* Exact UTF-8 GPT label, no slot expansion and no prefix match. GPT permits
 * exactly 36 UTF-16 units without a terminator. Duplicate labels are ambiguous
 * and fail; kbl.* is reserved for live read-only file handles.
 * The zeroed output reader reports partition-relative BlockIO capacity.
 * Opening does not read payload blocks, start KBL, reset a disk, or write.
 * A reader is a live read-only source, not an immutable disk snapshot. */
int kbl_partition_open(void *provider,const char *label,struct kbl_reader *);
int kbl_partition_block_open(void *provider,const char *label,unsigned access,struct kbl_block *);
/* Zero capacity queries the exact required count. Insufficient capacity or
 * ambiguous/malformed metadata returns ERROR without publishing a prefix.
 * No payload blocks are read, written or flushed by list/open/close. */
int kbl_partition_list(void *provider,struct kbl_block_info *,size_t,size_t *);
/* A live reader must be closed once before unload; allocator retains any
 * deferred FreePool failures. No firmware service is called by this check. */
int kbl_partition_can_unload(const struct kbl_partition_provider *);
/* Retries a failed firmware-owned enumeration FreePool. No pointer is lost
 * on failed cleanup. Returns ERROR while the retained allocation remains. */
int kbl_partition_cleanup(struct kbl_partition_provider *);

_Static_assert(offsetof(struct kbl_efi_block_media,block_size)==12,"BlockIO BlockSize ABI");
_Static_assert(offsetof(struct kbl_efi_block_media,last_block)==24,"BlockIO LastBlock ABI");
_Static_assert(offsetof(struct kbl_efi_block_io,read_blocks)==24,"BlockIO ReadBlocks ABI");
_Static_assert(offsetof(struct kbl_efi_block_io,write_blocks)==32,"BlockIO WriteBlocks ABI");
_Static_assert(offsetof(struct kbl_efi_block_io,flush_blocks)==40,"BlockIO FlushBlocks ABI");
_Static_assert(sizeof(struct kbl_efi_gpt_entry)==128,"GPT entry ABI");
_Static_assert(offsetof(struct kbl_efi_gpt_entry,name)==56,"GPT label ABI");
_Static_assert(offsetof(struct kbl_efi_partition_info,gpt)==16,"PartitionInfo GPT ABI");
_Static_assert(offsetof(struct kefi_boot_services,locate_handle_buffer)==0x138,"LocateHandleBuffer ABI");
#endif
