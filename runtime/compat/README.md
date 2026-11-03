# Freestanding C program port layer for KBL

This independently written MIT C subset adapts ordinary `main(int,char **)`
source to the existing KBL ABI1 entry. It adds no Linux syscalls, EL transition,
interrupt vector, native hardware driver or process model. All objects compile
with `-mgeneral-regs-only -ffixed-x18`; floating-point/SIMD programs are outside
this profile. Native AArch64 entry and SDK callbacks check the actual CurrentEL
against the API value and the level observed on entry. `main` returns normally;
failed cleanup or an EL mismatch turns a zero result into an error.

## Compile a C program

From the source root:

```text
python runtime/sdk/build_port.py --source runtime/examples/port_text.c --output build/port-text
python runtime/sdk/build_port.py --source runtime/examples/port_copy.c --require-files --output build/port-copy
python runtime/sdk/build_port.py --source runtime/examples/port_hash.c --require-files --extra-source runtime/kbl_sha256.c --output build/port-hash
```

Output is `program.kbl` plus a build manifest. `--source` accepts another ordinary
C `main`; use repeatable `--extra-source` options to link other C sources.
`--llvm-bin` selects Clang/LLD. The build checks self-contained ARM64 ET_DYN,
non-W+X LOADs, and only relative self-image relocations. There is no dynamic
linker, external library import or fabricated syscall implementation.

The default minimum API prefix is 56 bytes, covering `argc/argv`, so `port_text`
runs on the prior 64-byte API. `--require-files` requests the 136-byte VFS tail;
an older loader should reject that program before entry. Library file calls
independently check the tail size and callback presence before any access and
return `ENOSYS` when the file capability is absent.

After uploading through the companion console:

```text
:push "LOCAL_PROGRAM.kbl" /text
./text "hello world" 42
```

`port_text` uppercases ASCII argument bytes, uses the real allocator and integer
formatting, prints its execution EL, and verifies that the unsupported process
API returns `ENOSYS` without executing its string argument.

With the new VFS-capable runtime:

```text
:push "PORT_COPY.kbl" /copy
./copy /source.bin /copy.bin
:push "PORT_HASH.kbl" /hash
./hash /copy.bin
./hash /dev/block/by-name/abl_a
```

`port_copy` requires explicit source and destination. It opens the destination
with CREATE|EXCL, copies with short-I/O handling, calls `fsync`, reopens and checks
length and FNV-1a64. FNV here is a noncryptographic copy checksum, not a signature
or boot/authentication check. Existing files and block-device destinations are
not replaced by this example. The SDK itself exposes explicit block writes.
`port_hash` streams SHA-256 using the runtime's real SHA implementation. It reads
ordinary files or exact partition paths without making a full RAMFS copy.
Neither example
selects a slot automatically or performs an implicit firmware write.

## Implemented subset

- `stdio.h`: unbuffered `FILE` streams, open/close/read/write/seek, error/EOF,
  `printf`/`snprintf` and their v/f variants, integer/string/pointer conversions,
  `puts`, character/line input, `perror`. Formatting supports integer flags,
  width/precision up to the interface's `INT_MAX` count range, `h/hh/l/ll/z`;
  counts that overflow return `EOVERFLOW`. Floats return `ENOSYS`, unsupported conversions
  return an error. stdin has no console input provider and returns `ENOSYS`.
- `stdlib.h`: invocation-owned host memory when the optional MEMORY152 tail
  is present; a reclaimable, coalescing 64KiB private heap fallback on older APIs.
  Includes `malloc/calloc/realloc/free`, integer conversion with overflow checks,
  `qsort/bsearch`, integer absolute values. Change `KBL_COMPAT_HEAP_BYTES` when
  compiling the layer, or pass `--heap-bytes N` to the builder (at least 4KiB), to
  select another private-heap size. The builder imposes no heap budget above
  the AArch64 object-size range; the loader reserves the selected arena using
  available memory. This remains a per-program reservation, not a global cap.
- `string.h` and `ctype.h`: documented basic byte/string operations and ASCII
  classification/case conversion. There is no locale or Unicode case folding.
- `fcntl.h`, `unistd.h`, `sys/stat.h`: descriptor adapters over the KBL VFS,
  64-bit `off_t`, explicit `fsync`, path stat/mkdir/unlink. Opaque SDK generation
  handles remain private to the adapter; POSIX descriptors are local integers.
- `errno.h`: explicit supported errno values. A callback returning partial I/O
  plus an error returns the confirmed byte count, then delivers the deferred
  error on the next operation; stdio also sets the stream error immediately.
  Writes are not automatically replayed. Invocation cleanup closes owned handles.

There is no `fork`, `execve`, `system`, wall-clock sleep or mutable environment.
The declared process/sleep mutation APIs return explicit `ENOSYS`;
`system(NULL)` truthfully returns zero for an absent command processor. `getenv`
returns NULL for an absent environment. Functions not declared in these subset
headers require a real port instead of silent success. There is no full POSIX
claim, Linux binary ABI, complete libc or BusyBox binary compatibility.

Long byte loops and utility scans call cooperative checkpoints. This does not
create preemption or fault isolation for arbitrary privileged C code. Stdout
and stderr use the same KBL output callback. Files live in the runtime's RAMFS
or exposed block devices; there is no Android mount namespace or device `su`.

## Validation

```text
python runtime/compat/tests/run_ports.py --output build/port-tests
```

The host suite renames standard symbols to avoid host CRT interposition. It
checks allocation/coalescing, integer/text formatting, overflow, streams, stale
descriptors, short/error I/O, explicit cleanup, unsupported process APIs and the
64-byte API boundary. The ARM64 suite executes actual compiled C/entry/library
code and real CurrentEL instructions with CPACR FP access disabled at two load
bases. The ET_DYN loader, SDK file callbacks and yield responses in that suite
are explicit Python models. They do not prove firmware BlockIO or real thread
suspension. Separate runtime/phone validation covers those integration paths.

No third-party libc sources are included. The implementation targets the
semantics of the explicitly listed C/POSIX interfaces, not their complete ABI.
The primary C language/library reference is the public
[WG14 N1570 C11 committee draft](https://www.open-std.org/jtc1/sc22/wg14/www/docs/n1570.pdf).
The implemented integer/string/stream subset is narrower than that document;
the explicit limitations above remain part of this port's contract.

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
