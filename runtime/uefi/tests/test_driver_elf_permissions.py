"""Actual ARM64 driver with explicit PI GCD capability/failure models.

No physical firmware page tables or USB access. The no-RP capability model
tests the suspected device limitation; it is not a measurement of live caps.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import struct
import sys
from io import BytesIO
from elftools.elf.elffile import ELFFile

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('elf_driver_base',HERE/'test_driver_unicorn.py')
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
GET=b.CALLBACK+0xc00;SET=GET+0x40


class GcdFixture(b.Fixture):
    def __init__(self,driver,read_protect=False,partial_failure=False):
        super().__init__(driver,0x10000000)
        self.missing_attributes=True
        self.caps=8|0x4000|0x20000|(0x2000 if read_protect else 0)
        self.gcd_sets=[];self.gcd_gets=0;self.partial_failure=partial_failure
        self.segment_denied=False;self.restore_hold=False;self.restore_changes=0
        table=b.DATA+0xb000;config=b.DATA+0xc000
        self.cpu.mem_write(config,bytes.fromhex('ba34ad05026f1442952e4da0398e2bb9')+struct.pack('<Q',table))
        self.write64(b.ST+0x68,1);self.write64(b.ST+0x70,config)
        self.write64(table,0x565245535f455844);self.write32(table+12,0x48)
        self.write64(table+0x38,GET);self.write64(table+0x40,SET)

    def hook(self,cpu,address,size,user):
        if address not in [GET,SET]:return super().hook(cpu,address,size,user)
        a=[cpu.reg_read(r)for r in b.REGS]
        if address==GET:
            p,out=a[:2];raw,flags,at,_=self.attributes(p&~4095,4096);self.gcd_gets+=1
            cpu.mem_write(out,struct.pack('<4QII2Q',p&~4095,4096,self.caps,flags[at]|8,2,0,0,0));self.ret();return
        p,n,bits=a[:3];raw,flags,at,count=self.attributes(p,n)
        assert n==4096 and not bits&~self.caps
        self.gcd_sets.append([hex(p),hex(bits)])
        if self.partial_failure and not self.segment_denied and bits==(8|0x20000):
            self.segment_denied=True;self.restore_hold=True;self.ret(b.ERR(15));return
        if self.restore_hold and bits==(8|0x4000) and flags[at]&0x20000:
            self.restore_changes+=1
            if self.restore_changes>1:self.ret(b.ERR(15));return
        flags[at]=bits&0x26000
        f=flags[at];perms=(0 if f&0x2000 else 1)|(0 if f&0x20000 else 2)|(0 if f&0x4000 else 4)
        assert perms&6!=6
        cpu.mem_protect(raw+at*4096,4096,perms);self.ret()


def put(f,data):
    arg='/hello'.encode().hex();reply=f.request(8,arg,data)
    assert reply['final'][0][0]


def execute(driver,program,*,rp=False,partial=False,old=False,gap=False):
    f=GcdFixture(driver,rp,partial);f.boot();assert f.request(1)['final'][0][0]
    data=Path(program).read_bytes();put(f,data)
    submitted=f.request(5,b'./hello'.hex());assert submitted['final'][0][0]
    job=next(s[9:]for s in submitted['lines']if s.startswith('KBL1 job='))
    output=b'';details=[]
    for _ in range(100):
        reply=f.request(7,job);assert reply['final'][0][0],reply
        output+=b''.join(bytes.fromhex(s[9:])for s in reply['lines']if s.startswith('KBL1 out='))
        details += [s for s in reply['lines']if s.startswith('KBL1 elf_')]
        done=next(s for s in reply['lines']if s.startswith('KBL1 done='))
        if 'done=1' in done:
            rc=int(done.split('rc=')[1]);break
    else:raise AssertionError('job did not finish')
    expected_failure=old or (gap and not rp) or partial
    assert rc==(126 if expected_failure else 0),(rc,output,details)
    if expected_failure:assert output==b'ELF load failed; code=-5\n'
    else:assert output==b'KBL demo before yield\nCurrentEL=1\nargv[0]=./hello\nKBL demo after yield\n'
    if gap and not rp and not old:
        assert details==['KBL1 elf_phase=hole.set','KBL1 elf_flags=0000000000000000','KBL1 elf_fw=8000000000000003'],details
    if partial:
        assert f.segment_denied and f.restore_changes==2 and f.calls['free_pages']==0
        assert not f.call(f.can_unload,[f.protocol])
        assert details==['KBL1 elf_phase=segment.clear','KBL1 elf_flags=0000000000000005','KBL1 elf_fw=800000000000000f'],details
        f.restore_hold=False
    assert f.request(3)['final'][0][0]
    assert not f.allocations and f.calls['free_pages']==1 and f.call(f.can_unload,[f.protocol])==1
    return {'program_sha256':hashlib.sha256(data).hexdigest(),'return_code':rc,'output':output.decode(),
            'diagnostic_lines':details,'gcd_descriptor_reads':f.gcd_gets,'gcd_attribute_sets':len(f.gcd_sets),
            'partial_restore_retried':partial,'live_allocations_after_off':0}


def run(new,old,program,gapped,out):
    headers=[]
    for p in [program,gapped]:
        e=ELFFile(BytesIO(Path(p).read_bytes()))
        headers.append({'file':str(p),'loads':[{'va':hex(s['p_vaddr']),'memory':hex(s['p_memsz']),'flags':s['p_flags']}
                                               for s in e.iter_segments()if s['p_type']=='PT_LOAD']})
    cases={
        'old_driver_contiguous_loads_without_rp':execute(old,program,old=True),
        'new_driver_contiguous_loads_without_rp':execute(new,program),
        'real_hole_without_rp_still_rejected':execute(new,gapped,gap=True),
        'real_hole_with_rp_still_protected':execute(new,gapped,rp=True,gap=True),
        'partial_segment_failure_restore_failure_and_off_retry':execute(new,program,partial=True),
    }
    report={'scope':__doc__,'passed':True,'cases':len(cases),'new_driver_sha256':hashlib.sha256(Path(new).read_bytes()).hexdigest(),
            'old_driver_sha256':hashlib.sha256(Path(old).read_bytes()).hexdigest(),'program_headers':headers,'results':cases}
    Path(out).write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':True,'cases':len(cases)}))


if __name__=='__main__':run(*sys.argv[1:])
