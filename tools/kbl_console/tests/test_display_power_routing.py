"""Host routing fixtures only; never opens USB, ADB or a real Fastboot process."""
import importlib.util
import io
from pathlib import Path
import sys
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("kbl_display_host", Path(__file__).resolve().parents[1] / "kbl_console.py")
client = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = client
spec.loader.exec_module(client)


class FakeShell:
    """A local in-memory protocol fixture, not a bootloader transport."""
    def __init__(self, disconnect=False):
        self.calls = []
        self.disconnect = disconnect

    def request(self, name, *args):
        self.calls.append((name, *args))
        fields = {"id": "1", "el": "1", "status": "running"}
        if name == "kblpoll":
            fields.update(done="1", rc="0")
        return client.Reply(fields)

    def submit(self, line):
        self.calls.append(("submit", line))
        if self.disconnect:
            raise client.DisconnectedError("fixture disconnected")
        return client.Reply({"id": "1", "el": "1", "job": "1"})


def console_for(transport, lines):
    lines = iter(lines)
    def read(_):
        value = next(lines)
        if isinstance(value, BaseException):
            raise value
        return value
    return client.Console(transport, output=io.StringIO(), error=io.StringIO(), reader=read, sleeper=lambda _: None)


class DisplayPowerRoutingTest(unittest.TestCase):
    def test_display_oem_is_not_an_environment_control(self):
        self.assertTrue({"screenoff", "screenon", "poweroff"}.isdisjoint(client.CONTROL_NAMES))
        for name in ("screenoff", "screenon"):
            for args in (["oem", name], ["-s", "USB Serial", "oem", name],
                         ["--slot=b", "oem", name], ["oem", name, "extra"]):
                with self.subTest(args=args):
                    self.assertIsNone(client.control_invocation(args))

    def test_main_display_oem_passthroughs_once_without_attaching(self):
        for name in ("screenoff", "screenon"):
            args = ["-s", "USB Serial", "oem", name]
            with self.subTest(name=name), mock.patch.object(client, "find_native", return_value=Path("fixture")), \
                    mock.patch.object(client, "NativeFastboot") as native, mock.patch.object(client, "Console") as console:
                native.return_value.passthrough.return_value = 0
                self.assertEqual(0, client.main(args))
                native.return_value.passthrough.assert_called_once_with(args)
                native.return_value.request.assert_not_called()
                console.assert_not_called()

    def test_nonzero_native_display_result_is_not_retried(self):
        with mock.patch.object(client, "find_native", return_value=Path("fixture")), \
                mock.patch.object(client, "NativeFastboot") as native:
            native.return_value.passthrough.return_value = 7
            self.assertEqual(7, client.main(["oem", "screenoff"]))
            native.return_value.passthrough.assert_called_once_with(["oem", "screenoff"])
            native.return_value.request.assert_not_called()

    def test_native_passthrough_preserves_exact_arguments(self):
        args = ["-s", "USB Serial", "oem", "screenon"]
        native = client.NativeFastboot(["fixture", "unchanged"])
        with mock.patch.object(client.subprocess, "call", return_value=0) as call:
            self.assertEqual(0, native.passthrough(args))
            call.assert_called_once_with(["fixture", "unchanged", *args])

    def test_shell_display_and_poweroff_are_only_explicit_task_lines(self):
        transport = FakeShell()
        self.assertEqual(0, console_for(transport, ["screenoff", "screenon", "poweroff", "exit"]).attach("kblstart"))
        self.assertEqual([("submit", "screenoff"), ("submit", "screenon"), ("submit", "poweroff")],
                         [item for item in transport.calls if item[0] == "submit"])
        self.assertFalse(any(item[0] in ("kbloff", "kblstop", "kbladb", "reboot") for item in transport.calls))

    def test_interrupted_or_exited_console_never_synthesizes_poweroff(self):
        for end in ("exit", "quit", ":detach", EOFError(), KeyboardInterrupt()):
            transport = FakeShell()
            with self.subTest(end=end):
                self.assertIn(console_for(transport, [end]).attach("kblstart"), (0, 130))
                self.assertFalse(any(item[0] == "submit" for item in transport.calls))
                self.assertFalse(any(item[0] in ("kbloff", "kblstop", "kbladb", "reboot") for item in transport.calls))

    def test_ambiguous_poweroff_disconnect_is_never_resubmitted(self):
        transport = FakeShell(disconnect=True)
        with self.assertRaises(client.KblError):
            console_for(transport, ["poweroff"]).attach("kblstart")
        self.assertEqual([("kblstart",), ("submit", "poweroff")], transport.calls)

    def test_help_documents_display_and_poweroff_without_executing_them(self):
        transport = FakeShell()
        console = console_for(transport, [":help", "exit"])
        self.assertEqual(0, console.attach("kblstart"))
        self.assertIn("screenoff/screenon", console.error.getvalue())
        self.assertIn("poweroff", console.error.getvalue())
        self.assertEqual([("kblstart",)], transport.calls)


if __name__ == "__main__":
    unittest.main()
