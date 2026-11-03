Storage services use the existing KBL EL1 runtime and standard UEFI BlockIO.
The verified f102 permission provider remains unchanged. EfiMain and ordinary
Fastboot commands do not enumerate partitions or invoke BlockIO payload I/O.

`kbl_partition_block_open` opens one exact UTF-8 GPT label with explicit READ
and/or WRITE access. It uses standard PartitionInfo, or Qualcomm's published
PartitionRecord interface, with the same BlockIO handle. No slot expansion,
prefix matching, reset, write or flush occurs during open/list/close. Duplicate
labels are rejected. `kbl_partition_open` retains the read-only reader API.

Byte writes use an IO-aligned bounce buffer. A partial leading/trailing block
is read, modified and written while preserving the other bytes; aligned full
blocks avoid unnecessary reads. Each transfer checks the saved MediaID,
capacity, block size, alignment and present/logical media identity. Writes
also check current ReadOnly and WriteBlocks support. Flush is a separate
explicit callback; close does not flush. A write-open rejected as ReadOnly
returns that metadata flag with an empty reader, allowing VFS to report EROFS.

UEFI WriteBlocks returns a status, without a byte count. On failure the byte
API reports only progress from earlier successful calls. The failed transfer
may have unknown partial effects; the provider does not automatically retry.
RMW protects neighboring bytes under serialized, stable-media access. This is
not a transaction across several BlockIO calls or concurrent external writers.

`kbl_partition_list` first builds and validates complete private metadata,
sorts by exact UTF-8 label and rejects duplicates. Capacity zero returns the
required count. A short output array receives no truncated prefix. Failed
firmware FreePool ownership stays tracked until explicit cleanup succeeds.

The native platform tail exposes block_open/block_list only during explicit
KBL jobs or storage requests while the runtime is live. VFS presents the
partitions under `/dev/block/by-name`. The standard passive counter is supplied
to cooperative checkpoints when its source was semantically resolved and
bound; no clock, USB driver or timer is started for ordinary Fastboot.

The parent preserves an existing native FETCH handler. If none existed, it
routes a non-kbl alias FETCH to the driver only after an explicit successful
START and a bound USB observer. Failed START and OFF clear that permission.
The complete `label:offset:length` suffix reaches the existing fetch parser.
Original partition-size/has-slot getvars are preserved. Binary bodies remain
standard DATA packets with partial DMA progress and native final/RX rearming.

READ_OPEN remains immutable-RAMFS-only. A raw partition is read with explicit
bounded FETCH chunks; it is not synchronously hashed in READ_OPEN. Host tools
can perform cooperative partition SHA jobs before/after a readback instead.

Offline evidence includes 44 read-only provider cases, 23 RMW/write/list/flush
cases, SHA known-answer tests, actual ARM64 parent routing, and an actual
parent-to-driver-to-VFS-to-BlockIO-to-USB fixture. The fixture proves unaligned
byte preservation, explicit flush, native command preservation and zero owned
allocations after OFF. An ordinary C port_copy program also reads a modeled
partition through SDK/libc and verifies its RAMFS destination. EFI, disks and
decoded USB events are explicit models; no physical partition-write result is
implied. Original real ArmCpuDxe/getter permission regression remains separate.
