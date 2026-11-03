"""Run the production OTA presence action against a real, local ls directory."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/assets/scripts/ota-device.sh"
BASH = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"


class OtaPresence(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT / "tests")
        self.root = Path(self.tmp.name)
        self.byname = self.root / "by-name"
        self.byname.mkdir()
        script = SOURCE.read_text(encoding="utf-8")
        script = script.replace('/dev/block/by-name', self.byname.as_posix())
        script = script.replace('[ "$(id -u)" = 0 ] || die \'root uid not verified\'', ': # host uid fixture')
        self.script = self.root / "presence.sh"
        self.script.write_text(script, encoding="utf-8", newline="\n")

    def tearDown(self):
        self.tmp.cleanup()

    def invoke(self):
        return subprocess.run([BASH, self.script.as_posix(), self.root.as_posix(), "efisp-presence"],
                              capture_output=True, text=True, encoding="utf-8")

    def test_real_ls_finds_efisp_without_reading_identity_or_target_number(self):
        # Presence needs the name, not a hard-coded sda66 or sysfs geometry.
        for target in ("/dev/block/sda1", "/dev/block/sda66", "/dev/block/sdb137"):
            with self.subTest(target=target):
                (self.byname / "efisp").write_text(target)
                result = self.invoke()
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual("EFISP=present\n", result.stdout)
                self.assertEqual("", result.stderr)

    def test_nearby_names_do_not_count_as_efisp(self):
        for name in ("efisp_backup", "old_efisp", "efisp_a", "init_boot_a"):
            (self.byname / name).touch()
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("EFISP=absent\n", result.stdout)

    def test_empty_directory_is_absent(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("EFISP=absent\n", result.stdout)

    def test_failed_ls_is_a_read_error_instead_of_absent(self):
        self.byname.rmdir()
        result = self.invoke()
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("EFISP=absent", result.stdout)
        self.assertIn("分区列表失败", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
