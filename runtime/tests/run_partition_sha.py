"""Offline UEFI partition/SHA fixture build. Never accesses a device."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

HERE = Path(__file__).resolve().parent
RUNTIME = HERE.parent
ROOT = HERE.parents[4]
DEFAULT_CC = ("C:/Users/happy/AppData/Local/Microsoft/WinGet/Packages/"
              "MartinStorsjo.LLVM-MinGW.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe/"
              "llvm-mingw-20260616-ucrt-x86_64/bin/clang.exe")
DEFAULT_ARM_CC = "D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clang", default=DEFAULT_CC)
    parser.add_argument("--arm-clang", default=DEFAULT_ARM_CC)
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/kbl-services-20261030/storage/provider-tests")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    sources = [("partition", RUNTIME / "uefi/kbl_partition.c"), ("partition_rw", RUNTIME / "uefi/kbl_partition.c"), ("sha256", RUNTIME / "kbl_sha256.c")]
    for name, source in sources:
        executable = args.output / ("test_" + name + ".exe")
        commands = [
            [args.clang, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", str(source),
             str(HERE / ("test_" + name + ".c")), "-o", str(executable)],
            [str(executable)],
            [args.arm_clang, "--target=aarch64-none-elf", "-std=c11", "-O2", "-ffreestanding", "-fno-builtin",
             "-fno-stack-protector", "-mgeneral-regs-only", "-ffixed-x18", "-Wall", "-Wextra", "-Werror", "-c", str(source),
             "-o", str(args.output / (name + ".aarch64.o"))],
        ]
        for argv in commands:
            result = subprocess.run(argv, capture_output=True, text=True, check=False)
            results.append({"argv": argv, "returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
            if result.stdout:
                print(result.stdout, end="")
            if result.returncode:
                raise RuntimeError(json.dumps(results[-1], indent=2))
    paths = [p for _, p in sources] + [RUNTIME / "uefi/kbl_partition.h", RUNTIME / "kbl_sha256.h",
             RUNTIME / "kbl_block.h", HERE / "test_partition.c", HERE / "test_partition_rw.c", HERE / "test_sha256.c"]
    report = {"scope": "Mock UEFI BlockIO byte read/write/RMW/list/flush and SHA KAT; no physical device writes", "passed": all(r["returncode"] == 0 for r in results),
              "results": results, "sources": [{"path": str(p.relative_to(ROOT)),
              "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in paths]}
    (args.output / "validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
