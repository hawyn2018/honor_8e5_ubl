"""Production manual-counter helper with local EFI callbacks and mapped PE fixtures.

Each variable callback asserts the exact private GUID/name and forbids all other
records. No device, actual NVRAM, filesystem or partition is accessed by the helper.
"""
from pathlib import Path
import argparse
import ctypes as C
import hashlib
import json
import struct
import subprocess

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parents[2]
ERROR = 1 << 63
NOT_FOUND, UNSUPPORTED, COMPROMISED = (ERROR | n for n in (14, 3, 33))
NAME = "HnublEfispBootFailures"
GUID = bytes.fromhex("8fefc753a921dc4799dc43fe6a38d1e7")
GET = C.CFUNCTYPE(C.c_uint64, C.POINTER(C.c_uint16), C.c_void_p,
                 C.POINTER(C.c_uint32), C.POINTER(C.c_uint64), C.c_void_p)
SET = C.CFUNCTYPE(C.c_uint64, C.POINTER(C.c_uint16), C.c_void_p,
                 C.c_uint32, C.c_uint64, C.c_void_p)


class Result(C.Structure):
    _fields_ = [(name, C.c_uint64) for name in
                ("get_status", "set_status", "verify_status", "cache_status", "before", "after")] + [
        (name, C.c_uint) for name in
        ("phase", "record_present", "write_attempted", "verified", "cache_found", "cache_synced", "before_known", "after_known")]


def record(count):
    return struct.pack("<4I", 0x48424642, 1, count, 0x48424642 ^ 1 ^ count ^ 0x917AB36D)


class Fixture:
    def __init__(self, *, count=3, recovery=True, config_offset=0x300, relocation=0,
                 malformed=None, absent=False, get_error=0, set_error=0, verify_error=0,
                 no_get=False, no_set=False, no_runtime=False, readback=None):
        self.image = C.create_string_buffer(0x8000 + relocation)
        self.base = C.addressof(self.image)
        self.st = C.create_string_buffer(128)
        self.rt = C.create_string_buffer(128)
        self.st_address = C.addressof(self.st)
        self.rx, self.rw = 0x3000 + relocation, 0x5000 + relocation
        self.cache = self.rw
        self.config = self.rx + config_offset
        self.storage = None if absent else record(count)
        self.events = []
        self.errors = []
        self.get_error, self.set_error = get_error, set_error
        self.verify_error, self.readback = verify_error, readback
        struct.pack_into("<Q", self.st, 0, 0x5453595320494249)
        struct.pack_into("<Q", self.st, 0x58, 0 if no_runtime else C.addressof(self.rt))
        self.build_image(recovery)
        self.patch(malformed)
        self.initial_image = self.image.raw

        @GET
        def get(name, guid, attrs, size, out):
            self.check(name, guid)
            verifying = any(e[0] == "get" for e in self.events)
            self.events.append(("get", "verify" if verifying else "initial"))
            status = self.verify_error if verifying else self.get_error
            if status:
                return status
            data = self.storage
            if verifying and self.readback == "absent":
                data = None
            if verifying and self.readback == "nonzero":
                data = record(7)
            if verifying and self.readback == "bad-record":
                data = bytes(16)
            if data is None:
                return NOT_FOUND
            attrs[0] = 3 if malformed == "attrs" or (verifying and self.readback == "attrs") else 7
            size[0] = 15 if malformed == "record-size" else 16
            C.memmove(out, data, min(16, len(data)))
            return 0

        @SET
        def set_(name, guid, attrs, size, data):
            self.check(name, guid)
            raw = C.string_at(data, size)
            count = struct.unpack("<4I", raw)[2] if size == 16 else 0
            if attrs != 7 or size != 16 or raw != record(count):
                self.errors.append("SetVariable changed private record ABI")
            self.events.append(("set", attrs, size, count))
            if not self.set_error and self.readback != "unchanged":
                self.storage = raw
            return self.set_error

        self.get, self.set = get, set_
        struct.pack_into("<Q", self.rt, 0x48, 0 if no_get else C.cast(get, C.c_void_p).value)
        struct.pack_into("<Q", self.rt, 0x58, 0 if no_set else C.cast(set_, C.c_void_p).value)

    def check(self, name, guid):
        expected = (NAME + "\0").encode("utf-16le")
        if C.string_at(name, len(expected)) != expected or C.string_at(guid, 16) != GUID:
            self.errors.append("variable outside the exact private record")

    def word(self, at, value):
        struct.pack_into("<I", self.image, at, value)

    def qword(self, at, value):
        struct.pack_into("<Q", self.image, at, value)

    def build_image(self, recovery):
        self.image[0:2] = b"MZ"
        self.word(0x3c, 0x80)
        self.image[0x80:0x84] = b"PE\0\0"
        struct.pack_into("<HH", self.image, 0x84, 0xaa64, 3 if recovery else 1)
        struct.pack_into("<H", self.image, 0x94, 240)
        struct.pack_into("<H", self.image, 0x98, 0x20b)
        self.word(0x98 + 56, len(self.image))
        self.word(0x98 + 60, 0x1000)
        for index, (name, rva, flags) in enumerate([
            (b".text\0\0\0", 0x1000, 0x60000020),
            (b".textfr\0", self.rx, 0x60000020),
            (b".datafr\0", self.rw, 0xc0000040),
        ][:3 if recovery else 1]):
            at = 0x188 + index * 40
            self.image[at:at + 8] = name
            self.word(at+8, 0x1000)
            self.word(at+12, rva)
            self.word(at+16, 0x1000)
            self.word(at+36, flags)
        if not recovery:
            return
        config = [self.rx, self.rw, 0x1100, 0x2000, 0x2001, 0x1200, 0x1300,
                  0x2008, 3, 100000, 4096, 1, 8, 0x1400, 0x2010, 0x1500] + [0] * 16
        struct.pack_into("<32Q", self.image, self.config, *config)
        # Populate every state word so the exact preservation mask is checked.
        struct.pack_into("<45Q", self.image, self.cache, *[0x77000000 + i for i in range(45)])
        self.qword(self.cache, 0x3742524642484e48)
        self.qword(self.cache+8, self.base)
        self.qword(self.cache+24, self.st_address)
        self.qword(self.cache+280, 1)
        self.qword(self.cache+48, 1)
        self.qword(self.cache+56, 9)
        self.qword(self.cache+88, 0)
        self.qword(self.cache+320, 0)

    def patch(self, bad):
        if bad == "mz":
            self.image[0:2] = b"XX"
        elif bad == "pe-offset":
            self.word(0x3c, 0xfffffff0)
        elif bad == "machine":
            struct.pack_into("<H", self.image, 0x84, 0x8664)
        elif bad == "optional-size":
            struct.pack_into("<H", self.image, 0x94, 32)
        elif bad == "image-size":
            self.word(0x98+56, len(self.image)+1)
        elif bad == "header-size":
            self.word(0x98+60, 0x100)
        elif bad == "section-range":
            self.word(0x188+40+12, len(self.image)-1)
        elif bad == "section-alias":
            self.word(0x188+12, self.rw)
        elif bad == "missing-rw":
            self.image[0x188+80:0x188+88] = b".dataxx\0"
        elif bad == "duplicate-rx":
            self.image[0x188:0x190] = b".textfr\0"
        elif bad == "rw-executable":
            self.word(0x188+80+36, 0xe0000040)
        elif bad == "config-version":
            self.qword(self.config+88, 99)
        elif bad == "config-missing":
            self.qword(self.config, 0)
        elif bad == "config-state-size":
            self.qword(self.config+80, 8192)
        elif bad == "config-reserved":
            self.qword(self.config+128, 1)
        elif bad == "config-rva-overflow":
            self.qword(self.config+40, 0xffffffffffffffff)
        elif bad == "duplicate-config":
            self.image[self.config+256:self.config+512] = self.image.raw[self.config:self.config+256]
        elif bad == "state-magic":
            self.qword(self.cache, 0)
        elif bad == "state-base":
            self.qword(self.cache+8, self.base+4096)
        elif bad == "state-table":
            self.qword(self.cache+24, self.st_address+8)
        elif bad == "state-uninitialized":
            self.qword(self.cache+280, 0)
        elif bad in ("record-magic", "record-version", "record-checksum"):
            data = bytearray(self.storage)
            data[{"record-magic": 0, "record-version": 4, "record-checksum": 12}[bad]] ^= 1
            self.storage = bytes(data)

    def invoke(self, lib):
        self.result = Result()
        self.rc = lib.kbl_errorcount_clear(self.st, self.image, len(self.image), C.byref(self.result))
        assert not self.errors, self.errors

    def load_parent(self, path):
        """Use actual emitted PE headers/config; model only its live BRState."""
        import pefile
        pe = pefile.PE(str(path))
        mapped = pe.get_memory_mapped_image()
        self.image = C.create_string_buffer(pe.OPTIONAL_HEADER.SizeOfImage)
        assert len(mapped) <= len(self.image)
        C.memmove(self.image, mapped, len(mapped))
        self.base = C.addressof(self.image)
        rw = [s for s in pe.sections if s.Name.rstrip(b"\0") == b".datafr"]
        assert len(rw) == 1, path
        self.cache = rw[0].VirtualAddress
        struct.pack_into("<45Q", self.image, self.cache, *[0x77000000 + i for i in range(45)])
        for offset, value in ((0, 0x3742524642484e48), (8, self.base), (24, self.st_address),
                              (48, 1), (56, 9), (88, 0), (280, 1), (320, 0)):
            self.qword(self.cache+offset, value)
        self.initial_image = self.image.raw

    def preserved(self, success):
        expected = bytearray(self.initial_image)
        if success:
            for offset in (48, 56, 64, 72, 80, 272, 296, 304, 328):
                struct.pack_into("<Q", expected, self.cache+offset, 0)
        assert self.image.raw == bytes(expected), "Unexpected modification to mapped parent or saved native frame"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=SOURCE / "build/errorcount-test")
    parser.add_argument("--image", type=Path, action="append", default=[],
                        help="also verify an actual emitted parent PE mapping (repeatable)")
    parser.add_argument("--fixtures", type=Path,
                        help="also verify the four current FastbootRecoveryIntegrationTest outputs")
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    source = HERE.parent / "kbl_errorcount.c"
    dll = out / "errorcount.dll"
    subprocess.run(["C:/Program Files/LLVM/bin/clang.exe", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-shared", "-DKBL_ERRORCOUNT_TEST", str(source), str(HERE.parent / "boot_recovery.c"),
                    str(HERE / "errorcount_recovery_fixture.c"), "-o", str(dll)], check=True)
    lib = C.CDLL(str(dll))
    lib.kbl_errorcount_clear.argtypes = [C.c_void_p, C.c_void_p, C.c_size_t, C.POINTER(Result)]
    lib.kbl_errorcount_clear.restype = C.c_uint64
    lib.errorcount_test_note_failure.argtypes = [C.c_void_p, C.c_void_p]
    cases = []

    def check(name, options, expected=0, writes=0, cache=True, phase=6):
        f = Fixture(**options)
        f.invoke(lib)
        assert f.rc == expected, (name, hex(f.rc), hex(expected))
        assert f.result.phase == phase, (name, f.result.phase)
        assert sum(e[0] == "set" for e in f.events) == writes, (name, f.events)
        assert bool(f.result.verified) == (expected == 0), name
        assert bool(f.result.cache_synced) == (expected == 0 and cache), name
        f.preserved(expected == 0 and cache)
        if expected == 0:
            assert sum(e[0] == "get" for e in f.events) == 2
            assert f.result.before_known and f.result.after_known and f.result.after == 0
        if phase <= 3 and expected:
            assert not f.result.before_known and not f.result.after_known
        if options.get("verify_error") or options.get("readback") in ("bad-record", "attrs"):
            assert f.result.before_known and not f.result.after_known
        if options.get("readback") in ("unchanged", "nonzero"):
            assert f.result.after_known
        cases.append({"name": name, "passed": True, "status": hex(f.rc),
                      "phase": f.result.phase, "events": f.events,
                      "cacheSynced": bool(f.result.cache_synced)})
        return f

    check("private-three-clears-verified-nv-and-current-cache", {}, writes=1)
    check("max-private-count", {"count": 0xffffffff}, writes=1)
    check("already-zero-reconfirmed-syncs-stale-ram", {"count": 0})
    check("absent-reconfirmed-syncs-stale-ram", {"absent": True})
    check("no-recovery-sections-clears-only-private-nv", {"recovery": False}, writes=1, cache=False)
    check("different-section-rvas-and-config-offset", {"relocation": 0x2000, "config_offset": 0x650}, writes=1)
    check("write-success-record-absent-on-readback", {"readback": "absent"}, writes=1)
    for bad in ("mz", "pe-offset", "machine", "optional-size", "image-size", "header-size", "section-range",
                "section-alias", "missing-rw", "duplicate-rx", "rw-executable", "config-version", "config-missing",
                "config-state-size", "config-reserved", "config-rva-overflow", "duplicate-config", "state-magic",
                "state-base", "state-table", "state-uninitialized"):
        f = check("unknown-parent-"+bad, {"malformed": bad}, COMPROMISED, phase=1)
        assert not f.events
    for bad in ("record-magic", "record-version", "record-checksum", "record-size", "attrs"):
        check("unknown-variable-"+bad, {"malformed": bad}, COMPROMISED, phase=3)
    check("unknown-zero-attributes-also-errors", {"count": 0, "malformed": "attrs"}, COMPROMISED, phase=3)
    check("getvariable-error-retained", {"get_error": ERROR | 7}, ERROR | 7, phase=2)
    check("setvariable-denied-retained", {"set_error": ERROR | 15}, ERROR | 15, writes=1, phase=4)
    check("readback-error-retained", {"verify_error": ERROR | 7}, ERROR | 7, writes=1, phase=5)
    for readback in ("unchanged", "nonzero", "bad-record", "attrs"):
        check("readback-"+readback, {"readback": readback}, COMPROMISED, writes=1, phase=5)
    check("absent-then-nonzero-race", {"absent": True, "readback": "nonzero"}, COMPROMISED, phase=5)
    check("missing-getvariable", {"no_get": True}, UNSUPPORTED, phase=0)
    check("missing-runtime", {"no_runtime": True}, UNSUPPORTED, phase=0)
    check("missing-setvariable-for-nonzero", {"no_set": True}, UNSUPPORTED, phase=4)
    check("missing-setvariable-for-zero-still-reconfirms", {"no_set": True, "count": 0})
    # A second explicit clear is idempotent and preserves the diagnostic tail.
    f = check("first-idempotent-clear", {}, writes=1)
    before = f.image.raw
    f.events.clear()
    f.invoke(lib)
    assert not f.rc and f.image.raw == before and not any(e[0] == "set" for e in f.events)
    cases.append({"name": "second-explicit-clear-no-write", "passed": True, "events": f.events})
    f.events.clear()
    f.qword(f.cache+64, ERROR | 7)
    f.qword(f.cache+72, 5)
    lib.errorcount_test_note_failure(f.base+f.cache, f.base+f.config)
    assert struct.unpack_from("<Q", f.image, f.cache+56)[0] == 1
    assert struct.unpack_from("<Q", f.image, f.cache+48)[0] == 1
    assert struct.unpack_from("<Q", f.image, f.cache+328)[0] == 1
    assert f.storage == record(1) and f.events == [("set", 7, 16, 1)]
    cases.append({"name": "real-failure-after-clear-starts-at-one-and-persists", "passed": True, "events": f.events})
    assert lib.kbl_errorcount_clear(None, None, 0, None) == ERROR | 2
    cases.append({"name": "null-result-invalid", "passed": True})
    images = list(args.image)
    if args.fixtures:
        fixtures = sorted(args.fixtures.glob("*.json"))
        assert len(fixtures) == 4, fixtures
        for metadata in fixtures:
            row = json.loads(metadata.read_text(encoding="utf-8-sig"))
            path = Path(row["imagePath"])
            assert hashlib.sha256(path.read_bytes()).hexdigest() == row["outputSha256"]
            images.append(path)
    for path in images:
        f = Fixture()
        f.load_parent(path)
        f.invoke(lib)
        assert not f.rc and f.result.verified and f.result.cache_synced, (path, hex(f.rc), f.result.phase)
        assert f.storage == record(0) and f.result.before == 3 and f.result.after == 0
        assert f.events == [("get", "initial"), ("set", 7, 16, 0), ("get", "verify")]
        f.preserved(True)
        cases.append({"name": "actual-emitted-parent-" + path.name, "passed": True,
                      "path": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                      "stateRva": hex(f.cache), "cacheSynced": True, "events": f.events})
    report = {"passed": True, "caseCount": len(cases), "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
              "sourcesSha256": {str(p.relative_to(SOURCE)): hashlib.sha256(p.read_bytes()).hexdigest()
                                for p in (source, HERE.parent / "kbl_errorcount.h", HERE.parent / "boot_recovery.h",
                                          HERE.parent / "boot_recovery.c", HERE / "errorcount_recovery_fixture.c", Path(__file__))},
              "scope": __doc__, "deviceOperations": False, "cases": cases}
    (out / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": True, "cases": len(cases), "report": str(out / "result.json")}))


if __name__ == "__main__":
    main()
