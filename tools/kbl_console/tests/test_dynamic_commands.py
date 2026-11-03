import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kbl_console import NativeFastboot, ProtocolError, Reply, Console


class Upload(NativeFastboot):
    def __init__(self, dynamic=True):
        super().__init__("unused-local-fixture")
        self.dynamic = dynamic
        self.data = bytearray()
        self.frames = []
        self.committed = False

    def request(self, command, argument="", **kwargs):
        wire = "oem " + command + (" " + argument if argument else "")
        assert len(wire.encode("ascii")) <= self.wire_limit
        self.frames.append(wire)
        if argument == "B":
            return Reply({"put": "0000000000000001", **({"command64": "1"} if self.dynamic else {})})
        operation, body = argument[0], argument[1:]
        identity, offset, *payload = body.split(":")
        assert identity == "0000000000000001"
        assert int(offset, 16) == len(self.data)
        if operation == "D":
            self.data.extend(bytes.fromhex(payload[0]))
            return Reply()
        assert operation == "E" and not payload
        self.committed = True
        return Reply({"job": "1"})


class DynamicCommands(unittest.TestCase):
    def test_commands_above_old_buffer_and_four_digit_offset(self):
        for length in (4097, 65537):
            with self.subTest(length=length):
                transport = Upload()
                line = "x" * length
                transport.submit(line)
                self.assertEqual(line.encode(), transport.data)
                self.assertTrue(transport.committed)
                self.assertTrue(all(len(frame) <= 60 for frame in transport.frames))
                self.assertEqual(f"E0000000000000001:{length:04x}", transport.frames[-1].split()[-1])

    def test_capture_has_no_default_total_output_threshold(self):
        class Capture:
            def submit(self, line):return Reply({"job": "0000000000000001"})
            def request(self, *args):return Reply({"done": "1", "rc": "0"}, output=b"x" * 16385)
        console = Console(Capture(), sleeper=lambda _: None)
        self.assertEqual(b"x" * 16385, console._capture_command("cat /sample"))
        with self.assertRaises(ProtocolError):console._capture_command("cat /sample", limit=16384)

    def test_old_firmware_is_not_sent_an_oversized_command(self):
        transport = Upload(dynamic=False)
        with self.assertRaises(ProtocolError):
            transport.submit("x" * 4097)
        self.assertEqual(["oem kblput B"], transport.frames)
        self.assertFalse(transport.data or transport.committed)


if __name__ == "__main__":
    unittest.main()
