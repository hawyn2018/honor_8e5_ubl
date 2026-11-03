# FatFs used by explicit KBL mounts

Source: https://github.com/zephyrproject-rtos/fatfs
Pinned commit: `4f60a12c58c3de4246857ad87c40239ecd93349b`.
This is FatFs R0.16 plus upstream R0.16 patches and Zephyr's disclosed
post-release fixes, as documented in `UPSTREAM-README`. Original source SHA256
and local hashes are recorded in `PROVENANCE.json`. Download used the configured
system proxy. The Zephyr-specific diskio.c/OS glue is not compiled or vendored.

`diskio.h` and `ffunicode.c` are byte-identical to this commit. The one
local `ff.h` change selects fixed-width C99 types on Windows host tests through
`KBL_FATFS_PORTABLE_TYPES`, instead of including Windows API headers. A new
`ffconf.h` supplies the freestanding KBL configuration and public-symbol prefixes.
Original ChaN source notices are retained; `LICENSE` reproduces their conditions.
The optional `FF_KBL_CONTEXT` branches in `ff.c` are documented in
`PATCHES.KBL.md`: they select each opaque adapter volume's registration slot and
allocator-backed sharing records. Builds without that option retain upstream
registration and sharing behavior.

Production sources: `runtime/fs/fat.c`, `third_party/fatfs/ff.c`, and
`third_party/fatfs/ffunicode.c`. Compile with `-ffreestanding -fno-builtin`,
`-I runtime/compat/include`, and the normal KBL ARM64 GPR-only flags. ARM64 COFF
also uses `-mno-stack-arg-probe`, since the runtime supplies a contiguous private
stack and has no Windows guard-page stack probing service. There are no imports,
timers, driver constructors, Linux syscalls, or EL transitions. No filesystem is
mounted until an explicit `kbl_fat_mount` call.

The adapter implements FAT12/16/32 and exFAT read/write; UTF-8 long names;
per-volume registration and dynamically allocated shared file/directory locks;
variable 512/1024/2048/4096
filesystem sectors; 64-bit LBAs and exFAT file lengths. Backing IO sectors are
independent of VBR sector sizes; byte IO handles backing alignment. Sources are
filesystem VBR/partition images, not whole-disk partition-table traversal.

Successful mutations synchronize before returning. Read-only mounts hard-reject
all device writes and do not call backing flush, including on unmount. Failed or
short device writes and failed flushes latch a volume error. Later operations
return it without replaying writes; close/unmount retain ownership for explicit
diagnostics. The native owner must not release that source while the mount is
retained. Ordinary unmount with open handles returns EBUSY. Multiple callers are
serialized by KBL; callbacks must not yield inside the library.
Each call selects the corresponding opaque volume; its `FATFS` and open `FIL`
objects keep their addresses and mount identity across context switches. The
local FatFs numeric path remains `0:/...` inside that selected volume. Full
paths use owned buffers; on-disk component and integer field widths still apply.

File IO calls are capped at 64 KiB. Sparse-hole extension is implemented by
ordinary zero-data writes from EOF rather than extending `f_lseek`; real exFAT
tests exercised a failure in the latter upstream path. The upstream code itself
is retained for audit. Directory listing uses indexed rescans; cross-filesystem
rename is handled by the native router. Existing-target rename returns EEXIST.
Timestamps are fixed to 2026-01-01 until a real clock is connected; no fabricated
wall clock is claimed. No formatter is exposed in production. `f_mkfs` is compiled
only when the standalone host fixture test sets `KBL_FATFS_TEST_MKFS=1`.

Run `python runtime/fs/tests/test_fat_host.py`. Tests create and remount real
FAT12/16/32/exFAT RAM images, check Unicode data/holes/append/truncate/metadata,
RO-zero-write, mount-failure cleanup, and sticky partial-write/flush faults.
The resulting RAM images are host fixtures; these tests do not open USB or a
real block device. `runtime/native/tests/run_mount.py` additionally verifies 12
simultaneous real mounts, 65 distinct open files, 270 readers of one file,
cross-volume identity, lock-protected rename/unlink, and a full path over 4 KiB.
