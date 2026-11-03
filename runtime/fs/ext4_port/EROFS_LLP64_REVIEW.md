# EROFS LLP64 arithmetic review

`third_party/erofs-utils/lib/zmap.c` was additionally assigned to this backend agent for its LLP64 port. Original upstream types assume LP64 `unsigned long`. Windows AArch64 and native Windows test targets have 32-bit `long`.

Local changes:

- Logical cluster recorder fields, full/compact/from-disk helper arguments, lookback and initial cluster locals/arguments now use explicit u64. This prevents byte shifts from truncating at 4 GiB, and prevents helper argument truncation at 2^32 logical clusters.
- Compact index total and compacted two-byte index run count use u64. The rounded total is computed with quotient plus nonzero remainder, avoiding addition wrap on a corrupt maximum-sized inode.
- Final-cluster shifts first check the 64-bit shift range; final physical endpoint is bounded by inode size instead of wrapping UINT64_MAX.
- Empty compressed inode tail lookup rejects corruption; malformed inline pcluster span now sets a negative corruption status before exit instead of leaving success status.
- Diagnostic formats reflect 64-bit logical clusters.

`test_erofs_llp64.c` tests the actual static upstream mapping functions through a synthetic sparse metadata provider, using 32-bit Windows long: regular 4 KiB cluster, above 4 GiB file position, and above 2^32 clusters (16 TiB position). Logical byte offset, physical address, extent length and metadata lookup offset all match. Host result: PASS long=32-bit. ARM freestanding compile also passed (with the same upstream -Wno-sign-compare exception used by the parent build).

This is an arithmetic regression test, not an actual multi-terabyte filesystem or boot/USB test. Root owns and independently tests the actual EROFS adapter and compressed fixtures. No hardware operations were performed by this agent.
