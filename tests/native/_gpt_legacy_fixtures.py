"""Host-only GPT tests. Every apply targets a temporary regular-file fixture.

Run: python analysis/gpt-2.3-fixtures/test_gpttool.py
Uses clang from PATH; never invokes adb or opens a block device.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import uuid
import zlib

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SOURCE = ROOT / "app/src/main/cpp/gpttool.c"
EXE = HERE / ("gpttool-host.exe" if os.name == "nt" else "gpttool-host")
GUID = "d4052c9a-8972-4dda-982f-9ce47cb21217"
EFI_TYPE = uuid.UUID("c12a7328-f81f-11d2-ba4b-00a0c93ec93b").bytes_le
DISK_BYTES = 128 * 1024 * 1024
BIT50 = 1 << 50
BIT51 = 1 << 51
results = []


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    results.append({"name": name, "status": "pass"})


def run(sector, *args, success=True):
    cmd = [str(EXE)]
    if sector:
        cmd += ["--fixture-sector", str(sector)]
    p = subprocess.run(cmd + [str(a) for a in args], capture_output=True)
    if success and p.returncode:
        raise AssertionError(f"{args}: {p.stderr.decode(errors='replace')}")
    if not success and not p.returncode:
        raise AssertionError(f"unexpected success: {args}")
    return p


def hashfile(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def header_crc(h):
    h = bytearray(h)
    h[16:20] = bytes(4)
    return zlib.crc32(h[:struct.unpack_from("<I", h, 12)[0]])


def header(sector, current, alternate, first, last, entries_lba, table, count, marker):
    # Nonzero reserved-sector padding is deliberately retained by the planner.
    h = bytearray([marker] * sector)
    h[:92] = bytes(92)
    struct.pack_into("<8sIIIIQQQQ16sQIII", h, 0, b"EFI PART", 0x10000, 92, 0, 0,
                     current, alternate, first, last,
                     uuid.UUID("f29d70ee-cf6c-490f-aa68-dda614d52901").bytes_le,
                     entries_lba, count, 128, zlib.crc32(table))
    struct.pack_into("<I", h, 16, header_crc(h))
    return h


def make_disk(path, sector, differ=True):
    count = 97 if sector == 512 else 96
    eb = count * 128
    padded = (eb + sector - 1) // sector * sector
    total = DISK_BYTES // sector
    pe = 2
    # A deliberate 507-sector gap before the final backup header matches the
    # important geometry of the observed Honor disk, without allocating 509GB.
    be = total - 1 - 507 - padded // sector
    first, last = 64, be - 1
    tables = []
    for backup in (False, True):
        t = bytearray(padded)
        for i in range(45):
            off = i * 128
            t[off:off + 16] = uuid.UUID("0fc63daf-8483-4772-8e79-3d69d8477de4").bytes_le
            t[off + 16:off + 32] = uuid.UUID(int=100 + i).bytes_le
            start, end = ((4096, last) if i == 0 else (first + (i - 1) * 32, first + (i - 1) * 32 + 15))
            attrs = (0x1000000000000000 | i)
            if differ and backup and i < 44:
                attrs ^= BIT50
            struct.pack_into("<QQQ", t, off + 32, start, end, attrs)
            name = "userdata" if i == 0 else f"boot_{i}"
            t[off + 56:off + 56 + len(name) * 2] = name.encode("utf-16le")
        # GPT arrays can include alignment padding outside the CRC-covered area.
        t[eb:] = bytes([0xb4 if backup else 0xa3]) * (padded - eb)
        tables.append(t)
    mbr = bytearray([0x7d] * sector)
    mbr[:512] = bytes(512)
    mbr[:16] = b"HONOR-UBL-TEST!!\0"
    mbr[450] = 0xee
    struct.pack_into("<II", mbr, 454, 1, min(total - 1, 0xffffffff))
    mbr[510:512] = b"\x55\xaa"
    ph = header(sector, 1, total - 1, first, last, pe, tables[0][:eb], count, 0x91)
    bh = header(sector, total - 1, 1, first, last, be, tables[1][:eb], count, 0x82)
    with open(path, "wb") as f:
        f.truncate(DISK_BYTES)
        for off, data in [(0, mbr), (sector, ph), (pe * sector, tables[0]),
                          (be * sector, tables[1]), ((total - 1) * sector, bh),
                          ((total - 300) * sector, b"GAP-MUST-STAY-UNCHANGED" * 19),
                          (1024 * 1024, b"NON-GPT-PAYLOAD" * 127)]:
            f.seek(off)
            f.write(data)
    return {"sector": sector, "count": count, "entry_bytes": eb, "padded": padded,
            "pe": pe, "be": be, "bh": total - 1, "old_end": last,
            "new_end": last - 68 * 1024 * 1024 // sector, "efi_index": 46}


def snap_fields(data):
    sector = struct.unpack_from("<I", data, 8)[0]
    eb = struct.unpack_from("<Q", data, 56)[0]
    pad = (eb + sector - 1) // sector * sector
    return {"sector": sector, "entry_bytes": eb, "padded": pad,
            "mbr": 512, "ph": 512 + sector, "pe": 512 + 2 * sector,
            "bh": 512 + 2 * sector + pad, "be": 512 + 3 * sector + pad}


def common_efi_profile(data, fields, guid=GUID, index=46):
    """The observed three-device intersection: ESP / efisp / zero attrs.
    The caller supplies a unique per-plan UUID instead of cloning a device UUID.
    """
    for ep in (fields["pe"], fields["be"]):
        off = ep + (index - 1) * 128
        if data[off:off + 16] != EFI_TYPE or data[off + 16:off + 32] != uuid.UUID(guid).bytes_le:
            return False
        if struct.unpack_from("<Q", data, off + 48)[0] != 0:
            return False
        if data[off + 56:off + 128] != "efisp".encode("utf-16le") + bytes(62):
            return False
    return True


def recalc_snap(data):
    s = snap_fields(data)
    for hp, ep in ((s["ph"], s["pe"]), (s["bh"], s["be"])):
        struct.pack_into("<I", data, hp + 88, zlib.crc32(data[ep:ep + s["entry_bytes"]]))
        struct.pack_into("<I", data, hp + 16, header_crc(data[hp:hp + s["sector"]]))


def put_snapshot_on_disk(path, data):
    s = snap_fields(data)
    with open(path, "r+b") as f:
        for name, lba, length in [("mbr", 0, s["sector"]), ("ph", 1, s["sector"]),
                ("pe", struct.unpack_from("<Q", data, 32)[0], s["padded"]),
                ("bh", struct.unpack_from("<Q", data, 40)[0], s["sector"]),
                ("be", struct.unpack_from("<Q", data, 48)[0], s["padded"])]:
            f.seek(lba * s["sector"])
            f.write(data[s[name]:s[name] + length])


def reject_unchanged(name, sector, disk, before, after):
    digest = hashfile(disk)
    p = run(sector, "apply", disk, before, after, "--execute", success=False)
    check(name, hashfile(disk) == digest and b"RESULT=error" in p.stderr)


def suite(temp, sector):
    disk = temp / f"disk-{sector}.img"
    meta = make_disk(disk, sector)
    initial_hash = hashfile(disk)
    p = run(sector, "verify", disk)
    check(f"{sector}: independent CRC-valid bit50 attributes accepted", b"GPT_ATTRIBUTE_DIFF_COUNT=44" in p.stdout and b"GPT_ATTRIBUTE_DIFF_MASK=0004000000000000" in p.stdout)
    inspect_before = dict(line.split("=", 1) for line in run(sector, "inspect", disk, 1).stdout.decode().splitlines())
    check(f"{sector}: inspect reads original userdata identity and no EFI", inspect_before["UD_INDEX"] == "1" and int(inspect_before["UD_START"]) == 4096 and int(inspect_before["UD_END"]) == meta["old_end"] and inspect_before["EFI_COUNT"] == "0" and inspect_before["DISK_READ_MODE"] == "fixture")
    check(f"{sector}: inspect preserves independent userdata attributes", int(inspect_before["UD_ATTR"], 16) ^ int(inspect_before["UD_ATTR_BACKUP"], 16) == BIT50)
    check(f"{sector}: inspect reports captured disk and userdata identity", inspect_before["DISK_GUID"] == "F29D70EE-CF6C-490F-AA68-DDA614D52901" and inspect_before["UD_TYPE"] == "0FC63DAF-8483-4772-8E79-3D69D8477DE4" and inspect_before["UD_LABEL"] == "userdata" and inspect_before["UD_GUID"] == str(uuid.UUID(int=100)).upper())
    check(f"{sector}: inspect reports captured raw header geometry", int(inspect_before["GPT_CAPACITY"]) == meta["count"] and inspect_before["GPT_ENTRY_SIZE"] == "128" and inspect_before["GPT_HEADER_SIZE"] == "92" and inspect_before["FIRST_USABLE"] == "64" and int(inspect_before["LAST_USABLE"]) == meta["old_end"] and int(inspect_before["PRIMARY_ENTRIES_LBA"]) == meta["pe"] and int(inspect_before["BACKUP_ENTRIES_LBA"]) == meta["be"])
    check(f"{sector}: inspect lists exactly used GPT indices in ascending order", inspect_before["PARTITION_INDICES"] == " ".join(str(i) for i in range(1, 46)))
    check(f"{sector}: inspect rejects index outside userdata", run(sector, "inspect", disk, 2, success=False).returncode != 0 and run(sector, "inspect", disk, 0, success=False).returncode != 0)
    exported = run(sector, "export", disk).stdout
    check(f"{sector}: deterministic export", exported == run(sector, "export", disk).stdout)
    s = snap_fields(exported)
    check(f"{sector}: exact canonical container length", len(exported) == 512 + 3 * sector + 2 * meta["padded"] and exported[:8] == b"HUBLGT22" and not any(exported[64:512]))
    before, after = temp / f"before-{sector}.bin", temp / f"after-{sector}.bin"
    run(sector, "plan", disk, 1, meta["new_end"], meta["efi_index"], GUID, before, after)
    check(f"{sector}: plan is read-only and before equals export", hashfile(disk) == initial_hash and before.read_bytes() == exported)
    planned = after.read_bytes()
    check(f"{sector}: 68MiB new EFI uses exact three-device common profile in both tables", common_efi_profile(planned, s))
    expected = bytearray(exported)
    for ep in (s["pe"], s["be"]):
        struct.pack_into("<Q", expected, ep + 40, meta["new_end"])
        off = ep + (meta["efi_index"] - 1) * 128
        expected[off:off + 16] = EFI_TYPE
        expected[off + 16:off + 32] = uuid.UUID(GUID).bytes_le
        struct.pack_into("<QQQ", expected, off + 32, meta["new_end"] + 1, meta["old_end"], 0)
        expected[off + 56:off + 66] = "efisp".encode("utf-16le")
    recalc_snap(expected)
    check(f"{sector}: exact independent Python plan and padding preserved", planned == expected)
    run(sector, "plan", disk, 1, meta["new_end"], meta["efi_index"], GUID, before, after, success=False)
    check(f"{sector}: existing plan files never overwritten", before.read_bytes() == exported and after.read_bytes() == planned)
    run(sector, "apply", disk, before, after, "--execute")
    check(f"{sector}: apply readback matches after", run(sector, "export", disk).stdout == planned)
    inspected = dict(line.split("=", 1) for line in run(sector, "inspect", disk, 1).stdout.decode().splitlines())
    check(f"{sector}: inspect reads created 68MiB EFI layout directly from GPT", inspected["EFI_COUNT"] == "1" and int(inspected["UD_END"]) == meta["new_end"] and int(inspected["EFI_START"]) == meta["new_end"] + 1 and int(inspected["EFI_END"]) == meta["old_end"] and int(inspected["EFI_BYTES"]) == 68 * 1024 * 1024)
    check(f"{sector}: inspect reports exact created EFI identity", inspected["EFI_GUID"] == GUID.upper() and inspected["EFI_TYPE"] == "C12A7328-F81F-11D2-BA4B-00A0C93EC93B" and inspected["EFI_LABEL"] == "efisp" and int(inspected["EFI_ATTR"], 16) == int(inspected["EFI_ATTR_BACKUP"], 16) == 0)
    check(f"{sector}: inspect includes newly used index without changing header identity", inspected["PARTITION_INDICES"] == " ".join(str(i) for i in range(1, 47)) and all(inspected[k] == inspect_before[k] for k in ("DISK_GUID", "GPT_CAPACITY", "GPT_ENTRY_SIZE", "GPT_HEADER_SIZE", "FIRST_USABLE", "LAST_USABLE", "PRIMARY_ENTRIES_LBA", "BACKUP_ENTRIES_LBA", "UD_TYPE", "UD_LABEL", "UD_GUID")))
    installed_digest = hashfile(disk)
    run(sector, "plan", disk, 1, meta["new_end"] - 1, 47, "31366ea8-327f-4ed5-bad6-0578235ea602", temp / f"repeat-{sector}-before", temp / f"repeat-{sector}-after", success=False)
    check(f"{sector}: already-created EFI rejects second shrink into another empty slot", hashfile(disk) == installed_digest)
    check(f"{sector}: apply keeps all 44 independent attributes", b"GPT_ATTRIBUTE_DIFF_COUNT=44" in run(sector, "verify", disk).stdout)
    # Independently modify only GPT regions in a copy, then compare whole files.
    expected_disk = temp / f"expected-{sector}.img"
    make_disk(expected_disk, sector)
    put_snapshot_on_disk(expected_disk, planned)
    check(f"{sector}: non-GPT disk bytes unchanged", hashfile(disk) == hashfile(expected_disk))
    expected_disk.unlink()
    reject_unchanged(f"{sector}: no replay after GPT already changed", sector, disk, before, after)
    put_snapshot_on_disk(disk, exported)

    fresh_guids = [str(uuid.uuid4()), str(uuid.uuid4())]
    fresh_snapshots = []
    for number, fresh in enumerate(fresh_guids):
        a_path, b_path = temp / f"fresh-{sector}-{number}-before", temp / f"fresh-{sector}-{number}-after"
        run(sector, "plan", disk, 1, meta["new_end"], 46, fresh, a_path, b_path)
        fresh_snapshots.append(b_path.read_bytes())
        check(f"{sector}: independent UUID plan {number + 1} retains common EFI profile", common_efi_profile(fresh_snapshots[-1], s, fresh))
    check(f"{sector}: new plans use independently supplied nonzero UUIDs without cloning source identity", fresh_guids[0] != fresh_guids[1] and fresh_snapshots[0][s["pe"] + 45 * 128 + 16:s["pe"] + 45 * 128 + 32] != fresh_snapshots[1][s["pe"] + 45 * 128 + 16:s["pe"] + 45 * 128 + 32] and hashfile(disk) == initial_hash)

    # Dynamic tail sizes are derived from a caller-supplied on-disk filesystem
    # boundary. The native layer enforces a positive bounded GPT tail only.
    release_sectors = [1, 3, 1024 * 1024 // sector, 64 * 1024 * 1024 // sector,
                       96 * 1024 * 1024 // sector, meta["old_end"] - 4096]
    for released in release_sectors:
        end = meta["old_end"] - released
        dynamic_before, dynamic_after = temp / f"dynamic-{sector}-{released}-before", temp / f"dynamic-{sector}-{released}-after"
        run(sector, "plan", disk, 1, end, meta["efi_index"], GUID, dynamic_before, dynamic_after)
        a = dynamic_after.read_bytes()
        check(f"{sector}: dynamic {released} sectors new EFI retains three-device common profile", common_efi_profile(a, s))
        check(f"{sector}: dynamic {released} sectors plan spans the exact freed tail", all(
            struct.unpack_from("<Q", a, ep + 40)[0] == end and
            struct.unpack_from("<QQ", a, ep + 45 * 128 + 32) == (end + 1, meta["old_end"])
            for ep in (s["pe"], s["be"])))
        check(f"{sector}: dynamic {released} sectors plan keeps per-copy userdata attributes", all(a[ep + 48:ep + 56] == exported[ep + 48:ep + 56] for ep in (s["pe"], s["be"])))
        run(sector, "apply", disk, dynamic_before, dynamic_after, "--execute")
        check(f"{sector}: dynamic {released} sectors apply matches final snapshot", run(sector, "export", disk).stdout == a)
        put_snapshot_on_disk(disk, exported)

    for label, mask in (("bit51", BIT51), ("bit50-and-bit51", BIT50 | BIT51)):
        a = bytearray(exported)
        for index in range(44):
            attrs = struct.unpack_from("<Q", a, s["pe"] + index * 128 + 48)[0]
            struct.pack_into("<Q", a, s["be"] + index * 128 + 48, attrs ^ mask)
        recalc_snap(a)
        put_snapshot_on_disk(disk, a)
        verified = run(sector, "verify", disk).stdout
        check(f"{sector}: {label} CRC-valid independent attributes accepted", b"GPT_ATTRIBUTE_DIFF_COUNT=44" in verified and f"GPT_ATTRIBUTE_DIFF_MASK={mask:016x}".encode() in verified)
        variant_before, variant_after = temp / f"{label}-{sector}-before.bin", temp / f"{label}-{sector}-after.bin"
        run(sector, "plan", disk, 1, meta["new_end"], meta["efi_index"], GUID, variant_before, variant_after)
        b = variant_after.read_bytes()
        check(f"{sector}: {label} new EFI retains three-device common profile", common_efi_profile(b, s))
        check(f"{sector}: {label} plan retains every per-copy attribute", all(
            a[ep + index * 128 + 48:ep + index * 128 + 56] == b[ep + index * 128 + 48:ep + index * 128 + 56]
            for ep in (s["pe"], s["be"]) for index in range(45)))
        run(sector, "apply", disk, variant_before, variant_after, "--execute")
        check(f"{sector}: {label} apply readback matches preserving plan", run(sector, "export", disk).stdout == b)
    put_snapshot_on_disk(disk, exported)

    bad = temp / f"bad-{sector}.bin"
    mutations = [
        ("container version", lambda a: struct.pack_into("<I", a, 12, 2)),
        ("container reserved bytes", lambda a: a.__setitem__(100, 1)),
        ("container entry length", lambda a: struct.pack_into("<Q", a, 56, 1)),
        ("header CRC", lambda a: a.__setitem__(s["ph"] + 16, a[s["ph"] + 16] ^ 1)),
        ("entry CRC", lambda a: a.__setitem__(s["pe"] + 48, a[s["pe"] + 48] ^ 1)),
    ]
    for name, mutate in mutations:
        a = bytearray(planned)
        mutate(a)
        bad.write_bytes(a)
        reject_unchanged(f"{sector}: malformed {name} rejected before write", sector, disk, before, bad)
    bad.write_bytes(planned[:-1])
    reject_unchanged(f"{sector}: truncated snapshot rejected", sector, disk, before, bad)
    bad.write_bytes(planned + bytes(1))
    reject_unchanged(f"{sector}: trailing snapshot bytes rejected", sector, disk, before, bad)
    for name, offset, value in [
            ("non-GPT MBR bootstrap", s["mbr"] + 2, 0x99),
            ("header sector padding", s["ph"] + 100, 0x33),
            ("preserve primary userdata attrs", s["pe"] + 48, 0x66),
            ("preserve backup userdata attrs", s["be"] + 48, 0x66),
            ("unrelated partition", s["pe"] + 128 + 56, ord("z")),
            ("new EFI label", s["pe"] + 45 * 128 + 56, ord("x")),
    ]:
        a = bytearray(planned)
        a[offset] = value
        # Both-copy mutation with valid independent CRCs challenges the exact
        # preserving-transition validator, rather than just the CRC check.
        if name in ("unrelated partition", "new EFI label"):
            a[offset - s["pe"] + s["be"]] = value
        elif name == "preserve primary userdata attrs":
            a[s["be"] + 48] = value
        elif name == "preserve backup userdata attrs":
            a[s["pe"] + 48] = value
        recalc_snap(a)
        bad.write_bytes(a)
        reject_unchanged(f"{sector}: altered {name} rejected", sector, disk, before, bad)

    for name, offset in (("userdata start", 32), ("userdata type GUID", 0), ("userdata unique GUID", 16)):
        a = bytearray(planned)
        for ep in (s["pe"], s["be"]):
            a[ep + offset] ^= 1
        recalc_snap(a)
        bad.write_bytes(a)
        reject_unchanged(f"{sector}: altered {name} identity rejected", sector, disk, before, bad)
    a = bytearray(planned)
    a[s["ph"] + 56] ^= 1
    a[s["bh"] + 56] ^= 1
    recalc_snap(a)
    bad.write_bytes(a)
    reject_unchanged(f"{sector}: changed disk GUID rejected", sector, disk, before, bad)

    # A structurally valid second efisp is surfaced by inspect, and creation is
    # still rejected instead of choosing one arbitrarily or shrinking again.
    a = bytearray(planned)
    for ep in (s["pe"], s["be"]):
        off = ep + 46 * 128
        a[off:off + 16] = EFI_TYPE
        a[off + 16:off + 32] = uuid.UUID("a7b0477b-0351-4ae8-99a0-cfd328eb97b4").bytes_le
        struct.pack_into("<QQQ", a, off + 32, 1600, 1615, 0)
        a[off + 56:off + 66] = "efisp".encode("utf-16le")
    recalc_snap(a)
    put_snapshot_on_disk(disk, a)
    duplicated = dict(line.split("=", 1) for line in run(sector, "inspect", disk, 1).stdout.decode().splitlines())
    check(f"{sector}: inspect exposes multiple EFI names without choosing one", duplicated["EFI_COUNT"] == "2" and "EFI_INDEX" not in duplicated)
    dup_hash = hashfile(disk)
    run(sector, "plan", disk, 1, meta["new_end"] - 1, 48, GUID, temp / "duplicate-before", temp / "duplicate-after", success=False)
    check(f"{sector}: duplicate EFI names reject new plan without disk changes", hashfile(disk) == dup_hash)
    put_snapshot_on_disk(disk, exported)

    # Independent table CRCs stay valid but nonattribute mismatch is rejected.
    a = bytearray(exported)
    a[s["be"] + 128 + 56] = ord("z")
    recalc_snap(a)
    put_snapshot_on_disk(disk, a)
    check(f"{sector}: nonattribute mismatch verify rejected", run(sector, "verify", disk, success=False).returncode != 0)
    reject_unchanged(f"{sector}: nonattribute mismatch apply rejected", sector, disk, before, after)
    # Unsupported attribute differences are rejected, even with valid CRCs.
    a = bytearray(exported)
    a[s["be"] + 49] ^= 1
    recalc_snap(a)
    put_snapshot_on_disk(disk, a)
    check(f"{sector}: other attribute bit rejected", run(sector, "verify", disk, success=False).returncode != 0)
    reject_unchanged(f"{sector}: other attribute bit apply rejected", sector, disk, before, after)
    # Bad array CRC can still be exported verbatim as a diagnostic backup.
    a = bytearray(exported)
    a[s["pe"] + 64] ^= 1
    put_snapshot_on_disk(disk, a)
    check(f"{sector}: bad array CRC exported without repair", run(sector, "export", disk).stdout == a)
    check(f"{sector}: bad array CRC verify rejected", run(sector, "verify", disk, success=False).returncode != 0)
    reject_unchanged(f"{sector}: bad source CRC apply rejected", sector, disk, before, after)
    # Source changes outside entries (including MBR bootstrap) invalidate plan.
    a = bytearray(exported)
    a[s["mbr"] + 1] ^= 1
    put_snapshot_on_disk(disk, a)
    reject_unchanged(f"{sector}: exact source MBR change detected", sector, disk, before, after)
    put_snapshot_on_disk(disk, exported)
    for name, mutate in [
            ("overlapping used entries", lambda a, ep: struct.pack_into("<QQ", a, ep + 128 + 32, 4096, 4111)),
            ("partition outside usable range", lambda a, ep: struct.pack_into("<Q", a, ep + 128 + 32, 1)),
            ("duplicate partition GUID", lambda a, ep: a.__setitem__(slice(ep + 128 + 16, ep + 128 + 32), a[ep + 16:ep + 32])),
    ]:
        a = bytearray(exported)
        mutate(a, s["pe"])
        mutate(a, s["be"])
        recalc_snap(a)
        put_snapshot_on_disk(disk, a)
        check(f"{sector}: {name} verify rejected", run(sector, "verify", disk, success=False).returncode != 0)
        reject_unchanged(f"{sector}: {name} apply rejected", sector, disk, before, after)
    # Header geometry can remain CRC-valid while claiming overlapping metadata.
    a = bytearray(exported)
    struct.pack_into("<Q", a, s["ph"] + 72, 1)
    recalc_snap(a)
    bad.write_bytes(a)
    reject_unchanged(f"{sector}: CRC-valid overlapping table geometry rejected", sector, disk, before, bad)
    put_snapshot_on_disk(disk, exported)
    for name, ud, end, efi, guid in [
            ("wrong userdata index", 2, meta["new_end"], 46, GUID),
            ("zero tail shrink", 1, meta["old_end"], 46, GUID),
            ("tail would expand userdata", 1, meta["old_end"] + 1, 46, GUID),
            ("userdata would have negative size", 1, 4095, 46, GUID),
            ("occupied EFI slot", 1, meta["new_end"], 2, GUID),
            ("duplicate GUID", 1, meta["new_end"], 46, str(uuid.UUID(int=101))),
            ("zero GUID", 1, meta["new_end"], 46, str(uuid.UUID(int=0))),
    ]:
        run(sector, "plan", disk, ud, end, efi, guid, temp / "unused-a", temp / "unused-b", success=False)
        check(f"{sector}: invalid plan {name} rejected without changing disk", hashfile(disk) == initial_hash)
    make_disk(disk, sector, differ=False)
    check(f"{sector}: identical GPT tables accepted", b"GPT_STATUS=clean" in run(sector, "verify", disk).stdout)
    a = bytearray(run(sector, "export", disk).stdout)
    struct.pack_into("<I", a, 512 + 458, 0xffffffff)
    put_snapshot_on_disk(disk, a)
    check(f"{sector}: vendor saturated protective MBR count accepted", b"GPT_VALID=1" in run(sector, "verify", disk).stdout)
    disk.unlink()


def real_raw_compatibility(temp):
    """Validate the previously captured device bytes as a small snapshot file.
    No full-size disk fixture is created and no device is contacted.
    """
    raw = ROOT / "reports/gpt-readonly-raw"
    if not (raw / "protective-mbr-sector.bin").exists():
        return
    mbr = (raw / "protective-mbr-sector.bin").read_bytes()
    ph = (raw / "primary-header-final.bin").read_bytes()
    bh = (raw / "backup-header-final.bin").read_bytes()
    pe = (raw / "primary-entries-sectors.bin").read_bytes()
    be = (raw / "backup-entries-sectors.bin").read_bytes()
    sector = len(ph)
    head = bytearray(512)
    struct.pack_into("<8sIIQQQQQQ", head, 0, b"HUBLGT22", sector, 1,
                     (struct.unpack_from("<Q", bh, 24)[0] + 1) * sector,
                     1, struct.unpack_from("<Q", ph, 72)[0], struct.unpack_from("<Q", bh, 24)[0],
                     struct.unpack_from("<Q", bh, 72)[0], len(pe))
    captured = temp / "captured-readonly.snapshot"
    captured.write_bytes(head + mbr + ph + pe + bh + be)
    harness = HERE / "raw_snapshot_harness.c"
    harness.write_text('#define main gpttool_main\n#include "../../app/src/main/cpp/gpttool.c"\n#undef main\n'
        'int main(int argc,char **argv){struct snapshot s={0}; if(argc!=2||!load_snapshot(argv[1],&s)){fprintf(stderr,"%s\\n",error_text);return 1;} '
        'printf("count=%u diff=%u mask=%016" PRIx64 "\\n",s.count,s.diff_count,s.diff_mask);free_snapshot(&s);return 0;}\n', encoding="utf-8")
    harness_exe = temp / ("raw-harness.exe" if os.name == "nt" else "raw-harness")
    subprocess.run(["clang", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", str(harness), "-o", str(harness_exe)], check=True)
    p = subprocess.run([str(harness_exe), str(captured)], capture_output=True)
    check("captured real 96-entry GPT validates independently without full-size disk", p.returncode == 0 and b"count=96 diff=44 mask=0004000000000000" in p.stdout)
    sde = ROOT / "reports/backup-test-2.2.0/gpt_sde.raw"
    if sde.exists():
        p = subprocess.run([str(harness_exe), str(sde)], capture_output=True)
        check("captured real ABL-parent SDE 128-entry GPT bit51 difference validates", p.returncode == 0 and b"count=128 diff=1 mask=0008000000000000" in p.stdout)


def main():
    subprocess.run(["clang", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", str(SOURCE), "-o", str(EXE)], check=True)
    check("native CRC/endian/GUID self-test", b"SELF_TEST_RESULT=PASS" in run(None, "--self-test").stdout)
    c_source = SOURCE.read_text(encoding="utf-8")
    check("real block opens force O_DIRECT without cached fallback", "if(d->direct)flags|=O_DIRECT;" in c_source and "d->direct=!fixture;" in c_source and c_source.count("open(path,flags)") == 1)
    check("real block buffers use aligned direct I/O transport", "posix_memalign(&aligned,4096,size)" in c_source and "off%d->sector||size%d->sector" in c_source and "if(!d->direct)return" in c_source)
    check("GPT capture and apply use disk transport rather than cached fd I/O", "io_read(d->fd" not in c_source[c_source.index("static bool capture("):] and "io_write(d.fd" not in c_source and "disk_write(&d,after->be" in c_source and "disk_read(d,ph" in c_source)
    with tempfile.TemporaryDirectory(prefix="gpt-fixture-", dir=HERE) as directory:
        temp = Path(directory)
        suite(temp, 512)
        suite(temp, 4096)
        fixture = temp / "regular.img"
        make_disk(fixture, 512)
        check("host refuses regular file without explicit fixture mode", run(None, "verify", fixture, success=False).returncode != 0)
        real_raw_compatibility(temp)
    payload = {"tests": len(results), "passed": len(results), "failed": 0,
               "scope": "host regular-file fixtures and previously captured read-only GPT bytes; no device writes",
               "native_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(), "results": results}
    (HERE / "results.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {len(results)} native GPT fixture checks")


if __name__ == "__main__":
    main()
