"""Execute the actual ARM64 ELF loader and SDK program under Unicorn.

Memory providers and API I/O are explicit host models. This is not UEFI,
physical EL measurement, native firmware integration or a hardware boot test.
"""
from pathlib import Path
from io import BytesIO
import json
import struct
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_READ, UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X30, UC_ARM64_REG_PC, UC_ARM64_REG_SP, UC_ARM64_REG_PSTATE
from unicorn import arm64_const

REGS=[UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3,UC_ARM64_REG_X4]
INPUT=0x20000000; DATA=0x21000000; STACK=0x30000000; CALLBACK=0x40000000
ALLOC=CALLBACK; FREE=CALLBACK+0x100; PROTECT=CALLBACK+0x200; CACHE=CALLBACK+0x300
WRITE=CALLBACK+0x400; YIELD=CALLBACK+0x500; EL=CALLBACK+0x600; STOP=CALLBACK+0x700
IMAGE=DATA+0x100; API=DATA+0x300; RC=DATA+0x400

def execute(loader,program,base=0x50000000,invoke=True,level=1,api_bytes=64):
    cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM); elf=ELFFile(BytesIO(loader))
    segments=[s for s in elf.iter_segments()if s['p_type']=='PT_LOAD']
    lo=min(int(s['p_vaddr'])&~4095 for s in segments)
    hi=max((int(s['p_vaddr'])+int(s['p_memsz'])+4095)&~4095 for s in segments)
    cpu.mem_map(lo,hi-lo)
    for s in segments:cpu.mem_write(int(s['p_vaddr']),s.data())
    symbols={s.name:int(s['st_value'])for s in elf.get_section_by_name('.symtab').iter_symbols()}
    cpu.mem_map(INPUT,(len(program)+4095)&~4095 or 4096);cpu.mem_write(INPUT,program)
    cpu.mem_map(DATA,0x10000);cpu.mem_map(STACK,0x20000);cpu.mem_map(CALLBACK,4096)
    cpu.mem_write(CALLBACK,b'\xc0\x03\x5f\xd6'*1024)
    cpu.reg_write(UC_ARM64_REG_SP,STACK+0x1f000);cpu.reg_write(UC_ARM64_REG_PSTATE,0x3c1|(level<<2))
    state={'allocations':0,'releases':0,'protections':[],'icache':[],'output':[],
           'yields':[],'els':[],'mapping':None,'paused':False,'api_bytes':api_bytes,'optional_api_reads':0,
           'current_el_raw':[]}
    pending_mrs=[]
    def memory_read(_,access,address,size,value,user):
        if api_bytes==40 and address<API+64 and address+size>API+40:state['optional_api_reads']+=1
    cpu.hook_add(UC_HOOK_MEM_READ,memory_read)
    def ret(value=0):
        cpu.reg_write(UC_ARM64_REG_X0,value&((1<<64)-1));cpu.reg_write(UC_ARM64_REG_PC,cpu.reg_read(UC_ARM64_REG_X30))
    def hook(_,address,size,user):
        if pending_mrs and address==pending_mrs[0][1]:
            reg,_=pending_mrs.pop(0);state['current_el_raw'].append(cpu.reg_read(reg))
        word=struct.unpack('<I',cpu.mem_read(address,4))[0]
        if word&0xffffffe0==0xd5384240:
            pending_mrs.append((getattr(arm64_const,'UC_ARM64_REG_X'+str(word&31)),address+4))
        a=[cpu.reg_read(r)for r in REGS]
        if address==STOP:cpu.emu_stop();return
        if address==ALLOC:
            n,alignment,out=a[1:4];assert base%alignment==0 and n<=64*1024*1024
            assert state['mapping']is None
            cpu.mem_map(base,n,UC_PROT_READ|UC_PROT_WRITE);cpu.mem_write(base,b'\xcc'*n)
            cpu.mem_write(out,struct.pack('<QQ',base,n));state['mapping']=(base,n);state['allocations']+=1;ret()
        elif address==FREE:
            p,n=struct.unpack('<QQ',cpu.mem_read(a[1],16));assert state['mapping']==(p,n)
            cpu.mem_unmap(p,n);state['mapping']=None;state['releases']+=1;ret()
        elif address==PROTECT:
            p,n=struct.unpack('<QQ',cpu.mem_read(a[1],16));offset,count,flags=a[2:5]
            assert p==base and offset<=n and count<=n-offset and offset%4096==count%4096==0
            assert flags&~7==0 and flags&6!=6
            cpu.mem_protect(p+offset,count,flags);state['protections'].append([offset,count,flags]);ret()
        elif address==CACHE:
            state['icache'].append([a[2],a[3]]);ret()
        elif address==WRITE:
            assert a[2]<=4096;state['output'].append(bytes(cpu.mem_read(a[1],a[2])));ret()
        elif address==YIELD:
            state['yields'].append({'budget_us':a[1],'el':(cpu.reg_read(UC_ARM64_REG_PSTATE)>>2)&3})
            ret();state['paused']=True;cpu.emu_stop()
        elif address==EL:
            level=(cpu.reg_read(UC_ARM64_REG_PSTATE)>>2)&3;state['els'].append(level);ret(level)
    cpu.hook_add(UC_HOOK_CODE,hook)
    cpu.mem_write(DATA,struct.pack('<7Q',0,4096,64*1024*1024,ALLOC,FREE,PROTECT,CACHE))
    def call(name,args):
        for reg,value in zip(REGS,args):cpu.reg_write(reg,value)
        cpu.reg_write(UC_ARM64_REG_X30,STOP);cpu.reg_write(UC_ARM64_REG_PC,symbols[name])
        resumes=0
        while True:
            state['paused']=False
            cpu.emu_start(cpu.reg_read(UC_ARM64_REG_PC),STOP+4,count=2000000)
            if state['paused']:
                resumes+=1;assert resumes<=4;continue
            assert cpu.reg_read(UC_ARM64_REG_PC)==STOP,'instruction budget exhausted'
            return cpu.reg_read(UC_ARM64_REG_X0)&0xffffffff
    result=call('kbl_elf_load',[INPUT,len(program),DATA,IMAGE])
    state['load_result']=result if result<0x80000000 else result-0x100000000
    if state['load_result']==0 and invoke:
        # The optional real UEFI environment getter is deliberately NULL.
        cpu.mem_write(DATA+0x1000,b'./demo\0');cpu.mem_write(DATA+0x1020,b'argument\0')
        cpu.mem_write(DATA+0x1100,struct.pack('<QQQ',DATA+0x1000,DATA+0x1020,0))
        value=struct.pack('<IIQQQQi4xQQ',1,api_bytes,0,WRITE,YIELD,EL,2,DATA+0x1100,0)
        cpu.mem_write(API,value[:api_bytes])
        state['entry']=struct.unpack('<Q',cpu.mem_read(IMAGE+32,8))[0]
        value=call('kbl_elf_call',[IMAGE,API,0x1234,RC])
        state['invoke_result']=value if value<0x80000000 else value-0x100000000
        state['program_rc']=struct.unpack('<i',cpu.mem_read(RC,4))[0]
        state['final_el']=(cpu.reg_read(UC_ARM64_REG_PSTATE)>>2)&3
        call('kbl_elf_unload',[IMAGE])
    state['output_hex']=b''.join(state.pop('output')).hex()
    return state

def mutations(program):
    elf=ELFFile(BytesIO(program));phoff=int(elf['e_phoff']);ents=int(elf['e_phentsize'])
    segments=list(elf.iter_segments());dynamic=next(s for s in segments if s['p_type']=='PT_DYNAMIC')
    note=next(s for s in segments if s['p_type']=='PT_NOTE')
    relava=next(int(t['d_ptr'])for t in dynamic.iter_tags()if t['d_tag']=='DT_RELA')
    owner=next(s for s in segments if s['p_type']=='PT_LOAD'and int(s['p_vaddr'])<=relava<int(s['p_vaddr'])+int(s['p_filesz']))
    relaoff=int(owner['p_offset'])+relava-int(owner['p_vaddr'])
    rxindex=next(i for i,s in enumerate(segments)if s['p_type']=='PT_LOAD'and int(s['p_flags'])==5)
    cases=[('short_note_api',int(note['p_offset'])+20,'<I',39),
           ('note_reserved_field',int(note['p_offset'])+24,'<I',1),
           ('needed',int(dynamic['p_offset']),'<Q',1),('unsupported_relocation',relaoff+8,'<Q',257),
           ('writable_executable',phoff+ents*rxindex+4,'<I',7),('bad_machine',18,'<H',62),
           ('entry_outside',24,'<Q',0x70000000),('interpreter',phoff+ents*segments.index(note),'<I',3)]
    for name,offset,fmt,value in cases:
        data=bytearray(program);struct.pack_into(fmt,data,offset,value);yield name,bytes(data)

def run(loader_path,program_path,output,uefi_program_path=None):
    loader=Path(loader_path).read_bytes();program=Path(program_path).read_bytes();results={}
    for base,level,api_bytes in [(0x50000000,1,64),(0x60000000,1,64),(0x50000000,1,40)]:
        expected=b'KBL demo before yield\n'+f'CurrentEL={level}\n'.encode()+(b'argv[0]=./demo\n'if api_bytes>=56 else b'')+b'KBL demo after yield\n'
        result=execute(loader,program,base,level=level,api_bytes=api_bytes)
        assert result['load_result']==result['invoke_result']==result['program_rc']==0
        assert result['output_hex']==expected.hex()
        assert result['final_el']==level and result['els']==[level,level,level]
        assert result['yields']==[{'budget_us':1000,'el':level}]
        assert result['releases']==1 and result['mapping']is None
        assert result['optional_api_reads']==0
        results[f'rebased_{base:x}_el{level}_api{api_bytes}']=result
    # Unicorn's exposed PSTATE can report EL2 while its CurrentEL instruction
    # model still reports EL1. Do not fake the MRS result or claim an EL2 boot.
    inconsistent=execute(loader,program,level=2)
    if inconsistent['current_el_raw']==[4]:
        assert inconsistent['program_rc']==12 and not inconsistent['yields']
        inconsistent['scope']='Negative: model PSTATE EL2 disagrees with real emulated MRS CurrentEL EL1; demo correctly refuses.'
    else:
        assert inconsistent['current_el_raw']==[8] and inconsistent['program_rc']==0
        inconsistent['scope']='This emulator model consistently executed EL2; not a hardware EL measurement.'
    results['model_current_el_consistency']=inconsistent
    # The ABI number alone is advisory; executable layout and provided API
    # prefix remain validated. Execute real code for both zero/future numbers.
    note=next(s for s in ELFFile(BytesIO(program)).iter_segments()if s['p_type']=='PT_NOTE')
    for revision in [0,2,0xffffffff]:
        compatible=bytearray(program)
        struct.pack_into('<I',compatible,int(note['p_offset'])+16,revision)
        result=execute(loader,bytes(compatible))
        assert result['load_result']==result['invoke_result']==result['program_rc']==0
        assert result['final_el']==1 and result['releases']==1 and result['mapping']is None
        assert bytes.fromhex(result['output_hex'])==b'KBL demo before yield\nCurrentEL=1\nargv[0]=./demo\nKBL demo after yield\n'
        results[f'numeric_note_abi_{revision:x}']=result
    for name,bad in mutations(program):
        result=execute(loader,bad,invoke=False)
        assert result['load_result']<0 and result['allocations']==result['releases']==0
        results[name]=result
    if uefi_program_path:
        uefi_program=Path(uefi_program_path).read_bytes()
        absent=execute(loader,uefi_program)
        assert absent['load_result']==absent['invoke_result']==0 and absent['program_rc']==1
        assert bytes.fromhex(absent['output_hex'])==b'Real UEFI environment unavailable\n'
        assert not absent['yields'] and absent['releases']==1
        results['optional_real_uefi_capability_absent']=absent
        short=execute(loader,uefi_program,api_bytes=40)
        assert short['load_result']==0 and short['invoke_result']==-6 and not short['output_hex']
        assert not short['els'] and short['releases']==1
        results['required_uefi_api_tail_not_provided']=short
    report={'scope':'Actual ARM64 loader and SDK demo executed in Unicorn with explicit memory/I/O provider models; no physical/UEFI claim.',
            'passed':True,'cases':len(results),'results':results}
    Path(output).write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    return report

if __name__=='__main__':
    import sys
    result=run(*sys.argv[1:]);print(json.dumps({'passed':result['passed'],'cases':result['cases']}))
