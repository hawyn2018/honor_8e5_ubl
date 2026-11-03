"""Validate the native USB binding against an explicit synthetic provider."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import re
from unicorn import UC_HOOK_CODE
import unicorn.arm64_const as arm64
from run_adb_usb import load_elf,call

HERE=Path(__file__).resolve().parent
RUNTIME=HERE.parents[1]
SOURCE=RUNTIME.parent
WORKSPACE=SOURCE.parents[2]

def run(command):return subprocess.run([str(x)for x in command],capture_output=True,text=True,check=True).stdout
def execute_arm64(path,base,host):
    cpu,symbols=load_elf(path,base);arena=0x50000000;arena_bytes=0x1000000
    cpu.mem_map(arena,arena_bytes);state={'used':0,'allocations':0,'releases':0,'live':{},'failureLine':None}
    def ret(value=0):
        cpu.reg_write(arm64.UC_ARM64_REG_X0,value);cpu.reg_write(arm64.UC_ARM64_REG_PC,cpu.reg_read(arm64.UC_ARM64_REG_X30))
    def hook(_,address,size,user):
        if address==symbols['malloc']:
            n=cpu.reg_read(arm64.UC_ARM64_REG_X0);at=(state['used']+15)&~15
            assert n and at<=arena_bytes and n<=arena_bytes-at
            pointer=arena+at;state['live'][pointer]=n;state['used']=at+n;state['allocations']+=1;ret(pointer)
        elif address==symbols['free']:
            pointer=cpu.reg_read(arm64.UC_ARM64_REG_X0);assert pointer in state['live'],hex(pointer)
            del state['live'][pointer];state['releases']+=1;ret()
        elif address==symbols['printf']:ret()
        elif address==symbols['fprintf']:
            state['failureLine']=cpu.reg_read(arm64.UC_ARM64_REG_X2);ret()
        elif address==symbols['exit']:
            raise AssertionError(f'ARM64 fixture failed line {state["failureLine"]}, exit={cpu.reg_read(arm64.UC_ARM64_REG_X0)}')
    cpu.hook_add(UC_HOOK_CODE,hook)
    rc=call(cpu,symbols['main']);assert rc==0,rc
    counters={name:int.from_bytes(cpu.mem_read(symbols[name],4),'little')for name in ['cases','assertions','allocations','frees']}
    assert counters['cases']==host['cases'] and counters['assertions']==host['assertions'],counters
    assert counters['allocations']==counters['frees']==host['dma_allocations']
    assert state['allocations']==state['releases'] and not state['live'],state
    observed=call(cpu,symbols['native_usb_fixture_current_el']);assert observed==1,observed
    return {'passed':True,'base':hex(base),'observedEl':observed,**counters,
            'fixtureAllocations':state['allocations'],'fixtureReleases':state['releases']}
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--llvm-bin',type=Path,default=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin'))
    p.add_argument('--host-clang',default='clang')
    p.add_argument('--output',type=Path,default=WORKSPACE/'analysis/kbl-services-20261030/storage/native-usb-tests')
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    sources=[HERE.parent/'kbl_usb_native.c',HERE.parent/'kbl_usb.c']
    commands=[];exe=a.output/'test_native_usb.exe'
    commands.append([a.host_clang,'-std=c11','-O2','-Wall','-Wextra','-Werror',HERE/'test_native_usb.c',sources[1],'-o',exe]);run(commands[-1])
    host=json.loads(run([exe]));objects=[]
    for s in sources:
        obj=a.output/(s.stem+'.o');objects.append(obj)
        cmd=[a.llvm_bin/'clang.exe','--target=aarch64-none-elf','-std=c11','-O2','-ffreestanding',
             '-fno-builtin','-fno-stack-protector','-ffixed-x18','-mgeneral-regs-only','-Wall','-Wextra','-Werror','-c',s,'-o',obj]
        commands.append(cmd);run(cmd)
    combined=a.output/'native_usb_combined.o'
    cmd=[a.llvm_bin/'ld.lld.exe','-r','-o',combined,*objects];commands.append(cmd);run(cmd)
    undefined=run([a.llvm_bin/'llvm-nm.exe','--undefined-only',combined]).strip();assert not undefined,undefined
    disassembly=run([a.llvm_bin/'llvm-objdump.exe','-d',combined])
    forbidden=re.findall(r'\b(?:svc|hvc|smc|eret|fmov|fadd|fsub|fmul|fdiv)\b',disassembly)
    assert not forbidden,forbidden
    executable=a.output/'native_usb_fixture.elf'
    cmd=[a.llvm_bin/'clang.exe','--target=aarch64-none-elf','-std=c11','-O2','-ffreestanding',
         '-fno-builtin','-fno-stack-protector','-fPIC','-ffixed-x18','-mgeneral-regs-only',
         '-Wall','-Wextra','-Werror','-DKBL_NATIVE_USB_FREESTANDING','-fuse-ld=lld','-nostdlib',
         '-Wl,-shared,-Bsymbolic,--no-undefined',HERE/'test_native_usb.c',
         HERE/'native_usb_fixture_libc.c',sources[1],'-o',executable]
    commands.append(cmd);run(cmd)
    arm64_cases=[execute_arm64(executable,base,host)for base in [0x10000000,0x18000000]]
    paths=sources+[HERE.parent/'kbl_usb_native.h',HERE.parent/'kbl_usb.h',HERE.parent/'kbl_service.h',
                   HERE.parent/'kbl_efi_types.h',HERE/'test_native_usb.c',
                   HERE/'native_usb_fixture_libc.c',HERE/'native_usb_fixture_libc.h',Path(__file__)]
    report={'scope':'Synthetic EFI/USB provider, actual C observer and sender state machines. No phone, parent firmware, physical USB/DMA or boot claim.',
            'passed':True,'host':host,'actualArm64ModelCases':arm64_cases,
            'undefined_symbols':undefined,'forbidden_instructions':forbidden,
            'source_sha256':{str(s.relative_to(SOURCE)):hashlib.sha256(s.read_bytes()).hexdigest()for s in paths},
            'combined_sha256':hashlib.sha256(combined.read_bytes()).hexdigest(),
            'commands':[[str(x)for x in cmd]for cmd in commands],
            'remaining_integration':['Resolve actual common Fbd slot, ACK buffer and normal RX classification by data flow.',
            'Bind persistent parent environment, original native final and passive counter.',
            'Observe only after explicit KBL start, verify real ACK completion and fresh RX epoch before FETCH.',
            'Validate physical DATA framing, next ordinary command, disconnect, DMA lifetime and observer-off on hardware.']}
    (a.output/'validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':True,'cases':host['cases'],'assertions':host['assertions'],'report':str(a.output/'validation.json')},indent=2))
if __name__=='__main__':main()
