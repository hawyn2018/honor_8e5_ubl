# Optional KBL context and sharing metadata patch

Base: FatFs R0.16 / Zephyr commit
`4f60a12c58c3de4246857ad87c40239ecd93349b` (hashes in `PROVENANCE.json`).
ChaN notices and the original license remain in each source file.

`FF_KBL_CONTEXT=1` is selected only by this repository's KBL `ffconf.h`.
Without it, the original fixed `FatFs[]` / `Files[]`, numeric volume registration,
sharing counters, and result codes remain selected at compile time.

The guarded changes in `ff.c` are limited to:

- Route the logical-volume registration slot through
  `kbl_fatfs_context_slot()`. The adapter selects an opaque `fat_volume` before
  every serialized operation. Each volume owns one local numeric `0:/` slot;
  this removes a system-wide mount count without changing on-disk drive fields.
- Obtain sharing records and their capacity through the selected volume's
  `kbl_fatfs_lock_table/capacity/grow` hooks. Growth allocates a larger table,
  copies existing records, and commits only after allocation succeeds. Record
  indices and `FATFS`/`FIL` object addresses remain stable.
- Keep writer exclusion as a separate flag from the native `size_t` read-open
  count, removing the former collision at reader count `0x100`.
- Report allocation failure before registering a newly created file where
  possible. Arithmetic overflow and the public `UINT` lock-index width remain
  checked; actual memory reservation determines capacity.

The adapter owns and frees the registration context and sharing allocation.
Its device callbacks use the same explicitly selected volume. Native `FS_CALL`
serializes operations and forbids a cooperative yield inside the library;
the host tests use serialized callbacks as well. This patch does not add a
threading model, Linux calls, disk format changes, implicit mounts, or startup
execution.

Regression evidence includes 12 real simultaneous FAT mounts, 65 different
open files, 270 read handles to one file, write/rename/unlink exclusion, stable
cross-volume file identity, cleanup/remount, sticky write failures, FAT12/16/32
and exFAT, 512/4096-byte sectors, Unicode, and ARM64 freestanding builds.
