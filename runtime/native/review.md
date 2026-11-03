> Historical review: the former SIMD context described below is superseded by the final GPR-only profile in NATIVE-STATUS.md. No FP/SIMD/DAIF access remains in the current context switch.

# Native backend independent review

Date: 2026-10-29

Scope: read-only review of `kbl_native.c`, `kbl_native.h`, `kbl_context.h`, `kbl_context_switch.S`, associated SDK/ELF interfaces, and the native test fixture. No device operations or implementation edits were performed by this reviewer.

No remaining blocking correctness issue was identified within the documented single-threaded, trusted cooperative-program and valid-provider contract. Reviewed areas included allocation ownership and failure cleanup, RAMFS paths and replacement, parser bounds, immutable snapshots, output backpressure, suspended-program unload, stop boundaries, and the AArch64 context ABI.

Clang's AArch64 static analyzer completed with exit status 0 and no diagnostics. This is supporting evidence, not a proof of correctness. The reviewer did not rerun the full execution suite. The implementer reports the final regression run as seven groups / 1104 checks per run, a full register/control context roundtrip, and zero live allocations after cleanup.

## Finding resolved during review

An initially new `cp` destination was published as an empty file before the cooperative copy completed. A snapshot opened while the copy yielded could therefore observe the incomplete new destination. The implementer changed this to keep a new node detached but owned until commit, releasing the detached node on failure. Existing destinations retain their old bytes until commit. The added `copying_publishes_complete_files` regression covers publication of both new and existing destinations; the implementer reports it passing.

## Contracts and limits

- `stop` must run from the host context, after closing snapshot readers. It must not free its own active job stack. Open readers deliberately produce `AGAIN`.
- Provider allocation/release, actual CurrentEL, executable page permissions, and instruction-cache maintenance remain platform responsibilities. A provider's external failure bookkeeping must outlive a native context that has already been destroyed.
- Custom programs are trusted same-EL cooperative code. They must yield explicitly and release resources obtained directly through optional UEFI access. Arbitrary firmware allocations, protocol registrations, or retained callbacks created outside tracked provider APIs are outside native cleanup ownership. Callbacks into an unloaded program must not remain registered.
- Yield points bound cooperative work between checkpoints; they do not make firmware calls, ELF loading, or a non-yielding custom program preemptible. The runtime is not a Linux/POSIX process sandbox.
- The reviewed context switch preserves X19-X30, SP, full Q8-Q15, FPCR, FPSR, NZCV, and DAIF; X18 is reserved by the build. It performs no EL transition. Current emulator evidence observes EL1: requesting EL2 PSTATE still produces an actual `MRS CurrentEL` result of EL1, so these results are not EL2 hardware validation.

Follow-up platform validation should retain exact command/argument and quota boundary cases, clock-driven yield cases, and real firmware permission/resource cleanup checks. No hardware bootability claim follows from this source review.
