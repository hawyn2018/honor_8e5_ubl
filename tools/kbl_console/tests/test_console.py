import io
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kbl_console import (Console, KblError, NativeFastboot, ProtocolError, Reply,
                         TransportError, control_invocation, host_options, parse_reply)


def reader_from(*lines):
    items = iter(lines)
    def read(prompt):
        assert prompt == "# "
        try:
            value = next(items)
        except StopIteration:
            raise EOFError
        if isinstance(value, BaseException):
            raise value
        return value
    return read


class ProtocolTests(unittest.TestCase):
    def test_signed_errors_and_unsigned64_sizes_keep_real_field_bounds(self):
        for value in (-(1 << 63), -1, 0, (1 << 63), (1 << 64) - 1):
            self.assertEqual(value, Reply({"size": str(value)}).integer("size"))
        for value in (-(1 << 63) - 1, (1 << 64)):
            with self.subTest(value=value), self.assertRaises(ProtocolError):
                Reply({"size": str(value)}).integer("size")

    def test_info_hex_and_metadata(self):
        reply = parse_reply(b"", b"(bootloader) KBL1 status=busy id=ab el=1\n"
                            b"(bootloader) KBL1 out=e4b8ad\n"
                            b"(bootloader) KBL1 out=e69687\nKBL1 done=1 rc=-2\nOKAY\n")
        self.assertEqual(reply.output.decode(), "中文")
        self.assertEqual(reply.identifier("id"), "ab")
        self.assertEqual(reply.integer("rc"), -2)

    def test_okay_without_protocol_is_not_ready(self):
        with self.assertRaises(ProtocolError):
            parse_reply(b"OKAY [0.01s]\n")
        self.assertEqual(parse_reply(b"OKAY", tag_required=False), Reply())

    def test_bad_hex_fails(self):
        for value in ("abc", "xx", "00GG"):
            with self.subTest(value=value), self.assertRaises(ProtocolError):
                parse_reply(("KBL1 out=" + value).encode())

    def test_conflicting_identity_fails(self):
        with self.assertRaises(ProtocolError):
            parse_reply(b"KBL1 id=1\nKBL1 id=2")

    def test_oversized_info_fails(self):
        with self.assertRaises(ProtocolError):
            parse_reply(b"KBL1 out=" + b"ab" * 24)

    def test_unrelated_text_not_interpreted(self):
        with self.assertRaises(ProtocolError):
            parse_reply(b"noise KBL1 status=running id=1 el=1")

    def test_exact_control_only(self):
        self.assertEqual(control_invocation(["-s", "device with spaces", "oem", "kblin"]),
                         (["-s", "device with spaces"], "kblin"))
        for args in (["oem", "kblstartx"], ["oem", "kblstart", "bad"], ["getvar", "all"],
                     ["flash", "abl_b", "input with spaces.img"], ["--unknown", "x", "oem", "kblin"]):
            with self.subTest(args=args):
                self.assertIsNone(control_invocation(args))

    def test_host_options_preserve_native_arguments(self):
        options, args = host_options(["--kbl-wire-limit", "63", "-s", "USB Serial", "getvar", "all"])
        self.assertEqual(options["wire_limit"], 63)
        self.assertEqual(args, ["-s", "USB Serial", "getvar", "all"])


class TransportTests(unittest.TestCase):
    fixture_bytes = bytes(range(256)) * 16 + b"\0\xff"
    fixture_metadata = {"expected_size": len(fixture_bytes), "expected_sha256": hashlib.sha256(fixture_bytes).hexdigest()}
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.state = self.directory / "state.json"
        self.log = self.directory / "args.jsonl"
        self.transport = NativeFastboot([sys.executable, str(Path(__file__).with_name("fake_fastboot.py")),
                                        str(self.state), str(self.log)], prefix=["-s", "USB Serial"])

    def tearDown(self):
        self.temp.cleanup()

    def calls(self):
        return [json.loads(row) for row in self.log.read_text(encoding="utf-8").splitlines()] if self.log.exists() else []

    def device(self):
        return json.loads(self.state.read_text(encoding="utf-8"))

    def console(self, *lines, sleeper=lambda _: None):
        output, error = io.StringIO(), io.StringIO()
        console = Console(self.transport, output=output, error=error,
                          reader=reader_from(*lines), sleeper=sleeper)
        return console, output, error

    def assert_no_off(self):
        self.assertFalse(any("kbloff" in call for call in self.calls()))

    def test_exit_quit_eof_leave_runtime_alive(self):
        for line in ("exit", "quit", ":detach", EOFError()):
            with self.subTest(line=line):
                console, _, _ = self.console(line)
                self.assertEqual(console.attach("kblstart"), 0)
                self.assertEqual(self.device()["status"], "running")
        self.assertEqual(self.device()["starts"], 1)
        self.assert_no_off()

    def test_control_c_at_prompt_only_detaches(self):
        console, _, _ = self.console(KeyboardInterrupt())
        self.assertEqual(console.attach("kblstart"), 130)
        self.assertEqual(self.device()["status"], "running")
        self.assert_no_off()

    def test_utf8_output_preserves_split_characters(self):
        line = "echo " + "中文" * 2
        console, output, _ = self.console(line, "exit")
        self.assertEqual(console.attach("kblstart"), 0)
        self.assertEqual(output.getvalue(), "执行：" + line + "\n")
        self.assert_no_off()

    def test_long_command_has_offset_and_exact_commit_length(self):
        self.transport.request("kblstart")
        line = "printf '%s' " + "变量" * 40
        reply = self.transport.submit(line)
        self.assertIsNotNone(reply.identifier("job"))
        self.assertEqual(bytes.fromhex(self.device()["line"]), line.encode())
        protocol_calls = [call[2:] for call in self.calls()]
        self.assertTrue(all(len(" ".join(call)) <= 60 for call in protocol_calls))
        fragments = [call[-1] for call in protocol_calls if call[1] == "kblput" and call[-1].startswith("D")]
        self.assertGreater(len(fragments), 1)
        self.assertTrue(fragments[0].startswith("D0000000100000001:0000:"))
        self.assertEqual(protocol_calls[-1][-1], f"E0000000100000001:{len(line.encode()):04x}")

    def test_maximum_line_round_trips_without_truncation(self):
        # Wire limit 63 decreases the number of independent fixture invocations.
        self.transport.wire_limit = 63
        self.transport.request("kblstart")
        line = "x" * 4096
        self.transport.submit(line)
        self.assertEqual(bytes.fromhex(self.device()["line"]), line.encode())

    def test_invalid_input_is_rejected_before_any_request(self):
        for line in ("", "abc\0def", "abc\nnext", "abc\rnext"):
            with self.subTest(length=len(line)), self.assertRaises(KblError):
                self.transport.submit(line)
        self.assertEqual(self.calls(), [])

    def test_interrupt_poll_and_reattach_same_job(self):
        def interrupt(_):
            raise KeyboardInterrupt
        line = "echo " + "x" * 100
        first, output1, _ = self.console(line, sleeper=interrupt)
        self.assertEqual(first.attach("kblstart"), 130)
        self.assertEqual(self.device()["status"], "busy")
        identity, job = first.session_id, self.device()["job"]
        second, output2, _ = self.console("exit")
        self.assertEqual(second.attach("kblin"), 0)
        self.assertEqual(second.session_id, identity)
        self.assertEqual(output1.getvalue() + output2.getvalue(), "执行：" + line + "\n")
        self.assertTrue(any(call[-2:] == ["kblpoll", job] for call in self.calls()))
        self.assertEqual(self.device()["starts"], 1)
        self.assert_no_off()

    def test_kblin_does_not_implicitly_start(self):
        console, _, _ = self.console()
        with self.assertRaises(TransportError):
            console.attach("kblin")
        self.assert_no_off()
        self.assertFalse(self.state.exists())

    def test_only_explicit_off_destroys(self):
        self.transport.request("kblstart")
        self.transport.request("kbloff")
        self.assertEqual(self.device()["status"], "stopped")
        self.assertEqual(self.device()["stops"], 1)

    def test_stop_alias_destroys_only_after_explicit_request(self):
        self.transport.request("kblstart")
        self.transport.request("kblstop")
        self.assertEqual(self.device()["status"], "stopped")
        self.assertEqual(self.device()["stops"], 1)
        self.assertEqual(1, self.device()["starts"])

    def test_ordinary_arguments_are_passed_without_rewriting(self):
        arguments = ["-s", "USB Serial", "flash", "abl_b", "D:/路径 with spaces/stock.img"]
        self.assertEqual(self.transport.passthrough(arguments), 0)
        self.assertEqual(self.calls(), [arguments])

    def test_native_fetch_preserves_every_byte(self):
        target = self.directory / "abl b.img"
        self.transport.fetch("abl_b", target, **self.fixture_metadata)
        self.assertEqual(target.read_bytes(), bytes(range(256)) * 16 + b"\0\xff")
        self.assertFalse(any("kblstart" in call for call in self.calls()))
        self.assertFalse(list(self.directory.glob("*.partial")))

    def test_failed_fetch_does_not_publish_partial_file(self):
        target = self.directory / "missing.img"
        with self.assertRaises(TransportError):
            self.transport.fetch("unsupported", target, **self.fixture_metadata)
        self.assertFalse(target.exists())
        self.assertFalse(list(self.directory.glob("*.partial")))

    def test_fetch_never_overwrites_existing_file(self):
        target = self.directory / "kept.img"
        target.write_bytes(b"original")
        with self.assertRaises(KblError):
            self.transport.fetch("abl_b", target, **self.fixture_metadata)
        self.assertEqual(target.read_bytes(), b"original")
        self.assertEqual(self.calls(), [])

    def test_unsupported_file_backend_does_not_publish_a_fake_file(self):
        target = self.directory / "test"
        with self.assertRaises(KblError):
            self.transport.fetch_kbl_file("/tmp/test", target)
        self.assertFalse(target.exists())
        self.assertEqual(["-s", "USB Serial", "oem", "kblreadopen", "/tmp/test".encode().hex()], self.calls()[0])

    def test_fetch_requires_complete_integrity_metadata(self):
        target = self.directory / "test.img"
        for meta in ({}, {"expected_size": 0}, {"expected_size": -1, "expected_sha256": "0" * 64}):
            with self.subTest(meta=meta), self.assertRaises(KblError):
                self.transport.fetch("abl_b", target, **meta)
        self.assertEqual(self.calls(), [])

    def test_fetch_rejects_short_or_corrupt_output(self):
        for size, digest in ((8192, self.fixture_metadata["expected_sha256"]), (4098, "0" * 64)):
            with self.subTest(size=size, digest=digest):
                target = self.directory / "rejected.img"
                with self.assertRaises(TransportError):
                    self.transport.fetch("abl_b", target, expected_size=size, expected_sha256=digest)
                self.assertFalse(target.exists())
                self.assertFalse(list(self.directory.glob("*.partial")))

    def test_session_change_stops_polling(self):
        console, _, _ = self.console()
        console.session_id = "1"
        with self.assertRaises(ProtocolError):
            console._check_identity(Reply({"id": "2"}))

    def test_el_change_is_reported(self):
        console, _, _ = self.console()
        console.el = 1
        with self.assertRaises(ProtocolError):
            console._check_identity(Reply({"el": "2"}))

    def test_interrupt_terminates_only_local_native_client(self):
        process = mock.Mock()
        process.communicate.side_effect = [KeyboardInterrupt(), (b"", b"")]
        with mock.patch("kbl_console.subprocess.Popen", return_value=process) as launch:
            with self.assertRaises(KeyboardInterrupt):
                self.transport.request("kblpoll", "0000000000000001")
        process.terminate.assert_called_once_with()
        process.kill.assert_not_called()
        self.assertNotIn("kbloff", launch.call_args.args[0])

    def test_failed_submit_does_not_retry_or_destroy(self):
        console, _, _ = self.console("echo uncertain")
        original = self.transport.submit
        calls = []
        def uncertain(line):
            calls.append(line)
            original(line)  # Simulate device accepting the command before ACK loss.
            raise TransportError("response lost")
        with mock.patch.object(self.transport, "submit", side_effect=uncertain):
            with self.assertRaisesRegex(KblError, "kblin"):
                console.attach("kblstart")
        self.assertEqual(calls, ["echo uncertain"])
        self.assertEqual(self.device()["status"], "busy")
        self.assert_no_off()


if __name__ == "__main__":
    unittest.main()
