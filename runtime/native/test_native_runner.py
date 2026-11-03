"""Compile and execute the real AArch64 native backend and fiber in Unicorn.

Only allocation, executable-page attributes and cache-provider callbacks are
emulator models. RAMFS, command parsing, loader, program, and context switches
execute as AArch64 instructions. This is not UEFI/USB or phone testing.
"""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import subprocess
import unicorn.arm64_const as arm
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
    UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X5, UC_ARM64_REG_X30,
    UC_ARM64_REG_PC, UC_ARM64_REG_SP, UC_ARM64_REG_PSTATE, UC_ARM64_REG_CPACR_EL1)

HERE=Path(__file__).resolve().parent
RUNTIME=HERE.parent
LLVM=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin')

def build(out):
    out.mkdir(parents=True,exist_ok=True)
    subprocess.run(['python','-X','utf8',str(RUNTIME/'sdk/build_demo.py'),'--output',str(out/'demo')],check=True,capture_output=True)
    demo=(out/'demo/program.kbl').read_bytes()
    subprocess.run(['python','-X','utf8',str(RUNTIME/'sdk/build_demo.py'),'--source',str(HERE/'tests/file_program.c'),'--output',str(out/'file-demo')],check=True,capture_output=True)
    file_demo=(out/'file-demo/program.kbl').read_bytes()
    subprocess.run(['python','-X','utf8',str(RUNTIME/'sdk/build_demo.py'),'--source',str(HERE/'tests/memory_program.c'),'--output',str(out/'memory-demo')],check=True,capture_output=True)
    memory_demo=(out/'memory-demo/program.kbl').read_bytes()
    subprocess.run(['python','-X','utf8',str(RUNTIME/'sdk/build_port.py'),'--source',str(HERE/'tests/memory_port_program.c'),'--output',str(out/'memory-port')],check=True,capture_output=True)
    memory_port=(out/'memory-port/program.kbl').read_bytes()
    embedded=out/'demo_blob.c'
    embedded.write_text('const unsigned char demo_program[] = {'+','.join(str(b)for b in demo)+'};\nconst unsigned long demo_program_bytes=sizeof(demo_program);\n'+
        'const unsigned char file_program[] = {'+','.join(str(b)for b in file_demo)+'};\nconst unsigned long file_program_bytes=sizeof(file_program);\n'+
        'const unsigned char memory_program[] = {'+','.join(str(b)for b in memory_demo)+'};\nconst unsigned long memory_program_bytes=sizeof(memory_program);\n'+
        'const unsigned char memory_port_program[] = {'+','.join(str(b)for b in memory_port)+'};\nconst unsigned long memory_port_program_bytes=sizeof(memory_port_program);\n',encoding='ascii')
    sources=[HERE/'kbl_native.c',HERE/'kbl_efi_bootloader.c',HERE/'tests/fs_unavailable.c',RUNTIME/'kbl_elf.c',RUNTIME/'kbl_sha256.c',HERE/'tests/native_fixture.c',
             HERE/'kbl_context_switch.S',HERE/'tests/provider_stubs.S',embedded]
    objects=[]
    for i,source in enumerate(sources):
        obj=out/f'part{i}.o'; objects.append(obj)
        cmd=[str(LLVM/'clang.exe'),'--target=aarch64-none-elf','-O2','-ffreestanding','-fno-builtin',
             '-fno-stack-protector','-ffixed-x18','-mgeneral-regs-only','-fno-unwind-tables',
             '-fno-asynchronous-unwind-tables','-Wall','-Wextra','-Werror','-I',str(RUNTIME),
             '-c',str(source),'-o',str(obj)]
        if source.suffix=='.S': cmd.remove('-mgeneral-regs-only')
        result=subprocess.run(cmd,capture_output=True,text=True)
        if result.returncode: raise RuntimeError(result.stderr)
    script=out/'test.ld'
    script.write_text('ENTRY(native_test_entry)\nSECTIONS { . = 0x10000000; .text : { *(.text*) } .rodata : { *(.rodata*) } .data : { *(.data*) } .bss : { *(.bss*) *(COMMON) } /DISCARD/ : { *(.comment) *(.note*) } }\n',encoding='ascii')
    elf=out/'native.elf'
    result=subprocess.run([str(LLVM/'ld.lld.exe'),'-T',str(script),'--no-undefined','-o',str(elf),*[str(o)for o in objects]],capture_output=True,text=True)
    if result.returncode: raise RuntimeError(result.stderr)
    return elf

def run(elf,el):
    cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    with elf.open('rb') as stream:
        image=ELFFile(stream)
        loads=[s for s in image.iter_segments() if s['p_type']=='PT_LOAD']
        begin=min(s['p_vaddr']for s in loads)&~4095
        end=max(s['p_vaddr']+s['p_memsz']for s in loads)
        cpu.mem_map(begin,(end-begin+4095)&~4095)
        for segment in loads: cpu.mem_write(segment['p_vaddr'],segment.data())
        syms={s.name:s['st_value']for s in image.get_section_by_name('.symtab').iter_symbols()}
        entry=image['e_entry']
    stack=0x40000000; cpu.mem_map(stack,0x20000)
    stop=0x41000000; cpu.mem_map(stop,4096); cpu.mem_write(stop,b'\x00\x00\x20\xd4')
    cpu.reg_write(UC_ARM64_REG_SP,stack+0x1f000)
    cpu.reg_write(UC_ARM64_REG_X30,stop)
    cpu.reg_write(UC_ARM64_REG_PSTATE,0x3c0|(el<<2)|1)
    cpu.reg_write(UC_ARM64_REG_CPACR_EL1,0) # Pure-GPR profile must not require FP access.
    state={'cursor':0x50000000,'allocs':{},'alloc_calls':0,'released':0,
           'fail_after':None,'largest_data_allocation':0,'protects':0,'icaches':0,'errors':[]}
    regs=[UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3,UC_ARM64_REG_X4,UC_ARM64_REG_X5]
    def ret(value=0):
        cpu.reg_write(UC_ARM64_REG_X0,value&((1<<64)-1))
        cpu.reg_write(UC_ARM64_REG_PC,cpu.reg_read(UC_ARM64_REG_X30))
    def allocate(size,alignment,kind):
        state['alloc_calls']+=1
        if kind=='data':state['largest_data_allocation']=max(size,state['largest_data_allocation'])
        if state['fail_after'] is not None:
            if state['fail_after']==0: return 0
            state['fail_after']-=1
        assert size and alignment and alignment&(alignment-1)==0
        address=(state['cursor']+max(4096,alignment)-1)&~(max(4096,alignment)-1)
        mapped=(size+4095)&~4095
        cpu.mem_map(address,mapped,UC_PROT_READ|UC_PROT_WRITE)
        state['allocs'][address]=(size,mapped,kind)
        state['cursor']=address+mapped+4096
        return address
    def release(address,size=None,kind=None):
        assert address in state['allocs'],f'double/foreign free {address:x}'
        actual,mapped,actual_kind=state['allocs'].pop(address)
        assert size is None or size==actual
        assert kind is None or kind==actual_kind
        cpu.mem_unmap(address,mapped); state['released']+=1
    def provider(_,address,size,user):
        name=user; a=[cpu.reg_read(r)for r in regs]
        try:
            if name=='test_allocate':
                fail_large=struct.unpack('<I',cpu.mem_read(syms['native_memory_fail_large'],4))[0]
                ret(0 if fail_large and a[1]>1024*1024 else allocate(a[1],a[2],'data'))
            elif name=='test_release': release(a[1],a[2],'data'); ret()
            elif name=='test_elf_allocate':
                p=allocate(a[1],a[2],'elf')
                if p: cpu.mem_write(a[3],struct.pack('<QQ',p,a[1]))
                ret(0 if p else -1)
            elif name=='test_elf_release':
                p,n=struct.unpack('<QQ',cpu.mem_read(a[1],16)); release(p,n,'elf'); ret()
            elif name=='test_protect':
                p,n=struct.unpack('<QQ',cpu.mem_read(a[1],16)); off,count,flags=a[2:5]
                assert p in state['allocs'] and off+count<=n and not(off%4096) and not(count%4096)
                permissions=(UC_PROT_READ if flags&1 else 0)|(UC_PROT_WRITE if flags&2 else 0)|(UC_PROT_EXEC if flags&4 else 0)
                cpu.mem_protect(p+off,count,permissions);state['protects']+=1;ret()
            elif name=='test_icache':
                p,n=struct.unpack('<QQ',cpu.mem_read(a[1],16)); assert a[2]+a[3]<=n
                if hasattr(cpu,'ctl_remove_cache'):cpu.ctl_remove_cache(p+a[2],p+a[2]+a[3])
                state['icaches']+=1;ret()
            elif name=='test_live_allocations':ret(len(state['allocs']))
            elif name=='test_fail_after':state['fail_after']=a[0];ret()
            elif name=='test_fail_clear':state['fail_after']=None;ret()
            else:raise AssertionError(name)
        except Exception as exc:
            state['errors'].append(str(exc));cpu.emu_stop()
    names=['test_allocate','test_release','test_elf_allocate','test_elf_release','test_protect',
           'test_icache','test_live_allocations','test_fail_after','test_fail_clear']
    for name in names:
        cpu.hook_add(UC_HOOK_CODE,provider,user_data=name,begin=syms[name],end=syms[name])
    cpu.emu_start(entry,stop,count=400000000)
    read32=lambda name:struct.unpack('<I',cpu.mem_read(syms[name],4))[0]
    checks=read32('native_test_checks');failed=read32('native_test_failed');groups=read32('native_test_groups')
    rc=cpu.reg_read(UC_ARM64_REG_X0)
    observed=read32('native_test_observed_el')
    result={'requested_pstate_el':el,'observed_current_el':observed,
            'el_request_realized':observed==el,
            'checks':checks,'groups':groups,'failed_line':failed,'return_code':rc,
            'pc':hex(cpu.reg_read(UC_ARM64_REG_PC)),'live_allocations':len(state['allocs']),
            'largest_data_allocation':state['largest_data_allocation'],'alloc_calls':state['alloc_calls'],'releases':state['released'],
            'protect_calls':state['protects'],'icache_calls':state['icaches'],'provider_errors':state['errors']}
    result['capacity']={name:read32('native_test_'+field)for name,field in [('sessions','sessions'),('arguments','arguments'),('commandBytes','command_bytes'),('pathBytes','path_bytes'),('optionsBytes','options_bytes'),('exitUnits','exit_units')]}
    assert not failed and not rc and groups==21 and not state['allocs'] and not state['errors'],result
    assert cpu.reg_read(UC_ARM64_REG_PC)==stop,result
    return result

def context_roundtrip(elf):
    """Execute pure-GPR assembly; FP/SIMD/DAIF state stays untouched."""
    cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    with elf.open('rb') as stream:
        image=ELFFile(stream); loads=[s for s in image.iter_segments()if s['p_type']=='PT_LOAD']
        begin=min(s['p_vaddr']for s in loads)&~4095
        end=max(s['p_vaddr']+s['p_memsz']for s in loads)
        cpu.mem_map(begin,(end-begin+4095)&~4095)
        for segment in loads:cpu.mem_write(segment['p_vaddr'],segment.data())
        syms={s.name:s['st_value']for s in image.get_section_by_name('.symtab').iter_symbols()}
    mem=0x30000000;cpu.mem_map(mem,4096)
    stack=0x40000000;cpu.mem_map(stack,0x20000)
    first_stop=0x41000000;second_stop=0x42000000
    for address in (first_stop,second_stop):cpu.mem_map(address,4096)
    cpu.reg_write(arm.UC_ARM64_REG_PSTATE,0x3c5)
    cpu.reg_write(arm.UC_ARM64_REG_CPACR_EL1,0)
    old_gp=[0xA111111100000000+i for i in range(12)];old_gp[-1]=second_stop
    new_gp=[0xB222222200000000+i for i in range(12)];new_gp[-1]=first_stop
    old_q=[int.from_bytes(bytes([17+i])*16,'little')for i in range(8)]
    old_sp=stack+0x1f000;new_sp=stack+0xf000
    old_nzcv=0xA0000000;new_nzcv=0x50000000
    old_fpcr=0x400000
    old_fpsr=0x08000000
    platform_x18=0x777788889999AAAA;cpu.reg_write(arm.UC_ARM64_REG_X18,platform_x18)
    for i,value in enumerate(old_gp):cpu.reg_write(getattr(arm,f'UC_ARM64_REG_X{i+19}'),value)
    for i,value in enumerate(old_q):cpu.reg_write(getattr(arm,f'UC_ARM64_REG_Q{i+8}'),value)
    cpu.reg_write(arm.UC_ARM64_REG_SP,old_sp)
    cpu.reg_write(arm.UC_ARM64_REG_NZCV,old_nzcv)
    cpu.reg_write(arm.UC_ARM64_REG_FPCR,old_fpcr)
    cpu.reg_write(arm.UC_ARM64_REG_FPSR,old_fpsr)
    data=struct.pack('<12Q2Q',*new_gp,new_sp,new_nzcv)
    assert len(data)==112;cpu.mem_write(mem+512,data)
    cpu.reg_write(arm.UC_ARM64_REG_X0,mem);cpu.reg_write(arm.UC_ARM64_REG_X1,mem+512)
    cpu.emu_start(syms['kbl_context_switch'],first_stop,count=200)
    saved=bytes(cpu.mem_read(mem,112))
    assert list(struct.unpack_from('<12Q',saved))==old_gp
    assert list(struct.unpack_from('<2Q',saved,96))==[old_sp,old_nzcv]
    assert [cpu.reg_read(getattr(arm,f'UC_ARM64_REG_X{i+19}'))for i in range(12)]==new_gp
    assert [cpu.reg_read(getattr(arm,f'UC_ARM64_REG_Q{i+8}'))for i in range(8)]==old_q
    assert cpu.reg_read(arm.UC_ARM64_REG_SP)==new_sp
    assert cpu.reg_read(arm.UC_ARM64_REG_NZCV)==new_nzcv
    assert cpu.reg_read(arm.UC_ARM64_REG_FPCR)==old_fpcr
    assert cpu.reg_read(arm.UC_ARM64_REG_FPSR)==old_fpsr
    assert cpu.reg_read(arm.UC_ARM64_REG_PSTATE)&0x3C0==0x3C0
    assert cpu.reg_read(arm.UC_ARM64_REG_X18)==platform_x18
    cpu.reg_write(arm.UC_ARM64_REG_X0,mem+512);cpu.reg_write(arm.UC_ARM64_REG_X1,mem)
    cpu.emu_start(syms['kbl_context_switch'],second_stop,count=200)
    assert [cpu.reg_read(getattr(arm,f'UC_ARM64_REG_X{i+19}'))for i in range(12)]==old_gp
    assert [cpu.reg_read(getattr(arm,f'UC_ARM64_REG_Q{i+8}'))for i in range(8)]==old_q
    assert cpu.reg_read(arm.UC_ARM64_REG_SP)==old_sp
    assert cpu.reg_read(arm.UC_ARM64_REG_NZCV)==old_nzcv
    assert cpu.reg_read(arm.UC_ARM64_REG_FPCR)==old_fpcr
    assert cpu.reg_read(arm.UC_ARM64_REG_FPSR)==old_fpsr
    assert cpu.reg_read(arm.UC_ARM64_REG_PSTATE)&0x3C0==0x3C0
    assert cpu.reg_read(arm.UC_ARM64_REG_X18)==platform_x18
    return {'roundtrips':1,'callee_GPRs':'X19-X30','fp_simd_daif_unmodified':True,
            'controls':['SP','NZCV'],'CPACR_EL1':0,'X18_unchanged':True,'passed':True}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=HERE/'build/native-tests')
    args=parser.parse_args();out=args.output.resolve();elf=build(out)
    results=[run(elf,el)for el in (1,2)]
    report={'scope':__doc__,'elf_bytes':elf.stat().st_size,'elf_sha256':hashlib.sha256(elf.read_bytes()).hexdigest(),'cases':results,'passed_runs':len(results),
            'source_sha256':{str(p.relative_to(RUNTIME)):hashlib.sha256(p.read_bytes()).hexdigest()for p in [HERE/'kbl_native.c',HERE/'kbl_native.h',HERE/'kbl_vfs.inc',HERE/'kbl_mount.inc',HERE/'kbl_io.inc',HERE/'kbl_efi_command.inc',HERE/'kbl_efi_bootloader.c',HERE/'kbl_efi_bootloader.h',RUNTIME/'kbl_efi.h',HERE/'kbl_program_memory.inc',RUNTIME/'compat/core.c',RUNTIME/'compat/io.c',HERE/'kbl_native_io.h',HERE/'tests/native_fixture.c',HERE/'tests/provider_stubs.S',HERE/'tests/efi_bootloader_fixture.h',HERE/'tests/file_program.c',HERE/'tests/memory_program.c',HERE/'tests/memory_port_program.c',Path(__file__),RUNTIME/'sdk/kbl_program.h',RUNTIME/'sdk/kbl_file.h',RUNTIME/'kbl_sha256.c']},
            'context_roundtrip':context_roundtrip(elf),
            'actually_observed_levels':sorted(set(r['observed_current_el']for r in results)),
            'limitations':['Requested PSTATE is not evidence of actual EL; this Unicorn build may retain EL1 after an EL2 request.','Only observed levels are instruction-execution evidence; no physical phone/UEFI test.']}
    (out/'validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
