# Explicit UEFI application execution

`efi PATH [ARGS...]` is an explicit KBL command. It reads a complete PE file from the native filesystem, supplies its actual parent image handle to standard UEFI LoadImage, sets the child's LoadedImage.LoadOptions to a private UTF-16 copy, and calls StartImage. Firmware authentication and image loading remain firmware-owned. Only AArch64 PE32+ EFI_APPLICATION images are accepted; PE sizes, section ranges, entrypoint backing, data directories and DIR64 relocations are checked before firmware calls. No machine/version address list is used.

The source-file length and PE `SizeOfImage` have no fixed 64 MiB limit or replacement byte cap. Source lengths must fit the platform's `size_t` and address span; PE mapping extents retain their encoded field widths, alignment, section and directory bounds. Available firmware memory and `LoadImage` determine whether a valid image can actually load. Allocation and loader resource failures retain ownership/cleanup and same-EL checks.

Options use their actual UTF-16 length, bounded only by `LoadedImage.LoadOptionsSize`'s uint32 field and valid address ranges. The optional `efi_run_full` provider returns the complete firmware exit-data view with an explicit release callback; it adds no 256-unit truncation. While borrowed, the launcher rejects cleanup/new EFI calls and prevents driver unload. Release failures retain the firmware allocation for a later explicit cleanup retry. The original `efi_run` ABI and fixed result structure remain available with their compatibility preview, so older providers and callers retain their original contract.

Execution is synchronous and exclusive. The native executor does not yield or allow another session/file operation to reenter the callback. Existing USB configuration stays in place; there is no added timer, USB hook or active transport pump while the child executes. Fastboot/ADB responses resume after a returning child. Long-running software can exceed host timeouts. An application that takes over boot may never return. Same-EL firmware applications can change global firmware state or fault; this mechanism is not fault isolation and does not promise that arbitrary applications preserve Fastboot or system boot.

The launcher directly measures CurrentEL before LoadImage and after it, and before/after StartImage. It requests no EL transition. An unexpected EL change quarantines ownership and avoids making further Boot Services cleanup calls at the wrong EL. The native START configuration uses a 256 KiB task stack for firmware calls; stack size does not make third-party applications isolated.

The module owns its source-file copy, UTF-16 options and firmware ExitData. It explicitly unloads an unstarted or still-loaded child, and confirms an absent handle when an application already auto-unloaded on return. Failed unloading/freeing retains pointers and prevents controller unload until explicit cleanup succeeds. UEFI specifies that applications unload on exit; see [UEFI image execution](https://uefi.org/specs/UEFI/2.10/07_Services_Boot_Services.html) and [system-table image types](https://uefi.org/specs/UEFI/2.9_A/04_EFI_System_Table.html). Arbitrary allocations/events made by a child are the child's responsibility.

No LoadImage/StartImage, protocol lookup, timer registration or USB initialization occurs from this module before explicit START and the efi command. The original 1.2.17 EfiMain source body is byte-identical (audit in build/kbl-efi/efimain-baseline.json).

## Standalone examples

Build without the model tests:

```
python runtime/uefi/build_apps.py --llvm-bin LLVM_BIN --output build/kbl-efi
```

This requires clang and ld.lld with the AArch64 Windows PE target, not an OS-specific SDK library. `apps-manifest.json` records source/header hashes, flags, commands and PE hashes. Both apps are freestanding, general-registers-only, reserve x18, have DIR64 relocations and no imports. Neither changes USB configuration, enters another EL, registers callbacks or calls ExitBootServices.

- `return.efi`: directly reads CurrentEL twice, optionally prints a short console message, then returns EFI_SUCCESS. Example: `efi /tmp/return.efi`.
- `protocols.efi`: uses only standard LocateHandleBuffer/ProtocolsPerHandle to enumerate protocol GUIDs. It calls standard LocateProtocol for Qseecom GUID `a74862ce-680f-4fe1-a311-df41f4030391` only to report presence; no Qseecom/Keymaster/ICE/unknown method is invoked. Example: `efi /tmp/protocols.efi skip=0`, then use the returned next index. Each page contains two entries. It intentionally calls standard Exit with EFI_ABORTED so firmware returns its bounded UTF-16 diagnostic through ExitData. That status is expected for this diagnostic tool.

## Offline models

```
python runtime/uefi/tests/run_efi.py
```

The loader fake-Boot-Services model covers actual compiled AArch64 sample PE validation, malformed bounds, allocation failure, authentication rejection, auto-unloaded and retained children, unload/free failure ownership, nested rejection, bounded exit text, UTF-16 validation and wrong-EL quarantine. The protocol-inventory host model covers paging, GUID formatting, presence-only Qseecom probing, cleanup and invalid options. Model execution is not phone firmware validation. Root performs any later device testing separately.
