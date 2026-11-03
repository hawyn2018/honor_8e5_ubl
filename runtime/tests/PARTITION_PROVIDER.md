# Read-only partition provider and shared SHA-256

`uefi/kbl_partition.c` opens a named partition as `struct kbl_reader` using existing UEFI services. It neither starts KBL nor resets or writes a storage device. No USB binary transport binding or device test is implied.

API:

```c
struct kbl_partition_provider partitions = {0};
kbl_partition_init(&partitions, boot_services, allocator_context,
                   tracked_allocate, tracked_release);
struct kbl_reader reader = {0};
int result = kbl_partition_open(&partitions, "abl_b", &reader);
/* result == KBL_OK: reader.size is the partition-relative BlockIO capacity. */
/* read_at may be used; close exactly once, then discard the descriptor. */
```

`tracked_allocate` accepts bytes and alignment; `tracked_release` accepts the exact pointer and byte count. Release takes cleanup ownership even if firmware FreePool fails, so it must retain failed frees in its allocation ledger. This matches the UEFI driver's existing pool tracker. Firmware-allocated LocateHandleBuffer arrays are handled separately: a failed FreePool keeps `provider.pending_handles`, blocks new enumeration/unload, and is retried by `kbl_partition_cleanup`.

The caller serializes open/read/close with firmware unload and storage changes. `provider.readers` tracks live readers; `kbl_partition_can_unload` also checks pending enumeration cleanup. Closing during an active synchronous read is invalid; the provider leaves the object retained rather than freeing memory currently in use.

The name is an exact case-sensitive UTF-8 GPT label, up to 36 UTF-16 code units. Valid surrogate pairs, full 36-unit fields without NUL, and ordinary NUL-terminated names are supported. Malformed UTF-8, overlong names, prefix matches, duplicate labels, and reserved `kbl.*` names are rejected. There are no device, slot, model, or partition-address tables. The caller explicitly supplies `abl_a` or `abl_b` when that is the intended source.

Standard EFI_PARTITION_INFO_PROTOCOL is preferred. The Qualcomm PartitionRecord fallback is tried only when the standard protocol is absent/unsupported, not when it is present with a non-GPT type or an invalid revision. The selected interface must have a logical-partition BlockIO with present media, valid protocol revision, nonzero BlockSize and GPT type GUID, power-of-two IoAlign when greater than 1, nonoverflowing `(LastBlock+1)*BlockSize`, and GPT length equal to partition-relative BlockIO length. StartingLBA/EndingLBA belong to GPT metadata; reads use LBA 0 relative to the **child partition handle**, never add StartingLBA again.

The reader snapshots MediaId, geometry, alignment, and Media pointer. It checks them before and after every ReadBlocks call. Media replacement, missing media, geometry changes, unexpected read callback removal, I/O failure, and arithmetic/range errors return failure. A read-only-marked device is accepted: all reads are read-only regardless of that flag. WriteBlocks, FlushBlocks, Erase, Reset, ConnectController, and KBL start are never called.

Unaligned byte reads use a private aligned bounce buffer allocated on the tracked heap. Typical bounce size is 64 KiB rounded down to whole blocks; for larger native blocks it is one block. Head/tail bytes are copied only after a complete successful ReadBlocks and stable post-read media check. The caller's destination need not meet IoAlign. Both overflow in alignment allocation and requests outside reader capacity are rejected. No 64 KiB stack array is used.

A live partition reader is not an immutable disk snapshot. MediaId validation detects media identity changes; it is not a hash-based lock against another writer modifying the same media. Consumers requiring a stable digest should serialize storage operations and independently verify final length/hash. No claim is made that a live partition was unchanged over separate Fastboot commands.

## Local primary-source ABI evidence

The following local EDK2/Qcom sources were inspected; declarations use their standard layouts, not firmware offsets:

- `analysis/gbl-root-canoe/repo/submodules/uefi/edk2/MdePkg/Include/Protocol/PartitionInfo.h`: GUID `8cf2f62c-bc9b-4821-808d-ec9ec421a1a0`, revision `0x1000`, Type GPT=2, GPT union at +16.
- Same tree `MdePkg/Include/Protocol/BlockIo.h`: GUID `964e5b21-6459-11d2-8e39-00a0c969723b`, media fields and ReadBlocks ABI. Media.BlockSize +12, IoAlign +16, LastBlock +24; interface ReadBlocks +24 on AArch64.
- Same tree `MdePkg/Include/Uefi/UefiGpt.h`: 128-byte EFI_PARTITION_ENTRY, 36 UTF-16 PartitionName units at +56.
- Same tree `QcomModulePkg/QcomModulePkg.dec:106`: `gEfiPartitionRecordGuid = fe2555be-d716-4686-b9d0-79db5921b70d`.
- Same tree `QcomModulePkg/Library/FastbootLib/FastbootCmds.c`, `PartitionGetInfo`, around lines 371–405: original Qualcomm consumer passes an EFI_PARTITION_ENTRY pointer to HandleProtocol for that GUID, compares PartitionName, and obtains the matching handle's BlockIO.

There is no separate EFIPartitionRecord.h in the inspected local tree. The fallback uses the DEC declaration plus original Qualcomm consumer's explicit GPT-entry ABI, rather than inventing a wrapper structure. The standard path remains first choice. Compile-time assertions cover the reused minimal EFI types and structure offsets.

## Shared SHA-256

`kbl_sha256.h,c` provide init/update/final, lowercase hex encoding, and:

```c
unsigned char scratch[1024], digest[32];
int result = kbl_sha256_reader(&reader, scratch, sizeof scratch, digest);
```

The reader helper does not close the reader. Caller-owned scratch controls memory usage. It handles partial-progress readers and rejects callback errors, no progress, overreported bytes, changed reported size, and SHA-256 64-bit bit-length overflow. An empty source produces the actual SHA-256 empty digest and permits null scratch. On error the output digest is zeroed. Incremental init/update/final can also hash a payload already in memory without a reader.

## Offline validation

Run `python repairs/abl-efisp-blsh-20261029/source/runtime/tests/run_partition_sha.py` from the workspace, or pass `--clang`, `--arm-clang`, and `--output`. Fixtures use synthetic Boot Services/BlockIO, never ADB/Fastboot or a physical partition.

- Partition fixture: 44 cases, including 512/4096-byte sectors, 131072-byte alignment, multi-window and byte-unaligned reads, standard and Qualcomm fallback, UTF-8/full-width labels, duplicate/missing labels, media/geometry changes, overflow, callback failure, allocation and enumeration cleanup, and live-reader unload protection. A write callback tripwire must remain unused.
- SHA fixture: 14 cases, including empty, `abc`, two published multiblock messages, one million `a` bytes, incremental operation, partial-reader progress, callback errors, no progress, overreporting, and overflow.
- Both sources compile as standalone freestanding AArch64 with `-Werror` and as host C fixtures. Generated validation JSON records commands, results, and source hashes under `analysis/kbl-runtime-20261029/partition-sha-tests`.

These tests validate the actual provider algorithm behind mock UEFI services and the SHA implementation. Actual protocol enumeration and partition readback on firmware remain untested until the complete native driver/transport binding is joined.
