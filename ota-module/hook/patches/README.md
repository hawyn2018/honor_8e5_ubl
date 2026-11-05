# Android 17 Java bridge backport

The npm input remains `frida-java-bridge` **7.0.13**, and the executable runtime
remains official Frida **17.25.0**. Only an isolated build dependency copy is
patched. The original npm tree and its SRI/version checkpoint remain unchanged.

Source: official merged PR [#398](https://github.com/frida-packages/frida-java-bridge/pull/398),
commit `67f1b1116e8b827014825a46693929221136b0c8`, authored by Wes Smith
(`Wes765`) and committed by `oleavr`. Its two changes correct runtime access-flag
probing and flush an ARM64 callback literal pool before a relocated body.
`bridge-backport.json` records exact upstream/local patch and before/after file
hashes, authorship, source URLs and the declared upstream license.

The declared package license is `LGPL-2.0 WITH WxWindows-exception-3.1`.
The included license and exception texts are supplied for attribution and
redistribution alongside the backport. The official patch is kept unmodified;
the separate local diff yields the same bytes from the pinned npm source.

`apply_bridge_backport.py` rejects a changed/already-patched npm input, verifies
both patch forms produce the exact expected hash, changes only `lib/android.js`
in a fresh working copy, and verifies the original package tree is untouched.
`build_module.py` resolves the bridge through an explicit esbuild alias to that
copy and records source/dependency checksums and the resulting bundle inputs.

No global deoptimization call is added. Upstream open PR
[#400](https://github.com/frida-packages/frida-java-bridge/pull/400) describes a
separate live-attach/JVMTI initialization issue. Its native FinishSetup workaround
is **not included** here. The separate local `api37-reflection-policy.json` and
diff disable JVMTI plugin loading at API 37 and later and select the bridge's
existing JNI reflection member-model branch. They also skip raw-class handle
unwrapping for that model. Explicit class loading, implementation replacement
and method calls remain in scope; heap choose and class enumeration are not
used by this OUC agent. Older Android APIs retain the original member model.

Both source transformations have separate strict before/after hashes. Build
bundles remove whitespace/path comments without identifier or syntax minification,
so a fresh temporary build path does not change their executable bytes. Runtime
verification remains separate from source and bundle validation.

## Method-level and bounded-batch selective ART policies

The independent `selective-deoptimization-policy.json` first decodes opaque
JNI method IDs using the bridge's runtime-aware `unwrapMethodId`. The separate
`selective-deoptimization-atomic-policy.json` places modern selective mutations
in the existing Native-state exclusive suspension helper, with `ResumeAll` in
`finally`. Neither layer changes the original global or legacy Dbg route.

`selective-deoptimization-batch-policy.json` follows those exact historical
hashes. It adds `Java.deoptimizeMethods(handles, {batchSize: 32})` and routes the
existing `Java.deoptimizeMethod` interface through that shared implementation.
Batch size may be 1 through 64; this is a
short critical-section bound, not a limit on total method count. All opaque
method IDs, capabilities, specification offsets and each batch array resolve
before its suspension. The stopped section invokes only the prepared native
enable/deoptimization functions and pointer reads. No JNI, Java reflection,
lazy symbol resolution, user callback or diagnostic emission occurs there.
Each batch resumes threads before a subsequent one begins. Android 24–29 uses
the existing single-method Runnable/Dbg route, and unsupported capabilities
throw to the caller rather than adding a new global fallback here.

This API returns `{count, batches, batch_size, strategy, skipped,
requested_count, already_global}`. `count` is newly applied selective native
operations, while `skipped` reports methods already covered. It caches no addresses
persistently and accepts only process-local method handles. A 2756-method plan
uses 87 default-sized suspension cycles rather than 2756. It still asks ART to
deoptimize each selected method; these counters do not imply less native ART
stack-walking work or guarantee a latency improvement before device measurement.

The batch recipe records exact before/after hashes for `lib/android.js`,
`index.js`, and `index.d.ts`. Preparation verifies the complete chain and the
untouched npm input tree. The old atomic model strictly reverses the exact
recorded batch addition and revalidates the old atomic hash before running its
historical checks. The independent batch model executes the actual prepared
batch implementation and its original `finally` helper with strict ordering
ports, including failures in a later batch. Models do not execute ART; an
independent harmless device fixture remains required for runtime validation.

### Repeated calls and other ART clients

ART may fatally reject deoptimizing the same selected method twice. Single and
batch calls therefore share a runtime-local success registry of decoded
ArtMethod identities. A preallocated byte array and integer prefix record
successes inside the short critical section; registry updates happen only
after the helper resumes ART threads, even when a later method throws. A
failed native operation is never recorded as successful. This covers retries
after a partially completed batch without allocating `Set` entries during STW.

The optional official `Instrumentation::IsDeoptimized(ArtMethod*)` export is
resolved by symbol identity and queried under the same exclusive lock before
mutation. Its `bool` prototype and shared-mutator requirement are documented in
[AOSP instrumentation.h](https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/instrumentation.h);
the export was also confirmed in the diagnostic device's libart dynamic symbol
table. No object layout or fixed offset is introduced. When the query is
available it is authoritative, including after another engine undeoptimizes a
previously cached method. Without the export, the local registry covers only
operations this bridge has completed; it does not claim knowledge of every
external ART client's state.

A successful original `deoptimizeEverything` sets a separate runtime-local
global flag, so repeated global calls and subsequent selective calls do not
repeat native mutation. An unsuccessful global call sets no flag. Boot-image
deoptimization never implies that all application methods are covered. These
registries are not persisted, serialized, or transferred to another process.

## Native Binder transaction-code fast pass

`native-transaction-filter-policy.json` is a further independent layer. The
scoped `overload.setNativeCodeFilter(handler, matchedCode)` API verifies the
boot-loaded `android.os.BinderProxy.transactNative(int, Parcel, Parcel, int)`
instance/boolean ABI, installs the existing Java implementation, and optionally
adds a C-only code selector. Its `implementation` getter keeps the upstream
contract: it returns the installed NativeCallback replacement, not the supplied
handler. Ownership checks capture that actual callback. Assigning `null` uses
the original bridge restoration path.

The gate reads the registered JNI entry from the bridge's captured original
ArtMethod, using the existing opaque-ID decoder and runtime-discovered layout.
It rejects unresolved ART stubs, non-executable or non-system module entries,
static/FastNative ABI variants, foreign replacement slots and
non-private method clones. It patches only this script's private replacement
ArtMethod, never the original JNI function body or another framework's entry.
These capability failures preserve the complete Java guard through the normal
setter; they do not disable protection or add a new global-deoptimization path.

The system JNI entry still requires an executable, trusted module mapping.
Our own newly compiled CModule uses a different proof: its native read-only
counter routines must return zero, and its native publisher must first reject
a deliberately wrong expected value in an external scratch slot without
changing it, then successfully replace the correct scratch value and pass a
pointer readback. These probes execute no Binder transaction and run before
ART suspension or live-slot publication. Any failed probe retains the Java
guard. They do not call `transact_filter` for bootstrap, change permissions or
adjust SELinux policy.

This distinction avoids treating Frida's filtered range enumeration as an
executable-permission failure. In the pinned 17.25.0 runtime,
`gumquickprocess.c:1043–1055` implements `findRangeByAddress` using public range
enumeration; `gumprocess.c:861–879` clips runtime-owned ranges. The diagnostic
device's CModule entry range stayed null before and after `Interceptor.flush`,
with the same stripped pointer. The check therefore uses successful compiled
native execution instead of requiring that private mapping to be enumerated.

CriticalNative is excluded by the proved instance/reference-containing method
shape, since actual CriticalNative requires static, primitive-only JNI shape.
The filter does not interpret a historical access-flag alias as that ABI. For
example, observed flags `0x200101` mean public/native plus NterpInvokeFastPath on
current ART, and remain eligible. Original bridge-wide flag constants and the
existing mangler's clearing behavior remain untouched.

All module identities, dynamic layout offsets, C code, native function wrappers,
and strong references resolve before publication. An exclusive ART suspension
contains only the prepared native publisher. The publisher checks the current
private-clone slot with a GLib sequentially consistent atomic read, then uses
`g_atomic_pointer_add` to publish the replacement in one sequentially consistent
RMW. Under this private-clone/ART-STW contract there is no legitimate competing
writer. `ResumeAll` remains in the original helper's `finally`. No fixed method
offset or hard-coded instruction address is used, and no bare JS pointer store
publishes the live JNI entry.

Unmatched codes call the captured original JNI entry directly in C, forwarding
the same JNIEnv, receiver, transaction code, Parcel objects, flags, return value
and pending Java exception. They never enter JavaScript, the bridge's
`pendingCalls`, Parcel inspection, or the OTA authorization path. Matched codes
still enter the original `implement()` callback, preserving its JNI local
frames, object marshaling, exception handling and existing Binder/single-use
authorization policy. Thread-safe native counters are reported modulo 2^32.
Writable counters are external `Memory.alloc` symbols retained with the gate;
they are never CModule read-only static data.

`gate.inspect()` reports active mode, counters and the original module/path/RVA.
There is no `gate.ready` field; inspect the returned snapshot's `ready` field.
Successful gates and their original Java callbacks remain strongly retained
until script termination, even after the Java hook is restored. This keeps
ordinary native calls already in flight from returning into freed C memory.

The exact prepared-source model checks publication ordering, private-clone and
signature rejection, fallback, and retained lifetime. It also compiles and runs
the actual C dispatcher with an independent atomic test harness to check 10000
ordinary calls, target routing, argument/return/pending-exception transparency
and rejected/successful publication. This does not replace live ART/JNI tests.
Historical models strictly reverse this layer, validate prior hashes, and then
run their original checks; the original npm input remains untouched.

## Shared ART quick-entry publication

`art-quick-code-publication-policy.json` follows the exact native-filter layer.
It fixes publishing a live quick-entry prologue while its writer still contains
unresolved literal placeholders. The observed cold-start crash had a valid
`art_quick_generic_jni_trampoline` entry in x9, then x16 contained the little-
endian instruction pair `58000010 d61f0200` (`ldr x16, #0; br x16`), matching an
unflushed writer placeholder rather than a valid destination address.

All existing architecture and relocation choices are kept. X86, Thumb and
ARM64 writers first finish in private scratch storage, with `pc` set to the real
target. Literal pools are flushed before reading the exact emitted byte count.
The copy is bounded by the actual completed relocation prefix, avoiding unused
staging tails that would overwrite unrelocated instructions. An exclusive ART
suspension surrounds only `Memory.patchCode` copying the completed snapshot and
its instruction-cache synchronization. Its required JS apply callback performs
only the byte copy; it performs no writer generation, Java/JNI operation or
lazy API lookup. Byte snapshots, copy/rollback callbacks and cause/API lookup
are prepared before suspension.

If publication throws, a prepared rollback is attempted inside the same
suspension before `finally` resumes ART threads. Activation failures retain
their owner/slice because old paths may remain reachable. Shared bootstrap
failures remain sticky in that VM, requiring the controller's clean-process
recovery rather than treating partially installed global entrypoints as ready.

Restoration uses the same publication protocol. A preflight byte comparison
skips restoring over an identifiable later owner, and keeps the old trampoline.
This does not claim atomic synchronization with arbitrary third-party native
writers. Even after successful restoration, retired trampoline slices/owners
remain pinned until script end instead of returning to the allocator's reuse
list while native or paused invocations may still return through them.

The exact prepared-source host model covers finalized ARM64 literal/ADRP bytes,
X86 short-jump and Thumb lengths, completed-relocation bounds, before/partial/
after-copy failures, same-suspension rollback/resume, later-owner preservation,
retired lifetime and sticky failed bootstrap. Writer ports model documented
instruction/literal semantics; real concurrent ART/Gum validation remains a
separate physical stress test. Every previous independent recipe layer is
strictly reversed and its historical hash checked in regression models.
