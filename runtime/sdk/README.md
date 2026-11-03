# KBL ARM64 freestanding program ABI 1

This SDK builds **native KBL programs**. A program is an AArch64 ELF64 little-endian ET_DYN file with an explicit KBL ABI note. It receives a versioned callback table and runs at the runtime's current EL. The optional [C port layer](../compat/README.md) adapts ordinary `main(argc, argv)` source and a documented libc subset to this ABI. Linux/Android system calls, their binary ABI and a dynamic linker are outside this environment.

## Build and entry

```text
python runtime/sdk/build_demo.py --output build/my-kbl-program
```

Override `--source` and `--llvm-bin` for another program or toolchain. The script uses NDK Clang/LLD, a 4 KiB page-separated linker script, freestanding PIC code, no undefined symbols and no standard startup files. `-ffixed-x18` reserves the UEFI platform register. ABI1 is a **GPR-only cooperative profile**: `-mgeneral-regs-only` is required for all C objects, and handwritten assembly must avoid FP/SIMD instructions and FP control/status registers, reserve X18 and preserve the general-purpose callee-saved registers. The runtime does not modify or schedule firmware FP state. SIMD programs are outside this profile; a future explicit capability would need separate permission and context rules.

```c
#include "kbl_program.h"
KBL_DECLARE_PROGRAM();
int kbl_main(const struct kbl_program_api *api, void *opaque) {
    const char text[] = "hello\n";
    (void)opaque;
    if (!api || api->abi_version != KBL_PROGRAM_ABI ||
        api->struct_bytes < KBL_PROGRAM_API_BASE_BYTES) return 1;
    return api->write(api->host, text, sizeof(text) - 1);
}
```

The output file is `program.kbl`; its extension does not determine compatibility. The runtime must first integrate file lookup and explicit `./name` execution before this file can be invoked from that runtime's command line. The standalone loader does not add a shell, firmware command registration or USB endpoint.

## Callback table and optional capabilities

- The original 40-byte prefix contains ABI version, table size, host context, `write`, `yield` and `current_el`. `write` returns zero after accepting the entire span, or nonzero on failure. `yield` is a cooperative scheduling checkpoint; a real backend may suspend the program stack and resume it before returning zero.
- The 56-byte prefix adds `argc` and borrowed `argv`, including `argv[0]`. Read these only when `struct_bytes >= KBL_PROGRAM_API_ARGV_BYTES`.
- The 64-byte table adds optional `uefi_environment(host, &image, &system_table)`. Both table length and the function pointer must be checked. Host/emulator fixtures leave it NULL; they must not invent a UEFI system table. A real firmware provider may return borrowed real handles, or report unavailable.
- The 136-byte `KBL_PROGRAM_API_FILE_BYTES` table appends nine VFS callbacks: open/read/write/seek/close/stat/mkdir/unlink/sync. See `kbl_file.h` for flags, 64-bit sizes, opaque generation handles and negative `KBL_E*` errors. An invocation owns its open handles; return closes them. RAMFS and exact `/dev/block/by-name/LABEL` paths share these calls. Block writes are explicit, bounded by the partition and subject to the real BlockIO media's permissions. They do not change GPT or clear hardware read-only flags.

`KBL_DECLARE_PROGRAM()` requires only the 40-byte base. A program that requires arguments or firmware handles should declare `KBL_DECLARE_PROGRAM_WITH_API_BYTES(KBL_PROGRAM_API_ARGV_BYTES)` or `...KBL_PROGRAM_API_UEFI_BYTES`. The note's minimum requirement is checked before entry. Optional guarded use, as in `hello_yield.c`, permits older shorter callback tables.

`examples/hello_yield.c` deliberately forces real RELATIVE relocations, writes output, reads the actual CurrentEL instruction, compares it with the host callback, optionally prints `argv[0]`, yields, then writes again. `examples/uefi_environment.c` demonstrates explicit absence handling for real firmware capabilities; its presence branch is not validated by a fake UEFI environment.

For an ordinary C program with files, build with `python runtime/sdk/build_port.py --source runtime/examples/port_copy.c --require-files --output build/copy`. `port_hash.c` streams SHA-256 from files or partitions; link `runtime/kbl_sha256.c` with `--extra-source`. Programs declaring only the original 40/56/64-byte prefixes retain that compatibility; FILE136 programs are rejected before entry on shorter runtimes. The EFI driver deliberately leaves the raw `uefi_environment` pointer NULL.

The native shell includes `dd if=PATH of=PATH bs=N count=N skip=N seek=N conv=fsync`, `sync`, and streaming `sha256sum`. File writes and partition writes report confirmed partial I/O and errors; they are never silently replayed. The companion console supports RAMFS push/pull and `:pullpart LABEL LOCAL` with source size/hash checks before and after transfer. USB ADB exposes the same started environment; its lifecycle is documented in [ADB](../adb/README.md).

## Loader profile and ownership

`kbl_elf_load` validates the complete load plan before allocating. It rejects interpreter and dynamic dependency requests, non-KBL ABI images, overlapping permission pages, executable writable pages, executable stacks, invalid entry points and unsupported relocations. Only self-contained `R_AARCH64_RELATIVE` entries with symbol index zero are supported; every 8-byte target must lie in writable non-executable image memory and every addend must point into the same image. ELF PT_LOAD alignment is honored using a suitably aligned private allocation, with no fixed load address.

The provider must allocate private RW/NX memory, implement page protection and instruction-cache maintenance, and release protected allocations. The loader clears BSS, copies file bytes, applies relocations, revokes access to image gaps, sets per-segment permissions and flushes executable regions. Missing protection/cache support is an error, not permission to silently use W+X memory. Optional GNU RELRO metadata does not currently add protections beyond the PT_LOAD permission plan; build this SDK profile with `-z norelro` as supplied.

The allocation source is independent from the borrowed input file. Input and output overlap is rejected. Failure releases any non-null allocation returned by the provider; successful images are released exactly once with `kbl_elf_unload`. A live output image cannot be overwritten by another load. `kbl_elf_call` is enabled for native AArch64 only and checks the API requirement and reported EL before/after entry. The runtime owns scheduling, argument lifetime and all resource capabilities.

## Validation and limits

```text
python runtime/test_elf_runner.py
```

The host tests execute the real parser/loader against a memory-provider fixture. The independent Unicorn test executes the actual ARM64 loader, rebases and relocates the compiled demo, applies real emulated page permissions, enters the program and pauses/resumes its yield checkpoint. All provider I/O remains explicitly modeled. One model-consistency case detects that this Unicorn build can expose EL2 in PSTATE while its CurrentEL instruction still reports EL1; it is a negative case, not an EL2 boot claim.

These tests do not establish real UEFI allocation/protection, physical EL, firmware registration, complete `sh` support or normal phone boot. This is privileged native code, not a sandbox: a program that faults, changes system state, or never yields can disrupt the explicitly started runtime. Entry gating and valid ELF structure do not provide fault containment for arbitrary code.

## Display and shutdown commands in 1.3.4

The existing program ABI1 and FILE136 table are unchanged. The native Shell adds `screenoff`, `screenon`, `screenstatus` and explicit `poweroff`; these are firmware-backed built-ins, not Linux syscalls or new raw-pointer capabilities for SDK programs. Fastboot display requests can run without creating the native KBL environment. They do not enable ADB. Only explicit kbladb enables the ADB transport.

The display provider reports real UEFI Get/Set results and unsupported capability rather than presenting a black framebuffer as successful power control. `poweroff` requests UEFI Shutdown only after explicit command entry and a successful filesystem and block-device sync. A successful shutdown can disconnect the host before a final task reply; no client replay is permitted. This release's physical poweroff operation is reserved for manual testing. Exit, Ctrl+C and kbloff retain their earlier lifecycle semantics.

## Explicit EFI bootloader mode

`efi PATH [ARGS...]` retains its existing behavior and passes the original file bytes to the UEFI provider. To prepare an ABL/efisp RAM copy for the bootloader route, explicitly use `efi -bl PATH [ARGS...]` or `efi --bootloader PATH [ARGS...]`. Preparation derives the supported boot-mode assignment from the input PE and changes only its uniquely proved RAM location. Invalid PE, missing targets and ambiguous targets stop before the UEFI provider is called. The source file and physical partitions are untouched by preparation.

The switch is consumed only before PATH. A repeated switch before PATH is an error; switches occurring after PATH remain child arguments. Existing UTF-8 to UTF-16 `LoadOptions`, quoting, same-EL checks, firmware error reporting and cleanup remain in effect. For example, `efi -bl /candidate.img 'hello world'` passes only `"hello world"` as child options. This is not a Linux executable loader or a guarantee that arbitrary EFI applications implement an ABL bootloader route.

EFI loading has no fixed file-size or SizeOfImage policy ceiling. Default RAMFS file/total quotas are likewise unset (SIZE_MAX); embedding callers may still supply explicit nonzero quotas. Loading depends on available allocation capacity and the PE format, with arithmetic bounds and allocation-failure cleanup retained. Fastboot transfers retain their actual wire-format field bounds; STORE has no fixed 16 MiB policy ceiling. Streaming ADB and mounted filesystems remain available.

## Optional invocation memory in 1.3.6

ABI1 keeps its original 40/56/64-byte prefixes and the FILE136 prefix. The new
152-byte `KBL_PROGRAM_API_MEMORY_BYTES` tail adds `memory_allocate(host, bytes)`
and `memory_release(host, pointer, exact_bytes)`. New callers check table length
and both pointers before access. Returned storage is 16-byte aligned, remains
valid across yields, and belongs to the current invocation. Returning or cancelling
a job releases its remaining SDK blocks. An exact current-job pointer/size match
is required for explicit release; runtime-internal file and namespace storage is
outside this capability.

New C-port binaries use this optional host memory for malloc/calloc/realloc/free
when available, including descriptor/FILE metadata. Capacity follows actual
backend allocation availability. Older runtimes and shorter API tables keep the
configurable private arena (`--heap-bytes`, default 65536). Existing compiled
binaries keep their earlier allocation behavior; recompilation enables this
optional capability. This is same-EL cooperative memory ownership, not isolation.
