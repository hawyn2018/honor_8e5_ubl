"""Execute the actual relocatable COFF driver with explicit FakeEFI services.

Controller/native/RAMFS/fibers/ELF loader/SHA execute actual AArch64 code.
EFI allocation, memory-attribute and Fastboot-link callbacks are host models;
this does not validate a phone, parent firmware shim, USB DMA or hardware EL.
"""
from pathlib import Path
import hashlib
import json
import re
import struct
import pefile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
import unicorn.arm64_const as ar

DATA=0x20000000;STACK=0x30000000;CALLBACK=0x40000000;POOL=0x50000000;PAGES=0x70000000
ST=DATA;BS=DATA+0x1000;LOADED=DATA+0x2000;ATTR=DATA+0x2800;LINK=DATA+0x3000;REPLY=DATA+0x4000
ARG=DATA+0x10000;DOWNLOAD=DATA+0x20000;STOP=CALLBACK+0xf00;IMAGE=0x1234
REGS=[getattr(ar,f'UC_ARM64_REG_X{i}')for i in range(8)]
CALLS=['handle','install','uninstall','alloc_pool','free_pool','alloc_pages','free_pages',
       'locate','get','set','clear','info','final','data','write','abort','quiet']
CB={n:CALLBACK+i*0x40 for i,n in enumerate(CALLS)}
ERR=lambda n:0x8000000000000000|n

class Fixture:
    def __init__(self,path,base):
        self.cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM);self.base=base;self.pe=pefile.PE(str(path))
        self.cpu.mem_map(base,self.pe.OPTIONAL_HEADER.SizeOfImage)
        image=bytearray(self.pe.get_memory_mapped_image())
        delta=base-self.pe.OPTIONAL_HEADER.ImageBase;self.relocations=0
        for block in self.pe.DIRECTORY_ENTRY_BASERELOC:
            for e in block.entries:
                if not e.type:continue
                assert e.type==10
                old=struct.unpack_from('<Q',image,e.rva)[0]
                struct.pack_into('<Q',image,e.rva,(old+delta)&((1<<64)-1));self.relocations+=1
        self.cpu.mem_write(base,bytes(image))
        self.cpu.mem_protect(base,self.pe.OPTIONAL_HEADER.SizeOfHeaders//4096*4096 or 4096,1)
        for s in self.pe.sections:
            perms=(1 if s.Characteristics&0x40000000 else 0)|(2 if s.Characteristics&0x80000000 else 0)|(4 if s.Characteristics&0x20000000 else 0)
            assert perms&6!=6
            self.cpu.mem_protect(base+s.VirtualAddress,(s.Misc_VirtualSize+4095)&~4095,perms)
        self.cpu.mem_map(DATA,0x1000000,3);self.cpu.mem_map(STACK,0x200000,3)
        self.cpu.mem_map(CALLBACK,4096,5);self.cpu.mem_write(CALLBACK,b'\xc0\x03\x5f\xd6'*1024)
        self.cpu.reg_write(ar.UC_ARM64_REG_PSTATE,0x3c5);self.cpu.reg_write(ar.UC_ARM64_REG_CPACR_EL1,0)
        self.cpu.reg_write(ar.UC_ARM64_REG_X18,0x18a1)
        self.cursor=POOL;self.page_cursor=PAGES;self.allocations={};self.pages={};self.calls={n:0 for n in CALLS}
        self.protocol=0;self.output=[];self.final=[];self.body=bytearray();self.protections=[];self.quiet=1
        self.fail_write=False;self.fail_free=False;self.missing_attributes=False;self.aborted=False
        self.deny_release_restore=False;self.denied_release_restores=0
        self.write64(ST,0x5453595320494249);self.write32(ST+12,120);self.write64(ST+0x60,BS)
        self.write64(BS,0x56524553544f4f42);self.write32(BS+12,0x178)
        for offset,name in [(0x28,'alloc_pages'),(0x30,'free_pages'),(0x40,'alloc_pool'),(0x48,'free_pool'),(0x80,'install'),(0x90,'uninstall'),(0x98,'handle'),(0x140,'locate')]:self.write64(BS+offset,CB[name])
        self.write64(ATTR,CB['get']);self.write64(ATTR+8,CB['set']);self.write64(ATTR+16,CB['clear'])
        # Exact ABI is 8-byte header + opaque + 6 callbacks + flags =72B.
        self.cpu.mem_write(LINK,struct.pack('<II8Q',1,72,0,*[CB[n]for n in ['info','final','data','write','abort','quiet']],1))
        self.cpu.hook_add(UC_HOOK_CODE,self.hook,begin=CALLBACK,end=CALLBACK+4095)
    def write64(self,p,x):self.cpu.mem_write(p,struct.pack('<Q',x))
    def write32(self,p,x):self.cpu.mem_write(p,struct.pack('<I',x))
    def read64(self,p):return struct.unpack('<Q',self.cpu.mem_read(p,8))[0]
    def string(self,p):
        out=bytearray()
        while p and len(out)<256:
            c=self.cpu.mem_read(p,1)[0];p+=1
            if not c:break
            out.append(c)
        return out.decode('ascii')
    def ret(self,x=0):self.cpu.reg_write(ar.UC_ARM64_REG_X0,x&((1<<64)-1));self.cpu.reg_write(ar.UC_ARM64_REG_PC,self.cpu.reg_read(ar.UC_ARM64_REG_X30))
    def allocate(self,n,kind):
        assert 0<n<40*1024*1024
        address=self.cursor if kind=='pool' else self.page_cursor;size=(n+4095)&~4095
        self.cpu.mem_map(address,size,3)
        if kind=='pool':self.cursor+=size+4096
        else:self.page_cursor+=size+4096;self.pages[address]=[0]*(size//4096)
        self.allocations[address]=(n,size,kind);return address
    def release(self,p,kind):
        assert p in self.allocations,(hex(p),kind)
        n,mapped,k=self.allocations[p];assert kind==k
        if self.fail_free:return ERR(9)
        del self.allocations[p];self.pages.pop(p,None);self.cpu.mem_unmap(p,mapped);return 0
    def attributes(self,p,n):
        for base,flags in self.pages.items():
            if base<=p and p+n<=base+len(flags)*4096:
                assert p%4096==n%4096==0
                return base,flags,(p-base)//4096,n//4096
        raise AssertionError(('foreign attributes',hex(p),n))
    def hook(self,cpu,address,size,user):
        if address==STOP:cpu.emu_stop();return
        name=next((n for n,v in CB.items()if v==address),None);assert name,address
        self.calls[name]+=1;a=[cpu.reg_read(r)for r in REGS]
        if name=='handle':assert a[0]==IMAGE;self.write64(a[2],LOADED);self.ret()
        elif name=='install':assert self.read64(a[0])==IMAGE;self.protocol=a[3];self.ret()
        elif name=='uninstall':assert a[0]==IMAGE and a[2]==self.protocol;self.ret()
        elif name=='alloc_pool':assert a[0]==4;self.write64(a[2],self.allocate(a[1],'pool'));self.ret()
        elif name=='free_pool':self.ret(self.release(a[0],'pool'))
        elif name=='alloc_pages':assert a[0]==0 and a[1]==2;self.write64(a[3],self.allocate(a[2]*4096,'pages'));self.ret()
        elif name=='free_pages':assert self.allocations[a[0]][0]==a[1]*4096;self.ret(self.release(a[0],'pages'))
        elif name=='locate':
            assert bytes(cpu.mem_read(a[0],16))==bytes.fromhex('f60c56f4ec404a4ba192bf1d57d0b189')
            self.write64(a[2],0 if self.missing_attributes else ATTR);self.ret(ERR(3)if self.missing_attributes else 0)
        elif name in ['get','set','clear']:
            base,flags,at,count=self.attributes(a[1],a[2]);bits=a[3]
            if name=='get':assert len(set(flags[at:at+count]))==1;self.write64(bits,flags[at]);self.ret()
            else:
                if name=='clear'and self.deny_release_restore and a[1]==base and count==len(flags)and bits==0x22000 and any(f&0x22000 for f in flags):
                    self.denied_release_restores+=1;self.ret(ERR(15));return
                for i in range(at,at+count):
                    flags[i]=flags[i]|bits if name=='set'else flags[i]&~bits
                    f=flags[i];perms=(0 if f&0x2000 else 1)|(0 if f&0x20000 else 2)|(0 if f&0x4000 else 4)
                    # EFI AllocatePages begins inherited RW; driver first sets XP.
                    assert perms&6!=6,('W+X',name,hex(f))
                    cpu.mem_protect(base+i*4096,4096,perms)
                self.protections.append([name,a[1],a[2],bits]);self.ret()
        elif name=='info':self.output.append(self.string(a[1]));self.ret()
        elif name=='final':self.final.append((a[1],self.string(a[2])));self.ret()
        elif name=='data':assert a[1]<=8*1024*1024;self.ret()
        elif name=='write':
            if self.fail_write:self.write64(a[3],0);self.ret(-1)
            else:
                n=min(a[2],37);self.body.extend(cpu.mem_read(a[1],n));self.write64(a[3],n);self.ret()
        elif name=='abort':self.aborted=True;self.ret()
        elif name=='quiet':self.ret(self.quiet)
    def call(self,p,args):
        self.cpu.reg_write(ar.UC_ARM64_REG_SP,STACK+0x1ff000)
        for r,v in zip(REGS,args):self.cpu.reg_write(r,v)
        self.cpu.reg_write(ar.UC_ARM64_REG_X30,STOP)
        try:self.cpu.emu_start(p,STOP,count=30000000)
        except Exception as e:raise RuntimeError(f'{e}; pc={self.cpu.reg_read(ar.UC_ARM64_REG_PC):x}')from e
        assert self.cpu.reg_read(ar.UC_ARM64_REG_PC)==STOP,'instruction budget exhausted'
        assert self.cpu.reg_read(ar.UC_ARM64_REG_X18)==0x18a1
        return self.cpu.reg_read(ar.UC_ARM64_REG_X0)
    def boot(self):
        assert self.call(self.base+self.pe.OPTIONAL_HEADER.AddressOfEntryPoint,[IMAGE,ST])==0
        assert self.protocol and self.calls['handle']==self.calls['install']==1
        assert sum(v for k,v in self.calls.items()if k not in ['handle','install'])==0
        self.request_ptr=self.read64(self.protocol+16);self.can_unload=self.read64(self.protocol+24)
        assert self.call(self.can_unload,[self.protocol])==1
    def request(self,op,arg=b'',download=b'',pages=True):
        if isinstance(arg,str):arg=arg.encode()
        assert len(arg)<=4096 and len(download)<0xe00000
        self.cpu.mem_write(ARG,arg or b'\0');self.cpu.mem_write(DOWNLOAD,download or b'\0')
        self.cpu.mem_write(REPLY,struct.pack('<4I',16,0,0,0));self.final=[];self.output=[]
        rc=self.call(self.request_ptr,[self.protocol,op,ARG if arg else 0,len(arg),DOWNLOAD if download else 0,len(download),LINK,REPLY])
        reply=struct.unpack('<4I',self.cpu.mem_read(REPLY,16));final=self.final[:]
        assert rc in [0,0xfffffffe]and len(final)<=1
        lines=[]
        if pages and op not in [15,16]:
            self.request(15,pages=False);assert self.final[0][0]
            m=re.fullmatch(r'KBLC1 g=([0-9a-f]{16}) n=(\d+)',self.final[0][1]);assert m
            for i in range(int(m[2])):
                self.request(16,f'{m[1]}:{i}',pages=False);assert self.final[0][0];lines.append(self.final[0][1])
        return {'rc':rc,'reply':reply,'final':final,'lines':lines}
    def command(self,text):
        r=self.request(5,text.encode().hex());assert r['final'][0][0],r
        job=next(s.split('job=')[1]for s in r['lines']if s.startswith('KBL1 job='))
        self.request(2) # Snapshot/reconnect while busy must not execute.
        output=b'';polls=0
        while True:
            r=self.request(7,job);assert r['final'][0][0],r;polls+=1;assert polls<200
            output+=b''.join(bytes.fromhex(s[9:])for s in r['lines']if s.startswith('KBL1 out='))
            done=next(s for s in r['lines']if s.startswith('KBL1 done='))
            if 'done=1' in done:return output,int(done.split('rc=')[1]),polls

def run_one(driver,program,base):
    f=Fixture(driver,base);f.boot();initial=dict(f.calls)
    start=f.request(1);assert start['final'][0][0]and 'status=running' in start['lines'][0]
    count=len(f.allocations);f.request(1);f.request(2);assert len(f.allocations)==count
    out,rc,polls=f.command('echo test');assert out==b'test\n'and rc==0
    before=len(f.allocations);r=f.request(5,b'exit'.hex());assert r['final']==[(1,'KBL retained')]and len(f.allocations)==before
    data=Path(program).read_bytes();path=b'/hello';nonce=0x1122334455667788
    envelope=struct.pack('<8sQIIQ32s',b'KBLPUT01',nonce,len(path),0,len(data),hashlib.sha256(data).digest())+path+data
    store=f.request(8,f'env1:{nonce:016x}',envelope);assert store['final'][0][0]and store['lines'][0]==f'KBL1 stored=1 size={len(data)}'
    assert store['lines'][1:3]==['KBL1 hash0='+hashlib.sha256(data).hexdigest()[:32],'KBL1 hash1='+hashlib.sha256(data).hexdigest()[32:]]
    bad=bytearray(envelope);bad[-1]^=1;assert f.request(8,f'env1:{nonce:016x}',bytes(bad))['final'][0][0]==0
    output,rc,demo_polls=f.command('./hello');assert rc==0,(rc,output)
    assert output==b'KBL demo before yield\nCurrentEL=1\nargv[0]=./hello\nKBL demo after yield\n'
    assert demo_polls>1 and f.calls['locate']==1 and f.calls['alloc_pages']==1 and f.calls['free_pages']==1
    read_envelope=struct.pack('<8sQIIQ32s',b'KBLPUT01',nonce,len(path),0,0,hashlib.sha256(b'').digest())+path
    read=f.request(9,f'env1:{nonce:016x}',read_envelope);assert read['final'][0][0]
    alias=next(s[10:]for s in read['lines']if s.startswith('KBL1 file='))
    f.body.clear();fetch=f.request(11,alias);assert fetch['final'][0][0]and bytes(f.body)==data
    f.request(10,alias);assert f.request(10,alias)['final'][0][0]==0
    off=f.request(3);assert off['final'][0][0]and off['reply'][1]==1 and not f.allocations
    assert f.call(f.can_unload,[f.protocol])==1
    assert not f.calls['info'],'DEFER_INFO must not invoke unbound intermediate USB sends'
    assert f.call(f.read64(LOADED+0x58),[IMAGE])==0 and f.calls['uninstall']==1
    return {'image_base':hex(base),'dir64_relocations_applied':f.relocations,'entry_only_calls':initial,
            'real_shell_output':out.decode(),'real_sdk_output':output.decode(),'sdk_polls':demo_polls,
            'provider_calls':f.calls,'attribute_operations':len(f.protections),'live_allocations_after_off':len(f.allocations),
            'download_sha256':hashlib.sha256(data).hexdigest(),'fetch_sha256':hashlib.sha256(f.body).hexdigest(),
            'x18_preserved':True,'observed_demo_current_el':1,'cpacr_el1_during_test':0,
            'sdk_profile':'GPR-only; FP/SIMD/DAIF not scheduled or modified'}

def run(driver,program,report):
    result={'scope':'Actual ARM64 relocatable COFF driver/controller/native/RAMFS/fibers/ELF/SHA executed under Unicorn with explicit FakeEFI and synchronous link model. No parent-shim/USB-DMA/phone/physical-EL or boot guarantee.',
            'passed':True,'cases':2,'driver_sha256':hashlib.sha256(Path(driver).read_bytes()).hexdigest(),
            'results':[run_one(driver,program,b)for b in [0x10000000,0x18000000]]}
    Path(report).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');return result
if __name__=='__main__':
    import sys
    r=run(*sys.argv[1:]);print(json.dumps({'passed':r['passed'],'cases':r['cases']}))
