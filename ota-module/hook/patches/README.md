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
