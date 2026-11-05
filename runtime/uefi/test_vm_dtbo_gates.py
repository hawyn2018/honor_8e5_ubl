"""Execute the installed native-selector hook/thunk for real firmware fixtures.
Native board getter and logger are explicit models; no native launch/reset/EFI
storage is invoked. The emitted PIC and complete save/restore code execute.
"""
from pathlib import Path
import ctypes,hashlib,json,re,struct,sys
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn import arm64_const as a
from test_vm_dtbo import WORK,ROOT,tree,table
OUT=ROOT/'build/vm-dtbo-fixtures'
def parse(path):
    s=path.read_text();keys=['hook','tableRegister','lengthStack','tableStack','indexAddress','indexRegister','printLoad','logger','boardGetter','revisionAddress','bootLinux','normalBoot','fastbootGate','readCall']
    return {k:int(re.search(r'\b'+k+r'=(\d+)',s).group(1)) for k in keys}
def run(path,f,base,data,board,indices,want,status,revision=0,skip=None):
    b=path.read_bytes();p=struct.unpack_from('<I',b,60)[0];o=p+24;s=o+struct.unpack_from('<H',b,p+20)[0]
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);size=struct.unpack_from('<I',b,o+56)[0]
    u.mem_map(base,(size+4095)&~4095,7);u.mem_write(base,b[:struct.unpack_from('<I',b,o+60)[0]])
    for n in range(struct.unpack_from('<H',b,p+6)[0]):
        at=s+n*40;vs,rva,rawsize,raw=struct.unpack_from('<4I',b,at+8)
        if rawsize:u.mem_write(base+rva,b[raw:raw+rawsize])
        flags=struct.unpack_from('<I',b,at+36)[0];perms=(1 if flags&0x40000000 else 0)|(2 if flags&0x80000000 else 0)|(4 if flags&0x20000000 else 0)
        assert not perms&2 or not perms&4
        if vs:u.mem_protect(base+rva,(max(vs,rawsize)+4095)&~4095,perms)
    tb,stack=0x20000000,0x30000000;u.mem_map(tb,(len(data)+4095)&~4095,1);u.mem_write(tb,data);u.mem_map(stack,0x20000,3)
    sp=stack+0x10000
    regs={getattr(a,f'UC_ARM64_REG_X{i}'):0x12200000+i for i in range(31)}
    regs[getattr(a,f'UC_ARM64_REG_X{f["indexRegister"]}')]=base+f['indexAddress']
    for r,v in regs.items():u.reg_write(r,v)
    u.reg_write(a.UC_ARM64_REG_SP,sp);u.reg_write(a.UC_ARM64_REG_NZCV,0xa0000000)
    u.mem_write(sp+f['lengthStack'],struct.pack('<I',len(data)))
    u.mem_write(sp+f['tableStack'],struct.pack('<Q',tb))
    u.mem_write(base+f['indexAddress'],struct.pack('<2i',*indices));u.mem_write(base+f['revisionAddress'],struct.pack('<I',revision))
    for i in range(32):u.reg_write(getattr(a,f'UC_ARM64_REG_Q{i}'),(0xaabb0000+i)<<64|0x11223300+i)
    u.reg_write(a.UC_ARM64_REG_CPACR_EL1,0)
    calls=[];terminal=[];replays=[]
    original_ldp=0x29400000|(3<<10)|(f['indexRegister']<<5)|2
    hook_word=struct.unpack('<I',u.mem_read(base+f['hook'],4))[0]
    delta=hook_word&0x3ffffff;delta=delta-(1<<26) if delta&(1<<25) else delta
    thunk=base+f['hook']+delta*4
    if skip:u.mem_unmap(tb,(len(data)+4095)&~4095)
    def hook(cpu,pc,sz,opaque):
        rel=pc-base
        if skip:
            assert rel!=f['hook'],(skip,'unexpected selector hook')
            if skip=='fastboot':
                if rel==f['fastbootGate']+4:terminal.append('fastboot');cpu.emu_stop()
            else:
                if not(f['readCall']+4<=rel<f['readCall']+84):terminal.append('reader-error');cpu.emu_stop();return
                word=struct.unpack('<I',cpu.mem_read(pc,4))[0]
                if word&0xfc000000==0x94000000:
                    cpu.reg_write(a.UC_ARM64_REG_X0,0);cpu.reg_write(a.UC_ARM64_REG_PC,pc+4)
            return
        if thunk<=pc<thunk+1024 and struct.unpack('<I',cpu.mem_read(pc,4))[0]==original_ldp:replays.append(pc)
        if rel==f['boardGetter']:
            calls.append('board');cpu.reg_write(a.UC_ARM64_REG_X0,board);cpu.reg_write(a.UC_ARM64_REG_PC,cpu.reg_read(a.UC_ARM64_REG_X30))
        elif rel==f['logger']:
            calls.append(('log',ctypes.c_int32(cpu.reg_read(a.UC_ARM64_REG_X2)).value,cpu.reg_read(a.UC_ARM64_REG_X3),cpu.reg_read(a.UC_ARM64_REG_X4)));cpu.reg_write(a.UC_ARM64_REG_PC,cpu.reg_read(a.UC_ARM64_REG_X30))
        elif rel==f['hook']+4:
            terminal.append(rel);cpu.emu_stop()
        if rel in (f['boardGetter'],f['logger']):
            # Native AAPCS64 callbacks may clobber all volatile vector state.
            # The injected thunk/PIC execute with FP disabled and preserve the
            # ABI-required low halves of D8-D15 without accessing FP registers.
            for i in range(32):
                low=0x11223300+i if 8<=i<=15 else 0xdead0000+i
                cpu.reg_write(getattr(a,f'UC_ARM64_REG_Q{i}'),0xbeef<<64|low)
    u.hook_add(UC_HOOK_CODE,hook)
    start=base+f['hook']
    if skip=='reader-failure':
        start=base+f['readCall']+4;u.reg_write(a.UC_ARM64_REG_X0,0x8000000000000007)
        u.reg_write(getattr(a,f'UC_ARM64_REG_X{f["tableRegister"]}'),tb)
    elif skip=='fastboot':
        start=base+f['normalBoot']-4
        branch=struct.unpack('<I',u.mem_read(start,4))[0];gate=struct.unpack('<I',u.mem_read(base+f['fastbootGate'],4))[0]
        u.reg_write(getattr(a,f'UC_ARM64_REG_X{branch&31}'),1)
        u.reg_write(getattr(a,f'UC_ARM64_REG_X{gate&31}'),0)
    u.emu_start(start,0,count=20000000)
    if skip:
        assert not calls and not replays and terminal==['fastboot' if skip=='fastboot' else 'reader-error'],(skip,calls,terminal)
        return
    assert calls==['board',('log',status,board,revision)],calls
    assert terminal==[f['hook']+4],terminal
    assert len(replays)==1,replays
    assert list(struct.unpack('<2i',u.mem_read(base+f['indexAddress'],8)))==want
    assert u.reg_read(a.UC_ARM64_REG_SP)==sp
    assert u.reg_read(a.UC_ARM64_REG_NZCV)==0xa0000000
    for r,v in regs.items():
        if r in (a.UC_ARM64_REG_X2,a.UC_ARM64_REG_X3):continue
        assert u.reg_read(r)==v,(r,hex(u.reg_read(r)),hex(v))
    assert [u.reg_read(a.UC_ARM64_REG_X2),u.reg_read(a.UC_ARM64_REG_X3)]==[x&0xffffffff for x in want]
    assert all(u.reg_read(getattr(a,f'UC_ARM64_REG_Q{i}'))&((1<<64)-1)==0x11223300+i for i in range(8,16))
def main():
    global OUT
    expansion='--text-expansion-only' in sys.argv
    if expansion:OUT=ROOT/'build/vm-dtbo-text-expansion-fixtures'
    rows=[];raw=(WORK/'analysis/downgrade-fastboot-20261104/qtvm_dtbo_b.raw').read_bytes()
    capture_sha=hashlib.sha256(raw).hexdigest()
    assert capture_sha=='109842365bf3e3d356319f4b4852b31ce8cb3f2e4268eb6e2ba7110a33f1a089'
    final_text='--final-text' in sys.argv
    paths=sorted(OUT.glob('*-facts.txt'));expected=4 if expansion else 8
    if not expansion:paths=[p for p in paths if ('-text-vm-' in p.name)==final_text]
    assert len(paths)==expected,len(paths)
    for facts in paths:
        f=parse(facts);image=facts.with_name(facts.name.replace('-facts.txt','.img'))
        for base in [0x10000000,0x123450000]:
            run(image,f,base,raw,0x2262,[0,1],[0,2],1)
            run(image,f,base,raw,0x2262,[0,2],[0,2],0)
            run(image,f,base,raw,0xdead,[-1,-1],[-1,-1],-3)
            run(image,f,base,raw,0x22a6,[0,1],[0,2],1,revision=0x10000)
            run(image,f,base,raw,0x22a6,[0,2],[0,2],0,revision=0x10000)
            run(image,f,base,raw,0x22a6,[0,1],[0,1],-3,skip='reader-failure')
            run(image,f,base,raw,0x22a6,[0,1],[0,1],-3,skip='fastboot')
        rows.append({'fixture':image.name,'cases':14,'passed':True})
    report={'passed':True,'cases':len(paths)*14,'fixtures':rows,'capturedDtboSha256':capture_sha,'capturedDtboBytes':len(raw),'deviceOperations':False,'scope':'Actual injected selector/C and native reader-error/Fastboot decision prefixes execute with modeled native calls. Table is unmapped for skip cases; normal cases replay original LDP once and preserve ABI GPR/SP/NZCV/D8-D15 while FP execution is disabled. No native firmware initialization, disk, VM launcher or kernel executes.'}
    report_name='gate-results-text-expansion.json' if expansion else 'gate-results-final-text.json' if final_text else 'gate-results.json'
    (ROOT/'build/vm-dtbo'/report_name).write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()
