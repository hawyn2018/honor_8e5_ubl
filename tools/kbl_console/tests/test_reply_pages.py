import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest

MODULE = Path(__file__).resolve().parents[1] / "kbl_console.py"
spec = importlib.util.spec_from_file_location("kbl_reply_pages_client", MODULE)
client = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = client
spec.loader.exec_module(client)


class Pages(client.NativeFastboot):
    def __init__(self, pages=None, header="KBLC1 g=000000000000002a n=2"):
        super().__init__("unused-fixture")
        self.pages = pages if pages is not None else ["KBL1 status=busy id=0000000000000001 el=1", "KBL1 job=0000000000000007"]
        self.header, self.calls, self.reject_page = header, [], False

    def _capture(self, args):
        self.calls.append(list(args))
        if args[0] == "oem":
            return subprocess.CompletedProcess(args, 0, b"OKAY [0.001s]\n", b"")
        name = args[1]
        if name == "kbl-reply-header":
            value = self.header
        else:
            if self.reject_page:
                return subprocess.CompletedProcess(args, 1, b"FAILED stale generation", b"")
            value = self.pages[int(name.rsplit(":", 1)[1])]
        return subprocess.CompletedProcess(args, 0, b"", f"(bootloader) {name}: {value}\nFinished. Total time: 0.001s\n".encode())


class ReplyPagesTest(unittest.TestCase):
    def test_exact_generation_pages_recover_actual_metadata(self):
        t = Pages()
        reply = t.request("kblin")
        self.assertEqual("busy", reply.fields["status"])
        self.assertEqual("0000000000000007", reply.identifier("job"))
        self.assertEqual(["getvar", "kbl-reply:000000000000002a:1"], t.calls[-1])
        self.assertEqual(1, sum(c[0] == "oem" for c in t.calls))

    def test_stdout_hex_and_done_merge_across_pages(self):
        t = Pages(["KBL1 out=00ff4142", "KBL1 done=1 rc=-12"])
        reply = t.request("kblpoll", "0000000000000007")
        self.assertEqual(b"\0\xffAB", reply.output)
        self.assertEqual(-12, reply.integer("rc"))

    def test_plain_data_fragment_ack_does_not_query(self):
        t = Pages()
        t.request("kblput", "D0000000100000001:0000:61", tag_required=False)
        self.assertEqual(1, len(t.calls))

    def test_stale_page_does_not_retry_oem_or_read_another_generation(self):
        t = Pages(); t.reject_page = True
        with self.assertRaises(client.ProtocolError): t.request("kblin")
        self.assertEqual(1, sum(c[0] == "oem" for c in t.calls))

    def test_bad_headers_and_zero_count_are_rejected(self):
        for header in ["OKAY", "KBLC1 g=2a n=2", "KBLC1 g=000000000000002a n=0"]:
            with self.subTest(header=header), self.assertRaises(client.ProtocolError): Pages(header=header).request("kblstart")

    def test_conflicting_fields_are_not_silently_combined(self):
        with self.assertRaises(client.ProtocolError): Pages(["KBL1 el=1", "KBL1 el=2"]).request("kblstart")

    def test_all_twenty_four_status_pages_are_read(self):
        pages = ["KBL1 status=running id=0000000000000001 el=1"]
        pages += [f"KBL1 diagnostic{i}=ok" for i in range(23)]
        transport = Pages(pages, "KBLC1 g=000000000000002a n=24")
        reply = transport.request("kblstatus")
        self.assertEqual("ok", reply.fields["diagnostic22"])
        self.assertEqual(["getvar", "kbl-reply:000000000000002a:23"], transport.calls[-1])

    def test_reply_pages_above_old_twenty_four_limit(self):
        pages = ["KBL1 status=running id=0000000000000001 el=1"]
        pages += [f"KBL1 diagnostic{i}=ok" for i in range(24)]
        transport = Pages(pages, "KBLC1 g=000000000000002a n=25")
        reply = transport.request("kblstatus")
        self.assertEqual("ok", reply.fields["diagnostic23"])
        self.assertEqual(["getvar", "kbl-reply:000000000000002a:24"], transport.calls[-1])

    def test_non_protocol_and_overlength_pages_are_rejected(self):
        for page in ["all good", "KBL1 out=" + "ab" * 25]:
            with self.subTest(page=page), self.assertRaises(client.ProtocolError): Pages([page], "KBLC1 g=000000000000002a n=1").request("kblin")


if __name__ == "__main__": unittest.main()
