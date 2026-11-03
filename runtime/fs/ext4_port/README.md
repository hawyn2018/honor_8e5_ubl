# Native KBL ext2/3/4 backend

`kbl_ext4_mount` is a synchronous backend of `kbl_fs.h`. It uses pinned upstream lwext4 commit `58bcf89a121b72d4fb66334f1693d3b30e4cb9c5`. No firmware, USB, driver, timer, shell, EL-transition or pre-KBL initialization code is present. Caller serialization is required; calls never yield.

## Build

Compile `runtime/fs/kbl_ext4.c` and all `third_party/lwext4/src/*.c` **except** `ext4_mbr.c`, `ext4_mkfs.c`, and `ext4_debug.c` as a separate source group with:

```
-I runtime/fs/ext4_port/include
-I third_party/lwext4/include
-include runtime/fs/ext4_port/include/kbl_ext4_config.h
-Wno-unused-function -Wno-unused-but-set-variable
```

Normal KBL ARM flags remain required: freestanding, no builtin, no stack protector, fixed x18, general-registers-only, no outline atomics. This source group uses private prefixed string/sort functions, private `ext4_user_*` allocators and the supplied host allocation callbacks. No SDK program libc global API is used. `ext4_mkfs.c` is linked only by the offline fixture test, never by the firmware.

## Operations and boundaries

Supports existing file read/write, create, truncate, append, stat, directory listing, single-level mkdir, empty-directory unlink, regular-file unlink, rename without replacement, explicit sync and unmount. Four simultaneous ext mounts use separate internal names. Native path length budget is 512 bytes including the internal prefix. Linux special files and symlinks are reported unsupported rather than treated as ordinary data files; sparse file extension beyond EOF is not provided by this port.

Supported incompatible features are filetype, meta_bg, extents, flex_bg and 64bit. Normal ext3/4 journaling is started only on an explicit writable mount of a clean filesystem. RW rejects dirty/error state and `needs_recovery` **before the first write**; no automatic fsck, journal replay, repair or formatting occurs. RO accepts supported readable layouts without replay and the block callback hard rejects every write. Unknown incompatible features (including fscrypt/encryption, inline_data, compression and checksum_seed) reject mount. Unknown RO-compatible features reject RW; RO follows upstream readable-layout support. This is not Android FBE, metadata decryption or fs-verity verification.

Unsupported on-disk layouts and encrypted content must not be represented as plaintext. Files carrying the ext4 encrypted-inode flag also reject access.

An I/O error or short write latches the volume: later writes, sync, close and unmount return failure and keep ownership rather than replay uncertain spans. Ordinary flush failures keep ownership and can be retried. If the final clean-superblock flush fails, unmount resumes only its final flush/cleanup phase; ordinary filesystem access stays disabled in that phase. An unmount with live file handles returns busy. The mount owner closes the underlying source only after successful unmount.

## Offline verification

```
python runtime/fs/ext4_port/build_test.py host
python runtime/fs/ext4_port/build_test.py arm
```

The host test creates actual 64 MiB ext2, ext3+journal, ext4 without journal, and ext4+journal images using the independent platform-tools mke2fs 1.47.2, then tests create/read/write/append/truncate/list/rename/unlink/remount, missing parent errors, busy unmount, RO zero write including successful 127-byte short-read callbacks, two-stage flush-failure retention, dirty-RW refusal before writes, and failed-write no-replay retention. It writes healthy images before the subsequent intentional write-fault test. ARM compilation is freestanding and a link-level external-symbol audit is also required by the parent driver build. Six actual output images (including an 8 MiB image and a 16 MiB metadata_csum+64bit+journal image containing a 131073-byte patterned file) passed independent Cygwin e2fsck 1.44.5 `-fn`, all five passes, exit code 0. See `host-objects/e2fsck-independent-results.json`. This validation was entirely offline. The Cygwin test tools were fetched from the official mirror, SHA-512 checked against setup.ini, and only extracted inside the workspace; see host-tools/cygwin-provenance.json. Cygwin requires its own usr/bin on PATH and normal Windows named-object access to execute.

The optional upstream lwext4 fixture formatter has a last-group free-block-count defect on these small images. It is retained only for explicit upstream diagnostics; normal tests use mke2fs instead. The formatter is never included in the target firmware.
