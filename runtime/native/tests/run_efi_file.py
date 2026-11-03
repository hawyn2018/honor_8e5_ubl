"""Run the real EFI CLI/VFS on large RAMFS input. No device I/O."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
RUNTIME = HERE.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=RUNTIME.parent / 'build/efi-file')
args = parser.parse_args()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
sources = [HERE / 'test_efi_file.c', HERE / 'fs_unavailable.c', RUNTIME / 'kbl_elf.c',
           RUNTIME / 'kbl_sha256.c', RUNTIME / 'native/kbl_efi_bootloader.c']
command = [shutil.which('clang'), '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
           *map(str, sources), '-o', str(out / 'test_efi_file.exe')]
build = subprocess.run(command, capture_output=True)
(out / 'build.log').write_bytes(build.stdout + build.stderr)
build.check_returncode()
result = subprocess.run([str(out / 'test_efi_file.exe')], capture_output=True)
(out / 'run.log').write_bytes(result.stdout + result.stderr)
result.check_returncode()
report = json.loads(result.stdout)
assert report['passed'] and report['largeFileBytes'] > 128 * 1024 * 1024
assert report['liveAllocations'] == 0
report['scope'] = 'Actual production CLI/VFS; host allocator/EL/EFI-provider models; no device I/O.'
report['sourceSha256'] = {str(p.relative_to(RUNTIME)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in sources + [RUNTIME / 'native/kbl_native.c', RUNTIME / 'native/kbl_native.h',
                        RUNTIME / 'native/kbl_efi_command.inc', RUNTIME / 'native/kbl_vfs.inc']}
(out / 'validation.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, indent=2))
