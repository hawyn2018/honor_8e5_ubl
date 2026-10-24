# Bundled tool source materials

This directory supplies the original source archives, Android maintainer patches,
build scripts and license notices for BusyBox, GPT fdisk and e2fsprogs used by
honor_ubl 2.3.3. Archive hashes and upstream references are recorded in
[SOURCES.md](SOURCES.md). The five upstream archives are copied without changes.
Third-party components retain their individual licenses, including GPL-2.0-only
where stated; the project's own license does not replace those terms.

The historical development notes in SOURCES.md mention extracted source trees,
compiled output names, ELF inspection reports and fixture reports. This public
source tree includes the five source archives and build material rather than
the old build outputs or device/debug reports. Extracted source directories and
ELF reports are generated locally when rebuilding. The corresponding bundled
executables live under
[`app/src/main/jniLibs/arm64-v8a/`](../../app/src/main/jniLibs/arm64-v8a/).
Project-owned native helpers live in `app/src/main/cpp/` at the repository root.

## Prepare sources

Extract the archives beside these scripts:

- `aosp-gptfdisk-source.tar.gz` into `aosp-gptfdisk-source/`.
- `android-gptfdisk-source.zip` into `gptfdisk-source/`, retaining its existing
  `android-gptfdisk-<commit>/` top-level directory.
- `e2fsprogs-source.zip` into `e2fsprogs-source/`, retaining its existing
  `e2fsprogs-<commit>/` top-level directory.
- `busybox-1.36.1.tar.bz2` and `busybox-ndk-patches-source.zip` supply BusyBox's
  upstream source and the Android maintainer's configurations and patches;
  consult the maintainer's build instructions in that archive.

The AOSP GPT fdisk archive includes a `LICENSE` symlink to `COPYING`. If Windows
extraction omits the symlink, retain the existing `COPYING` file.

## Build on Windows

Use Android NDK r29, Python 3, PowerShell, host clang and awk. Select the NDK
using `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT`, or set `BOOTFORGE_NDK_BIN` to its
`toolchains/llvm/prebuilt/windows-x86_64/bin` directory. No host-specific SDK
path is embedded in these publication copies.

```powershell
# ANDROID_NDK_HOME already points to your installed NDK r29.
./build-sgdisk.ps1
python ./build-e2fsprogs.py
```

`build-sgdisk.ps1` also accepts `-NdkBin`. For e2fsprogs, put awk and a host
clang on `PATH`; `BOOTFORGE_AWK` can select an explicit awk executable.
The scripts generate local build directories and executable outputs. They do
not connect to a device. Historical ELF reports, compile logs, device metadata
and release APKs are intentionally absent from this public source directory.
