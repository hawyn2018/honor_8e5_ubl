"""STORE budgeting: real sparse files, modeled process I/O; no device/replay."""
import importlib.util
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
FILE = Path(__file__).resolve().parents[1] / "kbl_console.py"
spec = importlib.util.spec_from_file_location("kbl_transfer_wait_client", FILE)
client = importlib.util.module_from_spec(spec);sys.modules[spec.name] = client;spec.loader.exec_module(client)
class Pipeline:
    def __init__(self, transport, failure=None):
        self.transport, self.failure = transport, failure
        self.calls, self.waits, self.stage_path, self.envelope = [], [], None, None
    def process(self, command, **kwargs):
        owner = self;args = list(command[1:]);owner.calls.append(args)
        class Process:
            returncode = 0
            stopped = False
            def communicate(self, timeout):
                owner.waits.append((args[0], owner.transport.timeout, timeout))
                if not self.stopped and args[0] == "oem" and owner.failure in ("timeout", "interrupt"):
                    if owner.failure == "timeout":raise subprocess.TimeoutExpired(command,timeout)
                    raise KeyboardInterrupt()
                if args[0] == "stage":
                    owner.stage_path=Path(args[1])
                    with owner.stage_path.open("rb") as f:owner.envelope=struct.unpack("<8sQIIQ32s",f.read(64))
                    magic,nonce,path_n,reserved,count,digest=owner.envelope
                    assert magic==b"KBLPUT01" and reserved==0 and owner.stage_path.stat().st_size==64+path_n+count
                    if owner.failure=="stage":self.returncode=1
                    return b"OKAY",None
                assert args[:2]==["oem","kblstore"]
                magic,nonce,path_n,reserved,count,digest=owner.envelope
                assert args[2]==f"env1:{nonce:016x}"
                if owner.failure=="store":self.returncode=1;return b"FAILED modeled",None
                return (f"KBL1 stored=1 size={count}\nKBL1 id=0000000000000001\nKBL1 hash0={digest.hex()[:32]}\nKBL1 hash1={digest.hex()[32:]}\n".encode(),None)
            def terminate(self):self.stopped=True
            def kill(self):self.stopped=True
        return Process()
class TransferWait(unittest.TestCase):
    def test_large_payload_wait_scales_and_explicit_outer_deadline_still_wins(self):
        for size,deadline in ((64*1024*1024+17,None),(128*1024*1024+17,100.5)):
            with self.subTest(size=size),tempfile.TemporaryDirectory() as directory:
                source=Path(directory)/"sparse.img"
                with source.open("wb") as f:f.truncate(size)
                t=client.NativeFastboot("fixture-only",timeout=12)
                if deadline is not None:t._reconnect_deadline=deadline
                pipeline=Pipeline(t)
                with mock.patch.object(client.subprocess,"Popen",pipeline.process),mock.patch.object(client.time,"monotonic",return_value=100):
                    result=t.store_file(source,"/large.img")
                self.assertEqual(size,result.integer("size"));self.assertEqual(12,t.timeout)
                self.assertEqual(["stage","oem"],[c[0]for c in pipeline.calls])
                self.assertTrue(all(configured>=12+size/(1024*1024)for _,configured,_ in pipeline.waits))
                if deadline is None:self.assertTrue(all(wait>=12+size/(1024*1024)for _,_,wait in pipeline.waits))
                else:self.assertTrue(all(wait==.5 for _,_,wait in pipeline.waits));self.assertEqual(deadline,t._reconnect_deadline)
                self.assertFalse(pipeline.stage_path.exists())
    def test_failure_and_interrupt_restore_base_without_replaying_any_request(self):
        for failure,error in (("stage",client.TransportError),("store",client.TransportError),("timeout",client.DisconnectedError),("interrupt",KeyboardInterrupt)):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as directory:
                source=Path(directory)/"tiny";source.write_bytes(b"x")
                t=client.NativeFastboot("fixture-only",timeout=30);pipeline=Pipeline(t,failure)
                with mock.patch.object(client.subprocess,"Popen",pipeline.process),self.assertRaises(error):t.store_file(source,"/tiny")
                self.assertEqual(30,t.timeout)
                self.assertEqual(["stage"]if failure=="stage"else["stage","oem"],[c[0]for c in pipeline.calls])
                self.assertFalse(pipeline.stage_path.exists())
    def test_wait_formula_has_no_new_fixed_maximum(self):
        t=client.NativeFastboot("fixture-only",timeout=30)
        with t._transfer_wait(1<<40):self.assertEqual(30+(1<<20),t.timeout)
        self.assertEqual(30,t.timeout)
if __name__=="__main__":unittest.main()
