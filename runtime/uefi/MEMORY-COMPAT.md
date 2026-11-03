# KBL private ELF memory permission compatibility

This adapter exists because a firmware GCD capability declaration can reject RO
before the CPU's permission-setting routine runs. The physical test of driver
e10b returned EFI_UNSUPPORTED for both an R-only segment and an RX segment. This
isolates establishing RO, but does not by itself measure the live GCD caps.
Offline execution of the corresponding real ArmCpuDxe routine established that
the routine can encode RO in the supplied page-table fixture.

The implementation retains the native MemoryAttribute protocol as the preferred
provider. The compatibility path uses the standard CPU_ARCH setter together with
KBL's own restricted AArch64 hardware page-table reader. This reader is not a
standard CPU_ARCH getter: that protocol has no GetMemoryAttributes method. The
adapter does not add RO to the GCD capability declaration or treat GCD's software
database as proof that a direct CPU update succeeded.

The architecture reader has an explicit supported configuration, rather than a
device/model table: EL1, 4KiB stage-one tables, low TTBR0 addresses within 48-bit
physical addressing, aligned root tables and verified Normal identity mappings.
It checks applicable hierarchical permissions and relevant TCR/SCTLR/PAN state,
and rejects layouts or permission mechanisms it does not understand. Table loads
are preceded by AT translation checks; the short native probe restores PAR_EL1
and DAIF. The adapter does not write translation-table entries directly or
reconfigure TTBR, TCR, MAIR or SCTLR.

Only explicitly allocated private ELF pages are eligible. Allocation, permission
changes, failure cleanup and final release retain ownership and permission
verification. Writable executable mappings are rejected. A real hole still
requires actual read protection. Firmware failures remain failures; an
unsupported native operation may use a validated compatible provider, while a
native access-denied response is not converted into success.

The vendor CPU setter may also alter the AP bits that govern EL0 access. KBL does
not promise an EL0 security boundary or fault containment for privileged native
programs. The supported path verifies the permissions relevant to the current
EL1 execution environment; it does not temporarily disable PAN to make a read
succeed.

Permission initialization is reached by explicit ELF execution. Ordinary boot,
Fastboot command dispatch, and RAMFS builtins do not trigger ELF page protection.
The original runtime remains cooperative, and SDK programs must follow its ABI.

The implementation is independently written under its MIT notices. Primary
interface/reference material:

- [UEFI MemoryAttribute protocol](https://uefi.org/specs/UEFI/2.11/37_Secure_Technologies.html)
- [PI CPU Architectural Protocol](https://uefi.org/specs/PI/1.9/V2_DXE_Architectural_Protocols.html)
- [EDK2 ArmCpuDxe memory attributes](https://github.com/tianocore/edk2/blob/master/ArmPkg/Drivers/CpuDxe/MemoryAttribute.c)
- [EDK2 AArch64 MMU getter](https://github.com/tianocore/edk2/blob/master/ArmPkg/Drivers/CpuDxe/AArch64/Mmu.c)

Validation reports distinguish host fixtures, execution of the actual ARM64
adapter/firmware code with modeled tables, and physical phone tests. Offline
permission success is not a claim that this new provider has already passed on
the phone. The user controls firmware reboot/testing.
