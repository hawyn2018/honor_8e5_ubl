import importlib.util
from pathlib import Path
import hashlib
import struct
import subprocess
import sys
import tempfile
import unittest

FILE = Path(__file__).resolve().parents[1] / "kbl_console.py"
spec = importlib.util.spec_from_file_location("kbl_store_test_client", FILE)
client = importlib.util.module_from_spec(spec); sys.modules[spec.name] = client; spec.loader.exec_module(client)

class Store(client.NativeFastboot):
    def __init__(self):
        super().__init__("unused-store-fixture")
        self.calls, self.staged, self.stage_path = [], None, None
        self.fail_stage, self.bad_hash, self.bad_size = False, False, False
    def _capture(self, args):
        self.calls.append(list(args))
        if args[0] == "stage":
            self.stage_path = Path(args[1]); self.staged = self.stage_path.read_bytes()
            return subprocess.CompletedProcess(args, int(self.fail_stage), b"OKAY", b"")
        assert args[:2] == ["oem", "kblstore"]
        magic, nonce, path_n, reserved, size, digest = struct.unpack_from("<8sQIIQ32s", self.staged)
        assert magic == b"KBLPUT01" and reserved == 0
        assert args[2] == f"env1:{nonce:016x}"
        assert len(self.staged) == 64 + path_n + size
        assert hashlib.sha256(self.staged[64 + path_n:]).digest() == digest
        if self.bad_hash: digest = bytes(32)
        if self.bad_size: size += 1
        text = (f"KBL1 stored=1 size={size}\nKBL1 id=0000000000000001\n"
                f"KBL1 hash0={digest.hex()[:32]}\nKBL1 hash1={digest.hex()[32:]}\n")
        return subprocess.CompletedProcess(args, 0, text.encode(), b"")

class StoreTest(unittest.TestCase):
    def test_binary_unicode_path_nonce_hash_and_no_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "program no-extension"
            data = b"\0\xff" + bytes(range(256)); source.write_bytes(data)
            t = Store(); r = t.store_file(source, "/tmp/程序")
            self.assertEqual(len(data), r.integer("size"))
            n = struct.unpack_from("<I", t.staged, 16)[0]
            self.assertEqual("/tmp/程序".encode(), t.staged[64:64+n])
            self.assertEqual(data, t.staged[64+n:])
            self.assertEqual(["stage", "oem"], [c[0] for c in t.calls])
            self.assertFalse(t.stage_path.exists())
            self.assertEqual(data, source.read_bytes())
    def test_empty_file_is_an_explicit_valid_envelope(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "empty"; p.write_bytes(b"")
            self.assertEqual(0, Store().store_file(p, "/tmp/empty").integer("size"))
    def test_stage_failure_never_publishes_or_executes(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "p"; p.write_bytes(b"x")
            t = Store(); t.fail_stage = True
            with self.assertRaises(client.TransportError): t.store_file(p, "/tmp/p")
            self.assertEqual(1, len(t.calls)); self.assertFalse(t.stage_path.exists())
    def test_size_or_digest_mismatch_does_not_autoexecute_or_retry(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "p"; p.write_bytes(b"x")
            for attribute in ("bad_hash", "bad_size"):
                t = Store(); setattr(t, attribute, True)
                with self.subTest(attribute=attribute), self.assertRaises(client.ProtocolError): t.store_file(p, "/tmp/p")
                self.assertEqual(2, len(t.calls)); self.assertFalse(t.stage_path.exists())
    def test_invalid_paths_never_stage(self):
        t = Store()
        for path in ("relative", "/bad\0path"):
            with self.subTest(path=repr(path)), self.assertRaises(client.KblError): t.store_file("unused", path)
        self.assertEqual([], t.calls)
    def test_store_above_old_size_and_path_limits(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "filesystem.img"
            with p.open("wb") as f: f.truncate(16 * 1024 * 1024 + 1)
            t = Store(); remote = "/" + "a" * 512
            reply = t.store_file(p, remote)
            self.assertEqual(16 * 1024 * 1024 + 1, reply.integer("size"))
            path_bytes = struct.unpack_from("<I", t.staged, 16)[0]
            self.assertEqual(remote.encode(), t.staged[64:64 + path_bytes])
            self.assertEqual(["stage", "oem"], [call[0] for call in t.calls])
            self.assertFalse(t.stage_path.exists())

if __name__ == "__main__": unittest.main()
