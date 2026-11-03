# Controller contract tests

`test_control.c` links the actual `kbl_control.c` against a deterministic **fake backend**. It records callback calls, submitted bytes, job IDs, poll budgets and output frames. It is not a fake implementation marketed as a working microkernel: no firmware services, Linux shell, USB device or phone partition are accessed.

Run from the source root:

```text
python runtime/test_runner.py
```

`--host-clang`, `--ndk-bin` and `--output` override local tool paths. The script requires a native host Clang compiler and LLVM tools capable of compiling `aarch64-none-elf`. It places generated objects, the test executable and validation logs in the chosen output directory. It records source SHA256 values so each result is bound to the code actually tested.

The 36 controller tests cover:

- Ordinary Fastboot traffic and adjacent command names produce zero backend callbacks; only exact KBL namespaces are handled.
- Lazy snapshots, explicit start, idempotent start, no-start attach, retained literal `exit`/`quit`, disconnect/reconnect, and explicit/retryable shutdown.
- A failed start with a partially initialized non-null context preserves that context in the faulted state until explicit cleanup; no resource handle is silently discarded.
- Job preservation, poll budget and output-capacity arguments, binary output framing, signed exit codes, stale jobs and malformed backend results.
- Multipart assembly with exact offsets and final lengths, duplicate/reordered chunks, incomplete commit, atomic invalid hex/NUL rejection, stale IDs across uploads and sessions, and the entire 4096-byte staging boundary.
- Changed EL is reported as a fault, execution is deferred when detected before submit/poll, and cleanup context survives. Shutdown after an EL change waits for the original EL before invoking the fake cleanup callback.
- Exact-reader short reads, no-progress reads, callback errors, over-reported reads, zero-byte requests and range/overflow checks near UINT64_MAX.

The independent ARM64 compilation uses freestanding mode without builtin libc assumptions. The controller and fetch objects are linked together with `ld.lld -r`; unresolved symbols and disassembly of the combined object are saved. SVC/HVC/SMC/ERET instruction counts are checked. This only establishes properties of these portable modules; the backend must separately prove native same-EL execution and all real service dependencies.

`test_fetch.c` adds 20 synthetic-reader/wire tests, linked against the actual fetch and exact-reader implementations. It covers byte-exact partial I/O, parser/bounds checks, the 8 MiB cap, empty-source rejection, invalid reader/transport behavior, and closing a valid source exactly once. All source reads finish before DATA; failures after DATA use abort, never an in-band FAIL packet that could enter the downloaded file. A backend returning an invalid reader with no close callback has violated its contract; the module can reject it but cannot call a missing cleanup function.

Important limits: the poll test verifies that the budget is passed correctly; it does not create preemption if a backend ignores that budget. It does not prove CPU exception recovery, resource isolation, barebox integration, persistent UEFI context, physical USB reconnect, actual device EL, complete sh semantics or normal phone boot. Those require separate implementations and validation.

## Optional stock-Fastboot framing acceptance

```text
python runtime/tests/fastboot_tcp_fetch.py
```

This script makes **one** TCP attempt against a synthetic loopback device at `127.0.0.1`. It builds the real C controller/fetch sources into a host DLL and binds in-memory reader and wire callbacks with ctypes. It runs the stock workspace Fastboot client against that server; it does not use an ADB device or a phone Fastboot connection. `--clang`, `--fastboot` and `--output` override local paths.

The fixture advertises a 256-byte fetch cap, then serves 1025 binary bytes using partial source reads and partial wire writes. A passing run requires the client output to match the expected SHA256, multiple real fetch transactions, exactly one close/final per successful open, no aborts and no server errors. The test is separate from the default runner because it opens a localhost socket and native process execution can be restricted by the host sandbox.

A passing result establishes compatibility between the real C module's callback-driven DATA/body/OKAY sequence and that stock client's TCP implementation. The synthetic transport supplies framing and getvar support; neither has thereby been installed into UEFI/USB firmware.

## Optional PC Console/C-controller interoperability

```text
python runtime/tests/fastboot_tcp_control.py
```

This separate localhost fixture loads the actual C controller from a host DLL, invokes the stock Fastboot executable for every request, and imports the actual `tools/kbl_console/kbl_console.py`. It tests the production controller/client protocol together rather than replacing either with a Python imitation.

The backend is still deliberately fake: it records lifecycle callbacks and returns tagged fixture output. The test uses a roughly 108-byte UTF-8 command to exercise multipart transmission without hundreds of subprocesses. It checks `# ` prompts, idempotent start, same-session attach, local exit/Ctrl+C retention, a partially polled busy job reconnect, exact output recovery, and one final explicit shutdown. Ordinary getvar and a flash to a synthetic in-memory target must cause zero backend callbacks. The flash target is only the localhost fake device, never a phone partition.

Results and source/client/DLL fingerprints are recorded separately from the pure-C test report. A passing result is host/portable-controller interoperability evidence; it is not a real microkernel, actual EL measurement, firmware registration, exception-isolation or phone-boot result.

## Native KBL ELF program loader

```text
python runtime/test_elf_runner.py
```

This separate runner builds the explicit KBL ABI SDK programs, runs `test_elf.c` against a host-memory provider, then executes the actual ARM64 loader and examples under Unicorn. `sdk/README.md` documents the executable profile and optional callback tails. The parser tests include malformed and unsupported ELF metadata, permission-page overlap, source/relocation bounds, private-allocation alignment, BSS clearing, relocation rebasing, provider failures and exactly-once cleanup.

The Unicorn cases use real compiled loader/program instructions and modeled allocation/protection/I/O callbacks. They verify multiple load addresses, cooperative yield pause/resume, arguments, shorter ABI tables and absent real UEFI capability handling. A CurrentEL/PSTATE inconsistency in this emulator is explicitly treated as a negative model-consistency case, never as proof of EL2 hardware execution. Linux ELF compatibility, a real UEFI provider, shell `./` dispatch and hardware boot need their own integration checks.
