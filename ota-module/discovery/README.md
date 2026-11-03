# OUC semantic discovery

`net.hawyn.hnubl.discovery.OucDiscovery.discover(File)` returns a schema-1 JSON
mapping. `main APK [OUTPUT_JSON]` is the offline inspection entry. DEX parsing uses
`com.android.tools.smali.dexlib2`; the runtime build must bundle its required
dependencies. The SHA-256 binds each mapping to the exact APK being inspected.

The implementation does not contain a device/version-to-address or obfuscated
class/method mapping. It reads literal string references, descriptors, setting
read calls, literal argument register definitions and real invoke relationships.
Runnable construction plus a Runnable-typed submission adds an explicit
asynchronous edge. Argument provenance stops at branch/merge/exception boundaries
it has not proved. The earliest manual/automatic reason gate that reaches the
PowerManager reboot graph is selected, so only one gate consumes permission.
Decoded instruction objects are discarded after each method's semantic facts
are extracted, reducing peak graph memory for updaters with large bundled
libraries. An APK-hash cache in the controller can reuse completed discovery.

The automatic-update setting is inverse: 1 disables it. The original Java
predicate can mean either "switch on" or "setting missing"; therefore the agent
intercepts the discovered Android Settings read inside the OUC process instead
of replacing all vendor boolean methods. Caller comparisons remain intact.

Automatic countdown entry methods require a no-argument boolean/void signature
and corresponding auto-install/reboot countdown diagnostics. Older
CountDownTimer classes are accepted only when their finish path reaches an
automatic-reason gate and has no conditional multiplexing. A shared multi-action
timer is left alone; the common automatic reboot gate remains blocked.

UpdateEngine payload-start methods require the protocol descriptor and an actual
Binder transaction, excluding no-op Default stubs. Completion callbacks require
installation-specific diagnostics and exclude cleanup/merge callbacks. The
agent only resets permission on completion after it observed a new payload
start in that process; rebinding/reopening a completed update UI does not erase
the permission that hnubl just issued.

Required discovery roles are full-package selection, automatic-update control,
automatic countdown control, the early reboot gate, and a payload-reset path.
Missing/ambiguous structures produce a mapping with `discovery_complete=false`
and a `missing` list. Current evidence does not establish coverage for every
historic or future updater; each additional APK can be checked offline before
activation. String changes, control-flow changes, native-only paths and
unsupported DEX constructs can require another semantic rule.

## Observed APK evidence

The locally supplied 18.0.3.227 APK exposes a package-type selector with
`getPackageTypeForChecking`, `INCREMENT_PACKAGE`/`RECOVERY` diagnostics and a
`recovery` return literal. Its manual and automatic callers pass distinct reason
constants to one early String-to-void dispatcher. The original dispatcher leads
to slot/flag/alarm changes and an asynchronous PowerManager reboot; these later
functions are deliberately not separately permission-gated.

The primary automatic countdown uses Handler messages, not CountDownTimer.
Installation callback diagnostics differ from cleanup/merge callbacks. Those
observations form semantic patterns; their observed obfuscated names are not
embedded in the locator or agent.

## Optional cached-version read contract

`version_cache_query` identifies the application's own native
`Cursor(Uri, String[], String, String[], String)` read adapter. It is selected
from a firmware row mapper that reads `_id`, `fw_state`, `fw_version`,
`fw_package_type` and the complete BL metadata in `extends2`. The full firmware
list must call that row mapper and the adapter; the same adapter must feed an
ancestor's real `Cursor.getCount()` path. These edges keep list visibility and
native has-data classification consistent. This is not a global SQL hook and
does not identify a target by an obfuscated class name.

The role records the query descriptor, argument indices, `receiver_class`, and
the operator's `uri_source` static field. That field is obtained from the
operator's actual `Uri()` getter through `sget-object`/return provenance. The
`columns` map includes progress only when the mapper really reads
`fw_update_progress`. `current_version_source.fallback_supplier` is the existing
semantically discovered complete firmware supplier. `list_method`,
`count_method`, `row_mapper` and `uri_method` are evidence from this exact APK.

`pending_states: [0]` is emitted only when a firmware ContentValues writer proves
literal zero through `Integer.valueOf` into `put("fw_state", ...)` while also
writing the firmware version/package fields. Its witness methods are listed in
`pending_state_evidence`. An absent state/progress witness must not be guessed by
the agent. Unrecognized metadata, ongoing download/install state, URI/schema
mismatches and missing optional evidence preserve the native query unchanged.
The consumer may perform an extra read-only query to classify eligible rows and
exclude their verified numeric IDs in the original selection, retaining the real
Cursor and its projection, arguments and ordering. No row is deleted or updated;
payload, reboot and slot operations are outside this contract.

Missing cache evidence adds only `version_cache_query` to `optional_missing`;
critical reboot protection remains independent. `VersionCacheDiscoveryTest`
checks the real list/count/URI graph and unsigned DEX mutations that remove the
count edge, mapper column, static URI provenance, initial-state witness or
progress column. `DiscoveryCompatibilityTest` also verifies this contract after
its class/method renaming and DEX-order mutation. Neither test executes a fixture
APK or communicates with a device.
