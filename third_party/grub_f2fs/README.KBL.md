# GNU GRUB F2FS reader in KBL

Upstream release: GNU GRUB 2.14, downloaded from
https://ftp.gnu.org/gnu/grub/grub-2.14.tar.xz . Archive SHA256:
`bc8d3c73535b8838d8c8e2654d73edc4e6ae8c8acdb45d5df5dc9a1547446d43`.
`f2fs.upstream.c` is the exact upstream `grub-core/fs/f2fs.c` for audit;
`f2fs_core.inc` is its trimmed and locally adapted reader, used only by
`runtime/fs/f2fs.c`. GNU GRUB's GPL-3.0-or-later notices and the release `COPYING`
are retained. Source hashes are in `PROVENANCE.json`.

Only `runtime/fs/f2fs.c` is compiled. It supplies private allocator, bounds-
checked byte-device IO, path traversal and filesystem ABI shims. Every GRUB
symbol in that translation unit is static. The only exported entry is
`kbl_f2fs_mount`. The production object has no undefined symbols, CRT imports,
Linux syscalls, driver initialization, EL changes, or write/flush calls.
Read/write requests are rejected with EROFS; open-file unmount returns EBUSY.
This is an actual filesystem reader: it validates dual checkpoints with F2FS
CRC, follows NAT and checkpoint journals, parses directories/inodes, and reads
inline data and direct/indirect/double-indirect data nodes, including holes.

Local adjustments beyond the GRUB core extraction:

- Remove module registration, global GRUB filesystem dispatch, disk-driver and
  OS dependencies; preserve the parser as explicit per-volume state.
- Correct the node-footer storage size to 24 bytes, bound directory-name slot
  counts, checkpoint pack lengths, sector arithmetic, NAT addresses and bitmaps.
- Use 64-bit double-indirect capacity arithmetic and reject unsupported maps.
- Parse extra inode attributes and variable inline-data/directory layouts,
  including flexible inline-xattr size where the feature is present. These are
  on-disk format adaptations; field layout was cross-checked against the public
  Linux F2FS format definition, not a copied Linux implementation:
  https://github.com/torvalds/linux/blob/v6.18/include/linux/f2fs_fs.h .
- Reject encrypted inode access with EACCES and compressed file access with
  ENOSYS, rather than returning encrypted/compressed bytes as file data.
- Reject multi-device layouts and unsupported large NAT checkpoint bitmaps.
- Follow bounded symlinks within the mounted filesystem, skip dot entries,
  return UTF-8 byte names, and preserve confirmed partial reads on IO errors.

The reader uses the latest valid checkpoint snapshot and performs no journal
replay, repair, inode-checksum repair or roll-forward recovery. New data beyond
that checkpoint may be absent after an unclean shutdown. It supports 4 KiB F2FS
blocks on little-endian ARM64; 16 KiB-block F2FS, decompression, multi-device
mapping and hardware-backed Android decryption are separate capabilities.
Casefolded filenames are currently located by their exact stored UTF-8 form.

Android userdata often has two encryption layers: dm-default-key metadata
encryption plus fscrypt file encryption, using hardware-wrapped keys. A raw
userdata block source is not the plaintext F2FS device. This filesystem parser
does not recover keys, create dm mappings, decrypt metadata, or bypass FBE; the
current Android/UEFI EL does not supply those Linux services automatically.
An already-decrypted and unencrypted bounded source can be read with this ABI.

Host tests: `python runtime/fs/tests/test_f2fs_host.py` creates two real 128 MiB
images with workspace Android `make_f2fs.exe` 1.16: default and extra_attr +
inode_checksum + flexible_inline_xattr. Root/checkpoint/NAT mounts, backup
superblock recovery, failed-mount cleanup and hard read-only behavior pass.
Separate derivative reader fixtures add controlled data/inline/Unicode/symlink/
sparse/direct/indirect/double-indirect nodes and encrypted/compressed rejection
cases. They deliberately do not rewrite SIT/free-space/checksum accounting and
are labelled reader-only fixtures, never complete writable production images.
`test_f2fs_arm_build.py` verifies a GPR-only import-free ARM64 link; its test PE
has no real EFI application entry and must never be deployed.

No test opens USB or a real block device. Real F2FS test images exceed default
KBL RAMFS file limits; device testing begins with small FAT/exFAT RAM images.

The separate independently implemented `runtime/fs/f2fs_rw_core.c` includes this
same reader with a private mount symbol for bounded existing-file overwrites.
It does not turn this original RO mount function into a general writer. See
`runtime/fs/F2FS-WRITE.md` for actual supported operations and the unimplemented
allocation/checkpoint transaction boundary. Symlink path traversal now restarts
iteratively; ARM stack-usage checks and eight-hop relative-link/loop tests guard
against recursively accumulating the large path buffers.
