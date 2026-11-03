# Mount validation — Freedom 1.2.15 / fakebl 1.2.17

The shipping ARM64 EFI driver has SHA256
`84da87450958ee0da7c47b4de93723f626a745e0c1f315254b27c913d14f0a85`.
Its imported-symbol table is empty and executable sections are not writable.

On one physical ARM64 phone, the final EFISP image was flashed, read back byte
for byte, and restarted into Fastboot. Before exact `oem kblstart`, native
queries worked, KBL status was unregistered, and ADB was absent. After START,
ADB and native Fastboot coexisted and CurrentEL remained EL1. Normal OS boot
was not exercised in this validation; the original base, entry, parent code
and lazy entry source were checked independently for preservation.

Actual mounted RAM images passed:

- FAT16, exFAT and clean ext4: default RW, directory/create/copy/rename,
  ADB push/pull, sync, busy unmount refusal, RO remount and unchanged image
  hashes during RO access. The exported ext4 image passed independent
  `e2fsck -fn` with exit 0 and an unchanged before/after hash.
- LZ4 EROFS: RO listing, long/Unicode names, compressed multi-block file and
  relative symlinks returned the expected SHA256 values; RW was rejected.
- A 128 MiB clean F2FS image: uploaded via ADB into RAMFS. A custom SDK ELF
  overwrote ordinary cross-block data, inline data and an indirect-node range
  at 5 MiB, reporting `verified EL1`. A non-truncating dd update also passed.
  ADB and full-container exports matched expected bytes. Independent official
  fsck/dump returned 0, all seven regular files matched their oracles, and CP,
  NAT, SIT, SSA and superblock/prefix bytes stayed unchanged. Inode CRC matched.
- `adb shell kbl off` with a mounted RAM image cleaned the mount and RAMFS;
  native Fastboot stayed connected. A fresh START created session 2, restored
  ADB, retained EL1 and exposed a fresh RAMFS.

The only persistent phone partition written was EFISP. No GPT, real userdata,
ABL, init_boot or OEMINFO writes were part of this validation. Real userdata
was probed with READ_ONLY and its encrypted raw bytes were not mounted.

Host tests additionally covered real FAT12/16/32/exFAT and ext2/3/4 images,
eleven EROFS variants, two clean and explicitly labeled reader-only F2FS
fixtures, four healthy existing-range RW F2FS fixtures, partial reads/writes,
sticky failures, inline DATA_EXIST/CRC and symlink bounds. Firmware-provider
models and actual ARM64 execution are distinct from the physical tests above.

This evidence covers the stated features, not a full F2FS allocator or Android
decryption. See [capabilities](MOUNT.md) and [F2FS write boundaries](F2FS-WRITE.md).
