"""Offline instruction tests of the compiled independent recovery module.

The module bytes execute in Unicorn. Original ABL entry, BootLinux, manual
set_active and EFI GetVariable/SetVariable/Stall/Watchdog are explicit models.
No phone, real NVRAM, partitions, firmware dispatcher, TPL or kernel executes.
"""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import traceback
import uuid

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn import arm64_const as a


SOURCE = Path(__file__).resolve().parents[2]
WORKSPACE = SOURCE.parents[2]
X = [getattr(a, f"UC_ARM64_REG_X{i}") for i in range(31)]
D = [getattr(a, f"UC_ARM64_REG_D{i}") for i in range(8, 16)]
ERROR = 1 << 63
NOT_FOUND = ERROR | 14
UNSUPPORTED = ERROR | 3
NAME = "HnublEfispBootFailures"
GUID = uuid.UUID("53c7ef8f-21a9-47dc-99dc-43fe6a38d1e7").bytes_le
RECORD_MAGIC = 0x48424642
STATE = {"base": 8, "handle": 16, "table": 24, "sp": 32, "nzcv": 40,
         "force": 48, "failures": 56, "status": 64, "reason": 72, "site": 80,
         "manual_depth": 88, "nv_status": 272, "initialized": 280,
         "watchdog_status": 288, "retry_count": 296, "pending": 304, "initial_canary": 312,
         "boot_scope": 320, "nv_requested_count": 328,
         "manual_bootlinux_status": 336, "manual_bootlinux_count": 344,
         "manual_bootlinux_site": 352}


def q(value):
    return struct.pack("<Q", value)


def record(count):
    values = (RECORD_MAGIC, 1, count, RECORD_MAGIC ^ 1 ^ count ^ 0x917AB36D)
    return struct.pack("<4I", *values)


class Harness:
    def __init__(self, binary, manifest, *, image_base=0x10000000, wide_rva=False,
                 nv_count=None, get_status=0, set_status=0, get_present=True,
                 set_present=True, watchdog_present=True, stall_present=True,
                 runtime_present=True, boot_services_present=True, bad_record=None, canary_bytes=8):
        self.binary, self.manifest = binary, manifest
        self.offsets = manifest["offsets"]
        self.base = image_base
        extra = 0x100000000 if wide_rva else 0
        self.module_rva = extra + 0x100000
        self.state_rva = extra + 0x200000
        self.flags_rva = extra + 0x210000
        self.bootlinux_rva = extra + 0x220000
        self.manual_rva = extra + 0x220100
        self.auth_rva = extra + 0x220200
        self.cleanup_rva = extra + 0x220300
        self.module = self.base + self.module_rva
        self.state = self.base + self.state_rva
        self.flags = self.base + self.flags_rva
        self.table = self.base + 0x300000
        self.bs = self.table + 0x1000
        self.rt = self.table + 0x2000
        self.external = self.table + 0x3000
        self.parent_stop = self.external + 0x1000
        self.after = self.parent_stop + 0x100
        self.stack = 0x60000000
        self.root_sp = self.stack + 0x18000
        self.handle = self.table + 0x5000
        self.initial_canary = 0x19376A4D9F20C15B
        self.event_slot = self.flags + 48
        self.event_active = False
        self.initial_gpr = [0x4100000000000000 + i * 0x101 for i in range(31)]
        self.initial_gpr[0] = self.handle
        self.initial_gpr[1] = self.table
        self.initial_gpr[30] = self.parent_stop
        self.initial_fp = [0x3FF0000000000000 + i * 0x101 for i in range(8)]
        self.nv_count = nv_count
        self.get_status, self.set_status = get_status, set_status
        self.bad_record = bad_record
        self.events = []
        self.original_visits = []
        self.trace = []
        self.ended = None
        self.original_action = lambda harness, count: harness.stop("original-entry-nonreturn")
        self.after_action = None
        self.bootlinux_action = None
        self.manual_action = None
        self.auth_action = None
        self.u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.u.reg_write(a.UC_ARM64_REG_CPACR_EL1, 3 << 20)
        for address, size in [(self.module, (len(binary) + 4095) & ~4095),
                              (self.state, 4096), (self.flags, 4096),
                              (self.base + self.bootlinux_rva, 4096),
                              (self.table, 0x6000), (self.stack, 0x20000)]:
            self.u.mem_map(address, size)
        self.u.mem_write(self.module, binary)
        self.u.mem_write(self.flags, b"\0")
        self.u.mem_write(self.flags + 16, b"\1")
        self.u.mem_write(self.flags + 32, q(self.initial_canary))
        config = [self.module_rva + self.offsets["ENTRY"], self.state_rva,
                  self.module_rva + self.offsets["ORIGINAL_ENTRY"], self.flags_rva,
                  self.flags_rva + 16, self.bootlinux_rva, self.manual_rva,
                  self.flags_rva + 32, 3, 500000, 4096, 1, canary_bytes, self.auth_rva] + [0] * 18
        self.u.mem_write(self.module + self.offsets["CONFIG"], struct.pack("<32Q", *config))
        self.write_q(self.table + 0x58, self.rt if runtime_present else 0)
        self.write_q(self.table + 0x60, self.bs if boot_services_present else 0)
        for name, offset, present in [("get", 0x48, get_present), ("set", 0x58, set_present)]:
            self.write_q(self.rt + offset, self.external + (0 if name == "get" else 4) if present else 0)
        self.write_q(self.bs + 0xF8, self.external + 8 if stall_present else 0)
        self.write_q(self.bs + 0x100, self.external + 12 if watchdog_present else 0)
        for reg, value in zip(X, self.initial_gpr):
            self.u.reg_write(reg, value)
        for reg, value in zip(D, self.initial_fp):
            self.u.reg_write(reg, value)
        self.u.reg_write(a.UC_ARM64_REG_SP, self.root_sp)
        self.u.reg_write(a.UC_ARM64_REG_NZCV, 0xA0000000)
        self.u.hook_add(UC_HOOK_CODE, self.hook)

    def read_q(self, address):
        return struct.unpack("<Q", self.u.mem_read(address, 8))[0]

    def write_q(self, address, value):
        self.u.mem_write(address, q(value))

    def state_value(self, name):
        return self.read_q(self.state + STATE[name])

    def counts(self, kind):
        return [event for event in self.events if event["kind"] == kind]

    def stop(self, why):
        self.ended = why
        self.u.emu_stop()

    def go(self, name, args=None, *, link=None):
        if args is not None:
            for reg, value in zip(X, args):
                self.u.reg_write(reg, value)
        if link is not None:
            self.u.reg_write(X[30], link)
        self.u.reg_write(a.UC_ARM64_REG_PC, self.module + self.offsets[name])

    def native_return(self, status, secondary=None):
        destination = self.u.reg_read(X[30])
        for index in range(1, 18):
            self.u.reg_write(X[index], 0x7300000000000000 + index)
        self.u.reg_write(X[0], status)
        if secondary is not None:
            self.u.reg_write(X[1], secondary)
        self.u.reg_write(a.UC_ARM64_REG_NZCV, 0x50000000)
        self.u.reg_write(a.UC_ARM64_REG_PC, destination)

    def loader_return(self, status):
        destination = self.u.reg_read(X[30])
        self.u.reg_write(X[0], status)
        self.u.reg_write(a.UC_ARM64_REG_PC, destination)

    def snapshot(self):
        return {"sp": self.u.reg_read(a.UC_ARM64_REG_SP),
                "args": [self.u.reg_read(X[index]) for index in range(2)],
                "callee": [self.u.reg_read(X[index]) for index in range(18, 30)],
                "fp": [self.u.reg_read(reg) for reg in D],
                "nzcv": self.u.reg_read(a.UC_ARM64_REG_NZCV),
                "force": self.state_value("force"), "failures": self.state_value("failures"),
                "boot_scope": self.state_value("boot_scope"),
                "request": self.u.mem_read(self.flags, 1)[0],
                "continue": self.u.mem_read(self.flags + 16, 1)[0],
                "canary": self.read_q(self.flags + 32)}

    def assert_root_context(self, visit):
        assert visit["sp"] == self.root_sp, visit
        assert visit["args"] == [self.handle, self.table], visit
        assert visit["callee"] == self.initial_gpr[18:30], visit
        assert visit["fp"] == self.initial_fp, visit
        assert visit["nzcv"] == 0xA0000000, visit
        assert visit["boot_scope"] == 1, visit

    def assert_variable(self, name_pointer, guid_pointer):
        text = bytearray()
        for offset in range(0, 128, 2):
            code = bytes(self.u.mem_read(name_pointer + offset, 2))
            if code == b"\0\0":
                break
            text.extend(code)
        assert text.decode("utf-16le") == NAME
        assert bytes(self.u.mem_read(guid_pointer, 16)) == GUID

    def hook(self, cpu, pc, size, opaque):
        self.trace.append(pc)
        self.trace = self.trace[-48:]
        if pc == self.parent_stop:
            raise AssertionError("Recovery module returned to its parent")
        if pc == self.base + self.cleanup_rva:
            assert self.read_q(self.event_slot) != 0
            self.events.append({"kind": "NativeEventCleanup", "sp": cpu.reg_read(a.UC_ARM64_REG_SP),
                                "failuresBefore": self.state_value("failures")})
            self.write_q(self.event_slot, 0)
            self.native_return(0)
            return
        if pc == self.module + self.offsets["ORIGINAL_ENTRY"]:
            visit = self.snapshot()
            self.original_visits.append(visit)
            self.assert_root_context(visit)
            self.original_action(self, len(self.original_visits))
            return
        if pc == self.after:
            assert self.after_action is not None
            self.after_action(self)
            return
        if pc == self.base + self.bootlinux_rva:
            self.events.append({"kind": "BootLinux", "args": [cpu.reg_read(reg) for reg in X[:8]],
                                "sp": cpu.reg_read(a.UC_ARM64_REG_SP)})
            assert self.bootlinux_action is not None
            self.bootlinux_action(self)
            return
        if pc == self.base + self.manual_rva:
            self.events.append({"kind": "manualSetActive", "args": [cpu.reg_read(reg) for reg in X[:8]],
                                "depth": self.state_value("manual_depth"), "sp": cpu.reg_read(a.UC_ARM64_REG_SP)})
            assert self.manual_action is not None
            self.manual_action(self)
            return
        if pc == self.base + self.auth_rva:
            self.events.append({"kind": "nativeAuth", "args": [cpu.reg_read(reg) for reg in X[:8]],
                                "bootScope": self.state_value("boot_scope"), "sp": cpu.reg_read(a.UC_ARM64_REG_SP)})
            assert self.auth_action is not None
            self.auth_action(self)
            return
        if pc not in [self.external + offset for offset in (0, 4, 8, 12)]:
            return
        args = [cpu.reg_read(reg) for reg in X[:5]]
        if pc == self.external:
            self.assert_variable(args[0], args[1])
            assert args[2] == 0 and self.read_q(args[3]) == 16
            status = self.get_status
            if not status:
                if self.nv_count is None:
                    status = NOT_FOUND
                else:
                    data = bytearray(record(self.nv_count))
                    if self.bad_record == "checksum":
                        data[-1] ^= 0x80
                    elif self.bad_record == "magic":
                        data[0] ^= 0x80
                    elif self.bad_record == "version":
                        data[4:8] = struct.pack("<I", 2)
                    cpu.mem_write(args[4], bytes(data))
                    self.write_q(args[3], 8 if self.bad_record == "size" else 16)
            self.events.append({"kind": "GetVariable", "status": hex(status), "name": NAME})
            self.native_return(status)
        elif pc == self.external + 4:
            self.assert_variable(args[0], args[1])
            assert args[2] == 7 and args[3] == 16
            data = bytes(cpu.mem_read(args[4], 16))
            magic, version, count, checksum = struct.unpack("<4I", data)
            assert magic == RECORD_MAGIC and version == 1
            assert checksum == RECORD_MAGIC ^ 1 ^ count ^ 0x917AB36D
            self.events.append({"kind": "SetVariable", "count": count, "attributes": 7,
                                "status": hex(self.set_status), "name": NAME})
            if not self.set_status:
                self.nv_count = count
            self.native_return(self.set_status)
        elif pc == self.external + 8:
            assert args[0] == 500000
            self.events.append({"kind": "Stall", "microseconds": args[0]})
            self.native_return(0)
        else:
            assert args[:4] == [0, 0x10000, 0, 0]
            self.events.append({"kind": "Watchdog", "timeout": 0, "code": hex(args[1])})
            self.native_return(0)

    def run(self):
        self.u.emu_start(self.module + self.offsets["ENTRY"], 0, count=1000000)
        assert self.ended is not None, "No controlled modeled boundary reached"
        assert self.state_value("base") == self.base
        assert self.state_value("initialized") == 1
        return {"ended": self.ended, "imageBase": hex(self.base), "moduleRva": hex(self.module_rva),
                "originalVisits": len(self.original_visits), "originalSp": [hex(v["sp"]) for v in self.original_visits],
                "forces": [v["force"] for v in self.original_visits],
                "state": {key: hex(self.state_value(key)) for key in STATE}, "events": self.events}


def healthy(h):
    h.run()
    assert h.original_visits[0]["force"] == 0
    assert h.original_visits[0]["request"] == 0 and h.original_visits[0]["continue"] == 1
    assert not h.counts("SetVariable") and not h.counts("Stall") and not h.counts("Watchdog")


def entry_return(h, status, returns=1):
    def original(m, count):
        if count <= returns:
            m.u.mem_write(m.flags + 16, b"\1")
            m.loader_return(status)
        else:
            assert m.original_visits[-1]["force"] == 1
            assert m.original_visits[-1]["request"] == 1 and m.original_visits[-1]["continue"] == 0
            m.stop("forced-original-entry-nonreturn")
    h.original_action = original
    h.run()
    expected = returns if status else 0
    assert h.state_value("failures") == expected
    assert [event["count"] for event in h.counts("SetVariable")] == list(range(1, min(expected, 3) + 1))
    assert len(h.counts("Watchdog")) == returns
    assert len(h.counts("Stall")) == max(0, returns - 1)
    assert h.state_value("reason") == 1


def deep_request(h):
    def original(m, count):
        if count == 1:
            for index in range(18, 30):
                m.u.reg_write(X[index], 0xDD00000000000000 + index)
            for index, reg in enumerate(D):
                m.u.reg_write(reg, 0xCC00000000000000 + index)
            m.u.reg_write(a.UC_ARM64_REG_SP, m.root_sp - 0x5000)
            m.u.reg_write(a.UC_ARM64_REG_NZCV, 0xF0000000)
            m.u.mem_write(m.flags + 16, b"\1")
            m.write_q(m.flags + 32, 0xDEADBEEF01234567)
            m.go("REQUEST", [2, ERROR | 7, m.base + 0x12340])
        else:
            assert m.original_visits[-1]["canary"] == m.initial_canary
            assert m.original_visits[-1]["request"] == 1 and m.original_visits[-1]["continue"] == 0
            assert m.state_value("manual_depth") == 0
            m.stop("deep-frame-restored")
    h.original_action = original
    h.run()
    assert h.state_value("reason") == 2 and h.state_value("failures") == 1
    assert h.state_value("status") == ERROR | 7


def deferred(h):
    deep_sp = h.root_sp - 0x1800
    def original(m, count):
        if count == 1:
            m.u.reg_write(a.UC_ARM64_REG_SP, deep_sp)
            m.go("DEFER", [3, ERROR | 1, m.base + 0x23450], link=m.after)
        else:
            assert m.state_value("pending") == 0 and m.state_value("failures") == 1
            m.stop("synchronous-pending-recovery")
    def after(m):
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == deep_sp
        assert m.state_value("pending") == 1 and m.state_value("force") == 1
        assert m.state_value("failures") == 0
        assert not m.counts("SetVariable") and not m.counts("Watchdog") and not m.counts("Stall")
        assert not m.counts("NativeEventCleanup")
        if m.event_active:
            assert m.read_q(m.event_slot) != 0
        assert bytes(m.u.mem_read(m.flags, 1)) == b"\0"
        assert bytes(m.u.mem_read(m.flags + 16, 1)) == b"\1"
        m.go("PENDING", link=m.parent_stop)
    h.original_action, h.after_action = original, after
    h.run()
    assert h.state_value("reason") == 3 and len(h.counts("SetVariable")) == 1


def pending_empty(h):
    nested = h.root_sp - 0x1000
    def original(m, count):
        m.u.reg_write(a.UC_ARM64_REG_SP, nested)
        m.go("PENDING", link=m.after)
    def after(m):
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == nested
        assert m.state_value("force") == 0 and m.state_value("failures") == 0
        assert not m.counts("SetVariable") and not m.counts("Watchdog")
        m.stop("pending-empty-normal-return")
    h.original_action, h.after_action = original, after
    h.run()


def mark_only(h):
    nested = h.root_sp - 0x1000
    def original(m, count):
        m.u.reg_write(a.UC_ARM64_REG_SP, nested)
        m.go("MARK_ONLY", [6, NOT_FOUND, m.base + 0x34560], link=m.after)
    def after(m):
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == nested
        assert m.state_value("force") == 1 and m.state_value("failures") == 1
        assert not m.counts("Watchdog") and not m.counts("Stall")
        assert bytes(m.u.mem_read(m.flags, 1)) == b"\0"
        m.stop("mark-only-native-caller-continues")
    h.original_action, h.after_action = original, after
    h.run()
    assert len(h.counts("SetVariable")) == 1 and h.state_value("reason") == 6


def manual(h, status):
    arguments = [0xA100000000000000 + index for index in range(8)]
    nested = h.root_sp - 0x800
    secondary = 0x51EA000000000001
    def original(m, count):
        m.u.reg_write(a.UC_ARM64_REG_SP, nested)
        m.go("SET_ACTIVE", arguments, link=m.after)
    def native(m):
        event = m.counts("manualSetActive")[-1]
        assert event["args"] == arguments and event["depth"] == 1
        assert event["sp"] == nested - 96
        m.native_return(status, secondary)
    def after(m):
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == nested
        assert m.u.reg_read(X[0]) == status and m.u.reg_read(X[1]) == secondary
        assert m.state_value("manual_depth") == 0 and m.state_value("force") == 0
        assert not m.counts("SetVariable") and not m.counts("Watchdog")
        m.stop("manual-wrapper-return")
    h.original_action, h.manual_action, h.after_action = original, native, after
    h.run()


def bootlinux(h, returned):
    arguments = [0xA200000000000000 + index for index in range(8)]
    nested = h.root_sp - 0x2000
    def original(m, count):
        if count == 1:
            m.u.reg_write(a.UC_ARM64_REG_SP, nested)
            m.go("BOOTLINUX", arguments, link=m.parent_stop)
        else:
            assert returned
            assert m.state_value("failures") == 3 and m.state_value("reason") == 5
            assert m.original_visits[-1]["continue"] == 0 and m.original_visits[-1]["request"] == 1
            m.stop("returned-bootlinux-forced-fastboot-entry")
    def native(m):
        event = m.counts("BootLinux")[-1]
        assert event["args"] == arguments and event["sp"] == nested - 128
        assert [event["count"] for event in m.counts("SetVariable")] == [0]
        assert m.state_value("failures") == 2
        if returned:
            m.native_return(ERROR | 7)
        else:
            m.stop("modeled-kernel-handoff-nonreturn")
    h.original_action, h.bootlinux_action = original, native
    h.run()
    assert [event["count"] for event in h.counts("SetVariable")] == ([0, 3] if returned else [0])


def initial_threshold(h):
    h.run()
    visit = h.original_visits[0]
    assert visit["force"] == 1 and visit["failures"] >= 3
    assert visit["request"] == 1 and visit["continue"] == 0
    assert len(h.counts("Watchdog")) == 1 and not h.counts("Stall") and not h.counts("SetVariable")


def manual_bootlinux_return(h, status):
    """Real module instructions retain the native FASTBOOT -> AUTH -> handoff ABI."""
    arguments = [0xA700000000000000 + index for index in range(8)]
    secondary = 0x7A77000000000001
    nested = h.root_sp - 0x2400
    initial_count = h.nv_count or 0
    initial_force = int(initial_count >= 3)
    stage = 0
    def original(m, count):
        assert count == 1, "Manual BootLinux return restarted native main"
        m.u.reg_write(a.UC_ARM64_REG_SP, nested)
        m.go("FASTBOOT_ENTER", link=m.after)
    def auth_native(m):
        assert m.state_value("boot_scope") == 1
        assert m.counts("nativeAuth")[-1]["args"] == arguments
        m.native_return(0, secondary)
    def boot_native(m):
        event = m.counts("BootLinux")[-1]
        assert event["args"] == arguments and event["sp"] == nested - 128
        assert m.state_value("boot_scope") == 1
        assert m.state_value("failures") == initial_count
        m.native_return(status, secondary)
    def after(m):
        nonlocal stage
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == nested
        assert m.state_value("boot_scope") == 0
        if stage == 0:
            stage = 1
            m.go("AUTH", arguments, link=m.after)
        elif stage == 1:
            assert m.u.reg_read(X[0]) == 0 and m.u.reg_read(X[1]) == secondary
            stage = 2
            m.go("BOOTLINUX", arguments, link=m.after)
        else:
            assert m.u.reg_read(X[0]) == status and m.u.reg_read(X[1]) == secondary
            assert [m.u.reg_read(r) for r in X[18:30]] == m.initial_gpr[18:30]
            assert [m.u.reg_read(r) for r in D] == m.initial_fp
            assert m.state_value("status") == status and m.state_value("reason") == 5
            assert m.state_value("site") == m.module + m.offsets["BOOTLINUX"]
            assert m.state_value("manual_bootlinux_status") == status
            assert m.state_value("manual_bootlinux_count") == 1
            assert m.state_value("manual_bootlinux_site") == m.module + m.offsets["BOOTLINUX"]
            assert m.state_value("failures") == initial_count
            assert m.state_value("force") == initial_force and m.state_value("pending") == 0
            assert len(m.original_visits) == 1 and len(m.counts("BootLinux")) == 1
            assert not m.counts("Stall") and not m.counts("NativeEventCleanup")
            assert [e["count"] for e in m.counts("SetVariable")] == ([0] if initial_count else [])
            assert bytes(m.u.mem_read(m.flags + 16, 1)) == (b"\0" if initial_force else b"\1")
            assert bytes(m.u.mem_read(m.flags, 1)) == (b"\1" if initial_force else b"\0")
            m.stop("manual-bootlinux-return-native-frame-and-status-preserved")
    h.original_action, h.auth_action, h.bootlinux_action, h.after_action = original, auth_native, boot_native, after
    h.run()


def manual_bootlinux_diagnostic_survives_entry_return(h, status):
    nested = h.root_sp - 0x2800
    stage = 0
    entry_return_link = 0
    def original(m, count):
        nonlocal entry_return_link
        if count == 1:
            entry_return_link = m.u.reg_read(X[30])
            m.u.reg_write(a.UC_ARM64_REG_SP, nested)
            m.go("FASTBOOT_ENTER", link=m.after)
        else:
            assert count == 2
            assert m.state_value("reason") == 1 and m.state_value("status") == 0
            assert m.state_value("manual_bootlinux_status") == status
            assert m.state_value("manual_bootlinux_count") == 1
            assert m.state_value("manual_bootlinux_site") == m.module + m.offsets["BOOTLINUX"]
            assert m.state_value("failures") == 0
            m.stop("manual-diagnostic-survives-native-cleanup-and-entry-return")
    def native(m):
        m.native_return(status, 0x77A4)
    def after(m):
        nonlocal stage
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == nested
        if stage == 0:
            stage = 1
            m.go("BOOTLINUX", [0xA800000000000000+i for i in range(8)], link=m.after)
        else:
            assert m.u.reg_read(X[0]) == status
            assert m.state_value("boot_scope") == 0
            # Model the existing native command epilogue and Fastboot cleanup,
            # then its ordinary LoaderEntry RET. The module executes the retry.
            m.u.reg_write(a.UC_ARM64_REG_SP, m.root_sp)
            m.u.reg_write(X[30], entry_return_link)
            m.loader_return(0)
    h.original_action, h.bootlinux_action, h.after_action = original, native, after
    h.run()


def manual_bootlinux_native_cleanup(h, status):
    """Return to the live native frame, which then calls its own event cleanup."""
    nested = h.root_sp - 0x2800
    initial_count = h.nv_count or 0
    initial_force = int(initial_count >= 3)
    entry_return_link = 0
    stage = 0
    h.event_active = True
    h.write_q(h.event_slot, 0xB00C000000000001)
    config = h.module + h.offsets["CONFIG"]
    h.write_q(config + 112, h.flags_rva + 48)
    h.write_q(config + 120, h.cleanup_rva)

    def original(m, count):
        nonlocal entry_return_link
        if count == 1:
            entry_return_link = m.u.reg_read(X[30])
            m.u.reg_write(a.UC_ARM64_REG_SP, nested)
            m.go("FASTBOOT_ENTER", link=m.after)
        else:
            assert count == 2
            assert m.state_value("failures") == initial_count
            # The later ordinary LoaderEntry return retains its pre-existing
            # retry policy; this is after native cleanup, outside the wrapper.
            assert m.state_value("force") == 1
            assert m.state_value("manual_bootlinux_status") == status
            assert m.state_value("manual_bootlinux_count") == 1
            assert m.read_q(m.event_slot) == 0
            cleanup = m.counts("NativeEventCleanup")
            assert len(cleanup) == 1 and cleanup[0]["sp"] == nested
            assert [e["count"] for e in m.counts("SetVariable")] == ([0] if initial_count else [])
            m.stop("manual-return-native-command-frame-performs-original-cleanup")

    def native(m):
        m.native_return(status, 0x77A4)

    def after(m):
        nonlocal stage
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == nested
        if stage == 0:
            stage = 1
            m.go("BOOTLINUX", [0xA800000000000000+i for i in range(8)], link=m.after)
        elif stage == 1:
            assert m.u.reg_read(X[0]) == status and m.u.reg_read(X[1]) == 0x77A4
            assert m.state_value("boot_scope") == 0
            assert m.state_value("failures") == initial_count and m.state_value("force") == initial_force
            assert not m.counts("NativeEventCleanup") and m.read_q(m.event_slot) != 0
            assert bytes(m.u.mem_read(m.flags + 16, 1)) == (b"\0" if initial_force else b"\1")
            # The modeled native command consumes BootLinux's original result
            # before its own cleanup. The wrapper has already returned to it.
            m.events.append({"kind": "NativeContinueResult", "status": hex(status), "sp": nested})
            stage = 2
            m.u.reg_write(X[30], m.after)
            m.u.reg_write(a.UC_ARM64_REG_PC, m.base + m.cleanup_rva)
        else:
            assert stage == 2 and m.read_q(m.event_slot) == 0
            assert len(m.counts("NativeEventCleanup")) == 1
            m.u.reg_write(a.UC_ARM64_REG_SP, m.root_sp)
            m.u.reg_write(X[30], entry_return_link)
            m.loader_return(0)

    h.original_action, h.bootlinux_action, h.after_action = original, native, after
    h.run()


def set_failure(h):
    entry_return(h, ERROR | 7)
    assert h.state_value("failures") == 1
    assert h.state_value("nv_status") == h.set_status


def absent_set(h):
    def original(m, count):
        if count == 1:
            m.loader_return(ERROR | 7)
        else:
            assert m.state_value("failures") == 1 and m.state_value("force") == 1
            m.stop("ram-only-recovery-without-setvariable")
    h.original_action = original
    h.run()
    assert h.state_value("nv_status") == UNSUPPORTED and not h.counts("SetVariable")


def no_watchdog(h):
    def original(m, count):
        if count == 1:
            m.loader_return(ERROR | 7)
        else:
            assert m.state_value("failures") == 1 and m.state_value("force") == 1
            m.stop("ram-recovery-without-boot-services")
    h.original_action = original
    h.run()
    assert len(h.counts("SetVariable")) == 1
    assert not h.counts("Watchdog") and not h.counts("Stall")


def word_canary(h):
    def original(m, count):
        if count == 1:
            m.write_q(m.flags + 32, 0xDEADBEEF12345678)
            m.u.reg_write(a.UC_ARM64_REG_SP, m.root_sp - 0x3000)
            m.go("REQUEST", [3, ERROR | 7, m.base + 0x45670])
        else:
            assert m.read_q(m.flags + 32) == (0xDEADBEEF00000000 | (m.initial_canary & 0xFFFFFFFF))
            assert m.state_value("initial_canary") == (m.initial_canary & 0xFFFFFFFF)
            m.stop("word-canary-restored-with-neighbor-preserved")
    h.original_action = original
    h.run()


def fastboot_scope(h):
    stage = 0
    nested = h.root_sp - 0x800
    def original(m, count):
        m.u.reg_write(a.UC_ARM64_REG_SP, nested)
        m.go("BOOT_SCOPE", link=m.after)
    def after(m):
        nonlocal stage
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == nested
        if stage == 0:
            assert m.u.reg_read(X[0]) == 1 and m.state_value("boot_scope") == 1
            stage = 1
            m.go("FASTBOOT_ENTER", link=m.after)
        elif stage == 1:
            assert m.state_value("boot_scope") == 0
            stage = 2
            m.go("BOOT_SCOPE", link=m.after)
        else:
            assert m.u.reg_read(X[0]) == 0 and m.state_value("boot_scope") == 0
            assert m.state_value("force") == 0 and m.state_value("failures") == 0
            assert not m.counts("SetVariable") and not m.counts("Watchdog") and not m.counts("Stall")
            m.stop("fastboot-scope-cleared-and-query-visible")
    h.original_action, h.after_action = original, after
    h.run()


def auth(h, status, manual_continue):
    arguments = [0xA300000000000000 + index for index in range(8)]
    secondary = 0x7A33000000000001
    nested = h.root_sp - 0x1000
    awaiting_fastboot_enter = manual_continue
    def original(m, count):
        if count == 1:
            m.u.reg_write(a.UC_ARM64_REG_SP, nested)
            if manual_continue:
                m.go("FASTBOOT_ENTER", link=m.after)
            else:
                m.go("AUTH", arguments, link=m.after)
        else:
            raise AssertionError("Scope-only auth wrapper restarted native entry")
    def native(m):
        event = m.counts("nativeAuth")[-1]
        assert event["args"] == arguments
        assert event["sp"] == nested - 128
        # Even a manual continue attempt is a boot operation while the native
        # loader runs, so its nested fatal paths can be recognized as such.
        assert event["bootScope"] == 1
        m.native_return(status, secondary)
    def after(m):
        nonlocal awaiting_fastboot_enter
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == nested
        if awaiting_fastboot_enter:
            awaiting_fastboot_enter = False
            assert m.state_value("boot_scope") == 0
            m.go("AUTH", arguments, link=m.after)
            return
        assert m.u.reg_read(X[0]) == status and m.u.reg_read(X[1]) == secondary
        assert m.state_value("boot_scope") == (0 if manual_continue else 1)
        assert m.state_value("failures") == 0 and m.state_value("force") == 0
        assert not m.counts("Watchdog") and not m.counts("Stall")
        assert bytes(m.u.mem_read(m.flags, 1)) == b"\0"
        assert bytes(m.u.mem_read(m.flags + 16, 1)) == b"\1"
        m.stop("manual-auth-native-error-return" if status else "auth-native-success-return")
    h.original_action, h.auth_action, h.after_action = original, native, after
    h.run()
    assert len(h.counts("nativeAuth")) == 1
    assert not h.counts("SetVariable")
    assert len(h.original_visits) == 1 and h.state_value("manual_depth") == 0


def pending_value(h, value):
    nested = h.root_sp - 0x900
    def original(m, count):
        m.write_q(m.state + STATE["pending"], value)
        m.u.reg_write(a.UC_ARM64_REG_SP, nested)
        m.go("PENDING_VALUE", link=m.after)
    def after(m):
        assert m.u.reg_read(X[0]) == value
        assert m.u.reg_read(a.UC_ARM64_REG_SP) == nested
        assert m.state_value("pending") == value
        assert m.state_value("force") == 0 and m.state_value("failures") == 0
        assert not m.counts("SetVariable") and not m.counts("Watchdog") and not m.counts("Stall")
        assert not m.counts("NativeEventCleanup")
        if m.event_active:
            assert m.read_q(m.event_slot) != 0
        m.stop("pending-query-is-pure")
    h.original_action, h.after_action = original, after
    h.run()


def persistent_write_deduplication(h):
    entry_return(h, ERROR | 7, 6)
    assert h.state_value("failures") == 6
    assert h.state_value("nv_requested_count") == 3
    assert [event["count"] for event in h.counts("SetVariable")] == [1, 2, 3]
    if h.set_status:
        assert h.state_value("nv_status") == h.set_status and h.nv_count is None
    else:
        assert h.nv_count == 3


def clear_then_record_again(h):
    arguments = [0xA400000000000000 + index for index in range(8)]
    def original(m, count):
        if count == 1:
            m.go("MARK_ONLY", [6, NOT_FOUND, m.base + 0x45680], link=m.after)
        else:
            assert m.state_value("failures") == 5
            assert [event["count"] for event in m.counts("SetVariable")] == [3, 0, 3]
            m.stop("threshold-writes-reenabled-after-clear")
    def after(m):
        assert m.state_value("failures") == 4
        assert [event["count"] for event in m.counts("SetVariable")] == [3]
        m.go("BOOTLINUX", arguments, link=m.parent_stop)
    def native(m):
        assert m.counts("BootLinux")[-1]["args"] == arguments
        assert [event["count"] for event in m.counts("SetVariable")] == [3, 0]
        m.native_return(ERROR | 7)
    h.original_action, h.after_action, h.bootlinux_action = original, after, native
    h.run()


def with_active_event(h, test, *, deep=False):
    h.event_active = True
    h.write_q(h.event_slot, 0xB00C000000000001)
    config = h.module + h.offsets["CONFIG"]
    h.write_q(config + 112, h.flags_rva + 48)
    h.write_q(config + 120, h.cleanup_rva)
    test(h)
    cleanup = h.counts("NativeEventCleanup")
    assert len(cleanup) == 1 and h.read_q(h.event_slot) == 0
    if deep:
        assert h.root_sp - 0x5100 <= cleanup[0]["sp"] <= h.root_sp - 0x5000
    kinds = [event["kind"] for event in h.events]
    close_index = kinds.index("NativeEventCleanup")
    for index, event in enumerate(h.events):
        if event["kind"] == "SetVariable" and event["count"] != 0:
            assert close_index < index


def active_event_query(h):
    h.event_active = True
    h.write_q(h.event_slot, 0xB00C000000000001)
    config = h.module + h.offsets["CONFIG"]
    h.write_q(config + 112, h.flags_rva + 48)
    h.write_q(config + 120, h.cleanup_rva)
    pending_value(h, 1)
    assert not h.counts("NativeEventCleanup") and h.read_q(h.event_slot) != 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", type=Path, default=SOURCE / "build/boot-recovery")
    parser.add_argument("--report", type=Path,
                        default=WORKSPACE / "analysis/efisp-fastboot-recovery-137-20261031/runtime-module-tests.json")
    args = parser.parse_args()
    manifest = json.loads((args.module / "manifest.json").read_text(encoding="utf-8"))
    binary = (args.module / "recovery.bin").read_bytes()
    assert hashlib.sha256(binary).hexdigest() == manifest["sha256"]
    assert len(binary) == manifest["bytes"] and not manifest["undefined"] and not manifest["relocations"]
    cases = []
    specs = [
        ("healthy-entry-nonreturn", {}, healthy),
        ("zero-entry-return-forces-without-error-count", {}, lambda h: entry_return(h, 0)),
        ("error-entry-return-counts", {}, lambda h: entry_return(h, ERROR | 7)),
        ("repeated-returns-constant-stack", {}, lambda h: entry_return(h, ERROR | 7, 6)),
        ("deep-request-restores-gpr-fp-sp-flags-arguments-canary", {}, deep_request),
        ("callback-defer-is-ram-only-until-synchronous-pending", {}, deferred),
        ("pending-empty-preserves-caller-frame", {}, pending_empty),
        ("default-slot-mark-only-returns-native-frame", {}, mark_only),
        ("manual-set-active-success-arguments-results-depth", {}, lambda h: manual(h, 0)),
        ("manual-set-active-error-is-returned-not-recovered", {}, lambda h: manual(h, NOT_FOUND)),
        ("bootlinux-success-clears-persistent-count-before-handoff", {"nv_count": 2}, lambda h: bootlinux(h, False)),
        ("bootlinux-return-restores-ram-count-and-adds-one", {"nv_count": 2}, lambda h: bootlinux(h, True)),
        ("manual-continue-bootlinux-zero-return-preserves-native-frame", {}, lambda h: manual_bootlinux_return(h, 0)),
        ("manual-continue-bootlinux-error-return-is-diagnostic-only", {}, lambda h: manual_bootlinux_return(h, ERROR | 7)),
        ("manual-continue-bootlinux-warning-return-preserved", {}, lambda h: manual_bootlinux_return(h, 1)),
        ("manual-continue-bootlinux-does-not-increment-existing-count", {"nv_count": 2}, lambda h: manual_bootlinux_return(h, ERROR | 14)),
        ("manual-continue-bootlinux-does-not-rewrite-threshold-count", {"nv_count": 3}, lambda h: manual_bootlinux_return(h, ERROR | 7)),
        ("high-address-manual-continue-bootlinux-native-return", {"image_base": 0x190000000}, lambda h: manual_bootlinux_return(h, ERROR | 7)),
        ("wide-rva-manual-continue-bootlinux-native-return", {"image_base": 0x190000000, "wide_rva": True}, lambda h: manual_bootlinux_return(h, ERROR | 7)),
        ("manual-bootlinux-error-diagnostic-survives-native-entry-return", {}, lambda h: manual_bootlinux_diagnostic_survives_entry_return(h, ERROR | 7)),
        ("manual-bootlinux-zero-diagnostic-survives-native-entry-return", {}, lambda h: manual_bootlinux_diagnostic_survives_entry_return(h, 0)),
        ("high-address-manual-diagnostic-survives-native-entry-return", {"image_base": 0x190000000}, lambda h: manual_bootlinux_diagnostic_survives_entry_return(h, ERROR | 14)),
        ("manual-zero-return-original-caller-performs-native-event-cleanup", {}, lambda h: manual_bootlinux_native_cleanup(h, 0)),
        ("manual-error-return-original-caller-performs-native-event-cleanup", {}, lambda h: manual_bootlinux_native_cleanup(h, ERROR | 7)),
        ("manual-error-existing-count-native-cleanup-and-diagnostic", {"nv_count": 3}, lambda h: manual_bootlinux_native_cleanup(h, ERROR | 14)),
        ("high-wide-manual-warning-native-cleanup", {"image_base": 0x190000000, "wide_rva": True, "nv_count": 2}, lambda h: manual_bootlinux_native_cleanup(h, 1)),
        ("valid-counter-three-forces-first-entry", {"nv_count": 3}, initial_threshold),
        ("valid-counter-larger-than-threshold-forces-first-entry", {"nv_count": 9}, initial_threshold),
        ("getvariable-absent-allows-healthy-entry", {"get_present": False}, healthy),
        ("runtime-services-absent-allows-healthy-entry", {"runtime_present": False}, healthy),
        ("getvariable-error-allows-healthy-entry", {"get_status": ERROR | 7}, healthy),
        ("setvariable-absent-recovers-in-ram", {"set_present": False}, absent_set),
        ("setvariable-error-recovers-in-ram", {"set_status": ERROR | 7}, set_failure),
        ("watchdog-and-stall-absent-still-recover", {"watchdog_present": False, "stall_present": False}, no_watchdog),
        ("boot-services-absent-still-recover", {"boot_services_present": False}, no_watchdog),
        ("four-byte-canary-does-not-overwrite-adjacent-word", {"canary_bytes": 4}, word_canary),
        ("bad-checksum-is-not-a-persistent-failure-count", {"nv_count": 9, "bad_record": "checksum"}, healthy),
        ("bad-record-size-is-not-a-persistent-failure-count", {"nv_count": 9, "bad_record": "size"}, healthy),
        ("bad-record-magic-is-not-a-persistent-failure-count", {"nv_count": 9, "bad_record": "magic"}, healthy),
        ("bad-record-version-is-not-a-persistent-failure-count", {"nv_count": 9, "bad_record": "version"}, healthy),
        ("pic-high-image-base-deep-recovery", {"image_base": 0x190000000}, deep_request),
        ("pic-high-image-base-manual-call", {"image_base": 0x190000000}, lambda h: manual(h, 0)),
        ("pic-high-image-base-bootlinux-return", {"image_base": 0x190000000, "nv_count": 2}, lambda h: bootlinux(h, True)),
        ("synthetic-wide-config-rva-arithmetic", {"image_base": 0x190000000, "wide_rva": True}, deep_request),
        ("fastboot-entry-clears-boot-scope-without-recovery", {}, fastboot_scope),
        ("normal-auth-success-keeps-native-result-and-scope", {}, lambda h: auth(h, 0, False)),
        ("normal-auth-failure-keeps-native-error-return", {}, lambda h: auth(h, ERROR | 7, False)),
        ("manual-continue-auth-failure-keeps-native-error-return", {}, lambda h: auth(h, ERROR | 7, True)),
        ("manual-continue-auth-success-restores-fastboot-scope", {}, lambda h: auth(h, 0, True)),
        ("high-address-normal-auth-failure", {"image_base": 0x190000000}, lambda h: auth(h, ERROR | 7, False)),
        ("wide-rva-manual-auth-failure", {"image_base": 0x190000000, "wide_rva": True}, lambda h: auth(h, ERROR | 7, True)),
        ("normal-auth-warning-keeps-native-result", {}, lambda h: auth(h, 1, False)),
        ("manual-auth-warning-keeps-native-result", {}, lambda h: auth(h, 1, True)),
        ("pending-value-zero-is-pure", {}, lambda h: pending_value(h, 0)),
        ("pending-value-one-is-pure", {}, lambda h: pending_value(h, 1)),
        ("six-failures-persist-at-most-threshold", {}, persistent_write_deduplication),
        ("rejected-nv-write-does-not-repeat-saturated-value", {"set_status": ERROR | 7}, persistent_write_deduplication),
        ("handoff-clear-reenables-threshold-record", {"nv_count": 3}, clear_then_record_again),
        ("active-event-direct-request-cleans-before-root-unwind-and-nv", {}, lambda h: with_active_event(h, deep_request, deep=True)),
        ("active-event-error-entry-return-cleans-before-retry", {}, lambda h: with_active_event(h, lambda m: entry_return(m, ERROR | 7))),
        ("active-event-zero-entry-return-cleans-without-error-count", {}, lambda h: with_active_event(h, lambda m: entry_return(m, 0))),
        ("active-event-bootlinux-return-cleans-before-failure-record", {"nv_count": 2}, lambda h: with_active_event(h, lambda m: bootlinux(m, True))),
        ("active-event-defer-leaves-live-until-synchronous-pending", {}, lambda h: with_active_event(h, deferred)),
        ("active-event-pending-value-query-does-not-close", {}, active_event_query),
    ]
    for name, options, test in specs:
        harness = None
        try:
            harness = Harness(binary, manifest, **options)
            test(harness)
            cases.append({"name": name, "passed": True, "options": options, "result": {
                "ended": harness.ended, "originalVisits": len(harness.original_visits),
                "originalSp": [hex(v["sp"]) for v in harness.original_visits],
                "forces": [v["force"] for v in harness.original_visits],
                "state": {key: hex(harness.state_value(key)) for key in STATE},
                "events": harness.events}})
        except Exception as error:
            cases.append({"name": name, "passed": False, "options": options,
                          "error": repr(error), "traceback": traceback.format_exc(),
                          "trace": [hex(pc) for pc in harness.trace] if harness else [],
                          "events": harness.events if harness else []})
    passed = all(case["passed"] for case in cases)
    report = {"passed": passed, "caseCount": len(cases),
              "passedCases": sum(case["passed"] for case in cases),
              "moduleSha256": manifest["sha256"], "moduleBytes": len(binary),
              "testSourceSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "manifest": manifest, "deviceOperations": False,
              "scope": __doc__, "limits": [
                  "Native ABL entry and BootLinux are modeled boundaries, not complete hardware boot.",
                  "EFI variable, watchdog and stall callbacks are host models; no physical NVRAM is accessed.",
                  "Defer verifies local ABI behavior, not a real firmware dispatcher or TPL transition.",
                  "The synthetic wide-RVA case checks 64-bit module config arithmetic, not PE format support for >4GiB RVAs.",
                  "Only ABI-preserved low 64 bits of D8-D15 are required; no MMU/EL transition is modeled."],
              "cases": cases}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"passed": passed, "cases": len(cases), "passedCases": report["passedCases"],
                      "failed": [case["name"] for case in cases if not case["passed"]],
                      "report": str(args.report)}, indent=2))
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
