"""Collect raw GPT evidence through the user-granted Root bridge, without writes.

Only identity, disk-size, sysfs reads and input-only dd commands are submitted.
Raw regions are kept even when their CRC is invalid. Nothing invokes sgdisk or
repairs GPT, and the two GPT entry arrays are preserved independently.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import uuid
import zlib


ROOT = Path(__file__).resolve().parents[1]
MAX_TABLE_BYTES = 16 * 1024 * 1024
MAX_DISK_BYTES = (1 << 63) - 1
MAX_DIFFS = 128
HEADER = struct.Struct("<8sIIIIQQQQ16sQIII")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def unsigned_decimal(text, name):
    if not re.fullmatch(r"[0-9]+", text):
        raise ValueError(f"Invalid {name}: {text!r}")
    value = int(text)
    if value <= 0 or value > MAX_DISK_BYTES:
        raise ValueError(f"Out-of-range {name}: {value}")
    return value


class Collector:
    def __init__(self, serial, disk, output, report):
        self.serial, self.disk, self.output, self.report = serial, disk, output, report
        self.device = "/dev/block/" + disk
        node = re.escape(disk)
        self.allowed = re.compile(
            rf"(?:id -u|blockdev --getsize64 /dev/block/{node}|"
            rf"cat /sys/class/block/{node}/(?:size|queue/logical_block_size)|"
            rf"dd if=/dev/block/{node} bs=[0-9]+ skip=[0-9]+ count=[0-9]+)"
        )
        self.sector = None
        self.total_lbas = None
        self.header_baselines = {}

    def command(self, label, command):
        if not command or any(not self.allowed.fullmatch(line) for line in command.splitlines()):
            raise ValueError("Command is outside the read-only acquisition allowlist")
        job = self.output / "jobs" / f"{len(self.report['commands']) + 1:02d}-{label}"
        job.mkdir(parents=True)
        record = {"label": label, "command": command, "directory": str(job.relative_to(self.output))}
        self.report["commands"].append(record)
        # Binary streams never pass through a text decoder or a shell pipeline.
        with (job / "client-stdout.log").open("wb") as stdout, (job / "client-stderr.log").open("wb") as stderr:
            result = subprocess.run(
                [sys.executable, str(ROOT / "tools/root-bridge-client.py"),
                 "--serial", self.serial, "--command", command, "--output", str(job)],
                cwd=ROOT, stdout=stdout, stderr=stderr, timeout=240, check=False,
            )
        status_path = job / "status.json"
        if not status_path.is_file():
            raise RuntimeError(f"Bridge did not produce status.json; see {job}")
        status = json.loads(status_path.read_text(encoding="utf-8"))
        record["status"] = status
        if result.returncode != 0 or type(status.get("exitCode")) is not int or status["exitCode"] != 0:
            raise RuntimeError(f"Bridge read failed ({label}); see {job}")
        data = (job / "stdout.bin").read_bytes()
        record.update(stdout_bytes=len(data), stdout_sha256=sha256(data))
        return data

    def save(self, name, data, lba, count, **extra):
        (self.output / name).write_bytes(data)
        item = {"file": name, "source_lba": lba, "source_lba_count": count,
                "bytes": len(data), "sha256": sha256(data), **extra}
        self.report["regions"].append(item)
        return item

    def read(self, label, lba, count):
        if self.sector is None or self.total_lbas is None:
            raise ValueError("Disk geometry is not validated")
        if lba < 0 or count <= 0 or lba >= self.total_lbas or count > self.total_lbas - lba:
            raise ValueError("Requested GPT region is outside the disk")
        if count * self.sector > MAX_TABLE_BYTES + self.sector:
            raise ValueError("Requested GPT region exceeds the acquisition limit")
        data = self.command(label, f"dd if={self.device} bs={self.sector} skip={lba} count={count}")
        if len(data) != count * self.sector:
            raise ValueError(f"Short or oversized read for {label}: {len(data)} bytes")
        return data

    def recheck_headers(self):
        stability = self.report.setdefault("stability", {})
        for name, (lba, baseline) in self.header_baselines.items():
            try:
                data = self.read(name + "-header-final", lba, 1)
                self.save(name + "-header-final.bin", data, lba, 1)
                stability[name + "_header_unchanged"] = data == baseline
                if data != baseline:
                    self.report["issues"].append(name + " header changed during collection")
            except Exception as error:
                stability[name + "_header_unchanged"] = None
                self.report["capture_errors"].append(str(error))
        stability["both_headers_unchanged"] = (
            len(self.header_baselines) == 2
            and all(stability.get(name + "_header_unchanged") is True for name in ("primary", "backup"))
        )


def decode_header(data, lba, total_lbas, sector, role):
    fields = HEADER.unpack_from(data)
    signature, revision, size, crc, reserved, current, alternate, first, last, guid, entries, count, entry_size, entries_crc = fields
    header = {
        "source_lba": lba, "signature_hex": signature.hex(), "revision": revision,
        "header_size": size, "stored_header_crc32": crc, "computed_header_crc32": None,
        "header_crc_valid": False, "reserved": reserved, "current_lba": current,
        "alternate_lba": alternate, "first_usable_lba": first, "last_usable_lba": last,
        "disk_guid": str(uuid.UUID(bytes_le=guid)), "entries_lba": entries,
        "entry_count": count, "entry_size": entry_size, "stored_entries_crc32": entries_crc,
        "validation_errors": [],
    }
    errors = header["validation_errors"]
    if signature != b"EFI PART":
        errors.append("Invalid GPT signature")
    if revision != 0x00010000 or reserved != 0:
        errors.append("Invalid GPT revision or reserved field")
    if not 92 <= size <= sector:
        errors.append("Header size is outside its logical sector")
    else:
        raw = bytearray(data[:size])
        raw[16:20] = b"\0" * 4
        actual = zlib.crc32(raw) & 0xffffffff
        header.update(computed_header_crc32=actual, header_crc_valid=(actual == crc))
        if actual != crc:
            errors.append("Header CRC mismatch; geometry is not trusted")
    if current != lba or not 1 <= alternate < total_lbas or alternate == current:
        errors.append("Invalid header current/alternate LBA")
    if not 2 <= first <= last < total_lbas - 1:
        errors.append("Invalid usable LBA range")
    # Bound unsigned fields before arithmetic or any command construction.
    if (count == 0 or entry_size < 128 or entry_size & (entry_size - 1)
            or entry_size > MAX_TABLE_BYTES or count > MAX_TABLE_BYTES // entry_size):
        errors.append("Entry count/size exceeds the supported acquisition bounds")
    else:
        table_bytes = count * entry_size
        table_sectors = (table_bytes + sector - 1) // sector
        header.update(entries_bytes=table_bytes, entries_sector_count=table_sectors)
        if not 2 <= entries < total_lbas or table_sectors > total_lbas - entries:
            errors.append("Entry array is outside the disk")
        elif role == "primary" and entries + table_sectors > first:
            errors.append("Primary entry array overlaps usable space")
        elif role == "backup" and (entries <= last or entries + table_sectors > current):
            errors.append("Backup entry array overlaps usable space or its header")
    return header


def entry_description(data):
    if data is None:
        return None
    return {
        "used": data[:16] != b"\0" * 16,
        "type_guid": str(uuid.UUID(bytes_le=data[:16])),
        "unique_guid": str(uuid.UUID(bytes_le=data[16:32])),
        "first_lba": struct.unpack_from("<Q", data, 32)[0],
        "last_lba": struct.unpack_from("<Q", data, 40)[0],
        "attributes": struct.unpack_from("<Q", data, 48)[0],
        "attributes_hex": f"0x{struct.unpack_from('<Q', data, 48)[0]:016x}",
        "name": data[56:128].decode("utf-16-le", errors="replace").split("\0", 1)[0],
        "sha256": sha256(data),
    }


def compare_entries(primary, backup, headers):
    diffs, difference_count = [], 0
    left, right = headers["primary"], headers["backup"]
    for index in range(max(left["entry_count"], right["entry_count"])):
        a = primary[index * left["entry_size"]:(index + 1) * left["entry_size"]] if index < left["entry_count"] else None
        b = backup[index * right["entry_size"]:(index + 1) * right["entry_size"]] if index < right["entry_count"] else None
        if a != b:
            difference_count += 1
            if len(diffs) < MAX_DIFFS:
                diffs.append({"entry_index": index + 1, "primary": entry_description(a), "backup": entry_description(b)})
    return {"bytes_equal": primary == backup, "different_entry_count": difference_count,
            "entry_index_base": 1, "diffs": diffs, "diffs_truncated": difference_count > len(diffs)}


def inspect(collector):
    report, disk = collector.report, collector.disk
    metadata = collector.command("geometry", "\n".join([
        "id -u", f"blockdev --getsize64 /dev/block/{disk}",
        f"cat /sys/class/block/{disk}/size", f"cat /sys/class/block/{disk}/queue/logical_block_size",
    ])).decode("ascii").splitlines()
    if len(metadata) != 4 or metadata[0].strip() != "0":
        raise ValueError("Expected UID 0 and exactly four disk geometry fields")
    disk_bytes = unsigned_decimal(metadata[1].strip(), "disk bytes")
    size512 = unsigned_decimal(metadata[2].strip(), "512-byte sector count")
    sector = unsigned_decimal(metadata[3].strip(), "logical sector bytes")
    if size512 > MAX_DISK_BYTES // 512 or size512 * 512 != disk_bytes:
        raise ValueError("blockdev and sysfs disk sizes disagree")
    if not 512 <= sector <= 65536 or sector & (sector - 1) or disk_bytes % sector:
        raise ValueError("Invalid logical sector size or disk alignment")
    total_lbas = disk_bytes // sector
    if total_lbas < 6:
        raise ValueError("Disk is too small for GPT")
    collector.sector, collector.total_lbas = sector, total_lbas
    report["geometry"] = {"uid": 0, "disk_bytes": disk_bytes, "size512": size512,
                          "logical_sector_bytes": sector, "logical_sector_count": total_lbas}
    front = collector.read("front-two-sectors", 0, 2)
    collector.save("front-two-sectors.bin", front, 0, 2)
    collector.save("protective-mbr-sector.bin", front[:sector], 0, 1)
    primary_raw = front[sector:]
    collector.save("primary-header.bin", primary_raw, 1, 1)
    collector.header_baselines["primary"] = (1, primary_raw)
    report["protective_mbr_signature_valid"] = front[510:512] == b"\x55\xaa"
    if not report["protective_mbr_signature_valid"]:
        report["issues"].append("Protective MBR signature is invalid")
    headers = report["headers"] = {}
    headers["primary"] = decode_header(primary_raw, 1, total_lbas, sector, "primary")
    if headers["primary"]["validation_errors"]:
        raise ValueError("Primary header is untrusted; further geometry-based reads stopped")
    alternate = headers["primary"]["alternate_lba"]
    backup_raw = collector.read("backup-header", alternate, 1)
    collector.save("backup-header.bin", backup_raw, alternate, 1)
    collector.header_baselines["backup"] = (alternate, backup_raw)
    headers["backup"] = decode_header(backup_raw, alternate, total_lbas, sector, "backup")
    if headers["backup"]["validation_errors"]:
        raise ValueError("Backup header is untrusted; entry-array reads stopped")
    a, b = headers["primary"], headers["backup"]
    matching_fields = ("disk_guid", "first_usable_lba", "last_usable_lba", "entry_count", "entry_size", "stored_entries_crc32")
    report["header_pair"] = {"matching_fields": {key: a[key] == b[key] for key in matching_fields},
                             "pointers_reciprocal": b["alternate_lba"] == 1,
                             "backup_at_disk_end": alternate == total_lbas - 1}
    if not all(report["header_pair"]["matching_fields"].values()) or b["alternate_lba"] != 1 or alternate != total_lbas - 1:
        report["issues"].append("Primary and backup GPT header geometry/metadata differ")
    arrays = {}
    for role, header in headers.items():
        raw = collector.read(role + "-entries", header["entries_lba"], header["entries_sector_count"])
        collector.save(role + "-entries-sectors.bin", raw, header["entries_lba"], header["entries_sector_count"])
        exact = raw[:header["entries_bytes"]]
        collector.save(role + "-entries.bin", exact, header["entries_lba"], header["entries_sector_count"],
                       crc_payload_bytes=len(exact), excludes_sector_padding=True)
        computed = zlib.crc32(exact) & 0xffffffff
        header.update(computed_entries_crc32=computed, entries_crc_valid=(computed == header["stored_entries_crc32"]))
        if not header["entries_crc_valid"]:
            report["issues"].append(role + " partition entry array CRC mismatch")
        arrays[role] = exact
    report["entry_comparison"] = compare_entries(arrays["primary"], arrays["backup"], headers)
    if not report["entry_comparison"]["bytes_equal"]:
        report["issues"].append("Primary and backup partition entry arrays differ")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--disk", default="sda")
    parser.add_argument("--output", type=Path, default=ROOT / "reports/gpt-readonly-current")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", args.disk):
        parser.error("--disk must be a simple block-device node name")
    output = args.output.resolve()
    # Existing evidence is never silently overwritten.
    output.mkdir(parents=True, exist_ok=False)
    report = {"format": "honor-ubl-raw-gpt-evidence-v1", "serial": args.serial,
              "disk": "/dev/block/" + args.disk, "started_at_utc": datetime.now(timezone.utc).isoformat(),
              "source_read_only": True, "partition_writes": False, "capture_complete": False,
              "commands": [], "regions": [], "issues": [], "capture_errors": [],
              "snapshot_limit": "Sequential reads; unchanged headers do not establish an atomic disk snapshot."}
    collector = Collector(args.serial, args.disk, output, report)
    try:
        inspect(collector)
        report["capture_complete"] = True
    except Exception as error:
        report["capture_errors"].append(str(error))
    finally:
        collector.recheck_headers()
        if report["capture_errors"] or not report.get("stability", {}).get("both_headers_unchanged"):
            report["capture_complete"] = False
        report["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
        report["gpt_checks_passed"] = report["capture_complete"] and not report["issues"]
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "capture_complete": report["capture_complete"],
                      "source_read_only": True, "gpt_checks_passed": report["gpt_checks_passed"],
                      "issues": report["issues"], "capture_errors": report["capture_errors"]}, ensure_ascii=True, indent=2))
    return 0 if report["capture_complete"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
