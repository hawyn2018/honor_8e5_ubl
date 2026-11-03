The driver is a real AArch64 PE/COFF `EFI_BOOT_SERVICE_DRIVER`. `EfiMain`
records the supplied environment, retrieves only its own LoadedImage protocol,
registers its own unload callback, and installs the private KBL service on its
own image handle. It performs no kernel, USB, disk, clock or variable startup.
The source-firmware parent shim and USB transport binding are separate work.

Explicit service START initializes the actual native RAMFS/fiber backend.
Allocation uses UEFI pool/pages with a ledger. Failed releases retain ownership
and block unload until a later explicit OFF retries cleanup. OFF also closes
read snapshots. Driver image unload first requires a fully stopped, allocation-
free and quiescent service, then uninstalls its own protocol. OFF itself retains
the small controller image and reply cache for host queries.

Freestanding KBL ELF programs first use the standard MemoryAttribute protocol.
An explicit policy rejection such as ACCESS_DENIED is preserved. Only
UNSUPPORTED may select standard CPU_ARCH.SetMemoryAttributes and a real
architectural stage-1 permission getter. Without the native protocol, a GCD
descriptor missing RO capability selects that CPU/getter pair at the first
private RW/NX transition, before the loader receives its allocation. Selection
is sticky through partial failures and cleanup; GCD capabilities are never
modified and a software GCD attribute value is not used as CPU readback.

The architectural profile is EL1, little-endian, 4KiB TTBR0 identity mapping,
at most 48 physical address bits, T0SZ 16..39, MMU on, PAN off, HA/HD/DS off,
and TCR2 zero when implemented. Unknown profiles fail before DXE page allocation.
The getter checks upper-level APTable/PXNTable, HPD0, WXN, leaf AF/AP/PXN,
Normal cache attributes and unchanged physical/cache/shareability/NS metadata.
DBM and contiguous descriptors are rejected; BTI-guarded executable pages are
rejected when the SDK entry contract does not establish a landing instruction.
The table reader verifies readable Normal identity mappings using AT, saves
and restores PAR/DAIF, and makes no firmware call while interrupts are masked.
CPUSet uses the real standard protocol, not direct PTE writes. Vendor CPUSet
may set AP=11 on private RO pages, so this is an EL1 permission contract, not
EL0 isolation. EL2/security-world additional restrictions are not measured.
Allocated LoaderData pages are private RW/NX before the loader receives them.
Only actual page gaps between LOAD regions require no-access protection; a
gapless ELF does not require an unsupported read-protect operation over its
entire image. Segment permissions are applied with deny-first ordering and
readback, and W+X remains rejected. A real gap still fails explicitly when
no-access protection is unavailable. Missing permission services fail
explicitly. Pages reach FreePages
only after RW/NX restoration and readback succeed; failed or partial restoration
stays owned in the released ledger for explicit OFF retry. Unexpected PA/cache/
NS/config changes taint the permission adapter and quarantine its private
pages rather than returning questionable mappings to the firmware allocator.
There is no fake W^X
or model-specific fallback. Cache maintenance uses native AArch64 instructions.
The ABI1 SDK and native fibers use only general-purpose registers; they do not
read or write FP/SIMD registers or FP control state and do not change DAIF.
Handwritten custom programs must obey that GPR-only contract as well.
The SDK raw UEFI environment getter is absent by default: unmanaged external
callbacks cannot be reclaimed by this driver's allocator broker.

STORE accepts legacy UTF8 HEXPATH or the nonce/SHA256 checked `env1` envelope.
Only a fully validated upload is atomically published to private RAMFS; STORE
does not execute it. READ_OPEN creates a stable RAMFS snapshot and returns its
alias, decimal size and true SHA256 halves. READ_OPEN also accepts an `env1`
envelope with zero payload and the true empty SHA256, so long UTF8 paths do not
exceed the Fastboot command frame. Driver binary FETCH has alias and exact
GPT-label sources. The services parent preserves an existing native FETCH;
if none existed, explicit successful START plus the bound observer enables
the partition source. Offset/length chunks are retained. Native VFS jobs can
also explicitly open read/write BlockIO byte services, with no automatic
write/flush in startup or ordinary Fastboot. See `STORAGE-STATUS.md` for RMW,
complete metadata listing, ReadOnly and partial-failure contracts. The new
storage integration tests use modeled disks and do not claim phone writes.

Each non-query request has an immutable generation of up to 16 INFO records.
`KBL_LINK_DEFER_INFO` caches these records instead of invoking intermediate
transport sends. The host obtains the header/pages through dedicated getvars;
queries do not run jobs or change the generation. This only solves control
responses: binary FETCH still needs real DATA/write/abort callbacks. Transport
write must complete synchronously or copy into parent-owned persistent memory.
An uncertain I/O failure retains owned memory until an explicit reliable
quiescent callback proves that all references and DMA have ceased.

The service's optional `bind_usb` tail copies an 88-byte semantically resolved
parent-facts descriptor into driver BSS. Explicit START alone installs the
existing Fbd protocol clone, initially without a TX-idle witness. A completed
native ACK and new normal-command RX epoch are required before exact FETCH may
use protocol-owned DMA. DATA announces the complete fetch size; each body DMA
submission is at most 64 KiB and returns partial progress to the fetch loop.
Other events are replayed to the original owner; final native ACK completion
remains with the original RX-rearm branch. OFF requests safe observer removal;
pending DMA/events/native TX retain the driver until completion. See
`USB-NATIVE-STATUS.md` for ownership and hardware-validation boundaries.

The failing poll retains only three compact permission diagnostic lines.
An explicit kblstatus adds the selected provider and the last inner phase,
capabilities (or unavailable), original/requested attributes and EFI status.
These extra details stay out of a full poll's 16-line reply budget. A fresh
allocation clears the prior failure; successful provider selection is not an
old error. Ordinary Fastboot and START perform no permission-page probes.

Run `python runtime/uefi/tests/test_driver_runner.py` from the isolated source.
The report records source hashes and the exact PE. Host tests deliberately use
fake EFI and a fake native backend to exercise failures. Separate Unicorn tests
execute the actual ARM64 PE, relocated to two bases, through EfiMain, the real
controller/native fibers, env1 upload, `./hello`, cooperative yield, snapshot,
SHA256, partial binary fetch, OFF, and unload. EFI services and the transport are
explicit host models. The demo observes emulated CurrentEL=1 and preserves X18
with CPACR_EL1 left at zero throughout the actual-PE test;
these results do not establish physical EL2, phone startup, parent USB binding,
DMA behavior, arbitrary program cleanup, preemption or fault isolation.
