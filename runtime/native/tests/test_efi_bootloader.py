"""Build/test the real C helper; BootloaderHelper.prepare also serves RAM diagnostics.

Only host fixtures and explicitly supplied local files are opened. No device IO.
"""
from pathlib import Path
import argparse
import ctypes
import hashlib
import json
import mmap
import os
import shutil
import struct
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / "kbl_efi_bootloader.c"


class Preparation(ctypes.Structure):
    _fields_ = [("file_offset", ctypes.c_size_t), ("load_rva", ctypes.c_uint32),
                ("before", ctypes.c_uint32), ("after", ctypes.c_uint32),
                ("already_forced", ctypes.c_uint)]


class BootloaderHelper:
    def __init__(self, library):
        self.library = ctypes.CDLL(str(Path(library).resolve()))
        self.library.kbl_efi_force_bootloader.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(Preparation)]
        self.library.kbl_efi_force_bootloader.restype = ctypes.c_int
        self.library.kbl_efi_bootloader_error.argtypes = [ctypes.c_int]
        self.library.kbl_efi_bootloader_error.restype = ctypes.c_char_p

    def prepare(self, source: bytes):
        """Pass a private copy to production C; return transformed bytes and proof metadata."""
        buffer = ctypes.create_string_buffer(source, len(source))
        result = Preparation()
        status = self.library.kbl_efi_force_bootloader(buffer, len(source), ctypes.byref(result))
        if status:
            if buffer.raw != source:
                raise AssertionError("A failed preparation changed its input")
            raise ValueError(self.library.kbl_efi_bootloader_error(status).decode("ascii"))
        return buffer.raw, {name: getattr(result, name) for name, _ in result._fields_}

    def fixture(self, shift=0, register=8, field=0x33):
        self.library.kbl_test_efi_fixture.argtypes = [ctypes.c_void_p, ctypes.c_size_t,
                                                    ctypes.c_uint32, ctypes.c_uint, ctypes.c_uint]
        self.library.kbl_test_efi_fixture.restype = ctypes.c_size_t
        data = ctypes.create_string_buffer(0x1400)
        size = self.library.kbl_test_efi_fixture(data, len(data), shift, register, field)
        assert size
        return data.raw[:size]


def build_library(output):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    clang = shutil.which("clang")
    assert clang, "clang is required"
    fixture = output / "fixture_exports.c"
    fixture.write_text('#include "' + (HERE / "efi_bootloader_fixture.h").as_posix() + '"\n'
                       'size_t kbl_test_efi_fixture(unsigned char*b,size_t n,uint32_t shift,unsigned reg,unsigned field)'
                       '{return bl_fixture_pe(b,n,shift,reg,field);}\n', encoding="utf-8")
    library = output / ("bootloader_helper.dll" if os.name == "nt" else "bootloader_helper.so")
    cmd = [clang, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-shared", str(SOURCE), str(fixture), "-o", str(library)]
    cmd += ["-Wl,--export-all-symbols"] if os.name == "nt" else ["-fPIC"]
    subprocess.run(cmd, check=True, capture_output=True)
    return library


def check_large_sparse(helper, output):
    """Exercise real >4GiB file offsets without allocating/copying that content."""
    if ctypes.sizeof(ctypes.c_size_t) < 8:
        return {"applicable": False, "reason": "Host size_t cannot represent a >4GiB file"}
    descriptor, name = tempfile.mkstemp(prefix="large-pe-", suffix=".sparse", dir=output)
    path = Path(name)
    try:
        with os.fdopen(descriptor, "w+b") as file:
            if os.name == "nt":
                import msvcrt
                from ctypes import wintypes
                kernel = ctypes.WinDLL("kernel32", use_last_error=True)
                ioctl = kernel.DeviceIoControl
                ioctl.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD,
                                  ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), ctypes.c_void_p]
                ioctl.restype = wintypes.BOOL
                returned = wintypes.DWORD()
                if not ioctl(msvcrt.get_osfhandle(file.fileno()), 0x000900C4, None, 0, None, 0,
                             ctypes.byref(returned), None):
                    raise ctypes.WinError(ctypes.get_last_error())
            length = (1 << 32) + 0x800
            file.truncate(length)
            file.flush()
            with mmap.mmap(file.fileno(), length, access=mmap.ACCESS_WRITE) as image:
                fixture = helper.fixture()
                header, text, data = bytearray(fixture[:0x400]), bytearray(fixture[0x400:0xc00]), fixture[0xc00:]
                raw = 0xfffffe00
                struct.pack_into("<I", header, 0x19c, raw)
                def word(buffer, at, value):
                    struct.pack_into("<I", buffer, at, value & 0xffffffff)
                def branch(pc, target, link=False):
                    return (0x94000000 if link else 0x14000000) | (((target-pc)//4) & 0x3ffffff)
                word(text, 0, branch(0x1000, 0x1600))
                word(text, 0x40, 0xd503201f)
                word(text, 0x44, 0xd503201f)
                word(text, 0x600, 0x39400000 | (0x33 << 10) | (26 << 5) | 8)
                word(text, 0x604, 0x37000008 | ((((0x1080-0x1604)//4) & 0x3fff) << 5))
                word(text, 0x608, branch(0x1608, 0x1300, True))
                image[:0x400] = header
                image[0xc00:0x1400] = data
                image[raw:raw+len(text)] = text
                pointer = ctypes.addressof(ctypes.c_ubyte.from_buffer(image))
                result = Preparation()
                status = helper.library.kbl_efi_force_bootloader(pointer, length, ctypes.byref(result))
                expected = (1 << 32) + 0x400
                assert status == 0 and result.file_offset == expected, (status, result.file_offset)
                assert result.load_rva == 0x1600 and result.after == 0x52800028
                updated = bytearray(text)
                word(updated, 0x600, result.after)
                assert image[:0x400] == header and image[0xc00:0x1400] == data
                assert image[raw:raw+len(text)] == updated
                # A high e_lfanew whose subsequent additions cross 32 bits
                # must not wrap into a fake low optional header/section table.
                low = bytearray(fixture[:0x400])
                low[8:8+0xf0] = fixture[0x98:0x188]
                low[0xf8:0xf8+80] = fixture[0x188:0x188+80]
                word(low, 0x3c, 0xfffffff0)
                image[:0x400] = low
                image[0xfffffff0:0x100000008] = fixture[0x80:0x98]
                image[0x100000008:0x1000000f8] = fixture[0x98:0x188]
                prefix = image[:0x400]
                high = image[0xfffffff0:0x1000000f8]
                status = helper.library.kbl_efi_force_bootloader(pointer, length, ctypes.byref(Preparation()))
                assert status == -1 and image[:0x400] == prefix and image[0xfffffff0:0x1000000f8] == high
                return {"applicable": True, "passed": True, "mappedFileBytes": length,
                        "modifiedFileOffset": expected, "modifiedRva": 0x1600,
                        "singleWordCommit": True, "wrappedPeHeaderRejectedUnchanged": True,
                        "method": "sparse file mapping; no full-file allocation or copy"}
    finally:
        path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--real-input", type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    helper = BootloaderHelper(build_library(output))
    clang = shutil.which("clang")
    executable = output / ("test_efi_bootloader.exe" if os.name == "nt" else "test_efi_bootloader")
    subprocess.run([clang, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", str(HERE / "test_efi_bootloader.c"),
                    str(SOURCE), "-o", str(executable)], check=True, capture_output=True)
    host = json.loads(subprocess.run([str(executable)], check=True, capture_output=True, text=True).stdout)
    large_sparse = check_large_sparse(helper, output)
    # Also verify the production helper is freestanding/GPR-only ARM64 code.
    subprocess.run([clang, "--target=aarch64-none-elf", "-ffreestanding", "-fno-builtin", "-mgeneral-regs-only",
                    "-fno-stack-protector", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-c", str(SOURCE),
                    "-o", str(output / "bootloader_helper.aarch64.o")], check=True, capture_output=True)
    import pefile
    from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
    from unicorn.arm64_const import UC_ARM64_REG_X0
    cases = []
    original = helper.fixture()
    forced, evidence = helper.prepare(original)
    assert evidence["file_offset"] == 0x440 and forced != original
    again, repeat = helper.prepare(forced)
    assert again == forced and repeat["already_forced"] == 1
    pe = pefile.PE(data=original)
    for base in (0x10000000, 0x18000000):
        for image, flag, error in ((original, 0, False), (original, 1, False), (forced, 0, False),
                                   (forced, 1, False), (forced, 0, True), (original, 1, True)):
            cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
            cpu.mem_map(base, pe.OPTIONAL_HEADER.SizeOfImage)
            cpu.mem_write(base, pefile.PE(data=image).get_memory_mapped_image())
            memory = 0x50000000
            cpu.mem_map(memory, 0x10000)
            cpu.reg_write(UC_ARM64_REG_X0 + 26, memory)
            cpu.reg_write(UC_ARM64_REG_X0 + 19, (1 << 63) if error else 0)
            cpu.mem_write(memory + 0x33, bytes([flag]))
            stops = []
            def stop(uc, address, size, opaque):
                if address - base in (0x1048, 0x1090, 0x10c0):
                    stops.append(address - base)
                    uc.emu_stop()
            cpu.hook_add(UC_HOOK_CODE, stop)
            cpu.emu_start(base + 0x1040, base + 0x1800, count=32)
            expected = 0x10c0 if error and (image == forced or flag) else 0x1090 if image == forced or flag else 0x1048
            assert stops == [expected], stops
            assert cpu.mem_read(memory + 0x33, 1) == bytes([flag])
            cases.append({"base": hex(base), "forced": image == forced, "originalFlag": flag,
                          "efiError": error, "endpoint": hex(expected), "sourceFieldUnchanged": True})
    real = None
    if args.real_input:
        data = args.real_input.read_bytes()
        before = hashlib.sha256(data).hexdigest()
        transformed, match = helper.prepare(data)
        after = hashlib.sha256(transformed).hexdigest()
        assert args.real_input.read_bytes() == data
        changes = [i for i, (a, b) in enumerate(zip(data, transformed)) if a != b]
        assert changes and all(match["file_offset"] <= i < match["file_offset"] + 4 for i in changes)
        (output / "real-bootloader.img").write_bytes(transformed)
        real = {"sourceSha256": before, "outputSha256": after, "sourceUnchanged": True, "match": match,
                "changedByteOffsets": changes}
        if before == "0831760b4dcbfbd5a296d042db83453b2e5d96bb91668573b6f7ac16c697a491":
            assert after == "b8d88088d23b43a4ef87fcf56c661ef6469c2b860a11228e6999a1c6a9eb051f"
    report = {"passed": True, "hostCases": host["cases"], "largeSparseFile": large_sparse, "unicornCases": cases, "realInput": real,
              "freestandingArm64Build": True, "deviceOperations": False,
              "sourceHashes": {str(p.relative_to(HERE.parents[2])): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in (SOURCE, SOURCE.with_suffix(".h"), HERE / "test_efi_bootloader.c")}}
    (output / "verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({**report, "unicornCases": len(cases)}, indent=2))


if __name__ == "__main__":
    main()
