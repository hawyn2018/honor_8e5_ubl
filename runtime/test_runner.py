"""Compile/run the real C controller against an explicit host fake backend.

The additional AArch64 build is freestanding code-generation validation only.
It neither installs an image nor implements/tests a UEFI backend or microkernel.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys

RUNTIME = Path(__file__).resolve().parent
WORKSPACE = next((p for p in RUNTIME.parents if (p / 'platform-tools/adb.exe').is_file()), None)
DEFAULT_OUT = (WORKSPACE / 'analysis/kbl-runtime-20261029/control-tests' if WORKSPACE
               else RUNTIME.parent / 'build/kbl-tests')
NDK_BIN = Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin')

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def run(argv, output, label):
    result = subprocess.run([str(a) for a in argv], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=90)
    stdout = result.stdout.decode('utf-8', errors='replace')
    stderr = result.stderr.decode('utf-8', errors='replace')
    (output / f'{label}.stdout').write_text(stdout, encoding='utf-8')
    (output / f'{label}.stderr').write_text(stderr, encoding='utf-8')
    return {'command': [str(a) for a in argv], 'exit': result.returncode,
            'stdout': stdout, 'stderr': stderr}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUT)
    parser.add_argument('--host-clang', type=Path, help='Native host C compiler (defaults to clang on PATH)')
    parser.add_argument('--ndk-bin', type=Path, default=NDK_BIN, help='LLVM tools directory for the ARM64 object check')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    host_clang = args.host_clang or shutil.which('clang.exe') or shutil.which('clang')
    if not host_clang:
        raise SystemExit('LLVM-MinGW clang not found')
    report = {'scope': 'Real C controller/fetch modules; explicit deterministic fake backend and wire. No phone/UEFI/runtime boot test.',
              'sources': {p.name: digest(p) for p in [RUNTIME/'kbl_api.h', RUNTIME/'kbl_control.c',
                  RUNTIME/'kbl_fetch.h', RUNTIME/'kbl_fetch.c', RUNTIME/'tests/test_control.c', RUNTIME/'tests/test_fetch.c']},
              'steps': {}}
    report['steps']['host_compile'] = run([host_clang, '-std=c11', '-O1', '-Wall', '-Wextra', '-Werror',
        '-I', RUNTIME, RUNTIME/'kbl_control.c', RUNTIME/'tests/test_control.c',
        '-o', output/'test_control.exe'], output, 'host-compile')
    if report['steps']['host_compile']['exit'] == 0:
        report['steps']['host_tests'] = run([output/'test_control.exe'], output, 'host-tests')
        match = re.search(r'RESULT tests=(\d+) failed=(\d+) assertions=(\d+) callback_errors=(\d+)',
                          report['steps']['host_tests']['stdout'])
        if match:
            report['test_counts'] = dict(zip(['tests', 'failed', 'assertions', 'callback_errors'], map(int, match.groups())))
    report['steps']['fetch_host_compile'] = run([host_clang, '-std=c11', '-O1', '-Wall', '-Wextra', '-Werror',
        '-I', RUNTIME, RUNTIME/'kbl_control.c', RUNTIME/'kbl_fetch.c', RUNTIME/'tests/test_fetch.c',
        '-o', output/'test_fetch.exe'], output, 'fetch-host-compile')
    if report['steps']['fetch_host_compile']['exit'] == 0:
        report['steps']['fetch_host_tests'] = run([output/'test_fetch.exe'], output, 'fetch-host-tests')
        match = re.search(r'RESULT tests=(\d+) failed=(\d+) assertions=(\d+) callback_errors=(\d+)',
                          report['steps']['fetch_host_tests']['stdout'])
        if match:
            report['fetch_test_counts'] = dict(zip(['tests', 'failed', 'assertions', 'callback_errors'], map(int, match.groups())))
    def llvm(name):
        return args.ndk_bin / (name + '.exe' if (args.ndk_bin / (name + '.exe')).is_file() else name)
    report['steps']['arm64_compile'] = run([llvm('clang'), '--target=aarch64-none-elf',
        '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-ffreestanding', '-fno-builtin',
        '-fno-stack-protector', '-fPIC', '-mgeneral-regs-only', '-I', RUNTIME,
        '-c', RUNTIME/'kbl_control.c', '-o', output/'kbl_control.aarch64.o'], output, 'arm64-compile')
    report['steps']['fetch_arm64_compile'] = run([llvm('clang'), '--target=aarch64-none-elf',
        '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-ffreestanding', '-fno-builtin',
        '-fno-stack-protector', '-fPIC', '-mgeneral-regs-only', '-I', RUNTIME,
        '-c', RUNTIME/'kbl_fetch.c', '-o', output/'kbl_fetch.aarch64.o'], output, 'fetch-arm64-compile')
    if report['steps']['arm64_compile']['exit'] == 0 and report['steps']['fetch_arm64_compile']['exit'] == 0:
        report['steps']['arm64_relocatable_link'] = run([llvm('ld.lld'), '-r',
            output/'kbl_control.aarch64.o', output/'kbl_fetch.aarch64.o',
            '-o', output/'kbl_combined.aarch64.o'], output, 'arm64-link')
    if report['steps'].get('arm64_relocatable_link', {}).get('exit') == 0:
        report['steps']['arm64_undefined_symbols'] = run([llvm('llvm-nm'), '--undefined-only',
            output/'kbl_combined.aarch64.o'], output, 'arm64-undefined')
        report['steps']['arm64_disassembly'] = run([llvm('llvm-objdump'), '-d',
            output/'kbl_combined.aarch64.o'], output, 'arm64-disassembly')
        asm = report['steps']['arm64_disassembly']['stdout']
        report['arm64_checks'] = {
            'object_sha256': digest(output/'kbl_combined.aarch64.o'),
            'svc_instructions': len(re.findall(r'\bsvc\b', asm)),
            'hvc_instructions': len(re.findall(r'\bhvc\b', asm)),
            'smc_instructions': len(re.findall(r'\bsmc\b', asm)),
            'eret_instructions': len(re.findall(r'\beret(?:aa|ab)?\b', asm)),
            'undefined_symbols': report['steps']['arm64_undefined_symbols']['stdout'].strip(),
            'limitation': 'No direct Linux SVC or EL-transition instructions in this object is not a real same-EL UEFI/backend test.'}
    failures = [name for name, step in report['steps'].items() if step['exit'] != 0]
    traps = report.get('arm64_checks', {})
    report['passed'] = (not failures and report.get('test_counts', {}).get('failed') == 0 and
                        report.get('fetch_test_counts', {}).get('failed') == 0 and
                        traps.get('undefined_symbols', '!') == '' and
                        all(traps.get(k, -1) == 0 for k in ['svc_instructions', 'hvc_instructions',
                            'smc_instructions', 'eret_instructions']))
    (output/'validation.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': report['passed'], 'failures': failures,
                      'counts': report.get('test_counts'), 'fetch_counts': report.get('fetch_test_counts'),
                      'arm64': report.get('arm64_checks'),
                      'report': str(output/'validation.json')}, indent=2))
    return 0 if report['passed'] else 1

if __name__ == '__main__':
    sys.exit(main())
