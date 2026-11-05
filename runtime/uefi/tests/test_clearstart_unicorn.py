"""Execute production ARM64 driver, native fiber, separate handoff PE and target PE.

Only firmware LoadImage/StartImage, allocation and link are explicit models.
No device, network, firmware deployment or partition write is performed.
"""
from pathlib import Path
import argparse,hashlib,importlib.util,json,struct
import pefile
import unicorn.arm64_const as ar
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('clearstart_base',HERE/'test_driver_unicorn.py')
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
LOAD=b.CALLBACK+0xb00;START=LOAD+0x40;UNLOAD=LOAD+0x80;RETURN=LOAD+0xc0

class Fixture(b.Fixture):
    def __init__(self,path,base):
        self.images={};self.frames=[];self.loaded=[];self.started=[];self.fail_load=0;self.fail_unload=False;self.target_status=0;self.expect_clean=True
        super().__init__(path,base)
        self.write64(b.BS+0xc8,LOAD);self.write64(b.BS+0xd0,START);self.write64(b.BS+0xe0,UNLOAD)
    def hook(self,cpu,address,size,user):
        a=[cpu.reg_read(r)for r in b.REGS]
        if address==LOAD:
            assert a[:3]==[0,b.IMAGE,0]
            raw=bytes(cpu.mem_read(a[3],a[4]));pe=pefile.PE(data=raw);self.loaded.append(raw)
            self.write64(a[5],0)
            if self.fail_load==len(self.loaded):self.ret(b.ERR(26));return
            assert pe.FILE_HEADER.Machine==0xaa64 and pe.OPTIONAL_HEADER.Subsystem==10
            base=0x90000000+len(self.images)*0x1000000;image=bytearray(pe.get_memory_mapped_image())
            for block in getattr(pe,'DIRECTORY_ENTRY_BASERELOC',[]):
                for e in block.entries:
                    if e.type:
                        assert e.type==10;old=struct.unpack_from('<Q',image,e.rva)[0]
                        struct.pack_into('<Q',image,e.rva,(old+base-pe.OPTIONAL_HEADER.ImageBase)&((1<<64)-1))
            cpu.mem_map(base,pe.OPTIONAL_HEADER.SizeOfImage,3);cpu.mem_write(base,bytes(image))
            for section in pe.sections:
                flags=section.Characteristics;perms=(1 if flags&0x40000000 else 0)|(2 if flags&0x80000000 else 0)|(4 if flags&0x20000000 else 0)
                assert perms&6!=6
                cpu.mem_protect(base+section.VirtualAddress,(section.Misc_VirtualSize+4095)&~4095,perms)
            loaded=b.DATA+0xd000+len(self.images)*0x100
            self.write64(loaded+0x10,b.ST);self.write32(loaded+0x50,1);self.write64(loaded+0x48,pe.OPTIONAL_HEADER.SizeOfImage)
            self.images[base]={'loaded':loaded,'entry':base+pe.OPTIONAL_HEADER.AddressOfEntryPoint,'live':True,'mapped':pe.OPTIONAL_HEADER.SizeOfImage}
            self.write64(a[5],base);self.ret();return
        if address==b.CB['handle']and a[0]in self.images:
            image=self.images[a[0]];self.write64(a[2],image['loaded']if image['live']else 0);self.ret(0 if image['live']else b.ERR(2));return
        if address==UNLOAD:
            image=self.images[a[0]]
            if self.fail_unload:self.ret(b.ERR(15));return
            if not image['live']:self.ret(b.ERR(2));return
            image['live']=False;self.ret();return
        if address==START:
            image=self.images[a[0]];assert image['live'];self.started.append(a[0])
            # Native context, RAMFS, command/fiber stack, output and tracked
            # pools have gone. Only context/source/options direct pools remain.
            if self.expect_clean:assert len(self.allocations)==3,self.allocations
            else:assert len(self.allocations)>3,self.allocations
            assert not self.pages
            self.frames.append((a[0],cpu.reg_read(ar.UC_ARM64_REG_X30),a[1],a[2]))
            cpu.reg_write(ar.UC_ARM64_REG_X0,a[0]);cpu.reg_write(ar.UC_ARM64_REG_X1,b.ST)
            cpu.reg_write(ar.UC_ARM64_REG_X30,RETURN);cpu.reg_write(ar.UC_ARM64_REG_PC,image['entry']);return
        if address==RETURN:
            handle,lr,out_bytes,out_data=self.frames.pop();status=cpu.reg_read(ar.UC_ARM64_REG_X0)
            if handle==0x90000000:status=self.target_status
            self.images[handle]['live']=False;self.write64(out_bytes,0);self.write64(out_data,0)
            cpu.reg_write(ar.UC_ARM64_REG_X0,status);cpu.reg_write(ar.UC_ARM64_REG_PC,lr);return
        super().hook(cpu,address,size,user)

def store(f,path,data):
    raw=path.encode();nonce=0x1122
    envelope=struct.pack('<8sQIIQ32s',b'KBLPUT01',nonce,len(raw),0,len(data),hashlib.sha256(data).digest())+raw+data
    assert f.request(8,f'env1:{nonce:016x}',envelope)['final'][0][0]

def run(driver,target,base):
    f=Fixture(driver,base);f.boot();assert not f.loaded and not f.started
    assert f.request(1)['final'][0][0];assert not f.loaded
    store(f,'/r',target)
    out,rc,_=f.command('efi -cs /r one "a b"');assert not rc and b'clearstart queued'in out
    assert len(f.loaded)==len(f.started)==2 and f.started==[0x91000000,0x90000000]
    assert not f.frames and not f.allocations and all(not i['live']for i in f.images.values())
    status=f.request(4);assert 'KBL1 clearstart=returned'in status['lines']
    assert 'KBL1 cs_resident=parent,driver'in status['lines']
    assert f.request(1)['final'][0][0];out,rc,_=f.command('echo fastboot-alive');assert not rc and out==b'fastboot-alive\n'
    assert f.request(3)['final'][0][0]and not f.allocations
    return {'passed':True,'base':hex(base),'separateActualPeImages':2,'nativePoolsAtTargetStart':0,'postReturnAllocations':0,'fastbootRestart':True}

def ordinary(driver,target):
    f=Fixture(driver,0x18000000);f.expect_clean=False;f.boot();f.request(1);store(f,'/r',target)
    out,rc,_=f.command('efi /r');assert not rc and b'EFI stage='in out and len(f.loaded)==len(f.started)==1
    out,rc,_=f.command('echo sync-efi-returned');assert not rc and out==b'sync-efi-returned\n'
    out,rc,_=f.command('efi -bl /r');assert rc==1 and b'bootloader preparation'in out and len(f.loaded)==1
    assert f.request(3)['final'][0][0]and not f.allocations
    return {'passed':True,'plainEfiRemainsSynchronous':True,'nativeRuntimePreservedOnReturn':True,'bootloaderPreflightPreserved':True}

def failures(driver,target):
    results=[]
    for fail in [1,2]:
        f=Fixture(driver,0x18000000);f.boot();f.request(1);store(f,'/r',target);f.fail_load=fail
        out,rc,_=f.command('efi --clearstart /r');assert rc==1 and b'Fastboot retained'in out and not f.started
        out,rc,_=f.command('echo still-running');assert not rc and out==b'still-running\n'
        assert f.request(3)['final'][0][0]and not f.allocations
        results.append({'loadFailure':fail,'targetStarts':0,'nativeFastbootCommandStillWorks':True})
    return results

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--driver',type=Path,required=True);p.add_argument('--target',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    target=a.target.read_bytes();report={'passed':True,'scope':__doc__,'driverSha256':hashlib.sha256(a.driver.read_bytes()).hexdigest(),
        'results':[run(a.driver,target,base)for base in [0x10000000,0x18000000]],'failures':failures(a.driver,target),'ordinaryEfi':ordinary(a.driver,target),'physicalDeviceIO':False}
    (a.output/'arm64-clearstart.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()


