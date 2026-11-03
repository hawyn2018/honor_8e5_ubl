"""Local scripted client results only; no USB, device or real Fastboot."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

MODULE = Path(__file__).resolve().parents[1] / "kbl_console.py"
spec = importlib.util.spec_from_file_location("kbl_start_reconnect_client", MODULE)
client = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = client
spec.loader.exec_module(client)

def process(code=0, output=b"KBL1 status=running id=0000000000000001 el=1\n"):
    return subprocess.CompletedProcess([], code, output, b"")

LOST = process(1, b"FAILED (Status read failed (No such device))\n")

class Scripted(client.NativeFastboot):
    def __init__(self, results):
        super().__init__("unused-fixture", prefix=["-s", "same-serial"], timeout=0.05)
        self.results = iter(results)
        self.calls = []

    def _capture(self, args):
        self.calls.append(list(args))
        result = next(self.results)
        if isinstance(result, BaseException):
            raise result
        return result

class ReconnectTest(unittest.TestCase):
    def test_start_ack_loss_queries_status_without_replaying_start(self):
        t = Scripted([LOST, LOST, process()])
        with mock.patch.object(client.time, "sleep"):
            reply = t.request("kblstart")
        self.assertEqual("running", reply.fields["status"])
        self.assertEqual([["oem", "kblstart"], ["oem", "kblstatus"], ["oem", "kblstatus"]], t.calls)
        self.assertEqual(["-s", "same-serial"], t.prefix)
        self.assertFalse(hasattr(t, "_reconnect_deadline"))

    def test_start_page_query_loss_recovers_with_new_readonly_status(self):
        t = Scripted([process(output=b"OKAY\n"), LOST, process()])
        reply = t.request("kblstart")
        self.assertEqual("1", reply.fields["el"])
        self.assertEqual([["oem", "kblstart"], ["getvar", "kbl-reply-header"], ["oem", "kblstatus"]], t.calls)

    def test_completed_remote_failure_does_not_retry_anything(self):
        t = Scripted([process(1, b"FAILED (remote: 'KBL driver load failed')\n")])
        with self.assertRaises(client.TransportError):
            t.request("kblstart")
        self.assertEqual([["oem", "kblstart"]], t.calls)

    def test_timeout_start_is_not_replayed(self):
        t = Scripted([client.DisconnectedError("local timeout"), process()])
        self.assertEqual("running", t.request("kblstart").fields["status"])
        self.assertEqual([["oem", "kblstart"], ["oem", "kblstatus"]], t.calls)

    def test_ctrl_c_during_reconnection_only_interrupts_local_wait(self):
        t = Scripted([LOST, KeyboardInterrupt()])
        with self.assertRaises(KeyboardInterrupt):
            t.request("kblstart")
        self.assertEqual([["oem", "kblstart"], ["oem", "kblstatus"]], t.calls)
        self.assertFalse(hasattr(t, "_reconnect_deadline"))

    def test_mutating_commands_never_gain_automatic_retry(self):
        for name in ("kblcmd", "kblput", "kblstore", "kbloff", "kblstop"):
            t = Scripted([LOST])
            with self.subTest(name=name), self.assertRaises(client.DisconnectedError):
                t.request(name, "01" if name in ("kblcmd", "kblput", "kblstore") else None)
            self.assertEqual(1, len(t.calls))

    def test_reconnection_still_requires_real_identity_and_running_status(self):
        for fields in (b"status=stopped id=1 el=1", b"status=running el=1", b"status=running id=0 el=1", b"status=running id=1 el=0", b"status=busy id=1 el=1"):
            t = Scripted([LOST, process(output=b"KBL1 " + fields + b"\n")])
            with self.subTest(fields=fields), self.assertRaises(client.ProtocolError):
                t.request("kblstart")
            self.assertEqual([["oem", "kblstart"], ["oem", "kblstatus"]], t.calls)

    def test_deadline_does_not_resend_start(self):
        t = Scripted([LOST, LOST, LOST])
        with mock.patch.object(client.time, "monotonic", side_effect=[0, 0, .01, .02, .03, .051]), mock.patch.object(client.time, "sleep"):
            with self.assertRaises(client.TransportError):
                t.request("kblstart")
        self.assertEqual([["oem", "kblstart"], ["oem", "kblstatus"], ["oem", "kblstatus"]], t.calls)
        self.assertFalse(hasattr(t, "_reconnect_deadline"))

    def test_stop_alias_is_an_exact_control(self):
        self.assertEqual((["-s", "same-serial"], "kblstop"), client.control_invocation(["-s", "same-serial", "oem", "kblstop"]))
        self.assertIsNone(client.control_invocation(["oem", "kblstopx"]))
        self.assertIsNone(client.control_invocation(["oem", "kblstop", "extra"]))

if __name__ == "__main__":
    unittest.main()
