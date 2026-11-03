# Native KBL USB transport

From 1.3.3, `fastboot oem kblstart` starts native KBL only. The separate exact
`fastboot oem kbladb` command starts KBL if necessary and explicitly enables
ADB. Repeating it on a running or busy KBL preserves its context, generation,
current job and RAM files. `kblin`, `kblstatus`, normal boot and ordinary
Fastboot never arm ADB. Both complete entry names are recognized by the lazy
unknown-command gate, before registration or any EFI call; suffixes and
prefix lookalikes fall through the original FAIL path.

ADB remains deferred until the exact `kbladb` final ACK completes. Replies
report `KBL1 adb=waiting-start-ack`, then read-only `kblstatus` reports `active`
after preparation/USB re-enumeration. `unavailable-*`, `control-error` and
`transport-error` retain their diagnostic meaning. ADB preparation failure
preserves the native KBL environment. After OFF parks the resident transport,
`kblstart` recreates KBL while ADB stays parked; `kbladb` explicitly resumes it.
The host sends each start/enable operation once and only repeats status/page
queries while reconnecting.

Command/string bounds: native dispatcher packet clamp is 64 bytes plus its
NUL in a proved 65-byte buffer; parent suffix/name scans accept at most 63
bytes; controller wire requests use a 63-byte budget and a 64-byte explicitly
terminated driver buffer (the host defaults to a conservative 60-byte budget);
native final formatter is 64 bytes (4-byte tag,
at most 59 payload bytes, NUL); each cached INFO slot is 57 bytes (at most 56
ASCII bytes, NUL). The 24-slot reply cache accommodates busy-job and full ELF
plus ADB diagnostics in one immutable reply generation. The parent registration
name region and entry region retain explicit capacity checks. Long shell lines
remain bounded multipart uploads, independent of command string buffers.

`kbl_adb_usb.c` is a freestanding composite-descriptor builder and transfer
ledger, integrated by the driver. It does not poll a USB event queue, find a
protocol, launch a network server, change EL, or install its own hook. The
original shared event owner calls the two event entry points exactly once for
each returned event. `runtime/adb/kbl_adb.c` owns the ADB wire/service protocol.

The parent passes the original descriptor-set pointer resolved from the actual
StartEx argument data flow, plus a source-span validation callback. Every source
descriptor and pointer array is checked and copied before controller changes.
Fastboot's VID/serial, interface body and EP1/0x81 descriptors are retained;
the new FF/42/01 interface gets the first unoccupied endpoint pair starting at
2/0x82. SS endpoints receive the required companion descriptors. An existing MS
OS 2.0 platform capability causes a clean preparation error rather than vendor
channel replacement. The same storage keeps a deep copy for failure rollback.

The composite-only device descriptors use debug PID `0xD0D1` and device revision
`0x0102`, consistently for HS and SS. They are configurable with
`KBL_ADB_USB_DEBUG_PID` and `KBL_ADB_USB_DEBUG_BCD_DEVICE`. The original descriptors,
including their original PID/revision/serial, stay byte-for-byte intact for
rollback; firmware behavior before explicit ADB opt-in is unchanged. Successful OFF keeps the
debug composite identity until reboot, retaining the active Fastboot connection.
Changing only bcdDevice refreshes Windows OS-descriptor cache identity, but does
not prevent a legacy INF matching the same VID/PID from claiming the whole
composite device. A separate PID also separates those parent driver bindings.

Microsoft OS 2.0 configuration subsets use zero-based configuration indices,
not the similarly named USB configuration descriptor's bConfigurationValue.
The test uses USB configuration values `1,4,7` while expecting OS 2.0 subset
indices `0,1,2`; the original USB values remain unchanged. See Microsoft's
[official descriptor example](https://learn.microsoft.com/en-us/windows-hardware/drivers/stream/uvc-camera-implementation-guide)
and [composite parent driver selection](https://learn.microsoft.com/en-us/windows-hardware/drivers/usbcon/enumeration-of-the-composite-parent-device).

`init` and `arm` are pure preparation. `step` performs its first transfer-size
query, DMA allocation, control-router installation and StartEx only after the
parent's activation fence confirms successful explicit `kbladb` and forwarding
of the original native OKAY's exact terminal DMA completion, and establishes
quiescent Fastboot RX ownership. The real provider's StartEx stops/restarts the
controller, so this first activation reconnects USB. Normal boot/prestart has no
new USB initialization. The driver must leave the original Fastboot event path
running and pass ADB events by endpoint plus exact DMA pointer.

Each ADB input and output span remains owned by the controller until an exact
terminal completion returns it. Send/Abort errors and timeouts do not prove
ownership returned. Reported completed bytes are passed once to ADB core;
terminal success requires the requested byte count. A short successful TX
completion is a transport fault: the confirmed prefix is reported with an error
and the remainder is never retried as a new USB short packet. A final stop reply
is allowed to complete before OUT is stalled/aborted. Malformed or foreign
completions are not substituted for owned completions.

USB output preserves the ADB host's message-read boundaries. Every 24-byte ADB
header is sent as a separate transfer, followed by its payload, if any. Payload
fragments respect the provider's actual MaxTransferSize; every nonfinal fragment
is a multiple of 1024 bytes when SS descriptors exist, or 512 bytes for HS-only
descriptors. A limit below that packet bound fails preparation. The transport
does not add an IN zero-length packet after a packet-aligned payload. This
matches the [AOSP USB client's header and payload reads](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/android15-qpr2-s8-release/client/transport_usb.cpp):
the host requests a packet-rounded buffer but requires exactly 24 header bytes,
then exactly the advertised payload length. Joining a header and short payload
in one USB transfer therefore causes a host disconnect even when the byteframe
is valid ADB. Host-sent OUT zero-length packets are accepted without feeding
empty input to the protocol core.

The root installs a lower UsbfnIo.EventHandler forwarding hook only after the
same activation fence, after proving the function-table slot is presently normal
writable memory. `fn_event` consumes exact Microsoft OS requests and its own EP0
data/status DMA; all other requests and bus events continue to the original
owner. EP0 setup preemption aborts and retains the old buffer until terminal
return; a subsequent owned setup is queued. MS OS 1.0 and 2.0 descriptors expose
WINUSB plus Android's interface GUID. Physical Windows binding still needs
validation: cached whole-device bootloader INF ownership can prevent USBCCGP
from exposing child interfaces even when descriptors are valid.

Healthy OFF is separate from endpoint/controller teardown. The driver closes
native file sessions, stops and releases native/RAMFS/ELF resources, and parks
the same ADB core as a minimal stopped responder. New services receive CLSE;
no native command or file callback is admitted. The controller's RX/TX buffers,
sequence state, EP0 router and descriptors remain resident. There is no STALL,
Abort, ClearStall, new bulk arm, StartEx, Stop or USB re-enumeration on this path.
ADB can remain visible to the host while its native environment is stopped.

A later explicit `kbladb` creates a new native environment if needed and resumes
the same core only after that command's original Fastboot ACK terminal completion. It does not
replace the ADB connection or reset host/device bulk sequence state. Partial
OPEN packets from an older STOP/START generation are rejected rather than
executed in the replacement environment. See `runtime/adb/README.md` for the
separate native-quiescence and protocol-drain predicates.

Transport/protocol faults retain full teardown: wait for TX terminal return,
abort RX, wait for its exact terminal DMA return, then STALL the idle private
ADB endpoints once each. Those fault buffers are released only when eligible;
uncertain controller ownership retains them. Descriptor storage, EP0 router and
control DMA remain resident until reboot, so `can_release` stays false after
successful composite activation. Fastboot's endpoints and events remain owned
by its original path. A faulted core is never resumed as a healthy parked core.

Only a failed first StartEx may attempt restoring the original descriptor set;
failed restoration retains the router/descriptors/DMA for deferred recovery.
The callback executing this component must never unload its own image. Driver
cleanup and portable KBL/core destruction are separate lifetime operations.

Offline validation command:

```powershell
python runtime/uefi/tests/run_adb_usb.py
```

The report records host and actual AArch64 state-machine tests at two relocation
bases (FP disabled, X18 reserved) and executes the current Qualcomm firmware's
real HS/SS descriptor parser on the generated buffers. Allocation, CopyMem and
controller callbacks in these tests are models. They do not establish physical
endpoint capacity, Windows binding or continued Fastboot operation on hardware.

Primary interface/protocol references: the local Qualcomm `EFIUsbDevice.h` and
`EFIUsbfnIo.h` headers, current UsbDeviceDxe PE recorded by SHA-256 in the report,
[Android ADB](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.h),
and Microsoft's [WinUSB automatic installation](https://learn.microsoft.com/en-us/windows-hardware/drivers/usbcon/automatic-installation-of-winusb)
descriptor specification. All new component code is independent MIT code.
