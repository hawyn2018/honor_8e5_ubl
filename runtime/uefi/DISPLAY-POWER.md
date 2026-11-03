# Explicit display control and shutdown

`fastboot oem screenoff`, `fastboot oem screenon`, and the read-only
`fastboot oem screenstatus` lazily load the controller image only. They do not
start native KBL, bind the USB observer, or enable ADB. Other Fastboot commands
and normal boot never call the display module. In a started KBL shell the
commands are `screenoff`, `screenon`, and `screenstatus`.

The provider is the published `EFI_DISPLAY_POWER_PROTOCOL` located by GUID
`f352021d-9593-4432-bf04-67b9f3b76008`. Revision is UINT32 at offset 0; Set and
Get are pointer members at offsets 8 and 16 with the protocol `This` argument.
Off is state 1 and Maximum/on is state 2. No hardware register, model name,
firmware-version table, arbitrary function pointer, or fixed source-image
address is used. The protocol implementation owns physical panel/backlight
handling. Missing/broken providers fail explicitly; no framebuffer black fill
is reported as panel power-off.

Every operation locates the actual protocol again and calls Get. Off/on first
read the previous state, call Set when needed, then read Get again. An initial
Unknown (0) is reported as unknown by status, but explicit off/on may initialize
it with Set and must verify the resulting known state through Get. A successful
Set with mismatching Get is an error. Repeated already-off/on requests are
idempotent. `screenon` always requests Maximum; it opens a display even if its
first screenoff was issued while it was already off. The prior standard state
is recorded as a diagnostic; standard DisplayPower has no numeric brightness
getter. No unsupported Qcom brightness ABI is guessed.

Replies contain 10 bounded `KBL1 display_*` lines. `display_observed=1` and
`display_state` refer to that operation's actual protocol Get result. Status
is read-only, so its `display_set_attempted=0` and `display_set_status` is an
UNSUPPORTED sentinel, not a stale success from an earlier command. Off/on
transactions themselves retain that call's Set return and subsequent Get.
Fastboot uses the existing generation-bound reply cache and sends one native
final ACK; a read-only status command works without a KBL session.

`poweroff` exists only in the KBL shell. After the existing full sync command synchronizes open write handles, mounted
filesystems and all enumerated writable block devices (including closed raw
write handles), it calls the standard Runtime Services
`ResetSystem(EfiResetShutdown=2, EFI_SUCCESS, 0, NULL)`. Sync failure cancels the
shutdown. There is no cold/warm reboot fallback. ResetSystem normally never
returns, so the transport can disconnect before a shell exit reply; clients
must not replay it. A firmware function that unexpectedly returns reports an
error and retains the KBL context. `kbloff`, `kbl off`, `exit`, Ctrl+C and normal
boot have no path to this shutdown function.

## Verification

- `runtime/uefi/tests/test_display.py`: actual ARM64 production display/reset
  ABI code, with local C protocol mocks. 34 checks per requested EL configuration.
  The report measures actual CurrentEL; this Unicorn build remains EL1 after
  an EL2 request, so this is not EL2 hardware evidence.
- `runtime/uefi/tests/test_driver_display.py`: actual relocatable driver and
  native shell at two image bases, explicit mock EFI services. Exercises
  pre-start display without native allocations, full Set/Get, idempotence,
  malformed requests, false-success Set, unsupported protocol, and shell
  liveness after display-off/on.
- `runtime/native/test_native_runner.py`: 17 groups, including unavailable
  optional providers, exact screen command parsing and mock-only explicit
  poweroff. The raw-block cache fixture verifies FlushBlocks failure cancels
  shutdown after a raw dd without fsync has closed its handle. No physical device or shutdown API is called.
- Phone acceptance is separate; automated physical tests must never run poweroff.

## Primary interface references

- [EFI display protocol](https://learn.microsoft.com/en-us/windows-hardware/drivers/bringup/efi-display-power-protocol)
- [SetDisplayPowerState](https://learn.microsoft.com/en-us/windows-hardware/drivers/bringup/efi-display-power-protocolsetdisplaypowerstate)
- [GetDisplayPowerState](https://learn.microsoft.com/en-us/windows-hardware/drivers/bringup/efi-display-power-protocolgetdisplaypowerstate)
- [Power states](https://learn.microsoft.com/en-us/windows-hardware/drivers/bringup/efi-display-power-state)
- [UEFI Runtime Services ResetSystem](https://uefi.org/specs/UEFI/2.10/08_Services_Runtime_Services.html#reset-system)
