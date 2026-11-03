# EROFS host fixtures

`make_fixtures.py` uses Python 3.9+ and user-supplied upstream `mkfs.erofs` / `fsck.erofs`. It generates synthetic test data only. There are no embedded host executables, downloads, mounts, or device operations.

Use erofs-utils 1.9.4 and LZ4 1.10.0 for the reference matrix. Upstream build instructions: https://erofs.docs.kernel.org/en/latest/install.html and https://github.com/erofs/erofs-utils . Build LZ4 (https://github.com/lz4/lz4), then configure erofs-utils with its LZ4 library visible to pkg-config. A minimal example on a POSIX build host is:

```sh
./autogen.sh
./configure --enable-lz4 --disable-multithreading --disable-fuse \
  --disable-lzma --without-zlib --without-libzstd --without-uuid \
  --without-selinux --disable-shared
make -j4
python3 make_fixtures.py --output /tmp/kbl-erofs-fixtures \
  --mkfs /path/to/erofs-utils/mkfs/mkfs.erofs \
  --fsck /path/to/erofs-utils/fsck/fsck.erofs
```

Autotools, libtool, pkg-config, a C compiler and make are host build dependencies. On MSYS2, run in its shell with the built tools' DLL dependencies on PATH; use the equivalent `.exe` paths. The tools remain outside the source package.

`--tar-only` generates the portable PAX tar, `expected.json`, and regular-file bytes under `file-content/` without host tools. Tar records carry their own POSIX modes, Unicode names, and symlinks, so Windows host symlink privileges are irrelevant. Output filenames matching a previous run are replaced; use a dedicated test directory.

The matrix includes uncompressed, LZ4 compact/full, 64-KiB big-pcluster, ztailpacking, fragments, all-fragments, mixed features, LZ4HC, plus 1-KiB and 16-KiB filesystem blocks. Main fixtures explicitly select 4096-byte blocks because host page size may otherwise choose another default. `-T0` and a fixed UUID stabilize metadata. Every image is checked with official `fsck.erofs --extract` without an output directory, which validates all file/decompression data without creating host symlinks.

`expected.json` describes 19 regular files, five directories including root, and 30 symlinks. The symlink matrix includes a 24-link valid chain, a root link that can consume repeated input components, an ordinary cycle and a growing cycle. `symlinkSpecial` distinguishes directory/cycle cases from file aliases. Files cover Unicode names, nested directories, long names, spaces, empty data, short tails, compressible data over 64 KiB, incompressible data and zeroes. `images.json` records output geometry/features and SHA256 after checks pass.

For the KBL EROFS adapter, select `mount -t erofs -o ro IMAGE TARGET`; it is read-only. The current adapter accepts filesystem block bits 9 through 12, so the 16-KiB image is an expected `ENOSYS` geometry case. It excludes metabox, external blob devices and compression algorithms other than LZ4. These are host parser/decompression fixtures, not evidence about physical UFS, Android encryption, USB timing or boot behavior.
