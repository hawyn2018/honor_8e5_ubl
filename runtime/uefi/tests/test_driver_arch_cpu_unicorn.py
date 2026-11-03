"""Production driver/getter + actual vendor CPU setter share real model tables.

FakeEFI supplies allocations/protocols/GCD metadata (deliberately no RO cap).
AT results and the SCTLR.M register result are injected; native config/table-reader instructions and
the actual ArmCpuDxe SetMemoryAttributes/page-table mutation execute. Unicorn
page permissions mirror resulting actual PTEs; this is not phone validation.
"""
from pathlib import Path
import importlib.util,hashlib,json,struct,sys
import pefile
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_ARM
from unicorn import UC_HOOK_CODE

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('full_arch_base',HERE/'test_driver_unicorn.py')
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
CPU_BASE=0x18000000;TABLE=b.DATA+0x500000;CONFIG=b.DATA+0x600000;DXE=CONFIG+4096
GET=b.CALLBACK+0xc00;SET=GET+0x40


class ActualCpuFixture(b.Fixture):
    def __init__(self,driver,cpu_path):
        super().__init__(driver,0x10000000)
        self.actual_cpu_calls=0;self.at_calls=0;self.gcd_calls=0;self.cpu_pending=None;self.mirrored=[]
        self.missing_attributes=True;pe=pefile.PE(str(cpu_path));image=bytearray(pe.get_memory_mapped_image())
        for block in pe.DIRECTORY_ENTRY_BASERELOC:
            for e in block.entries:
                if e.type:
                    assert e.type==10;struct.pack_into('<Q',image,e.rva,struct.unpack_from('<Q',image,e.rva)[0]+CPU_BASE)
        self.cpu.mem_map(CPU_BASE,pe.OPTIONAL_HEADER.SizeOfImage);self.cpu.mem_write(CPU_BASE,bytes(image))
        self.write64(TABLE,TABLE+4096+3)
        self.write64(TABLE+4096,0x70d)
        self.write64(TABLE+4096+8,TABLE+8192+3)
        for i in range(512):self.write64(TABLE+8192+i*8,0x40000000+i*0x200000+0x70d)
        private_index=(b.PAGES-0x40000000)//0x200000
        self.write64(TABLE+8192+private_index*8,TABLE+12288+3)
        for i in range(512):self.write64(TABLE+12288+i*8,b.PAGES+i*4096+0x70f)
        self.cpu.mem_write(CONFIG,bytes.fromhex('ba34ad05026f1442952e4da0398e2bb9')+struct.pack('<Q',DXE))
        self.write64(b.ST+0x68,1);self.write64(b.ST+0x70,CONFIG)
        self.write64(DXE,0x565245535f455844);self.write32(DXE+12,0x48)
        self.write64(DXE+0x38,GET);self.write64(DXE+0x40,SET)
        self.cpu.reg_write(b.ar.UC_ARM64_REG_TTBR0_EL1,TABLE)
        self.cpu.reg_write(b.ar.UC_ARM64_REG_MAIR_EL1,0xffbb4400)
        self.cpu.reg_write(b.ar.UC_ARM64_REG_CP_REG,(2,0,3,0,2,16|(5<<32)|(1<<23)))
        self.cpu.reg_write(b.ar.UC_ARM64_REG_CP_REG,(0,7,3,0,3,0))  # MMFR3: TCR2 absent.
        self.cpu.reg_write(b.ar.UC_ARM64_REG_CP_REG,(7,4,3,0,0,0x12345000))  # PAR sentinel.
        md=Cs(CS_ARCH_ARM64,CS_MODE_ARM);md.skipdata=True;self.at_sites=set();self.sctlr_sites=set()
        for s in self.pe.sections:
            if s.Characteristics&0x20000000:
                for x in md.disasm(s.get_data(),self.base+s.VirtualAddress):
                    if x.mnemonic=='at'and x.op_str.startswith('s1e1r,'):self.at_sites.add(x.address)
                    if x.mnemonic=='mrs'and x.op_str.endswith('sctlr_el1'):self.sctlr_sites.add(x.address)
        assert self.at_sites
        self.cpu.hook_add(UC_HOOK_CODE,self.native_hook)

    def native_hook(self,cpu,pc,size,user):
        if pc in self.sctlr_sites:
            word=struct.unpack('<I',cpu.mem_read(pc,4))[0]
            cpu.reg_write(getattr(b.ar,f'UC_ARM64_REG_X{word&31}'),1);cpu.reg_write(b.ar.UC_ARM64_REG_PC,pc+4)
        elif pc in self.at_sites:
            word=struct.unpack('<I',cpu.mem_read(pc,4))[0];p=cpu.reg_read(getattr(b.ar,f'UC_ARM64_REG_X{word&31}'))
            assert TABLE<=p<TABLE+16384
            self.at_calls+=1
            cpu.reg_write(b.ar.UC_ARM64_REG_CP_REG,(7,4,3,0,0,(0xff<<56)|(p&~4095)))
            cpu.reg_write(b.ar.UC_ARM64_REG_PC,pc+4)
        elif pc==CPU_BASE+0x1860:
            self.actual_cpu_calls+=1
            assert cpu.reg_read(b.ar.UC_ARM64_REG_X0)==CPU_BASE+0xb128
            self.cpu_pending=(cpu.reg_read(b.ar.UC_ARM64_REG_X1),cpu.reg_read(b.ar.UC_ARM64_REG_X2),cpu.reg_read(b.ar.UC_ARM64_REG_X30))
        elif self.cpu_pending and pc==self.cpu_pending[2]:
            p,n,_=self.cpu_pending;self.cpu_pending=None
            for at in range(p,p+n,4096):
                pte=self.read64(TABLE+12288+(at-b.PAGES)//4096*8)
                perms=(1 if pte&0x400 else 0)|(0 if pte&0x80 else 2)|(0 if pte&(1<<53)else 4)
                assert perms&6!=6
                cpu.mem_protect(at,4096,perms);self.mirrored.append([hex(at),hex(pte),perms])

    def hook(self,cpu,address,size,user):
        a=[cpu.reg_read(r)for r in b.REGS]
        if address==b.CB['locate']and bytes(cpu.mem_read(a[0],16))==bytes.fromhex('b1ccba26426fd411bce70080c73c8881'):
            self.write64(a[2],CPU_BASE+0xb128);self.ret();return
        if address==GET:
            p,out=a[:2];assert b.PAGES<=p<self.page_cursor
            cpu.mem_write(out,struct.pack('<4QII2Q',p&~4095,4096,0x4008,0x4008,2,0,0,0));self.gcd_calls+=1;self.ret();return
        if address==SET:raise AssertionError('missing RO must select CPU before any GCD mutation')
        return super().hook(cpu,address,size,user)


def run(driver,cpu_path,program,report):
    f=ActualCpuFixture(driver,cpu_path);f.boot();assert f.request(1)['final'][0][0]
    data=Path(program).read_bytes();assert f.request(8,b'/hello'.hex(),data)['final'][0][0]
    output,rc,polls=f.command('./hello')
    assert rc==0 and output==b'KBL demo before yield\nCurrentEL=1\nargv[0]=./hello\nKBL demo after yield\n',(rc,output)
    status=f.request(4);assert 'KBL1 elf_provider=cpuarch'in status['lines']
    assert not any('elf_inner='in s for s in status['lines'])
    assert f.actual_cpu_calls>4 and f.at_calls>0
    assert f.request(3)['final'][0][0]and not f.allocations and f.call(f.can_unload,[f.protocol])==1
    assert f.cpu.reg_read(b.ar.UC_ARM64_REG_CP_REG,(7,4,3,0,0))==0x12345000
    result={'scope':__doc__,'passed':True,'driver_sha256':hashlib.sha256(Path(driver).read_bytes()).hexdigest(),
            'vendor_cpu_sha256':hashlib.sha256(Path(cpu_path).read_bytes()).hexdigest(),'program_sha256':hashlib.sha256(data).hexdigest(),
            'output':output.decode(),'return_code':rc,'polls':polls,'actual_cpu_set_calls':f.actual_cpu_calls,'fake_at_results':f.at_calls,
            'real_pte_transitions':f.mirrored,'gcd_read_only_metadata_queries':f.gcd_calls,'live_allocations_after_off':0,
            'par_restored':True,'selected_provider_lines':status['lines']}
    Path(report).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':True,'actual_cpu_set_calls':f.actual_cpu_calls,'fake_at_results':f.at_calls}))


if __name__=='__main__':run(*sys.argv[1:])
