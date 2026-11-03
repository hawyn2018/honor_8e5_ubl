"""Host-only failure injection for the actual OTA commit loop; never opens a device.

Only the Android uid, tmpfs/partition identity and read-range interfaces are replaced.
Hashing, plan parsing, capacity/slot gates, backup barriers, dd, and readback checks
run from the production script against temporary regular files.
"""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/assets/scripts/ota-device.sh"
BASH = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"


def sha(data):
    return hashlib.sha256(data).hexdigest()


class OtaTransaction(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT / "tests")
        self.root = Path(self.tmp.name)
        self.run = self.root / "runtime"
        self.ram = self.root / "ram"
        self.backup = self.root / "backup"
        for p in (self.run / "tools", self.ram, self.backup):
            p.mkdir(parents=True)
        self.events = self.run / "events"
        self.events.write_text("")
        self.io_arguments = self.run / "io-arguments"
        self.io_arguments.write_text("")
        (self.run / "partition-names").write_text("abl_a\nabl_b\ninit_boot_a\ninit_boot_b\n")
        bb = self.run / "tools/busybox"
        bb.write_text('#!/usr/bin/env bash\nexec "$@"\n')
        bb.chmod(0o700)
        worker = self.run / "partition_worker"
        worker.write_text('''#!/usr/bin/env bash
set -eu
printf 'read\n' >> "${0%/*}/events"
printf 'read-range|%s|%s\n' "$2" "$4" >> "${0%/*}/io-arguments"
head -c "$4" "$2"
if [ -f "${0%/*}/corrupt-readback" ]; then
  count=$(grep -c '^read$' "${0%/*}/events")
  if [ "$count" -ge 2 ]; then printf 'corrupt'; fi
fi
''')
        worker.chmod(0o700)
        script = SOURCE.read_text(encoding="utf-8")
        script = re.sub(r"slot\(\) \{.*?\n\}", 'slot() { printf "%s\\n" "${OTA_TEST_SLOT:-a}"; }', script, count=1, flags=re.S)
        script = re.sub(r"identity\(\) \{.*?\n\}", 'identity() { cat "$RUN/identity-$1"; }', script, count=1, flags=re.S)
        script = re.sub(r"assert_ram\(\) \{.*?\n\}", 'assert_ram() { [ -d "$1" ]; }', script, count=1, flags=re.S)
        script = script.replace('[ "$(id -u)" = 0 ] || die \'root uid not verified\'', ': # uid fixture')
        script = script.replace('tool() { "$@" 2>/dev/null || "$BB" "$@"; }', '''tool() {
  if [ "$1" = blockdev ]; then
    printf 'setrw\n' >> "$RUN/events"
    printf 'setrw|%s\n' "$3" >> "$RUN/io-arguments"
    [ "$2" = --setrw ]
  elif [ "$1" = ls ] && [ "$2" = /dev/block/by-name ]; then
    cat "$RUN/partition-names"
  else "$@"; fi
}
dd() {
  printf 'dd\n' >> "$RUN/events"
  for argument in "$@"; do
    case "$argument" in of=*) printf 'dd|%s\n' "${argument#of=}" >> "$RUN/io-arguments";; esac
  done
  [ ! -f "$RUN/fail-dd" ] || return 1
  command dd "$@"
}
''')
        self.script = self.root / "fixture.sh"
        self.script.write_text(script, encoding="utf-8", newline="\n")
        self.original = b"A" * 4096
        self.image = b"B" * 2048
        self.target = self.root / "abl_b"
        self.target.write_bytes(self.original)
        self.source = self.ram / "new-abl.img"
        self.source.write_bytes(self.image)
        (self.backup / "abl_b.img").write_bytes(self.original)
        self.identity = f"abl_b|{self.target.as_posix()}|{self.target.as_posix()}|1:2|4096|0|512"
        (self.run / "identity-abl_b").write_text(self.identity + "\n")
        self.plan = [f"BACKUP|abl_b.img|4096|{sha(self.original)}",
                     f"SOURCE|new-abl.img|2048|{sha(self.image)}",
                     f"WRITE|abl_b|new-abl.img|{sha(self.image)}|2048|{sha(self.original)}|{self.identity}"]

    def tearDown(self):
        self.tmp.cleanup()

    def invoke(self, slot="a", scope="full"):
        plan = self.ram / "commit.plan"
        plan.write_text("\n".join(self.plan) + "\n", newline="\n")
        stat = subprocess.run([BASH, "-c", 'stat -c "%d:%i" "$1"', "_", self.backup.as_posix()], capture_output=True, text=True, check=True).stdout.strip()
        env = dict(os.environ, OTA_TEST_SLOT=slot)
        return subprocess.run([BASH, self.script.as_posix(), self.run.as_posix(), "commit", self.ram.as_posix(), sha(plan.read_bytes()), self.backup.as_posix(), stat, slot, scope],
                              capture_output=True, text=True, env=env)

    def unchanged(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.original, self.target.read_bytes())
        self.assertNotIn("setrw", self.events.read_text())
        self.assertNotIn("dd", self.events.read_text())

    def test_success_orders_setrw_before_dd_and_checks_readback(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("RESULT=success", result.stdout)
        self.assertEqual(self.image, self.target.read_bytes()[:2048])
        self.assertEqual(["read", "setrw", "dd", "read"], self.events.read_text().splitlines())
        self.assertIn("WRITE_VERIFIED=abl_b", (self.backup / "ota-write-journal.log").read_text())

    def test_read_and_commit_use_by_name_alias_not_canonical_evidence(self):
        by_name = self.root / "by-name" / "abl_b"
        by_name.parent.mkdir()
        # Git Bash's sys symlinks provide real shell-visible links on Windows
        # without requiring SeCreateSymbolicLinkPrivilege. Unix uses native links.
        subprocess.run(
            [BASH, "-c", 'ln -s "$1" "$2" && test -L "$2"', "_",
             self.target.as_posix(), by_name.as_posix()],
            env=dict(os.environ, MSYS="winsymlinks:sys"),
            capture_output=True, text=True, check=True,
        )
        unavailable_canonical = self.root / "unavailable-canonical" / "sda999"
        self.assertFalse(unavailable_canonical.exists())
        self.identity = f"abl_b|{by_name.as_posix()}|{unavailable_canonical.as_posix()}|1:2|4096|0|512"
        (self.run / "identity-abl_b").write_text(self.identity + "\n")
        self.plan[-1] = f"WRITE|abl_b|new-abl.img|{sha(self.image)}|2048|{sha(self.original)}|{self.identity}"

        extracted = subprocess.run(
            [BASH, self.script.as_posix(), self.run.as_posix(), "read", self.identity],
            capture_output=True,
        )
        self.assertEqual(0, extracted.returncode, extracted.stderr)
        self.assertEqual(self.original, extracted.stdout)
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.image, self.target.read_bytes()[:2048])
        alias = by_name.as_posix()
        self.assertEqual(
            [f"read-range|{alias}|4096", f"read-range|{alias}|4096",
             f"setrw|{alias}", f"dd|{alias}", f"read-range|{alias}|2048"],
            self.io_arguments.read_text().splitlines(),
        )
        self.assertFalse(unavailable_canonical.exists())
        self.assertIn("WRITE_VERIFIED=abl_b", (self.backup / "ota-write-journal.log").read_text())

    def test_missing_backup_blocks_every_write(self):
        (self.backup / "abl_b.img").unlink()
        self.unchanged(self.invoke())

    def test_modified_public_backup_blocks_every_write(self):
        (self.backup / "abl_b.img").write_bytes(b"C" * 4096)
        self.unchanged(self.invoke())

    def test_modified_ram_source_blocks_every_write(self):
        self.source.write_bytes(b"C" * 2048)
        self.unchanged(self.invoke())

    def test_wrong_source_even_in_later_record_blocks_first_write(self):
        self.plan.append("SOURCE|missing.img|2048|" + sha(self.image))
        self.unchanged(self.invoke())

    def test_active_slot_write_is_blocked(self):
        self.unchanged(self.invoke(slot="b"))

    def test_later_active_slot_target_blocks_first_write(self):
        self.plan.append(self.plan[-1].replace("WRITE|abl_b|", "WRITE|abl_a|", 1))
        self.unchanged(self.invoke())

    def test_capacity_overflow_blocks_before_setrw(self):
        self.plan[-1] = self.plan[-1].replace("|2048|", "|8192|", 1)
        self.unchanged(self.invoke())

    def test_target_changed_after_backup_blocks_write(self):
        self.target.write_bytes(b"C" * 4096)
        result = self.invoke()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"C" * 4096, self.target.read_bytes())
        self.assertNotIn("setrw", self.events.read_text())

    def test_direct_readback_failure_keeps_started_journal_without_success(self):
        (self.run / "corrupt-readback").write_text("1")
        result = self.invoke()
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("RESULT=success", result.stdout)
        journal = (self.backup / "ota-write-journal.log").read_text()
        self.assertIn("WRITE_STARTED", journal)
        self.assertNotIn("WRITE_VERIFIED", journal)

    def test_dd_failure_never_marks_write_verified(self):
        (self.run / "fail-dd").write_text("1")
        result = self.invoke()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(self.original, self.target.read_bytes())
        journal = (self.backup / "ota-write-journal.log").read_text()
        self.assertIn("WRITE_STARTED", journal)
        self.assertNotIn("WRITE_VERIFIED", journal)
        self.assertNotIn("RESULT=success", result.stdout)

    def test_other_slot_extraction_stays_frozen_before_parent_abl_overwrite(self):
        efisp = self.root / "efisp"
        efisp_original = b"E" * 4096
        efisp.write_bytes(efisp_original)
        # Represents a generated product derived from the ORIGINAL other-slot ABL.
        frozen = self.ram / "generated-efisp.img"
        frozen.write_bytes(self.original[:2048])
        (self.ram / "frozen-other-abl.img").write_bytes(self.original)
        (self.backup / "efisp.img").write_bytes(efisp_original)
        identity = f"efisp|{efisp.as_posix()}|{efisp.as_posix()}|1:3|4096|0|512"
        (self.run / "identity-efisp").write_text(identity + "\n")
        self.plan += [f"BACKUP|efisp.img|4096|{sha(efisp_original)}",
                      f"SOURCE|frozen-other-abl.img|4096|{sha(self.original)}",
                      f"SOURCE|generated-efisp.img|2048|{sha(self.original[:2048])}",
                      f"WRITE|efisp|generated-efisp.img|{sha(self.original[:2048])}|2048|{sha(efisp_original)}|{identity}"]
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.image, self.target.read_bytes()[:2048])
        self.assertEqual(self.original[:2048], efisp.read_bytes()[:2048])

    def efisp_only_plan(self):
        efisp = self.root / "efisp"
        original = b"E" * 4096
        efisp.write_bytes(original)
        (self.backup / "efisp.img").write_bytes(original)
        identity = f"efisp|{efisp.as_posix()}|{efisp.as_posix()}|1:3|4096|0|512"
        (self.run / "identity-efisp").write_text(identity + "\n")
        self.plan = [f"BACKUP|efisp.img|4096|{sha(original)}",
                     f"SOURCE|new-abl.img|2048|{sha(self.image)}",
                     f"WRITE|efisp|new-abl.img|{sha(self.image)}|2048|{sha(original)}|{identity}"]
        return efisp, original

    def test_efisp_only_commits_without_any_abl_or_init_boot_backup(self):
        efisp, original = self.efisp_only_plan()
        (self.backup / "abl_b.img").unlink()
        result = self.invoke(scope="efisp-only")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(self.original, self.target.read_bytes())
        self.assertEqual(self.image, efisp.read_bytes()[:2048])
        self.assertEqual(["read", "setrw", "dd", "read"], self.events.read_text().splitlines())
        journal = (self.backup / "ota-write-journal.log").read_text()
        self.assertIn("WRITE_VERIFIED=efisp", journal)
        self.assertNotIn("abl", journal)

    def test_efisp_only_scope_rejects_abl_before_setrw(self):
        self.unchanged(self.invoke(scope="efisp-only"))

    def test_efisp_only_late_abl_target_blocks_efisp_write(self):
        abl_plan = self.plan.copy()
        efisp, original = self.efisp_only_plan()
        self.plan += abl_plan
        self.unchanged(self.invoke(scope="efisp-only"))
        self.assertEqual(original, efisp.read_bytes())

    def test_efisp_only_missing_original_backup_blocks_write(self):
        efisp, original = self.efisp_only_plan()
        self.plan = [line for line in self.plan if not line.startswith("BACKUP|")]
        self.unchanged(self.invoke(scope="efisp-only"))
        self.assertEqual(original, efisp.read_bytes())

    def test_export_scope_cannot_reach_partition_commit(self):
        self.unchanged(self.invoke(scope="export-efisp"))

    def test_missing_efisp_check_does_not_read_or_write_partitions(self):
        result = subprocess.run([BASH, self.script.as_posix(), self.run.as_posix(), "efisp-presence"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("EFISP=absent", result.stdout.strip())
        self.assertEqual("", self.events.read_text())
        self.assertEqual(self.original, self.target.read_bytes())
        self.assertFalse((self.backup / "ota-write-journal.log").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
