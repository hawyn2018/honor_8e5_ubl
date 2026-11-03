# Private hnubl module broker — 2.9.2

Entry point:

```sh
CLASSPATH="$MODDIR/broker.jar" /system/bin/app_process /system/bin net.hawyn.hnubl.module.RootBroker
```

Normal module startup uses `net.hawyn.hnubl.module.ModuleMain`, which hosts the resident ROOT broker and marker together with a replaceable controller. The standalone `RootBroker` entry above provides the resident ROOT service; the 2.9.2 OTA listener belongs to the controller.

`../build_module.py` compiles the shipped broker/controller/discovery Java sources with `javac --release 17` against the Android platform JAR, then runs D8 with `--min-api 28` to produce `broker.jar`. The source inventory described below is separate from that builder's before/after source snapshot.

For the shared VM, `RootBroker.start(Context)` creates one resident ROOT listener and the single marker instance, then returns without blocking. Controller code uses an isolated child class loader. Replacing its OTA/native listeners does not restart the resident broker, close a ROOT connection carrying the upgrade, or create a second marker/consumption lock. `ResidentMarkerBridge` uses the resident `requestOtaMarker(String)` ABI; its 2.8.x compatibility path borrows the unique existing marker object from the old listener captures and retires only the obsolete OTA listener. It never constructs another marker. The broker has no `System.exit` call. Its cached client thread pool creates threads on demand and retires idle workers.

The C client is a native executable, even when packaged as `libbootforge_module_client.so` for extraction into the application's native library directory. Build it as an executable, not as a shared-library-only object. Invocation:

```sh
libbootforge_module_client.so /data/user_de/0/net.hawyn.honor8e5ubl/files/hnubl-module/root.sock -c 'id -u'
```

`--self-test` checks endian roundtrips without opening a socket or issuing a command.

## Root protocol

The filesystem socket is inside hnubl's private device-protected files. Directories have mode `0700`, socket entries have mode `0600`, and their owner is the currently installed application UID. Creation uses pinned directory descriptors and `O_NOFOLLOW`; SELinux labels are inherited from the pinned application directory, including user categories. The module's SELinux policy still needs the appropriate app-to-root-domain `unix_stream_socket connectto` permission. The broker does not alter the KernelSU allowlist.

Every connection is authenticated using the kernel's `SO_PEERCRED`, the installed package's current UID, exclusive UID ownership and the pinned hnubl signing certificate (including a verified signing history). The native client separately requires that the server UID be `0`.

After successful authentication, the server sends exactly eight ASCII bytes `HNUBLRA1`. Only then does the client submit a request:

1. Eight ASCII bytes `HNUBLRT1`.
2. An unsigned 32-bit big-endian UTF-8 byte length (within Java's array range).
3. Exactly those command bytes. Invalid UTF-8 and embedded NUL are rejected.

The response begins with `HNUBLRS1`, followed by frames: a one-byte type, an unsigned big-endian 32-bit payload length, then the payload. Types are `1=stdout`, `2=stderr`, `3=exit` (four bytes), `4=error before shell creation`, and `16=started` (empty). Child stdin is closed, stdout and stderr are streamed separately, and output is not retained in one large memory buffer.

The broker launches one `/system/bin/sh -c` process for an authenticated, complete request. It never retries a command. A disconnected client does not kill an already started command: the broker continues draining child stdout and stderr so an active write cannot block on abandoned pipes. No overall command timeout is imposed by the client; existing hnubl read-only deadlines remain controlled by `RootProcessIO`.

Client exit `126` means the command was not submitted, or the broker explicitly reported failure before process creation. Exit `125` after a complete request means the outcome/output is unknown; the command must not be replayed using another provider. A normal remote process exit is returned unchanged.

## Restricted OTA protocol and BEGIN

`OtaTransport` creates `<PackageManager ApplicationInfo.dataDir>/files/hnubl-module/ota.sock` for `com.hihonor.ouc`. The controller binds to the updater's actual CE/DE storage root and supplies this same path to its agent; a fixed DE assumption is not the OTA path contract. `SecureFs` uses a verified global root directory view and pinned descriptors when the resident process inherited an app-isolated mount view. UID, private mode, `O_NOFOLLOW` and SELinux-label checks remain in force. The listener accepts only the updater UID and a peer PID whose effective UID and command line agree with `com.hihonor.ouc` or one of its colon-suffixed processes. This endpoint never executes a shell command.

Send one uppercase command plus LF: `STATE`, `BEGIN`, `CONSUME` or `RESET`. The JSON reply includes typed `ok`, `state` and `decision`, ending with LF. For example:

```json
{"ok":true,"state":0,"decision":"DENY"}
```

The marker remains root-owned at `/data/adb/hnubl/freeotaok` (directory `0700`, regular file `0600`, no symlink/hardlink acceptance). Only a file containing exactly the single ASCII byte `1` is considered ready. Whitespace, missing/empty files, unexpected owner/mode and malformed values deny permission.

The resident marker ABI remains restricted to `STATE`, `RESET` and `CONSUME`. `BEGIN` is handled by the replaceable transport, so an already-running 2.8.x marker needs no new command implementation.

- `STATE` reads the same live resident marker without changing it.
- `BEGIN` is sent at the original payload-start boundary. Under the deferred-record transaction lock, it first uses the resident marker's `RESET`, validates that response, and durably replaces any old cycle with a new `ARMED` cycle bound to the current boot ID and source slot. A storage or marker failure does not produce a successful ARM acknowledgment.
- `RESET` at payload completion clears only the marker. It preserves `ARMED`, `DEFERRED`, `DONE` and later native-plan evidence; reopening an already-completed UI is not a new BEGIN.
- `CONSUME` first proves the real marker is exactly `1`. If no activation was deferred, or the current cycle is only `ARMED`, it uses the original resident consume path and adds an optional-native warning. A stale, malformed, failed or unfinished deferred record denies permission instead of being treated as absent.

The resident consume transaction is unchanged: atomically rename and fsync a single-byte `0`, fsync the parent directory, then report `ALLOW`. A failed write reports `DENY`. A lifetime process lock prevents two brokers from consuming the same marker concurrently. The broker never writes `1`; only hnubl's verified FULL OTA success path may do so.

If native enhancement setup is unavailable, `LegacyOtaFallback` maps BEGIN to the original marker RESET and returns diagnostic `warnings: string[]`. Original-slot consumption is permitted only when the bootstrap has positively established that no deferred record needs handling; an unknown record remains a denial. Optional-native failure is not a fabricated success for an existing deferred activation.

## Native delayed activation protocol

The optional `NativeSlotRuntime` and `NativeSlotEndpoint` are controller-owned. The private native socket is `/data/misc/update_engine/hnubl-module/native-slot.sock`; the OUC UID is not accepted there. Each connection is authenticated against the verified init-managed update_engine PID, effective UID, process start time, executable path/device/inode and SELinux domain. The native client independently requires a UID-0 server. Socket and parent ownership/modes are private, with pinned-directory and label checks.

Native requests are bounded UTF-8 JSON lines. The identity envelope includes `protocol:1`, `activation_id`, `boot_id`, `pid`, `start_time`, `nonce` and `event`. Replies bind protocol, nonce, activation and boot identity. Events are:

| Event | Required relationship and effect |
| --- | --- |
| `HELLO` | Returns the current cycle/source; before a valid BEGIN, `WAIT_BEGIN` means no activation may be deferred. |
| `DEFER` | Requires the current cycle and opposite target slot. The root store persists engine identity and `DEFERRED` before returning `decision:"DEFER",durable:true`. Duplicate same-cycle acknowledgments preserve later completion/commit evidence. |
| `DONE` | Requires the same engine/cycle/target and real native completion code 0; durably records `DONE`. |
| `FAIL` | Records native failure without granting a later reboot commit. It cannot rewrite an already-started commit. |

The native agent preserves original payload parameters, headers, signatures, manifests, Snapshot `FinishUpdate`, termination status and OUC completion codes. It defers only the proved BootControl `SetActiveBootSlot` call directly inside a successful, eligible `CompletePostinstall` context, after the actual `FinishUpdate` returned true and root durably acknowledged DEFER. Other native calls retain their original behavior. In particular, this protocol does not use `SWITCH_SLOT_ON_REBOOT=0`, replace code 52 with code 0, or submit one metadata file as a substitute for a merged multi-payload plan.

`DeferredSlotFiles` keeps `deferred-slot.json` and its transaction lock under the existing private root state directory. Record replacement uses a private temporary file, file fsync, atomic rename and directory fsync. Each record binds schema, boot, source/target slots, cycle, engine identity and phase; the phases are `ARMED`, `DEFERRED`, `DONE`, `FAILED`, `COMMITTING` and `COMMITTED`. Completion RESET does not erase this record.

## Typed activation at the original manual reboot gate

Only a user-triggered original manual reboot entry may request CONSUME. Automatic, countdown and unclassified entries never spend its permission. For a matching `DONE` plan and a real marker `1`, the transport performs this ordered transaction:

1. Revalidate current boot/source and the opposite recorded target.
2. Persist `COMMITTING` before calling the platform.
3. `TypedSlotCommitter` obtains the current `android.os.IUpdateEngine` proxy through the runtime `Stub.asInterface` API and invokes the actual no-argument `boolean setSlot()` method. It accepts only an exact Boolean true result.
4. Persist `COMMITTED`, then consume the same resident marker to durable zero.
5. Return `ALLOW`; the OUC agent then calls its saved original manual reboot path once.

There are no fixed Binder transaction numbers, shell slot commands, model/version address tables or new metadata setters in this commit path. The earlier native Snapshot submission has already completed; typed setSlot activates its recorded opposite target at the final gate. A false result, exception, crash or uncertain result is not replayed automatically: `COMMITTING` remains evidence and CONSUME stays denied. A later marker-write failure after a confirmed COMMITTED record can retry only marker consumption, not the platform call.

## Readiness, diagnostics and tests

The broker does not manufacture `hook_ready`. The controller distinguishes real `critical_ready` coverage of manual/automatic reboot, countdown and payload-start entries from an actual `broker_ready` STATE proof; complete OUC `hook_ready` requires both. Optional-native readiness is reported separately. Missing optional version/full-package/settings features and recoverable runtime failures are warnings, with phase/details logged by the target agent to Android Log and best-effort Frida messages. Unknown in-progress deferred state still keeps the reboot gate closed. Shell command text is not logged by the ROOT broker.

Host tests in `broker/test` cover exact marker values and ROOT framing (`BrokerCoreTest`), reuse of the resident marker lock (`ResidentMarkerBridgeTest`), BEGIN/deferred-state/commit ordering and interrupted transactions (`DeferredSlotStoreTest`), and bootstrap/fallback contracts (`TransportContractTest`). They do not themselves perform a real update, Snapshot commit, HAL slot change or reboot. Typed platform calls and the native interception path have their own controller/agent fixtures and device evidence; test stubs are not hardware success claims.

## Source inventory

`source-manifest.json` is a flat SHA-256 inventory relative to the source-package root. It covers this README, every distributed file under `broker/src` and `broker/test`, and `app/src/main/cpp/hnubl_module_client.c`. It excludes itself, generated classes/JARs, build output and logs. It is not an immutable upstream-provenance record. The module builder currently does not read this inventory: `build_module.py` independently hashes its broker/controller/discovery/hook/module build inputs before and after compilation. Refresh this inventory from the final distributed sources when those covered files change.


2.9.2 runtime compatibility: text reads use the Android-available readAllBytes API and Paths.get. Optional native worker, endpoint and cleanup linkage failures are reported without terminating the resident ROOT VM. A stale failed-reset flag never clears a later successful freeotaok=1 at the manual reboot gate. Label refresh is read-only; only the original manual gate consumes the permit.
