# Native cooperative KBL backend

This is actual freestanding AArch64 code for the `kbl_backend` contract. It supplies private RAMFS, a shared file/block/mount VFS, a basic command interpreter and a cooperative execution stack. Filesystem backends run only inside explicit mount operations after START. It is not Linux, BusyBox, a complete POSIX shell, a privilege sandbox or an independent hardware kernel. New mount support has separate host/ARM64 and device validation records.

`kbl_native_backend()` only fills function pointers; it does not allocate, initialize hardware or run a command. The controller/driver must call `start` solely after the exact requested start command. Backend code installs no constructors, EFI variables, USB hooks, interrupt vectors, timers or MMU mappings.

## Provider and lifecycle

`kbl_native.h` fixes the provider ABI: private aligned data allocation/release, actual CurrentEL measurement, optional monotonic clock, the explicit ELF executable-page provider and optional real firmware-environment getter. Missing data allocation/EL callbacks fail startup. Missing ELF allocation/protect/cache callbacks fail executable loading, while RAMFS commands remain usable; there is no fake executable-success fallback.

All state is explicit in one context: allocation list, directory tree, current path, mount table, byte output queue, pending job, argument storage, own stack and executable image. Failed startup returns a nonnull context only when explicit cleanup remains. `stop` synchronizes/unmounts filesystems before freeing an active suspended image and owned data from the host stack; failed synchronization/unmount retains the environment and resources, and it rejects job-stack/reentrant calls and open snapshots. It never frees its currently executing stack. A fresh startup allocates a new context rather than reusing stale statics.

`submit` copies and parses a command, allocates an execution stack and queues it. It does not execute that command. `poll` resumes only that context, then returns at a cooperative checkpoint/completion and drains output with backpressure. No bytes are dropped when output exceeds the fixed ring. `exit` completes a command with a retained-runtime message; it does not stop the context. Reattachment/detachment and exact Fastboot namespace matching belong to the controller, not to this backend.

The first execution profile is **general registers only**, including handwritten program assembly. The 112-byte context preserves X19–X30, SP and NZCV; X19 carries the context on first entry. It never reads/writes FPCR, FPSR, vector registers or DAIF and never assumes or enables FP/SIMD access. Compile native C and SDK programs with both `-ffixed-x18` and `-mgeneral-regs-only`; the UEFI platform register and existing firmware FP/interrupt state remain untouched. Custom programs using FP/SIMD, changing interrupt masks, or changing execution environment are outside this profile. Ordinary branches/returns preserve the current EL.

Execution is single-threaded and cooperative. Without a clock callback every explicit checkpoint yields. With a clock callback checkpoints compare the requested deadline. Provider calls, allocation/zeroing and ELF loading are atomic operations; arbitrary custom code must call the SDK yield callback. No timer-based preemption or hard 2 ms latency guarantee exists. A custom program fault or non-yielding loop is not isolated at the same EL.

## Files and commands

The root filesystem is private RAM data. Actual partitions use explicit block paths; filesystems appear after explicit mount. Commands currently implemented:

`help el pwd ls cd mkdir touch cat cp mv rm echo hex stat true false exit sha256sum wc head tail grep hexdump xxd basename dirname printf dd sync mount umount`

File commands use the shared VFS for RAMFS, explicit block files and supported mounted paths:

| Command | Supported form and behavior |
| --- | --- |
| `sha256sum [--] FILE...` | Real streaming SHA-256, two spaces and the supplied filename. |
| `wc [-lwc] [--] FILE...` | Newline count, ASCII-whitespace word count and byte count; default all three; multi-file totals. |
| `head` / `tail` | `[-n N\|-c N] [--] FILE`, default 10 lines, binary-safe byte mode, zero count supported. |
| `grep [-Fnicv] [--] LITERAL FILE...` | Literal byte matching; `-i` folds ASCII A–Z only, `-n` numbers lines, `-c` counts selected lines, `-v` inverts. Exit 0 for a match, 1 for no match, 2 for input/usage errors. No regex interpretation. |
| `hexdump` / `xxd` | `[-n N\|-l N] [-C] [--] FILE`; both produce the same canonical 16-byte hex/ASCII view. These are aliases, not all upstream xxd options. The default reads the whole file. |
| `basename NAME [SUFFIX]` / `dirname NAME` | Byte pathname components, stripping trailing slashes. |
| `printf FORMAT [ARGS...]` | `%%`, `%s`, `%c`, `%d`, `%u`, `%x`, `%X`; escapes `\n`, `\r`, `\t`, `\\`. Exact argument count, no width/precision modifiers or implicit format repetition. Use single quotes to preserve backslashes, for example `printf '%s\n' hello`. |

Counts and integer arguments accept unsigned decimal or `0x` hexadecimal; `%d` also accepts a leading minus sign with signed 64-bit range checks. Files are explicit arguments: there is no fabricated stdin or Linux process API. Hashing, word counting, line scans and even the inner literal-comparison loop check cooperative progress at most every 256 work units. Hex views and selected lines use the existing output ring with backpressure; large output is retained across polls rather than truncated.

`cp` streams byte files through the VFS. Cross-filesystem `mv` returns EXDEV rather than silently copying/deleting. `rm` handles files and empty directories. `hex FILE` outputs the whole file. `touch` creates an empty file or keeps an existing file; timestamps/Unix ownership are not fabricated. `grep` matches literal bytes with its documented flags; it does not provide regular expressions. Parsing supports whitespace, single/double quotes and backslash escapes, with dynamically sized argument storage and commands. Unquoted pipes, redirection, lists and background operators are rejected rather than partially executed. There is no expansion, process model or Linux command compatibility layer.

Commands containing `/`, including `./xxx`, `../xxx` and absolute paths, resolve a VFS file and call the actual KBL ELF loader. Accepted programs are the explicit freestanding `EM_AARCH64` KBL ABI profile documented under `runtime/sdk`, not ordinary Linux/Android ELFs. The SDK receives `argc/argv`, write/yield/CurrentEL and the optional real UEFI-environment callback. ELF pages remain loaded across yield and are released after completion or explicit stop.

The optional firmware environment is borrowed from the provider. Programs that use raw firmware services must clean up their own allocations/protocol registrations and leave no callbacks or pointers into their image after returning. Native cleanup accounts for resources acquired through its own tracked interfaces; it cannot discover arbitrary raw firmware allocations made by a privileged program.

Public byte interfaces are:

- `kbl_native_put`: atomic file replacement; external mutation is rejected while a job is pending/running.
- `kbl_native_get`: copy a current byte range, reporting complete file size.
- `kbl_native_open_snapshot`: create an immutable copy for consistent fetches. The caller supplies a zeroed reader and calls its close callback exactly once. Stop returns AGAIN while such a reader is open, so fetch handles must be closed and invalidated before stop.

Snapshot bytes are independent of later file replacement/deletion. Each allocation and descriptor is accounted for and released on the documented lifecycle path. No automatic partition write, mount or firmware-variable persistence occurs here.

## Verification

Run `python runtime/native/test_native_runner.py --output OUTPUT` from this source tree, or pass an absolute path to the script. It cross-compiles the real native C, assembly, real ELF loader and SDK example, then executes them in Unicorn. Only platform allocation/protection/cache providers are emulator models; the model applies actual Unicorn page permissions.

Tests exercise binary RAMFS round trips, commands/quoting, copy/move/remove, stdout backpressure, immutable readers, allocation failure/atomic replacement, cleanup after suspended work, retained `exit`, actual `./hello` ELF execution and yielding, argv, and unavailable execution permissions. Command tests include known SHA-256 vectors, numeric bounds, binary/NUL output, newline edge cases, literal-vs-regex behavior, large no-output scans yielding before completion, suspended cleanup, repeated-prefix searches and complete multi-poll hex output. All run with CPACR_EL1 FP access disabled. A separate assembly test checks nonzero GPR/SP/NZCV round trips, unchanged X18, and untouched FP/SIMD/DAIF patterns; it does not claim to switch SIMD program contexts.

The runner records requested PSTATE separately from actual native `MRS CurrentEL`. This Unicorn build reports EL1 even after requesting EL2; those runs prove the observed EL1 path only. They do not constitute actual EL2, UEFI, USB or phone boot testing.
# File and raw partition services

The ABI1 file tail requires `KBL_PROGRAM_API_FILE_BYTES` (136 bytes on AArch64).
The original 40/56/64-byte prefixes retain their offsets. File callbacks return
zero or negative `KBL_E*`, report confirmed partial I/O, and check the current EL
before and after operations. They preserve the cooperative stack and never
change EL. Programs must loop over short reads/writes, including the 64 KiB
per-call transfer limit. Handles contain a generation and slot, belong to one
job, and are closed automatically before that job reports completion.
Raw/RAMFS close does not flush. Mounted filesystem close may commit its own
metadata; explicit `file_sync` confirms synchronization. Uncertain writes are
not retried by close or sync. A consumed close token is never reused; failed
cleanup remains owned by the runtime.

Uncovered RAM paths refer to private RAMFS; mounted paths use the selected
filesystem backend. `/dev/block/by-name/LABEL` uses the actual
platform block provider and partition-relative byte offsets. `ls` and `stat`
expose that namespace. Hardware read-only media rejects writes; no partition is
made writable by this service. Raw partition access alone does not mount or decrypt
Android filesystems. `kbl_native_open_snapshot` retains its RAMFS-only immutable
contract; partition export uses the separate live partition reader.

`dd if=PATH of=PATH bs=N count=N skip=N seek=N conv=fsync` copies between RAM,
block or mounted paths with bounded buffers and cooperative checkpoints. `count`, `skip`
and `seek` use logical `bs` records. Decimal/hex and K/M/G scales are accepted;
`bs` has no fixed 64 MiB policy ceiling; multiplication and offsets are checked before opening for I/O. Only
`conv=fsync` is supported. Partial errors stop after the confirmed bytes and are
reported with a failing status. No automatic write retry occurs. An identical
input/output path is rejected. `sync PATH...` explicitly flushes writable file
handles; plain `sync` synchronizes mounts and enumerates writable, unmounted
partitions. `sha256sum`
and `cat` stream through the same VFS, without the RAMFS file-size limit for a
partition source. Such a source is live, so use before/after hashes when it may
change during export.


## Filesystem mounts

`mount [-t TYPE] [-o ro|rw] SOURCE TARGET` defaults to read/write. Select
`-o ro` manually for read-only access; EROFS requires it and rejects RW
with EROFS rather than silently downgrading. F2FS RW permits only overwriting
existing allocated file ranges/inline bytes on a clean consistent filesystem;
allocation, growth, truncate and namespace mutations return ENOSYS. Create an
existing empty RAMFS target with `mkdir` first. The source is a bounded GPT
block path or an existing fixed-capacity RAMFS filesystem image. FAT12/16/32,
exFAT and supported clean ext2/3/4 have RW operation. F2FS retains its independent
RO mode alongside limited existing-range RW; EROFS (including LZ4) is RO.
No Linux device-mapper, Android FBE
decryption or LP/super logical-partition mapping is supplied. See
[filesystem mount reference](../fs/MOUNT.md) for format limits and examples.

The original ABI1 / 136-byte file API and existing offsets are retained.
Close files and leave the target directory before `umount TARGET`. Always
unmount before native Fastboot flash/erase touches that source: the original
Fastboot path is not a filesystem scheduler. Successful OFF destroys RAMFS
and its image changes; export first. Filesystem callbacks are synchronous;
cooperative checkpoints happen after they return.


本版增加 ADB 多独立会话与显式 UEFI 应用启动。Fastboot 交互前端仍使用自己的默认控制会话；独立终端请使用多个 `adb shell`，并可同时 `adb push` / `adb pull`。事务按 FIFO 串行执行。详见源码根目录的 `runtime/uefi/KBL-SESSIONS-APPS.md`。
