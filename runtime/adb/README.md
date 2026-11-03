# Native KBL ADB service

This is a phone-side ADB wire-protocol implementation for the explicitly
started KBL environment. The portable core has no socket, USB discovery,
controller initialization, independent interrupt, or exception-level transition.
The UEFI driver supplies the separately owned USB transport after the exact
successful `kblstart` ACK has completed and returned to the original Fastboot
loop. Native shell jobs, RAM files and raw partitions use the real KBL backend.

The current driver selects an explicit USB debug session without Android RSA
authorization. This is reported as `KBL_USB_DEBUG`; it is separate from Android
`adbd` and its authorization UI. An optional pair of fresh-token/signature
verification callbacks is available to native integrators. Supplying only one
callback is rejected. The test signature callback is a model, not cryptography.

## Protocol and services

The parser accepts fragmented CNXN, OPEN, WRTE, OKAY and CLSE packets, plus AUTH
when its callbacks are supplied. Payloads are bounded at 64 KiB and reduced to
the host's negotiated size. Legacy packet checksums and modern checksum-free
negotiation are implemented. The advertised feature list is exactly
`shell_v2,stat_v2,ls_v2`.

`shell:` and shell-v2 provide a native KBL command console or one command. The
interactive console is line oriented with a `# ` prompt; its pty mode echoes
input and handles backspace/CRLF. It is not a Linux PTY or Android process
service. Shell-v2 emits stdout, a one-byte exit result and orderly channel
closure. Each channel has its own native session, current directory, output,
job and file handles. `exit`, Ctrl+C or channel close cancels only that session;
the shared KBL world and other channels remain. Reconnecting creates a fresh
session rather than taking another channel's job.

`sync:` supports SEND/DATA/DONE, RECV, legacy STAT/LIST, stat-v2/lstat-v2 and
list-v2. Data flows through the SDK's generation-checked file handles. Files
can be RAMFS, mounted-filesystem or explicit `/dev/block/by-name/LABEL` paths.
Partition bounds and filesystem read-only errors are retained. Completed SEND
explicitly syncs before reporting success. Mounted-file close may flush metadata;
confirmed partial writes are not rolled back, and uncertain failed writes are
never automatically replayed. Metadata timestamps are zero because this VFS does
not supply persistent timestamps. Unsupported services close with an error.

Eight ordinary service channels can coexist, plus a separate reserved channel
for `shell:kbl off`. Shells share RAMFS and mounts but keep their own current
directories, output and generation-checked handles. Native work is queued in
FIFO order on a single executor. A sync connection acquires a lease only once a
complete STAT/LIST/RECV/SEND request is available; idle connections and incomplete
path headers hold no lease. The lease ends after the operation, file close and
commit. SEND/RECV are serialized transactions, including their network waits;
a stalled active transaction can delay queued work. Command, path, sync input and channel tables grow on demand using the configured
allocator. The first eight ordinary slots are inline storage, not a session cap;
the dedicated OFF slot remains available when ordinary allocation fails. Control
replies use an allocated queue and keep borrowed TX bytes unchanged. Real ADB
frames remain bounded by the negotiated payload; larger shell records, paths and
file transfers span multiple frames.
Poll order and channel output rotate rather than always selecting one channel.

Explicit UEFI execution is synchronous. Other work queues while an EFI program
runs; this does not promise real-time responses or preemption of a program that
fails to return. The reserved OFF channel avoids ordinary channel exhaustion,
but cannot interrupt an executing synchronous firmware call. This remains a
native UEFI environment rather than a Linux syscall implementation.

## Ownership and stop sequence

`output` lends a persistent frame span. Its bytes remain owned until
`output_done` confirms terminal DMA completion/abort; partial completions advance
the frame. Send success or abort-request success is not DMA completion. Core
destruction returns AGAIN while a span is borrowed or a callback is active.

Both shell `kbl off` and external `kbl_adb_request_stop` set an orderly stop
request. Repeated requests are idempotent. They broadcast cancellation to all
native channel sessions, close their owned sync handles,
drain pending output/exit, send CLSE and wait for reciprocal acknowledgments.
Late stdin or CloseStdin is acknowledged and discarded while the existing
stdout/EXIT drains, so a control CLSE never overtakes the shell's exit record.
`stop_ready` proves full protocol drain; it is separate from native quiescence.

Healthy OFF parks the same core and keeps its minimal USB ADB responder alive.
New OPEN requests receive stateless CLSE replies, without creating rejection
channels, jobs or file sessions. A full control ring backpressures RX instead of
faulting; repeated incoming requests do not block native resource release.
Owned file sessions are retried closed independently of peer acknowledgments.
The driver's host stack can stop the native backend as soon as
`native_quiescent` is true, then attest that destruction with `native_stopped`.
That confirmation clears stale job state and makes `parked` true. It never
frees an executing fiber or its own callback. Native ELF/RAMFS resources are
released while the core, protocol state and controller-owned buffers remain.

After creating the replacement native backend and completing the explicit
START acknowledgement, the owner calls `resume` on that same healthy core.
It preserves authentication, channel identifiers, pending output and partial
RX/TX. OPEN packets spanning a STOP/START generation remain rejected; they
are not replayed against the new backend. Resume before native-stop confirmation,
after a sticky protocol fault, or after an exception-level change is rejected.
An online ADB transport while parked does not imply an active native environment:
shell and sync services remain closed until explicit START.

Protocol/transport faults retain the separate disconnect and full teardown path
after borrowed DMA has returned. Exception-level checks remain active before and
after callbacks. `healthy`, `native_quiescent` and `parked` are bounded state
queries; they perform no USB action or native job poll.

After successful composite USB activation, the driver keeps the descriptors,
EP0 forwarding router and original Fastboot observer resident. Stopping ADB
does not issue another StartEx, controller Stop or USB re-enumeration. Those
resident references deliberately prevent unloading the EFI driver. Native
KBL/ELF/RAMFS resources are independently released.

Fault teardown orders operations as TX terminal completion, OUT abort,
identified OUT terminal completion, then one successful stall per idle ADB
endpoint. A pending OUT transfer is never stalled before its abort completes.
Successful OUT/IN stalls are cached while later cleanup retries. The native USB
observer initializes its event output to NONE/0 on every call, including error
returns, and preserves the underlying EFI status. This matches the native
provider's contract; the original Fastboot loop reads the output even when the
provider reports an error.

Healthy park/resume does not enter that endpoint-stop path. It does not STALL,
abort/rearm bulk DMA, change descriptors, call StartEx, or re-enumerate USB.

The composite configuration has its own debug PID `D0D1` and device revision
`0102`; the vendor ID and serial remain the original ones. Before START and in
failure rollback the original device descriptors remain unchanged. HS and SS
identities agree. Microsoft OS 2.0 configuration subsets use the zero-based
configuration index, independently from the actual USB configuration value.
The control router accepts the actual driver's 32-byte message union as well
as an 8-byte setup packet, reading only the first 8 bytes. Short messages are
not dereferenced. Tests include nonzero tail bytes and the actual lower driver
message-size result, not only a compact synthetic packet.

USB transfer boundaries are separate from the portable core's byte frame.
The adapter sends the 24-byte ADB header alone, then its body. Non-tail body
chunks are maximum-packet aligned; no extra device-to-host ZLP is inserted.
A short successful terminal count is treated as a transport fault after the
confirmed bytes are reported, rather than retrying a broken USB packet. This
matches legacy Windows readers that pad their USB buffers but require exactly
the header or declared body length. Tests exercise those read completions
instead of silently concatenating all transfers into a TCP-like stream.

`kblstatus` adds four bounded, RAM-only diagnostic values:

- `adb_usb`: phase, composite, router, connected, fault, RX pending, TX pending,
  EP0 pending, stop requested.
- `adb_flags`: driver error, driver stop, core stop, core stop-ready, RX held,
  disconnected, successful stall mask, configuration uncertain.
- `adb_fw`: latest component EFI status and first lower event error, in hex.
- `usb_errors`: lower event error count since this observer was initialized.

These reads perform no protocol discovery or USB action. An error phase is
retained rather than replaced by `active` just because a connection exists.

## Native external file broker

`native/kbl_native_io.h` provides session create/close, submit/poll and
session IO begin/end, plus the compatible default-session entry points. The
ADB configuration supplies all six per-channel callbacks together. Legacy
integrators may omit the entire group and retain the serialized default backend.

Each active file transaction gets a capability-limited SDK API and its own
explicit owner. It runs on the driver's host stack with `in_job` left zero.
Calls never fake a native fiber or change the 136-byte file SDK ABI. IO begin
returns AGAIN while its stable FIFO ticket waits; retrying it does not enqueue
another transaction or replay a write. Session end closes only its generation
handles. Channel CLSE is consumed immediately and cleanup retries asynchronously,
so closing a queued owner cannot block another channel's active DATA packets.
Native cleanup proceeds independently of wire acknowledgments. All driver
callbacks must remain inside the ADB operation scope, including fault cleanup
and destruction; setting a scope only during `io_begin` is insufficient.

## Verification and limits

`tests/test_server.py` compiles the production parser and native file broker,
then tests fragmented packets, stop/cleanup, 64-bit stat, TX borrowing, and a
stock `adb.exe` against a localhost protocol fixture. That fixture's command
executor is explicitly modeled. Its TCP socket is test-only and is not shipped
as a Fastboot-to-ADB bridge.

`tests/test_multi.py` exercises multiple shell/sync wire channels, isolated
session routing and directories, FIFO transaction queues, incomplete path
headers, queued-owner close, Ctrl+C, channel growth and OFF before peer ACK. Its
session executor is an explicit model. `tests/test_mounted_fat.py` uses the real
native file API and FatFs to verify SEND/RECV and parent-directory handling.
Instruction-level native session execution has a separate ARM64 fixture.

`tests/test_park.py` uses no sockets or device. It tests native release despite
rejection floods and borrowed TX, half-header/half-body OPEN across generations,
file-end retries without a peer ACK, parked handshakes, and rejected resume
after EL, malformed-packet and partial-TX faults. The backend destructor/start
callbacks in this portable fixture are explicit models; actual native backend
destruction is covered by the ARM64 driver integration suite.

`uefi/tests/test_driver_adb_unicorn.py` executes the production ARM64 EFI
driver, native shell/fiber/VFS, partition provider, ADB core and USB ledger at
two load bases. It checks exact ACK ordering, deferred native RX abort-terminal
fences, shell and sync callbacks, partial raw-partition writes, external and
shell stop, and fault-TX cleanup. EFI allocation, span validation, controller
events/DMA, and the original Fastboot final-TX/RX-rearm are explicit models.
Physical USB enumeration, endpoint availability and host-driver binding remain
separate hardware checks. The test image has test-only probes; the normal build
contains none of them.

Protocol definitions follow the primary AOSP sources:

- [ADB packet engine](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp)
- [Shell protocol](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/shell_protocol.h)
- [Shell service exit framing](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/shell_service.cpp)
- [SYNC protocol structures](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/file_sync_protocol.h)
