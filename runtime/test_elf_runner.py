"""Build the KBL SDK demo, host-test the loader, execute ARM64 code in Unicorn."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys

RUNTIME=Path(__file__).resolve().parent
WORKSPACE=next((p for p in RUNTIME.parents if(p/'platform-tools/adb.exe').exists()),None)
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--llvm-bin',type=Path,default=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin'))
    p.add_argument('--host-clang',default=shutil.which('clang.exe')or shutil.which('clang'))
    p.add_argument('--output',type=Path,default=(WORKSPACE/'analysis/kbl-runtime-20261029/elf-tests'if WORKSPACE else RUNTIME.parent/'build/kbl-elf-tests'))
    a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    tool=lambda n:a.llvm_bin/(n+'.exe'if(a.llvm_bin/(n+'.exe')).exists()else n)
    report={'scope':'Actual loader + SDK; host tests and Unicorn. Not UEFI integration, Linux ELF compatibility or hardware boot.',
            'sources':{str(f.relative_to(RUNTIME)):hashlib.sha256(f.read_bytes()).hexdigest()for f in
                [RUNTIME/'kbl_elf.h',RUNTIME/'kbl_elf.c',RUNTIME/'sdk/kbl_program.h',RUNTIME/'sdk/kbl.ld',RUNTIME/'examples/hello_yield.c',
                 RUNTIME/'examples/uefi_environment.c',RUNTIME/'tests/test_elf.c']},'steps':{}}
    def step(name,cmd):
        result=subprocess.run([str(x)for x in cmd],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
        item={'command':[str(x)for x in cmd],'exit':result.returncode,'stdout':result.stdout.decode(errors='replace'),'stderr':result.stderr.decode(errors='replace')}
        report['steps'][name]=item
        (out/(name+'.stdout')).write_text(item['stdout'],encoding='utf-8');(out/(name+'.stderr')).write_text(item['stderr'],encoding='utf-8')
        if result.returncode:raise RuntimeError(name+': '+item['stderr'])
        return item['stdout']
    try:
        step('sdk_build',[sys.executable,RUNTIME/'sdk/build_demo.py','--llvm-bin',a.llvm_bin,'--output',out/'sdk'])
        step('uefi_sdk_build',[sys.executable,RUNTIME/'sdk/build_demo.py','--llvm-bin',a.llvm_bin,
            '--source',RUNTIME/'examples/uefi_environment.c','--output',out/'uefi-sdk'])
        step('host_compile',[a.host_clang,'-std=c11','-O1','-Wall','-Wextra','-Werror','-I',RUNTIME,RUNTIME/'kbl_elf.c',RUNTIME/'tests/test_elf.c','-o',out/'test_elf.exe'])
        stdout=step('host_tests',[out/'test_elf.exe',out/'sdk/program.kbl'])
        m=re.search(r'RESULT tests=(\d+) failed=(\d+) assertions=(\d+) provider_errors=(\d+)',stdout)
        if not m:raise RuntimeError('No host test summary')
        report['host_counts']=dict(zip(['tests','failed','assertions','provider_errors'],map(int,m.groups())))
        step('capacity_compile',[a.host_clang,'-std=c11','-O1','-Wall','-Wextra','-Werror','-I',RUNTIME,RUNTIME/'kbl_elf.c',RUNTIME/'tests/test_elf_capacity.c','-o',out/'test_elf_capacity.exe'])
        report['capacity']=json.loads(step('capacity_tests',[out/'test_elf_capacity.exe']))
        step('arm64_compile',[tool('clang'),'--target=aarch64-none-elf','-std=c11','-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-fPIC',
             '-mgeneral-regs-only','-ffixed-x18','-Wall','-Wextra','-Werror','-I',RUNTIME,'-c',RUNTIME/'kbl_elf.c','-o',out/'loader.o'])
        script=out/'loader.ld';script.write_text('SECTIONS { . = 0x100000; .text : { *(.text .text.*) } . = ALIGN(4096); .rodata : { *(.rodata .rodata.*) } . = ALIGN(4096); .data : { *(.data .data.*) *(.got .got.*) } .bss : { *(.bss .bss.*) } /DISCARD/ : { *(.comment) *(.eh_frame*) } }',encoding='utf-8')
        step('arm64_link',[tool('ld.lld'),'-T',script,'-e','kbl_elf_load','--no-undefined','-o',out/'loader.elf',out/'loader.o'])
        undefined=step('undefined',[tool('llvm-nm'),'--undefined-only',out/'loader.elf']);report['undefined_symbols']=undefined.strip()
        step('unicorn',[sys.executable,RUNTIME/'tests/elf_unicorn.py',out/'loader.elf',out/'sdk/program.kbl',out/'unicorn.json',out/'uefi-sdk/program.kbl'])
        report['unicorn']=json.loads((out/'unicorn.json').read_text())
        report['program_sha256']=hashlib.sha256((out/'sdk/program.kbl').read_bytes()).hexdigest()
        report['passed']=report['host_counts']['failed']==0 and report['capacity']['passed'] and not report['undefined_symbols'] and report['unicorn']['passed']
    except Exception as ex:report['passed']=False;report['error']=repr(ex)
    (out/'validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':report['passed'],'host_counts':report.get('host_counts'),'unicorn_cases':report.get('unicorn',{}).get('cases'),
                      'error':report.get('error'),'report':str(out/'validation.json')},indent=2))
    return 0 if report['passed']else 1
if __name__=='__main__':raise SystemExit(main())
