import importlib.util
from pathlib import Path
import hashlib
import struct
import subprocess
import sys
import tempfile
import unittest

FILE = Path(__file__).resolve().parents[1] / "kbl_console.py"
spec = importlib.util.spec_from_file_location("kbl_readback_client", FILE)
client = importlib.util.module_from_spec(spec); sys.modules[spec.name] = client; spec.loader.exec_module(client)

class Readback(client.NativeFastboot):
    def __init__(self, data=b"\0\xffbinary\n"):
        super().__init__("unused-readback-fixture")
        self.data, self.calls, self.staged = data, [], None
        self.bad_hash, self.bad_size, self.fetch_fail, self.close_fail = False, False, False, False
    def _capture(self, args):
        self.calls.append(list(args))
        result = subprocess.CompletedProcess(args, 0, b"", b"")
        if args[0] == "stage":
            self.stage_path=Path(args[1]); self.staged=self.stage_path.read_bytes(); return result
        if args[:2] == ["oem", "kblreadopen"]:
            if args[2].startswith("env1:"):
                magic, nonce, n, reserved, size, sha=struct.unpack_from("<8sQIIQ32s",self.staged)
                assert magic==b"KBLPUT01" and reserved==size==0 and sha==hashlib.sha256(b"").digest()
                assert args[2]==f"env1:{nonce:016x}" and len(self.staged)==64+n
                self.path=self.staged[64:].decode()
            else: self.path=bytes.fromhex(args[2]).decode()
            sha=hashlib.sha256(self.data).hexdigest()
            if self.bad_hash: sha="bad"
            size=len(self.data)+int(self.bad_size)
            result.stdout=(f"KBL1 file=kbl.0000000000000001\nKBL1 size={size}\nKBL1 hash0={sha[:32]}\nKBL1 hash1={sha[32:]}\n").encode()
            if self.bad_hash: result.stdout=b"KBL1 file=kbl.0000000000000001\nKBL1 size=1\nKBL1 hash0=bad\n"
            return result
        if args[0] == "fetch":
            assert args[1]=="kbl.0000000000000001"
            Path(args[2]).write_bytes(self.data);result.returncode=int(self.fetch_fail);return result
        assert args==["oem","kblreadclose","kbl.0000000000000001"]
        result.returncode=int(self.close_fail);return result

class ReadbackTest(unittest.TestCase):
    def test_binary_exact_sha_and_snapshot_close(self):
        with tempfile.TemporaryDirectory() as d:
            t=Readback(bytes(range(256))*3); p=t.fetch_kbl_file("/tmp/p",Path(d)/"out")
            self.assertEqual(t.data,p.read_bytes());self.assertEqual("/tmp/p",t.path)
            self.assertEqual(["oem","fetch","oem"],[x[0] for x in t.calls])
    def test_long_utf8_path_uses_nonce_envelope_no_kernel_start(self):
        with tempfile.TemporaryDirectory() as d:
            t=Readback();remote="/tmp/"+"程序"*50;t.fetch_kbl_file(remote,Path(d)/"out")
            self.assertEqual(remote,t.path);self.assertFalse(t.stage_path.exists())
            self.assertEqual(["stage","oem","fetch","oem"],[x[0] for x in t.calls])
    def test_empty_snapshot_requires_empty_digest_and_never_fetches_zero_chunk(self):
        with tempfile.TemporaryDirectory() as d:
            t=Readback(b"");p=t.fetch_kbl_file("/tmp/e",Path(d)/"out")
            self.assertEqual(b"",p.read_bytes());self.assertNotIn("fetch",[x[0]for x in t.calls])
    def test_bad_metadata_and_fetch_failure_close_handle_without_publishing(self):
        with tempfile.TemporaryDirectory() as d:
            for flag in ("bad_hash","bad_size","fetch_fail"):
                t=Readback();setattr(t,flag,True);p=Path(d)/flag
                with self.subTest(flag=flag),self.assertRaises(client.KblError):t.fetch_kbl_file("/tmp/p",p)
                self.assertFalse(p.exists());self.assertEqual("kblreadclose",t.calls[-1][1])
    def test_preexisting_output_and_invalid_path_never_contact_device(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"out";p.write_bytes(b"keep");t=Readback()
            with self.assertRaises(client.KblError):t.fetch_kbl_file("/tmp/p",p)
            p.unlink()
            for remote in ("relative","/bad\0path"):
                with self.subTest(remote=repr(remote)),self.assertRaises(client.KblError):t.fetch_kbl_file(remote,p)
            self.assertEqual([],t.calls)
    def test_cleanup_error_reports_verified_file_was_saved(self):
        with tempfile.TemporaryDirectory() as d:
            t=Readback();t.close_fail=True;p=Path(d)/"out"
            with self.assertRaisesRegex(client.KblError,"文件已校验保存"):t.fetch_kbl_file("/tmp/p",p)
            self.assertEqual(t.data,p.read_bytes())

if __name__=="__main__":unittest.main()
