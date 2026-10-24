# BootForge bundled command-line tools

These files were obtained or built on 2026-10-24. None was executed on the connected device. The toolbox APK's extracted binaries are analysis inputs only and are not distributed in BootForge.

## BusyBox for Android NDK

- Maintainer/distribution: [osm0sis / Magisk-Modules-Repo](https://github.com/Magisk-Modules-Repo/busybox-ndk).
- Fixed distribution commit: `ad6700620120e3c475df05b0f8bfa7a40e741ee0`.
- [Exact binary source](https://raw.githubusercontent.com/Magisk-Modules-Repo/busybox-ndk/ad6700620120e3c475df05b0f8bfa7a40e741ee0/busybox-arm64).
- File: `busybox-arm64`, 1,498,688 bytes.
- Version embedded in the file: `BusyBox v1.36.1-osm0sis (2023-05-25 15:23:56 ADT)`.
- SHA-256: `c629fce4b0dd3ba9775f851d0941e74582115f423258d3a79800f2bd11d30f5c`.
- License: GPL-2.0-only, complete text in `licenses/BusyBox-COPYING.txt`.
- Corresponding upstream release source is retained as `busybox-1.36.1.tar.bz2`, from [BusyBox official downloads](https://busybox.net/downloads/busybox-1.36.1.tar.bz2).
- Android maintainer configurations and patches are retained as `busybox-ndk-patches-source.zip`, fixed [commit `3c96be250994e00fb3feb9b5a5b902e5e0222ca3`](https://github.com/osm0sis/android-busybox-ndk/tree/3c96be250994e00fb3feb9b5a5b902e5e0222ca3).
- Upstream configuration enables `CONFIG_STATIC=y`, `CONFIG_SH_IS_ASH=y`, `CONFIG_FEATURE_SH_MATH_64=y`, and the shell/core utility applets required by the scripts. String-table inspection confirms required applet names; execution-time probes still test actual usability.
- ELF inspection: ELF64 little-endian, AArch64, EXEC, no PT_INTERP or DT_NEEDED entries. Read-only static inspection only.

## sgdisk / GPT fdisk

- Primary source: [Android Open Source Project external/gptfdisk](https://android.googlesource.com/platform/external/gptfdisk/).
- Fixed [commit `fb2eab89b36c5143b4ef582bd80d562cb3dc0615`](https://android.googlesource.com/platform/external/gptfdisk/+/fb2eab89b36c5143b4ef582bd80d562cb3dc0615/).
- Upstream version in `support.h`: `1.0.9.2`.
- Original sources retained in `aosp-gptfdisk-source.tar.gz` and `aosp-gptfdisk-source/`.
- Uses AOSP's original `android_popt.cc`; no external popt binary or runtime dependency.
- UUID sources: original `uuid/*.c` and headers from Android port maintainer [sherpya/android-gptfdisk](https://github.com/sherpya/android-gptfdisk), fixed [commit `e1ca834eb471e63945a9c71052a6fbc3d9d15ba6`](https://github.com/sherpya/android-gptfdisk/tree/e1ca834eb471e63945a9c71052a6fbc3d9d15ba6), retained in `android-gptfdisk-source.zip` and its extracted directory. These standard UUID sources carry BSD-3-Clause notices.
- Local build: `build-sgdisk.ps1`, Android NDK r29, `aarch64-linux-android28-clang/clang++`, static bionic + libc++, `-O2 -D_FILE_OFFSET_BITS=64`, unmodified upstream source files. Debug symbols stripped with NDK llvm-strip.
- File: `sgdisk-arm64`, 1,299,048 bytes.
- SHA-256: `a3fb0f877353fdae3ec698ab8b34820a987e031e9cff9361ac35951f2e8d487e`.
- Licenses: GPL-2.0 family for GPT fdisk/AOSP adapter, complete text in `licenses/gptfdisk-COPYING.txt`; UUID BSD-3-Clause text in `licenses/uuid-COPYING.BSD-3.txt` with copyright notices retained in the corresponding source.
- ELF inspection: ELF64 little-endian, AArch64, EXEC, 16 KiB load alignment, no PT_INTERP or DT_NEEDED entries. Compiled for API 28. Read-only static inspection only.

## Source archive SHA-256

| File | SHA-256 |
| --- | --- |
| `busybox-1.36.1.tar.bz2` | `b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314` |
| `busybox-ndk-patches-source.zip` | `ca12d480a3d7c0f2479031dbfa27a405785da2e9930b1ec53d8952a18f66d4a1` |
| `aosp-gptfdisk-source.tar.gz` | `cfc6dae21a47951f7071b97624a1e6d8ef8ac3d290d6976bbfdcd919ab91ffc3` |
| `android-gptfdisk-source.zip` | `080a8ba6f81e9d4ac11de2cd37c7761178072b8b12512cab743e2c4cd2520fc1` |
| `e2fsprogs-source.zip` | `8493d4e01e89efa0aa362838d3287c0a22ba6ade44bb6e4e0495098c40b075d9` |

## e2fsck, resize2fs and dumpe2fs

- Primary source: [Theodore Ts'o / e2fsprogs](https://github.com/tytso/e2fsprogs), upstream release `v1.47.2`, fixed [commit `c3cce4a07efefc62bc7fc57a678cb870af27d0f2`](https://github.com/tytso/e2fsprogs/tree/c3cce4a07efefc62bc7fc57a678cb870af27d0f2).
- Original source archive retained as `e2fsprogs-source.zip`.
- Build: `build-e2fsprogs.py`, Android NDK r29, `clang --target=aarch64-linux-android28`, static bionic, `-O2 -D_FILE_OFFSET_BITS=64`. Host LLVM-MinGW clang generates the upstream CRC table; Git's awk runs upstream error-table generators. The upstream Android source lists are used, plus `getenv.c` and `orphan.c` that the upstream `lib/ext2fs/Makefile.in` lists but the older Android.bp source list omits.
- Generated configuration follows upstream `util/android_config.h`, with `ENABLE_LIBSPARSE` disabled. These builds operate on raw ext4 filesystem blocks; Android sparse-image wrappers are not used. Header/error/CRC tables are generated following upstream `util/gen-android-files`; no upstream `.c` source changes.
- All three are ELF64 little-endian AArch64 EXEC with 16 KiB load alignment and no PT_INTERP or DT_NEEDED entries. Static inspection reports retained as `e2fsck-elf.txt`, `resize2fs-elf.txt`, `dumpe2fs-elf.txt`. No connected-device execution.
- Licenses and full upstream notices are retained in `licenses/e2fsprogs-NOTICE.txt` and `licenses/e2fsprogs-uuid-COPYING.txt`; individual source headers retain GPL/LGPL/BSD/MIT notices, and the entire original source is supplied.

| Binary | Size (bytes) | SHA-256 |
| --- | ---: | --- |
| `e2fsck-arm64` | 876792 | `1cac7989bfd5b896576cca67b5b937aa1410fa850c0a7d0477a1eb2335f4c79e` |
| `resize2fs-arm64` | 542360 | `1806c6fcd660e8ef960568434548287ea863324dc3862827ee4e245f7b3caedc` |
| `dumpe2fs-arm64` | 548808 | `7c7a554df2d9dad3253869c5d115bd426da84072937c4c2e6949e061fa69277c` |

## Rebuilding the locally compiled tools

Extract `aosp-gptfdisk-source.tar.gz` into `aosp-gptfdisk-source/`, `android-gptfdisk-source.zip` into `gptfdisk-source/`, and `e2fsprogs-source.zip` into `e2fsprogs-source/` beside the build scripts. On Windows, provide Android NDK r29, Python 3, Git for Windows awk and host clang. Invoke `build-sgdisk.ps1` and `python build-e2fsprogs.py`; optional NDK path overrides are documented in their source. The BusyBox binary is the unmodified maintainer prebuilt; upstream source and the maintainer's patches/configurations accompany it rather than a claim of a local bit-for-bit BusyBox rebuild.

The AOSP source archive has a `LICENSE` symlink to the existing `COPYING` file. Windows tar could not create the symlink; all regular source files and `COPYING` extracted successfully. No source change was needed to build.

When distributing these GPL tools, retain the notices and accompany the binaries with their corresponding source/configuration/build material (or fulfill the applicable GPL source-delivery requirements). The source bundle in this directory preserves these materials for release packaging.
