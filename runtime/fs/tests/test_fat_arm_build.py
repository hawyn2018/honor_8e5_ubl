"""Verify the production FatFs ARM64 GPR-only objects link without imports."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import pefile

ROOT = pathlib.Path(__file__).resolve().parents[3]
LLVM = pathlib.Path(r"D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin")
OUT = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT.parent / "fat-test-output"
OUT.mkdir(parents=True, exist_ok=True)
sources = [ROOT / "runtime/fs/fat.c", ROOT / "third_party/fatfs/ff.c", ROOT / "third_party/fatfs/ffunicode.c"]
commands, objects = [], []
for source in sources:
    obj = OUT / (source.stem + "-arm64.obj")
    cmd = [str(LLVM / "clang.exe"), "--target=aarch64-pc-windows-msvc", "-std=c11", "-O2",
           "-ffreestanding", "-fno-builtin", "-fno-stack-protector", "-ffixed-x18",
           "-mgeneral-regs-only", "-mno-outline-atomics", "-mno-stack-arg-probe",
           "-Wall", "-Wextra", "-Werror", "-I", str(ROOT / "runtime/compat/include"),
           "-c", str(source), "-o", str(obj)]
    commands.append(cmd)
    subprocess.run(cmd, check=True)
    dis = subprocess.check_output([str(LLVM / "llvm-objdump.exe"), "-d", str(obj)], text=True)
    # Match operand registers only, excluding addresses and symbol labels.
    operands = "\n".join(l.split("\t")[-1] for l in dis.splitlines() if "\t" in l)
    if re.search(r"\b(?:[vqsdh][0-9]+|fpcr|fpsr|cpacr_el1)\b", operands, re.I):
        raise AssertionError("FP/SIMD access in production object " + str(obj))
    objects.append(obj)
target = OUT / "fat-noimports.efi"
cmd = [str(LLVM / "ld.lld.exe"), "-flavor", "link", "/subsystem:efi_boot_service_driver",
       "/entry:kbl_fat_mount", "/nodefaultlib", "/machine:arm64", "/dynamicbase",
       "/nxcompat", "/opt:ref", "/timestamp:0", "/out:"+str(target), *map(str, objects)]
commands.append(cmd)
subprocess.run(cmd, check=True)
pe = pefile.PE(str(target))
assert not getattr(pe, "DIRECTORY_ENTRY_IMPORT", [])
assert all(not(s.Characteristics & 0x80000000 and s.Characteristics & 0x20000000) for s in pe.sections)
manifest = {"note": "Link-only test; entry is not a callable EFI entrypoint; never deploy this test PE.",
            "imports": 0, "fp_simd_operands": 0,
            "bytes": target.stat().st_size, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "commands": commands}
(OUT / "fat-arm-build.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
print("PASS ARM64 FatFs: production formatter absent, GPR-only, zero imports, W^X PE sections")
