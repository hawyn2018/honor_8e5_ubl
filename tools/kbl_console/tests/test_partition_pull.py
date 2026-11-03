import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

FILE = Path(__file__).resolve().parents[1] / "kbl_console.py"
spec = importlib.util.spec_from_file_location("kbl_partition_pull_client", FILE)
client = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = client
spec.loader.exec_module(client)

class Partition(client.NativeFastboot):
    def __init__(self, data=b"\0\xffpartition\n"):
        super().__init__("unused-partition-fixture")
        self.data, self.calls, self.hashes = data, [], []
        self.job = 0
        self.hash_rc = 0
        self.hash_output = None
        self.fetch_bytes = data
        self.fetch_fail = False
        self.size_change = False
        self.size_calls = 0
        self.on_fetch = None
    def _kbl_getvar(self, name):
        self.calls.append(("getvar", name))
        self.size_calls += 1
        return hex(len(self.data) + (self.size_change and self.size_calls > 1))
    def submit(self, line):
        self.calls.append(("submit", line))
        self.job += 1
        return client.Reply({"id": "1", "el": "1", "job": f"{self.job:x}"})
    def request(self, name, argument=None):
        assert name == "kblpoll"
        self.calls.append((name, argument))
        digest = self.hashes.pop(0) if self.hashes else hashlib.sha256(self.data).hexdigest()
        raw = self.hash_output if self.hash_output is not None else (digest + "  /dev/block/by-name/efisp\n").encode()
        return client.Reply({"done": "1", "rc": str(self.hash_rc)}, raw)
    def _capture(self, args):
        self.calls.append(tuple(args))
        assert args[0:2] == ["fetch", "efisp"]
        Path(args[2]).write_bytes(self.fetch_bytes)
        if self.on_fetch:
            self.on_fetch()
        return subprocess.CompletedProcess(args, int(self.fetch_fail), b"fixture binary fetch", b"")

class PartitionPullTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp(prefix="kbl-partition-pull-test-")).resolve()
        self.assertEqual(self.directory.parent, Path(tempfile.gettempdir()).resolve())
    def tearDown(self):
        # Flat test-owned files only: no recursive removal or computed tree walk.
        for path in self.directory.iterdir():
            self.assertTrue(path.is_file())
            path.unlink()
        self.directory.rmdir()
    def console(self, transport):
        return client.Console(transport, output=io.StringIO(), error=io.StringIO(), poll_interval=0)
    def test_full_binary_data_and_two_device_hashes_before_publication(self):
        t = Partition(bytes(range(256)) * 513)
        target = self.directory / "out.img"
        self.assertEqual(target, self.console(t).pull_partition("efisp", target))
        self.assertEqual(t.data, target.read_bytes())
        self.assertEqual(2, len([x for x in t.calls if x[0] == "submit"]))
        self.assertEqual(1, len([x for x in t.calls if x[0] == "fetch"]))
        self.assertEqual([target], list(self.directory.iterdir()))
        self.assertFalse(any(x[0] in {"flash", "erase", "reboot", "kbloff"} for x in t.calls))
    def test_changed_device_hash_keeps_target_unpublished_and_cleans_stage(self):
        t = Partition();t.hashes = [hashlib.sha256(t.data).hexdigest(), "0" * 64]
        with self.assertRaisesRegex(client.TransportError, "摘要改变"):
            self.console(t).pull_partition("efisp", self.directory / "out")
        self.assertEqual([], list(self.directory.iterdir()))
    def test_changed_partition_size_keeps_target_unpublished(self):
        t = Partition();t.size_change = True
        with self.assertRaises(client.TransportError):
            self.console(t).pull_partition("efisp", self.directory / "out")
        self.assertEqual([], list(self.directory.iterdir()))
    def test_corrupted_or_short_binary_fetch_rejected(self):
        for data in [b"bad", b"x" * len(Partition().data)]:
            t = Partition();t.fetch_bytes = data
            with self.subTest(data=data), self.assertRaises(client.TransportError):
                self.console(t).pull_partition("efisp", self.directory / "out")
            self.assertEqual([], list(self.directory.iterdir()))
    def test_native_failure_and_keyboard_interrupt_clean_only_owned_temporaries(self):
        t = Partition();t.fetch_fail = True
        with self.assertRaises(client.TransportError):
            self.console(t).pull_partition("efisp", self.directory / "out")
        self.assertEqual([], list(self.directory.iterdir()))
        t = Partition()
        def interrupt():raise KeyboardInterrupt
        t.on_fetch = interrupt
        with self.assertRaises(KeyboardInterrupt):
            self.console(t).pull_partition("efisp", self.directory / "out")
        self.assertEqual([], list(self.directory.iterdir()))
    def test_preexisting_output_and_invalid_names_never_contact_device(self):
        target = self.directory / "keep";target.write_bytes(b"keep")
        t = Partition()
        with self.assertRaises(client.KblError):self.console(t).pull_partition("efisp", target)
        for label in ["", "kbl.1", "bad name", "x;reboot", "x" * 37, "\0", "分区"]:
            with self.subTest(label=label), self.assertRaises(client.KblError):
                self.console(t).pull_partition(label, self.directory / "out")
        self.assertEqual([], t.calls)
        self.assertEqual(b"keep", target.read_bytes())
    def test_hash_failure_and_malformed_output_do_not_fetch(self):
        for raw, rc in [(None, 1), (b"OKAY\n", 0), (b"0" * 64 + b"  wrong\n", 0)]:
            t = Partition();t.hash_output = raw;t.hash_rc = rc
            with self.subTest(raw=raw, rc=rc), self.assertRaises(client.KblError):
                self.console(t).pull_partition("efisp", self.directory / "out")
            self.assertFalse(any(x[0] == "fetch" for x in t.calls))
            self.assertEqual([], list(self.directory.iterdir()))
    def test_output_created_during_fetch_is_preserved(self):
        target = self.directory / "out";t = Partition()
        t.on_fetch = lambda: target.write_bytes(b"new user file")
        with self.assertRaises(FileExistsError):self.console(t).pull_partition("efisp", target)
        self.assertEqual(b"new user file", target.read_bytes())
        self.assertEqual([target], list(self.directory.iterdir()))
    def test_el_change_stops_before_fetch(self):
        t = Partition();c = self.console(t);c.el = 2
        with self.assertRaisesRegex(client.ProtocolError, "EL 与接管时不同"):
            c.pull_partition("efisp", self.directory / "out")
        self.assertFalse(any(x[0] == "fetch" for x in t.calls))
    def test_console_block_path_uses_verified_partition_route(self):
        class Attached(Partition):
            def request(self, name, argument=None):
                if name == "kblin":
                    self.calls.append((name,))
                    return client.Reply({"status": "running", "id": "1", "el": "1"})
                return super().request(name, argument)
        target = self.directory / "out.img";t = Attached()
        lines = iter([f':pull /dev/block/by-name/efisp "{target}"', "exit"])
        c = client.Console(t, output=io.StringIO(), error=io.StringIO(), reader=lambda _:next(lines), poll_interval=0)
        self.assertEqual(0, c.attach("kblin"))
        self.assertEqual(t.data, target.read_bytes())
        self.assertFalse(any(x[0] == "kblreadopen" for x in t.calls))

if __name__ == "__main__":unittest.main()
