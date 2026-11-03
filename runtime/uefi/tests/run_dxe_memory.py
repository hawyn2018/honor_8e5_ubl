"""Validate the private-page DXE fallback against explicit ABI models."""
from pathlib import Path
import argparse,hashlib,json,subprocess
HERE=Path(__file__).resolve().parent
SOURCE=HERE.parents[2]
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--host-clang',default='clang')
    p.add_argument('--llvm-bin',type=Path,default=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin'))
    p.add_argument('--report',type=Path,default=SOURCE.parents[2]/'analysis/kbl-services-20261030/storage/memory-tests/validation.json')
    p.add_argument('--output',type=Path,default=SOURCE/'build/kbl-memory')
    a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    c=HERE.parent/'kbl_dxe_memory.c';h=HERE.parent/'kbl_dxe_memory.h';test=HERE/'test_dxe_memory.c'
    command=[a.host_clang,'-std=c11','-O2','-Wall','-Wextra','-Werror',str(c),str(HERE.parent/'kbl_arch_memory.c'),str(test),'-o',str(out/'test.exe')]
    subprocess.run(command,check=True);r=json.loads(subprocess.check_output([str(out/'test.exe')],text=True))
    arch_command=[a.host_clang,'-std=c11','-O2','-Wall','-Wextra','-Werror',str(HERE.parent/'kbl_arch_memory.c'),str(HERE/'test_arch_memory.c'),'-o',str(out/'arch-test.exe')]
    subprocess.run(arch_command,check=True);arch_result=json.loads(subprocess.check_output([str(out/'arch-test.exe')],text=True))
    arm=[str(a.llvm_bin/'clang.exe'),'--target=aarch64-none-elf','-std=c11','-O2','-Wall','-Wextra','-Werror',
         '-ffreestanding','-fno-builtin','-fno-stack-protector','-mgeneral-regs-only','-ffixed-x18','-c',str(c),'-o',str(out/'memory.o')]
    subprocess.run(arm,check=True)
    arch_arm=arm.copy();arch_arm[arch_arm.index(str(c))]=str(HERE.parent/'kbl_arch_memory.c');arch_arm[-1]=str(out/'arch-memory.o');subprocess.run(arch_arm,check=True)
    report={**r,'archModel':arch_result,'host_command':command,'arch_host_command':arch_command,'arm64_command':arm,'arch_arm64_command':arch_arm,'source_sha256':{str(x.relative_to(SOURCE)):hashlib.sha256(x.read_bytes()).hexdigest()for x in [c,h,HERE.parent/'kbl_arch_memory.c',HERE.parent/'kbl_arch_memory.h',test,HERE/'test_arch_memory.c',Path(__file__)]},
        'primary_sources':['https://github.com/tianocore/edk2/blob/master/MdePkg/Include/Pi/PiDxeCis.h',
            'https://github.com/tianocore/edk2/blob/master/MdePkg/Include/Guid/DxeServices.h',
            'https://github.com/tianocore/edk2/blob/master/MdeModulePkg/Core/Dxe/Gcd/Gcd.c'],
        'limits':['Caller must own every affected page. No capabilities/cache policy changes.',
            'A provider failure may leave earlier private pages protected; caller retains/frees the allocation.',
            'GCD readback and successful platform call are checked; actual MMU/page protection is not proved by this model.',
            'Missing/denied capabilities fail explicitly, never permit a RWX execution fallback.']}
    a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'passed':r['passed'],'cases':r['cases'],'report':str(a.report)}))
if __name__=='__main__':main()
