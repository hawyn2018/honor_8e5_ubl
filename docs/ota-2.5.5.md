# OTA retention transaction

`OtaModel` / `OtaService` run this workflow manually after system OTA installation
and before reboot. There is no automatic reboot, slot change, GPT mutation or
userdata operation in `OtaEngine` or `scripts/ota-device.sh`.

The click captures device time and timezone. The engine first creates
`Download/hnubl/hnubl_ota_backup_yyyyMMdd_HHmmss`, then identifies the running
slot using agreement between boot properties, command line and bootconfig.
An existing prepared unlock job blocks admission, including persisted jobs.

## Discovery evidence

An A/B suffix alone is not boot-chain evidence. The normal Qualcomm boot-role
catalogue is intersected with actual distinct A/B nodes, sysfs `PARTNAME`,
capacity and Qualcomm signed-container structure. `abl` is explicitly excluded
from this early-chain check. OS partitions and unknown A/B roles are recorded as
excluded, rather than copied speculatively.

The catalogue includes `uefi` and the newer SHRM, DCP, SOCCP, PDP and CPUCP
firmware/configuration roles. Local independent boot evidence is
`analysis/kbl-pss-kpatch-20261031-recheck03/uefi/UefiLog17.canonical.txt`, SHA-256
`d9c98036f88289b11a7294fe9989dde9674b5afd09ec1e97ba5daa0fca9fede6`.
Before the UEFI start at line 349, SHRM (152), HYP_AC_CFG (230), DCP_DTB (246),
DCP (250), TZ_AC_CFG (274), SOCCP_DTB (278), SOCCP (282), SOCCP_DEBUG (286),
SOCCP_DCD (291), PDP_CDB (295), PDP (299), CPUCP_DTB (304), CPUCPFW (308), and
TZ_QTI_Config (313) have metadata authentication, segment hash checking and
completed image-load records. These are role evidence, not per-model addresses.
Actual partition names still must exist on the running device.

Additional known candidates (`imagefv`, `xbl_ac_config`, `soccp_config`, `dpm`,
`tme` and actual `tme_*` A/B names) additionally require exact ASCII/UTF-16 name
references in an already included earlier-stage image. The manifest records the
source image hash and literal offsets and explicitly labels this supplemental
evidence as a name reference, not an independent control-flow proof. A candidate
with no reference is excluded with a reason. This catalogue is not a claim to
recognize every possible future boot firmware role.

Signed-container recognition covers Qualcomm ELF hash program segments and
bare MBN 3/5/7/8 signing extents; it does not verify signing trust. ARB inspection
uses the existing structural parser. Any declared nonzero counter in an included
other-slot early-chain image triggers complete dual-slot early-chain backups and
current-to-other copying. Zero, absent or unrecognized metadata is recorded as
zero per user policy. File/partition I/O errors remain fatal. With all-zero or
unrecognized counters, no early-chain images are publicly exported or copied.

## Backup and source ordering

Both ABL and init_boot slots, `slot_active_a` or `slot_active_b` (one letter), and
existing efisp when it will be changed are always backed up and SHA-256 checked.
Every read uses the existing native `partition_worker read-range` O_DIRECT path.
Source identities are checked both before and after extraction.

Automatic parent ABL selection reuses the existing complete semantic proof for
an efisp parameter, partition lookup/read success, and EFI LoadImage use. A unique
proven efisp loader selects the frozen current ABL; otherwise the built-in parent
is selected. Explicit built-in/upload choices are also available.

All eight efisp selections are supported. Existing and uploaded old efisp images
go through the same semantic patch API to add missing functionality and retain
the application's text customization and selected fake-lock/freedom mode.
Every actual efisp product, including direct uploads, uses the shared
`EfispArbFallback`: accept zero/unreadable; on a positive counter regenerate from
the frozen current ABL and inspect again; if still positive regenerate from the
built-in ABL and adopt that final product without another ARB gate.

Before the first write, all source snapshots and products are pinned and hashed
in a random root-owned `/dev/hnubl-ota-*` directory verified to be tmpfs. This
includes the original other-slot ABL before parent ABL installation. The entire
commit plan is hash-bound; all public backups, every RAM source, all write
identities, capacities and inactive-slot rules are checked before any `setrw`.
Each write also checks the unchanged original target through O_DIRECT, issues
`blockdev --setrw` on the by-name label, executes dd, then verifies a new O_DIRECT
readback against the source SHA-256. A started/verified journal remains public.
Partial backup/write failures emit `ota-partial.json` where possible and preserve
diagnostic evidence. A failed operation never emits `RESULT=success`.

## Offline checks

`OtaPolicyTest` covers click timezone, unreadable/zero/nonzero ARB, modern bare
MBN extents, pre-ABL role exclusions, whole-name references and option round trips.
`OtaAblLoaderInspectorTest` checks rejection of malformed/string-only inputs.
`tests/test_ota_transaction.py` executes the production commit loop against
temporary regular files with only uid/tmpfs/device interfaces replaced. It tests
backup/source corruption, active-slot and capacity rejection, target changes,
`setrw`/dd/readback order, and readback failure journaling. No phone is contacted
by these checks; real-device retention remains unverified.
