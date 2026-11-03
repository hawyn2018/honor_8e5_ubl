"""Explicit ADB activation: scripted transports only, no connected device."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("kbl_adb_optin_client", Path(__file__).resolve().parents[1] / "kbl_console.py")
client = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = client
spec.loader.exec_module(client)

def reply(adb, *, status="running", identity="0000000000000001", job=""):
    fields = f"KBL1 status={status} id={identity} el=1\nKBL1 adb={adb}\n"
    if job:
        fields += f"KBL1 job={job}\n"
    return subprocess.CompletedProcess([], 0, fields.encode(), b"")

class Scripted(client.NativeFastboot):
    def __init__(self, results):
        super().__init__("fixture", prefix=["-s", "selected"], timeout=.05)
        self.results, self.calls = iter(results), []
    def _capture(self, args):
        self.calls.append(list(args))
        result = next(self.results)
        if isinstance(result, BaseException):
            raise result
        return result

class AdbOptInTest(unittest.TestCase):
    def test_start_accepts_disabled_adb_without_polling_or_enabling(self):
        t = Scripted([reply("disabled")])
        self.assertEqual("disabled", t.request("kblstart").fields["adb"])
        self.assertEqual([["oem", "kblstart"]], t.calls)

    def test_adb_active_needs_no_retry(self):
        t = Scripted([reply("active")])
        self.assertEqual("active", t.request("kbladb").fields["adb"])
        self.assertEqual([["oem", "kbladb"]], t.calls)

    def test_waiting_ack_polls_readonly_until_active(self):
        t = Scripted([reply("waiting-start-ack"), client.DisconnectedError("enumerating"), reply("active")])
        with mock.patch.object(client.time, "sleep"):
            self.assertEqual("active", t.request("kbladb").fields["adb"])
        self.assertEqual([["oem", "kbladb"], ["oem", "kblstatus"], ["oem", "kblstatus"]], t.calls)

    def test_lost_adb_ack_does_not_replay_either_activation_command(self):
        t = Scripted([client.DisconnectedError("lost ack"), reply("waiting-start-ack"), reply("active")])
        with mock.patch.object(client.time, "sleep"):
            self.assertEqual("active", t.request("kbladb").fields["adb"])
        self.assertEqual([["oem", "kbladb"], ["oem", "kblstatus"], ["oem", "kblstatus"]], t.calls)

    def test_active_busy_keeps_existing_job_identity(self):
        t = Scripted([reply("active", status="busy", job="0000000000000007")])
        self.assertEqual("0000000000000007", t.request("kbladb").fields["job"])

    def test_identity_change_and_terminal_adb_failure_are_reported(self):
        for second in [reply("active", identity="0000000000000002"), reply("unavailable-protocol")]:
            t = Scripted([reply("waiting-start-ack"), second])
            with self.subTest(second=second.stdout), self.assertRaises(client.ProtocolError):
                t.request("kbladb")
            self.assertEqual(1, sum(c == ["oem", "kbladb"] for c in t.calls))

    def test_ctrl_c_only_stops_local_wait(self):
        t = Scripted([reply("waiting-start-ack"), KeyboardInterrupt()])
        with self.assertRaises(KeyboardInterrupt):
            t.request("kbladb")
        self.assertEqual([["oem", "kbladb"], ["oem", "kblstatus"]], t.calls)

    def test_control_name_is_exact(self):
        self.assertEqual((["-s", "selected"], "kbladb"), client.control_invocation(["-s", "selected", "oem", "kbladb"]))
        self.assertIsNone(client.control_invocation(["oem", "kbladbx"]))
        self.assertIsNone(client.control_invocation(["oem", "kbladb", "extra"]))

if __name__ == "__main__":
    unittest.main()
