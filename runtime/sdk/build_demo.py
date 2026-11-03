"""Build an explicit KBL ABI ARM64 program; no Linux syscalls or UEFI provider."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

SDK=Path(__file__).resolve().parent
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--llvm-bin',type=Path,default=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin'))
    p.add_argument('--source',type=Path,default=SDK.parent/'examples/hello_yield.c')
    p.add_argument('--output',type=Path,default=SDK.parent.parent/'build/kbl-sdk')
    a=p.parse_args(); out=a.output.resolve(); out.mkdir(parents=True,exist_ok=True)
    tool=lambda name:a.llvm_bin/(name+'.exe' if (a.llvm_bin/(name+'.exe')).exists() else name)
    commands=[
        [tool('clang'),'--target=aarch64-none-elf','-std=c11','-O2','-ffreestanding','-fno-builtin',
         '-fno-stack-protector','-fPIC','-fvisibility=hidden','-mgeneral-regs-only','-ffixed-x18','-fno-unwind-tables',
         '-fno-asynchronous-unwind-tables','-Wall','-Wextra','-Werror','-I',SDK,'-c',a.source,'-o',out/'program.o'],
        [tool('ld.lld'),'-shared','-Bsymbolic','--no-undefined','-z','max-page-size=4096','-z','norelro',
         '-T',SDK/'kbl.ld','-e','kbl_main','-o',out/'program.kbl',out/'program.o']]
    for cmd in commands:
        subprocess.run([str(v)for v in cmd],check=True)
    data=(out/'program.kbl').read_bytes()
    manifest={'scope':'KBL ABI1 freestanding AArch64 ET_DYN; not Linux ABI, not firmware integration',
              'commands':[[str(v)for v in cmd]for cmd in commands],
              'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(manifest,indent=2))
if __name__=='__main__':main()
