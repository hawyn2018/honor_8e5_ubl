"""Execute actual emitted EFISP recovery gates in Unicorn, without device I/O.

Fixture images come from FastbootRecoveryIntegrationTest. PE instructions and
the embedded recovery module execute; EFI services and native continuation
destinations are explicit models. This does not model a full firmware boot,
physical TPL/interrupts, flash, verified boot or a Linux handoff.
"""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import traceback

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn import arm64_const as a
from test_boot_recovery import X, D, STATE, NAME, GUID, NOT_FOUND


SOURCE = Path(__file__).resolve().parents[2]
WORKSPACE = SOURCE.parents[2]


def u32(data, at):
    return struct.unpack_from("<I", data, at)[0]


def u64(data, at):
    return struct.unpack_from("<Q", data, at)[0]


def q(value):
    return struct.pack("<Q", value)


def sext(value, width):
    return value - (1 << width) if value >> (width - 1) else value


def branch_target(pc, word):
    assert word & 0x7c000000 == 0x14000000, hex(word)
    return pc + (sext(word & 0x3ffffff, 26) << 2)


def conditional_target(pc, word):
    if word & 0x7e000000 == 0x34000000 or word & 0xff000010 == 0x54000000:
        return pc + (sext((word >> 5) & 0x7ffff, 19) << 2)
    if word & 0x7e000000 == 0x36000000:
        return pc + (sext((word >> 5) & 0x3fff, 14) << 2)
    return None


def pe_word(data, rva):
    pe = u32(data, 0x3c)
    sections = pe + 24 + struct.unpack_from("<H", data, pe + 20)[0]
    for n in range(struct.unpack_from("<H", data, pe + 6)[0]):
        at = sections + n * 40
        va, size, raw = struct.unpack_from("<III", data, at + 12)
        if va <= rva and rva + 4 <= va + size:
            return u32(data, raw + rva - va)
    raise AssertionError(f"Unmapped PE word RVA {rva:x}")


class GateHarness:
    def __init__(self, fixture, manifest, *, base=0x10000000):
        self.f = fixture
        self.offsets = manifest["offsets"]
        self.base = base
        self.data = Path(fixture["imagePath"]).read_bytes()
        assert hashlib.sha256(self.data).hexdigest() == fixture["outputSha256"]
        pe = u32(self.data, 0x3c)
        optional = pe + 24
        size = u32(self.data, optional + 56)
        headers = u32(self.data, optional + 60)
        count = struct.unpack_from("<H", self.data, pe + 6)[0]
        section_table = optional + struct.unpack_from("<H", self.data, pe + 20)[0]
        self.u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.u.reg_write(a.UC_ARM64_REG_CPACR_EL1, 3 << 20)
        self.u.mem_map(base, (size + 4095) & ~4095)
        self.u.mem_write(base, self.data[:headers])
        for n in range(count):
            at = section_table + n * 40
            rva, raw_size, raw = struct.unpack_from("<III", self.data, at + 12)
            if raw_size:
                self.u.mem_write(base + rva, self.data[raw:raw + raw_size])
        # Model standard PE DIR64 relocation, so an existing relocated command
        # table slot is actually read at the selected RAM image base.
        preferred = u64(self.data, optional + 24)
        reloc_rva, reloc_bytes = struct.unpack_from("<II", self.data, optional + 112 + 5 * 8)
        relocation = reloc_rva
        while relocation < reloc_rva + reloc_bytes:
            page, length = struct.unpack("<II", self.u.mem_read(base + relocation, 8))
            assert length >= 8 and length % 2 == 0
            assert relocation + length <= reloc_rva + reloc_bytes
            for entry in struct.unpack("<" + "H" * ((length - 8) // 2),
                                       self.u.mem_read(base + relocation + 8, length - 8)):
                kind, offset = entry >> 12, entry & 4095
                if kind == 0:
                    continue
                assert kind == 10, f"Unsupported PE relocation type {kind}"
                address = base + page + offset
                value = u64(self.u.mem_read(address, 8), 0)
                self.u.mem_write(address, q((value + base - preferred) & 0xffffffffffffffff))
            relocation += length
        self.module = base + fixture["moduleRva"]
        self.state = base + fixture["stateRva"]
        self.table = base + 0x10000000
        self.bs = self.table + 0x1000
        self.rt = self.table + 0x2000
        self.external = self.table + 0x3000
        self.stop_address = self.external + 0x100
        self.handle = self.table + 0x5000
        self.stack = base + 0x18000000
        self.root_sp = self.stack + 0x18000
        self.buffer = self.stack + 0x1000
        self.u.mem_map(self.table, 0x6000)
        self.u.mem_map(self.stack, 0x20000)
        self.writeq(self.table + 0x58, self.rt)
        self.writeq(self.table + 0x60, self.bs)
        self.writeq(self.rt + 0x48, self.external)
        self.writeq(self.rt + 0x58, self.external + 4)
        self.writeq(self.bs + 0xf8, self.external + 8)
        self.writeq(self.bs + 0x100, self.external + 12)
        self.initial = [0x4100000000000000 + n * 0x101 for n in range(31)]
        self.initial[0:2] = [self.handle, self.table]
        self.initial[30] = self.stop_address
        self.fp = [0x3ff0000000000000 + n * 0x101 for n in range(8)]
        self.events = []
        self.cleanup_models = {}
        self.native_call_models = {}
        config = self.module + self.offsets["CONFIG"]
        assert self.readq(config) == fixture["moduleRva"], "Fixture embedded module differs from current manifest; rebuild fixtures"
        self.event_slot = base + self.readq(config + 112) if self.readq(config + 112) else None
        self.event_cleanup = base + self.readq(config + 120) if self.readq(config + 120) else None
        if self.event_slot and self.event_cleanup:
            self.cleanup_models[self.event_cleanup] = self.event_slot
        self.trace = []
        self.stops = set()
        self.stopped = None
        self.single = False
        self.u.hook_add(UC_HOOK_CODE, self.hook)
        self.set_context(self.initial, self.root_sp)
        # Actual ENTRY saves the real root frame. Stop before native entry;
        # this test subsequently executes individual gates, not full firmware.
        self.run(self.module + self.offsets["ENTRY"],
                 {self.module + self.offsets["ORIGINAL_ENTRY"]})
        assert self.state_value("base") == base
        assert self.state_value("sp") == self.root_sp
        assert self.state_value("boot_scope") == 1
        self.root = self.context()

    def writeq(self, address, value):
        self.u.mem_write(address, q(value))

    def readq(self, address):
        return u64(self.u.mem_read(address, 8), 0)

    def state_value(self, key):
        return self.readq(self.state + STATE[key])

    def state_write(self, key, value):
        self.writeq(self.state + STATE[key], value)

    def set_context(self, regs=None, sp=None, nzcv=0xa0000000):
        for reg, value in zip(X, self.initial if regs is None else regs):
            self.u.reg_write(reg, value)
        for reg, value in zip(D, self.fp):
            self.u.reg_write(reg, value)
        self.u.reg_write(a.UC_ARM64_REG_SP, self.root_sp if sp is None else sp)
        self.u.reg_write(a.UC_ARM64_REG_NZCV, nzcv)

    def context(self):
        return {"gpr": [self.u.reg_read(reg) for reg in X],
                "fp": [self.u.reg_read(reg) for reg in D],
                "sp": self.u.reg_read(a.UC_ARM64_REG_SP),
                "nzcv": self.u.reg_read(a.UC_ARM64_REG_NZCV)}

    def restore_context(self, snapshot):
        for reg, value in zip(X, snapshot["gpr"]):
            self.u.reg_write(reg, value)
        for reg, value in zip(D, snapshot["fp"]):
            self.u.reg_write(reg, value)
        self.u.reg_write(a.UC_ARM64_REG_SP, snapshot["sp"])
        self.u.reg_write(a.UC_ARM64_REG_NZCV, snapshot["nzcv"])

    def efi_return(self, value):
        destination = self.u.reg_read(X[30])
        for n in range(18):
            self.u.reg_write(X[n], 0x7300000000000000 + n)
        self.u.reg_write(X[0], value)
        self.u.reg_write(a.UC_ARM64_REG_NZCV, 0x50000000)
        self.u.reg_write(a.UC_ARM64_REG_PC, destination)

    def variable(self):
        text = bytearray()
        name, guid = [self.u.reg_read(reg) for reg in X[:2]]
        for n in range(128):
            char = bytes(self.u.mem_read(name + n * 2, 2))
            if char == b"\0\0":
                break
            text.extend(char)
        assert text.decode("utf-16le") == NAME
        assert bytes(self.u.mem_read(guid, 16)) == GUID

    def hook(self, cpu, pc, size, opaque):
        self.trace.append(pc)
        self.trace = self.trace[-64:]
        if pc in self.stops:
            self.stopped = pc
            cpu.emu_stop()
        elif pc in self.native_call_models:
            kind, result = self.native_call_models[pc]
            self.events.append({"kind": kind, "args": [cpu.reg_read(reg) for reg in X[:8]],
                                "sp": cpu.reg_read(a.UC_ARM64_REG_SP),
                                "boot_scope": self.state_value("boot_scope")})
            self.efi_return(result)
        elif pc in self.cleanup_models:
            slot = self.cleanup_models[pc]
            assert self.readq(slot) != 0
            self.events.append({"kind": "NativeEventCleanup", "event": self.readq(slot),
                                "sp": cpu.reg_read(a.UC_ARM64_REG_SP)})
            self.writeq(slot, 0)
            self.efi_return(0)
        elif pc == self.external:
            self.variable()
            self.events.append({"kind": "GetVariable"})
            self.efi_return(NOT_FOUND)
        elif pc == self.external + 4:
            self.variable()
            attrs, size, address = [cpu.reg_read(reg) for reg in X[2:5]]
            assert attrs == 7 and size == 16
            record = struct.unpack("<4I", cpu.mem_read(address, 16))
            assert record[0:2] == (0x48424642, 1)
            assert record[3] == record[0] ^ record[1] ^ record[2] ^ 0x917ab36d
            self.events.append({"kind": "SetVariable", "count": record[2]})
            self.efi_return(0)
        elif pc == self.external + 8:
            self.events.append({"kind": "Stall", "delay": cpu.reg_read(X[0])})
            self.efi_return(0)
        elif pc == self.external + 12:
            assert [cpu.reg_read(reg) for reg in X[:4]] == [0, 0x10000, 0, 0]
            self.events.append({"kind": "Watchdog"})
            self.efi_return(0)

    def run(self, start, stops, limit=10000):
        self.stops = set(stops)
        self.stopped = None
        try:
            self.u.emu_start(start, 0, count=limit)
        except Exception as error:
            raise AssertionError(f"{error}; trace={[hex(p-self.base) for p in self.trace]}") from error
        assert self.stopped is not None, f"Instruction limit at {self.trace[-8:]}"
        return self.stopped

    def baseline(self, rva, word):
        before = self.context()
        actual = bytes(self.u.mem_read(self.base + rva, 4))
        self.u.mem_write(self.base + rva, struct.pack("<I", word))
        self.u.ctl_remove_cache(self.base + rva, self.base + rva + 4)
        self.stops = set()
        self.u.emu_start(self.base + rva, 0, count=1)
        result = self.context(), self.u.reg_read(a.UC_ARM64_REG_PC)
        self.u.mem_write(self.base + rva, actual)
        self.u.ctl_remove_cache(self.base + rva, self.base + rva + 4)
        self.restore_context(before)
        return result

    def baseline_sequence(self, rva, words, destination):
        """Execute original contiguous prefix, including its modeled real call."""
        before = self.context()
        raw = self.base + rva
        saved = bytes(self.u.mem_read(raw, len(words) * 4))
        old_events = len(self.events)
        try:
            self.u.mem_write(raw, struct.pack("<" + "I" * len(words), *words))
            self.u.ctl_remove_cache(raw, raw + len(saved))
            self.run(raw, {destination})
            return self.context(), self.events[old_events:]
        finally:
            self.u.mem_write(raw, saved)
            self.u.ctl_remove_cache(raw, raw + len(saved))
            self.restore_context(before)
            del self.events[old_events:]

    def patch(self, name):
        return next(p for p in self.f["patches"] if p["name"] == name)

    def materialize(self):
        for register, rva in self.f["globalRegisters"].items():
            self.u.reg_write(X[int(register)], self.base + rva)
        for register, value in self.f["constantRegisters"].items():
            self.u.reg_write(X[int(register)], value)

    def activate_event(self):
        assert self.event_slot and self.event_cleanup
        self.writeq(self.event_slot, self.external + 0x800)

    def root_retry(self, rva, reason, active_event=False):
        if active_event:
            self.activate_event()
        regs = [0x3100000000000000 + n * 0x111 for n in range(31)]
        regs[30] = self.stop_address
        self.set_context(regs, self.root_sp - 0x1200, 0x60000000)
        self.run(self.base + rva, {self.module + self.offsets["ORIGINAL_ENTRY"]})
        context = self.context()
        assert context["sp"] == self.root_sp
        assert context["gpr"][:2] == self.initial[:2]
        assert context["gpr"][18:30] == self.initial[18:30]
        assert context["fp"] == self.fp and context["nzcv"] == 0xa0000000
        assert self.state_value("reason") == reason
        assert self.state_value("site") == self.base + rva
        assert self.state_value("force") == 1
        assert self.state_value("failures") == 1
        assert self.u.mem_read(self.base + self.f["continueFlagRva"], 1) == b"\0"
        if active_event:
            assert self.readq(self.event_slot) == 0
            kinds = [event["kind"] for event in self.events]
            assert kinds.count("NativeEventCleanup") == 1
            assert kinds.index("NativeEventCleanup") < kinds.index("SetVariable")


def cases(fixture, manifest, base):
    fixture_bytes = Path(fixture["imagePath"]).read_bytes()
    def harness():
        return GateHarness(fixture, manifest, base=base)

    def checkpoint_normal():
        h = harness()
        p = h.patch("阻断引导后的原生 Fastboot 入口")
        before = h.context()
        expected, destination = h.baseline(p["rva"], int.from_bytes(bytes.fromhex(p["before"]), "little"))
        h.run(base + p["rva"], {destination})
        assert h.context() == expected, (before, expected, h.context())
        assert h.state_value("force") == 0

    def checkpoint_forced():
        h = harness()
        h.state_write("force", 1)
        expected = h.context()
        for reg, rva in fixture["globalRegisters"].items():
            expected["gpr"][int(reg)] = base + rva
        for reg, value in fixture["constantRegisters"].items():
            expected["gpr"][int(reg)] = value
        h.run(base + fixture["checkpointRva"], {base + fixture["recoveryEntryRva"]})
        assert h.context() == expected

    def fastboot_scope():
        h = harness()
        h.materialize()
        prefix = fixture["scopePrefix"]
        assert prefix and prefix["startRva"] == fixture["fastbootSetupRva"]
        destination = base + prefix["resumeRva"]
        h.native_call_models[base + prefix["loggerTargetRva"]] = ("NativeLaunchingLogger", 0x876501)
        expected, original_events = h.baseline_sequence(prefix["startRva"], prefix["words"], destination)
        assert len(original_events) == 1 and original_events[0]["kind"] == "NativeLaunchingLogger"
        h.trace.clear()
        h.run(base + prefix["startRva"], {destination})
        assert h.context() == expected
        assert h.state_value("boot_scope") == 0
        native_events = [event for event in h.events if event["kind"] == "NativeLaunchingLogger"]
        assert len(native_events) == 1
        assert native_events[0]["args"] == original_events[0]["args"]
        assert native_events[0]["sp"] == original_events[0]["sp"]
        assert native_events[0]["boot_scope"] == 0
        assert native_events[0]["args"][prefix["formatRegister"]] == base + prefix["formatRva"]
        actual_calls = []
        for pc in h.trace:
            word = u32(h.u.mem_read(pc, 4), 0)
            if word & 0xfc000000 == 0x94000000 or word & 0xfffffc1f == 0xd63f0000:
                actual_calls.append(pc)
        assert len(actual_calls) == 1, "Inline scope clear must not introduce any extra BL/BLR"
        for n in range(1, len(prefix["words"])):
            assert u32(h.u.mem_read(base + prefix["startRva"] + n * 4, 4), 0) == 0xd503201f

    yield "checkpoint-normal-replays-native", checkpoint_normal
    yield "checkpoint-forced-preserves-materializes", checkpoint_forced
    yield "fastboot-entry-clears-scope-preserves-context", fastboot_scope

    if fixture.get("preparationRoute") is not None:
        route = fixture["preparationRoute"]
        for forced in (0, 1):
            for status in (0, 1, (1 << 63) | 7):
                def preparation(forced=forced, status=status, route=route):
                    h = harness()
                    h.state_write("force", forced)
                    h.u.reg_write(X[route["statusRegister"]], status)
                    if forced and status == 0:
                        expected = h.context()
                        for reg, rva in route["globalRegisters"].items():
                            expected["gpr"][int(reg)] = base + rva
                        for reg, value in route["constantRegisters"].items():
                            expected["gpr"][int(reg)] = value
                        destination = base + fixture["fastbootSetupRva"]
                    else:
                        expected, destination = h.baseline(route["successGateRva"], route["word"])
                    h.run(base + route["successGateRva"], {destination})
                    assert h.context() == expected
                    assert h.state_value("force") == forced
                yield f"preparation-native-status-{status:x}-force{forced}", preparation

    for condition in (0, 0xffffffffffffffff):
        def late_native_selector(condition=condition):
            h = harness()
            rva = fixture["requestFlagBranchRva"]
            word = pe_word(fixture_bytes, rva)
            assert word & 0x7e000000 in (0x34000000, 0x36000000)
            assert all(p["rva"] != rva for p in fixture["patches"]), "Native selector is preserved for the old -bl parser"
            h.u.reg_write(X[word & 31], condition)
            expected, destination = h.baseline(rva, word)
            h.run(base + rva, {destination})
            assert h.context() == expected
            assert h.state_value("force") == 0
        yield f"native-selector-preserved-condition-{condition:x}", late_native_selector

    def late_normal_path():
        h = harness()
        h.materialize()
        p = h.patch("原生模式选择保留已请求的故障恢复")
        assert p["rva"] == fixture["lateRecoveryHookRva"]
        word = int.from_bytes(bytes.fromhex(p["before"]), "little")
        is_call = word & 0xfc000000 == 0x94000000
        if is_call:
            target = base + branch_target(p["rva"], word)
            h.native_call_models[target] = ("NativeNormalPathCall", 0)
            if target == base + fixture["bootLinuxRva"]:
                before = h.context()
                h.run(base + p["rva"], {target})
                assert [h.u.reg_read(reg) for reg in X[:8]] == before["gpr"][:8]
                assert h.u.reg_read(a.UC_ARM64_REG_SP) == before["sp"] - 96
                assert h.state_value("force") == 0
                return
            expected, calls = h.baseline_sequence(p["rva"], [word], base + p["rva"] + 4)
            h.run(base + p["rva"], {base + p["rva"] + 4})
            actual = h.context()
            # The displaced auth wrapper's BL owns a different caller-clobbered
            # LR. Its original surrounding native frame still owns return LR.
            expected["gpr"][30] = actual["gpr"][30]
            assert actual == expected
            assert len(calls) == 1
            actual_calls = [event for event in h.events if event["kind"] == "NativeNormalPathCall"]
            assert len(actual_calls) == 1 and actual_calls[0]["args"] == calls[0]["args"]
        else:
            expected, destination = h.baseline(p["rva"], word)
            h.run(base + p["rva"], {destination})
            assert h.context() == expected
        assert h.state_value("force") == 0
    yield "late-normal-boot-path-replays-original-instruction", late_normal_path

    def late_selector_forced():
        h = harness()
        h.state_write("force", 1)
        before = h.context()
        h.run(base + fixture["lateRecoveryHookRva"], {base + fixture["fastbootGateRva"]})
        assert h.context() == before
    yield "late-selector-forced-survives-native-flag-overwrite", late_selector_forced

    def cardinfo_gate():
        h = harness()
        p = h.patch("CardInfo 错误仍尝试原生 Fastboot")
        assert bytes.fromhex(p["after"]) == b"\x1f\x20\x03\xd5"
        before = h.context()
        h.run(base + p["rva"], {base + p["rva"] + 4})
        assert h.context() == before
    yield "cardinfo-status-does-not-spin-before-fastboot", cardinfo_gate

    def manual_table_wrapper():
        h = harness()
        p = h.patch("保留手动 set_active 作用域")
        config = h.module + manifest["offsets"]["CONFIG"]
        native_rva = h.readq(config + 48)
        before = int.from_bytes(bytes.fromhex(p["before"]), "little")
        after = int.from_bytes(bytes.fromhex(p["after"]), "little")
        assert before in (native_rva, native_rva + fixture["imageBase"])
        target = base + after - (0 if before == native_rva else fixture["imageBase"])
        assert target == h.module + manifest["offsets"]["SET_ACTIVE"]
        assert h.readq(base + p["rva"]) == target
        regs = list(h.initial)
        regs[:8] = [0x1200 + n for n in range(8)]
        h.set_context(regs, h.root_sp - 0x600, 0x60000000)
        original_sp = h.u.reg_read(a.UC_ARM64_REG_SP)
        h.run(target, {base + native_rva})
        assert [h.u.reg_read(reg) for reg in X[:8]] == regs[:8]
        assert h.state_value("manual_depth") == 1
        assert h.u.reg_read(a.UC_ARM64_REG_SP) % 16 == 0
        h.efi_return((1 << 63) | 7)
        h.u.reg_write(X[1], 0x7395)
        h.run(h.u.reg_read(a.UC_ARM64_REG_PC), {h.stop_address})
        assert h.u.reg_read(X[0]) == (1 << 63) | 7 and h.u.reg_read(X[1]) == 0x7395
        assert h.u.reg_read(a.UC_ARM64_REG_SP) == original_sp
        assert h.state_value("manual_depth") == 0
        assert h.state_value("force") == 0
    yield "real-command-table-manual-set-active-wrapper", manual_table_wrapper

    for status in (0, (1 << 63) | 7):
        def entry_return(status=status):
            h = harness()
            return_pc = h.u.reg_read(X[30])
            h.activate_event()
            h.u.reg_write(X[0], status)
            h.run(return_pc, {h.module + manifest["offsets"]["ORIGINAL_ENTRY"]})
            assert h.state_value("reason") == 1 and h.state_value("force") == 1
            assert h.state_value("failures") == (1 if status else 0)
            assert h.readq(h.event_slot) == 0 and h.u.reg_read(a.UC_ARM64_REG_SP) == h.root_sp
            kinds = [event["kind"] for event in h.events]
            assert kinds.count("NativeEventCleanup") == 1
            if status:
                assert kinds.index("NativeEventCleanup") < kinds.index("SetVariable")
            else:
                assert "SetVariable" not in kinds
        yield f"embedded-entry-return-{status:x}-cleans-active-event-before-retry", entry_return

    for index, patch in enumerate(p for p in fixture["patches"] if p["name"] == "内核交接前清除故障计数"):
        def bootlinux_return(patch=patch):
            h = harness()
            word = int.from_bytes(bytes.fromhex(patch["after"]), "little")
            assert base + branch_target(patch["rva"], word) == h.module + manifest["offsets"]["BOOTLINUX"]
            regs = list(h.initial)
            regs[:8] = [0x1400 + n for n in range(8)]
            h.set_context(regs, h.root_sp - 0x1200, 0x60000000)
            h.state_write("failures", 2)
            h.activate_event()
            h.run(base + patch["rva"], {base + fixture["bootLinuxRva"]})
            assert [h.u.reg_read(reg) for reg in X[:8]] == regs[:8]
            assert [event["count"] for event in h.events if event["kind"] == "SetVariable"] == [0]
            h.efi_return((1 << 63) | 7)
            h.run(h.u.reg_read(a.UC_ARM64_REG_PC), {h.module + manifest["offsets"]["ORIGINAL_ENTRY"]})
            assert h.u.reg_read(a.UC_ARM64_REG_SP) == h.root_sp
            assert h.state_value("failures") == 3 and h.state_value("reason") == 5
            assert h.readq(h.event_slot) == 0
            kinds = [event["kind"] for event in h.events]
            assert kinds.count("NativeEventCleanup") == 1
            record_index = next(n for n, event in enumerate(h.events) if event["kind"] == "SetVariable" and event["count"] == 3)
            assert kinds.index("NativeEventCleanup") < record_index
        yield f"real-bootlinux-call-{index}-return-cleans-active-event", bootlinux_return

    for index, patch in enumerate(p for p in fixture["patches"] if p["name"] == "CardInfo fatal 自旋恢复"):
        yield f"cardinfo-fatal-spin-{index}-root-retry", lambda patch=patch: harness().root_retry(patch["rva"], 4)
        yield f"cardinfo-fatal-spin-{index}-active-event-cleanup", lambda patch=patch: harness().root_retry(patch["rva"], 4, True)

    for index, policy in enumerate(fixture.get("hashPolicies", [])):
        assert policy["existingStubRva"] is not None
        for allow, error in ((1, 3), (0, 3), (1, 2), (1, 4)):
            def hash_policy(policy=policy, allow=allow, error=error):
                h = harness()
                regs = list(h.initial)
                regs[policy["allowRegister"]] = allow
                regs[policy["errorRegister"]] = error
                regs[30] = base + policy["callRva"] + 4
                h.set_context(regs, h.root_sp - 0x600, 0x60000000)
                before = h.context()
                assert u32(h.u.mem_read(base + policy["existingStubRva"] + 12, 4), 0) == 0xd65f03c0
                destination = base + (policy["callRva"] + 4 if allow and error == 3 else policy["originalTarget"])
                h.run(base + policy["callRva"], {destination})
                after = h.context()
                assert after["gpr"] == before["gpr"] and after["sp"] == before["sp"] and after["fp"] == before["fp"]
                assert h.state_value("pending") == 0 and h.state_value("force") == 0
                assert not any(event["kind"] in ("SetVariable", "Watchdog") for event in h.events)
            yield f"hash-policy-{index}-allow{allow}-error{error}-native-ret-or-reporter", hash_policy

    for index, patch in enumerate(p for p in fixture["patches"] if p["name"] == "原生引导认证调用边界"):
        for scope, status in ((scope, status) for scope in (0, 1) for status in (0, 1, (1 << 63) | 7)):
            def auth_boundary(patch=patch, scope=scope, status=status):
                h = harness()
                before_word = int.from_bytes(bytes.fromhex(patch["before"]), "little")
                after_word = int.from_bytes(bytes.fromhex(patch["after"]), "little")
                assert branch_target(patch["rva"], before_word) == fixture["authTargetRva"]
                assert base + branch_target(patch["rva"], after_word) == h.module + manifest["offsets"]["AUTH"]
                regs = list(h.initial)
                regs[:8] = [0x1300 + n for n in range(8)]
                h.set_context(regs, h.root_sp - 0x800, 0x60000000)
                original_sp = h.u.reg_read(a.UC_ARM64_REG_SP)
                h.state_write("boot_scope", scope)
                h.run(base + patch["rva"], {base + fixture["authTargetRva"]})
                assert [h.u.reg_read(reg) for reg in X[:8]] == regs[:8]
                assert h.state_value("boot_scope") == 1
                h.efi_return(status)
                h.u.reg_write(X[1], 0x7396)
                destination = base + patch["rva"] + 4
                h.run(h.u.reg_read(a.UC_ARM64_REG_PC), {destination})
                assert h.u.reg_read(a.UC_ARM64_REG_SP) == original_sp
                assert h.u.reg_read(X[0]) == status and h.u.reg_read(X[1]) == 0x7396
                assert h.state_value("boot_scope") == scope
                assert h.state_value("failures") == 0 and h.state_value("force") == 0
                assert not any(e["kind"] in ("Watchdog", "SetVariable") for e in h.events)
            yield f"real-auth-call-{index}-scope{scope}-status{status:x}", auth_boundary

    for index, site in enumerate(fixture["defaultSites"]):
        def default_blocked(site=site):
            h = harness()
            regs = list(h.initial)
            regs[site["outputRegister"]] = h.buffer
            h.set_context(regs, h.root_sp - 0x700, 0x60000000)
            h.u.mem_write(h.buffer - 8, b"Z" * (site["outputBytes"] + 16))
            before = h.context()
            h.run(base + site["hookRva"], {base + site["errorRva"]})
            assert h.context() == before
            assert bytes(h.u.mem_read(h.buffer - 8, 8)) == b"Z" * 8
            assert bytes(h.u.mem_read(h.buffer, site["outputBytes"])) == bytes(site["outputBytes"])
            assert bytes(h.u.mem_read(h.buffer + site["outputBytes"], 8)) == b"Z" * 8
            assert h.state_value("force") == 1 and h.state_value("reason") == 6
            assert h.state_value("failures") == 1
            assert not any(e["kind"] == "Watchdog" for e in h.events)
        yield f"default-{index}-blocked-no-native-mutation", default_blocked
        for condition in (0, 1):
            def default_manual(site=site, condition=condition):
                h = harness()
                regs = list(h.initial)
                regs[site["outputRegister"]] = h.buffer
                # The proven native decision is CBZ/CBNZ; exercise both arms.
                assert site["word"] & 0x7e000000 == 0x34000000
                regs[site["word"] & 31] = condition
                h.set_context(regs, h.root_sp - 0x700, 0x60000000)
                h.state_write("manual_depth", 1)
                h.u.mem_write(h.buffer, b"Z" * site["outputBytes"])
                expected, destination = h.baseline(site["hookRva"], site["word"])
                h.run(base + site["hookRva"], {destination})
                assert h.context() == expected
                assert h.state_value("force") == 0 and h.state_value("manual_depth") == 1
                assert bytes(h.u.mem_read(h.buffer, site["outputBytes"])) == b"Z" * site["outputBytes"]
            yield f"default-{index}-manual-native-condition-{condition}", default_manual

    for index, site in enumerate(fixture["autoSites"]):
        if not site.get("requiresDeferredCallback"):
            yield f"auto-slot-{index}-{site['kind']}", lambda site=site: harness().root_retry(site["hookRva"], 2)
            yield f"auto-slot-{index}-{site['kind']}-active-event-cleanup", lambda site=site: harness().root_retry(site["hookRva"], 2, True)
        else:
            def auto_callback(site=site):
                h = harness()
                h.set_context(sp=h.root_sp - 0x1400, nzcv=0x60000000)
                before = h.context()
                h.activate_event()
                h.run(base + site["hookRva"], {base + site["callbackNaturalResumeRva"]})
                assert h.context() == before
                assert h.state_value("pending") == 1 and h.state_value("reason") == 2
                assert h.state_value("site") == base + site["hookRva"]
                assert h.state_value("failures") == 0
                assert h.readq(h.event_slot) != 0
                assert not any(e["kind"] in ("SetVariable", "Watchdog", "NativeEventCleanup") for e in h.events)
            yield f"auto-slot-callback-{index}-defer-natural-resume", auto_callback

    for index, site in enumerate(fixture["failureSites"]):
        if site["context"] != "CALLBACK":
            hook = site["hookRva"]
            if site["existingPolicyStubRva"] is not None:
                # Only the native fatal tail is changed. The accepted hash
                # policy's original RET remains executable and byte-identical.
                stub = site["existingPolicyStubRva"]
                h = harness()
                assert bytes(h.u.mem_read(base + stub + 12, 4)) == b"\xc0\x03\x5f\xd6"
                matching = [p for p in fixture["patches"] if p["name"].startswith("阻断启动的原生故障")
                            and stub <= p["rva"] < stub + 32]
                assert len(matching) == 1
                hook = matching[0]["rva"]
            yield f"fatal-{index}-{site['kind']}", lambda hook=hook: harness().root_retry(hook, 3)
            yield f"fatal-{index}-{site['kind']}-active-event-cleanup", lambda hook=hook: harness().root_retry(hook, 3, True)
            continue

        def callback_defer(site=site):
            h = harness()
            h.set_context(sp=h.root_sp - 0x500, nzcv=0x60000000)
            h.activate_event()
            before = h.context()
            h.run(base + site["hookRva"], {h.stop_address})
            assert h.context() == before
            assert h.state_value("pending") == 1 and h.state_value("force") == 1
            assert h.state_value("reason") == 3
            assert h.readq(h.event_slot) != 0
            assert not any(e["kind"] in ("SetVariable", "Watchdog", "Stall") for e in h.events)

        def callback_manual(site=site):
            h = harness()
            regs = list(h.initial)
            word = site["word"]
            # The proved restored callback tail is normally B shutdown;
            # permit an equivalent indirect tail without assuming a register.
            if word & 0x7c000000 == 0x14000000:
                destination = base + branch_target(site["hookRva"], word)
            else:
                assert word & 0xfffffc1f in (0xd61f0000, 0xd63f0000), hex(word)
                destination = h.external + 0x200
                regs[(word >> 5) & 31] = destination
            h.set_context(regs, h.root_sp - 0x500, 0x60000000)
            h.state_write("boot_scope", 0)
            before = h.context()
            h.run(base + site["hookRva"], {destination})
            after = h.context()
            if word & 0xfffffc1f == 0xd63f0000:
                before["gpr"][30] = after["gpr"][30]
            assert after == before
            assert h.state_value("pending") == 0 and h.state_value("force") == 0
            assert not any(e["kind"] in ("SetVariable", "Watchdog", "Stall") for e in h.events)

        yield f"callback-{index}-scope1-defer-restores-native-caller", callback_defer
        yield f"callback-{index}-scope0-keeps-native-action", callback_manual

        for guard_index, guard in enumerate(site["normalContinueGuard"][1:]):
            word = pe_word(fixture_bytes, guard)
            target = conditional_target(guard, word)
            if target is None or not site["hookRva"] + 4 < target < site["ownerEnd"]:
                continue
            def native_nonfatal_guard(guard=guard, word=word, target=target):
                h = harness()
                assert all(p["rva"] != guard for p in fixture["patches"])
                selected = None
                for flags in range(16):
                    for value in (0, 0xffffffffffffffff):
                        h.set_context(nzcv=flags << 28)
                        if word & 0x7e000000 in (0x34000000, 0x36000000):
                            h.u.reg_write(X[word & 31], value)
                        expected, destination = h.baseline(guard, word)
                        if destination == base + target:
                            selected = expected
                            break
                    if selected is not None:
                        break
                assert selected is not None
                h.trace.clear()
                h.run(base + guard, {base + target})
                assert h.context() == selected
                assert h.state_value("pending") == 0 and h.state_value("force") == 0
                assert not any(h.module <= pc < h.module + manifest["bytes"] for pc in h.trace)
            yield f"callback-{index}-native-nonfatal-guard-{guard_index}", native_nonfatal_guard

    for index, site in enumerate(fixture["safeWaits"]):
        def wait_clear(site=site):
            h = harness()
            expected, destination = h.baseline(site["hookRva"], site["word"])
            h.run(base + site["hookRva"], {destination})
            assert h.context() == expected
            assert h.state_value("force") == 0
        def wait_pending(site=site):
            h = harness()
            h.state_write("pending", 1)
            h.state_write("reason", 3)
            h.state_write("status", (1 << 63) | 21)
            h.state_write("site", base + site["hookRva"])
            h.root_retry(site["hookRva"], 3)
            assert not any(e["kind"] == "NativeEventCleanup" for e in h.events)
        def wait_pending_active(site=site):
            h = harness()
            assert site["cleanupRva"]
            event_slot = base + site["eventSlotRva"]
            h.writeq(event_slot, h.external + 0x800)
            h.cleanup_models[base + site["cleanupRva"]] = event_slot
            h.state_write("pending", 1)
            h.state_write("reason", 3)
            h.state_write("status", (1 << 63) | 21)
            h.state_write("site", base + site["hookRva"])
            h.root_retry(site["hookRva"], 3)
            assert h.readq(event_slot) == 0
            kinds = [event["kind"] for event in h.events]
            assert kinds.count("NativeEventCleanup") == 1
            assert kinds.index("NativeEventCleanup") < kinds.index("SetVariable")
        yield f"safe-wait-{index}-normal-native-replay", wait_clear
        yield f"safe-wait-{index}-pending-root-retry", wait_pending
        yield f"safe-wait-{index}-pending-closes-active-timer-first", wait_pending_active


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", type=Path, default=SOURCE / "abl-efisp-core/build/fastboot-recovery-fixtures")
    parser.add_argument("--report", type=Path, default=WORKSPACE / "analysis/efisp-fastboot-recovery-137-20261031/runtime-gate-tests.json")
    args = parser.parse_args()
    manifest = json.loads((SOURCE / "build/boot-recovery/manifest.json").read_text())
    files = sorted(args.fixtures.glob("*.json"))
    assert len(files) == 4, f"Expected old/new x fake/freedom, got {files}"
    results = []
    for file in files:
        fixture = json.loads(file.read_text(encoding="utf-8-sig"))
        for base in (0x10000000, 0x140000000):
            for name, test in cases(fixture, manifest, base):
                record = {"fixture": fixture["name"], "imageSha256": fixture["outputSha256"],
                          "base": hex(base), "name": name}
                try:
                    test()
                    record["pass"] = True
                except Exception:
                    record["pass"] = False
                    record["error"] = traceback.format_exc()
                results.append(record)
    patch_sources = SOURCE / "abl-efisp-core/src/main/kotlin/net/hawyn/ablefisp/patch"
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()
    passed_cases = sum(r["pass"] for r in results)
    failed_cases = len(results) - passed_cases
    report = {"scope": "Offline Unicorn execution of actual emitted PE gates and embedded runtime; EFI services and native destinations are models.",
              "deviceOperations": False, "fullFirmwareOrKernelBoot": False,
              "moduleSha256": manifest["sha256"], "total": len(results), "caseCount": len(results),
              "passed": failed_cases == 0, "passedCases": passed_cases, "failed": failed_cases,
              "testSourceSha256": sha(Path(__file__)),
              "emitterSourceSha256": sha(patch_sources / "FastbootRecoveryPatcher.kt"),
              "factsSourceSha256": {name: sha(patch_sources / name) for name in
                                    ("FastbootRecoveryFacts.kt", "FastbootSlotRecoveryFacts.kt", "AutoBootFailureFacts.kt")},
              "fixtures": [{"metadataPath": str(file), "metadataSha256": sha(file),
                            "imageSha256": sha(Path(json.loads(file.read_text(encoding="utf-8-sig"))["imagePath"]))}
                           for file in files],
              "results": results}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"total": report["total"], "passed": report["passed"], "passedCases": report["passedCases"], "failed": report["failed"], "report": str(args.report)}))
    if report["failed"]:
        for result in results:
            if not result["pass"]:
                print(result["fixture"], result["base"], result["name"], result["error"])
        raise SystemExit(1)


if __name__ == "__main__":
    main()
