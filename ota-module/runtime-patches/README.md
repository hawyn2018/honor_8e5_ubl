# Frida runtime changes in hnubl 3.0.1

This is a local modification of the official Frida 17.25.0 Android ARM64 inject
runtime. The original XZ and its official download manifest stay unchanged in
`../vendor`. They identify upstream input, not the modified executable.

The module uses two scoped changes:

1. The inject environment initializer still calls `frida_init_with_runtime`,
   restores its stack and link register, then returns. It omits its automatic
   `frida_selinux_patch_policy` tail call. This prevents Frida's whole-policy
   export/edit/reload at process initialization, even for `--version`.
2. The existing agent memfd label setter uses the Root provider's existing
   `u:object_r:ksu_file:s0` instead of introducing `frida_memfd`. No policy type,
   permission, global permissive setting or policy reload is added by this
   recipe. The other three `frida_file` context literals stay unchanged.

The provider context must already exist, and each trusted injection target must
already have the required file mapping and communication permissions. This was
verified against the KSU provider used for the integration test. Another Root
provider or device policy may need separate compatibility work. A failed
injection is reported normally; the build and runtime do not restore the
upstream global policy-patching initializer as a fallback. This change is not a
promise that a rooted device or injected process is undetectable.

## Reproducible build

`derive_runtime.py` uses only Python's standard library. The builder verifies
the complete official XZ and decompressed ELF SHA-256 values, ELF architecture
and section layout, initializer instructions, policy function hash, unique
direct policy caller, complete unique memfd context and its unchanged setter.
It replaces exactly the restored initializer's four-byte tail branch and one
fixed-size context string allocation. There are 17 differing bytes in total;
ELF length, headers, segments and section sizes are unchanged.

The complete output must equal the pinned derived hash. Different upstream
versions, damaged inputs and already-modified inputs stop the build. There is
no generic search-and-replace or fallback to the unmodified executable.

| Artifact | SHA-256 |
| --- | --- |
| Official XZ input | `987d0a900cf1bc3878ba8b34b00ad2889e55e4ba26a8da7140a881f652982a73` |
| Official ELF input | `1d3374cdbb73f3fe4737f7e7a77e6d17e19914f16d1bffbd6c42999800f8dbeb` |
| Derived ELF output | `3db476d1cfb390f4feab17ca4463c047c2007b71d1f62873f539a5e6523a802b` |

Run the positive, deterministic-rebuild, altered/truncated-input, already-
modified-input and local structural negative tests without a device:

```sh
python -B ota-module/runtime-patches/test_runtime_variant.py
```

Each module build writes identical `runtimeVariant` provenance into its build
manifest, staging `runtime-variant.json`, dependency lock and packaged
`licenses/frida-runtime-variant.json`. It includes the exact recipe/source-patch
hashes and all 17 byte changes. The final `payload-sha256.json` binds the derived
`bin/frida-inject`, rather than the upstream input hash.

`frida-17.25.0-root-provider.patch` expresses the same two changes at source
level. It documents semantics against the pinned upstream source; rebuilding
the complete Frida tree with different toolchains is not claimed to reproduce
the official release binary byte for byte. The shipped variant is derived from
that pinned official binary using this fully checked recipe.

## Upstream source and license

- [Official 17.25.0 release](https://github.com/frida/frida/releases/tag/17.25.0)
- [Environment initializer](https://github.com/frida/frida-core/blob/17.25.0/inject/inject-glue.c)
- [Agent memfd preparation](https://github.com/frida/frida-core/blob/17.25.0/src/linux/linjector.vala)
- [Original policy patcher](https://github.com/frida/frida-core/blob/17.25.0/lib/selinux/patch.c)

Frida retains its upstream license. The original text is packaged as
`licenses/frida-core-COPYING.txt`, including the wxWindows Library Licence 3.1
exception and its LGPL terms. hnubl modifications are identified here and in
the source patch; hnubl's own application/module code remains under its stated
AGPL-3.0-only license.
