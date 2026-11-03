"""Build/test FatFs against memory-backed real filesystem images; no USB IO."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT.parent / "fat-test-output"
OUT.mkdir(parents=True, exist_ok=True)
clang = shutil.which("clang")
if not clang:
    raise SystemExit("clang required")
exe = OUT / "fat_host_test.exe"
sources = [ROOT / "runtime/fs/tests/fat_host_test.c", ROOT / "runtime/fs/fat.c",
           ROOT / "third_party/fatfs/ff.c", ROOT / "third_party/fatfs/ffunicode.c"]
subprocess.run([clang, "-std=c11", "-O2", "-fno-builtin", "-Wall", "-Wextra", "-Werror",
                "-DKBL_FATFS_TEST_MKFS=1", "-I", str(ROOT / "third_party/fatfs"),
                *map(str, sources), "-o", str(exe)], check=True)
result = subprocess.run([str(exe), str(OUT)], text=True, capture_output=True)
print(result.stdout, end="")
print(result.stderr, end="", file=sys.stderr)
(OUT / "fat-host-result.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
if result.returncode:
    raise SystemExit(result.returncode)
manifest = {p.name: {"bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in sorted(OUT.glob("*.img"))}
(OUT / "fat-fixtures-sha256.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
