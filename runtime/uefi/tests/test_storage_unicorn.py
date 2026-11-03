"""Actual ARM64 parent -> driver -> VFS/provider -> native binary USB fixture.

EFI image bookkeeping, BlockIO memory disks and decoded USB events are explicit
models. Actual parent/controller/native/VFS/provider/USB C executes. No device
or real partition is touched, and a mock flash/write is never sent externally.
"""
from pathlib import Path
import hashlib,importlib.util,json,re,struct,sys
from unicorn import UC_HOOK_CODE

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('storage_usb_base',HERE/'test_driver_usb_unicorn.py')
u=importlib.util.module_from_spec(spec);spec.loader.exec_module(u)
b=u.base;ar=b.ar
PARENT=0x11000000;CFG=b.DATA+0x300000;PSTATE=CFG+4096;GLOBALS=CFG+8192;FACTS=CFG+12288;ARG=CFG+16384;UPLOAD=CFG+65536
DISK_INFO=b.DATA+0x800000
EXTRA={n:b.CALLBACK+0xc00+i*0x40 for i,n in enumerate(['enum','read','write','flush','load','start','unload','getvar','fetch'])}
SERVICE=bytes.fromhex('a816937f315c524badc328d266049dc1')


class StorageFixture(u.BoundFixture):
    def __init__(self,driver,module):
        super().__init__(driver,0x10000000)
        self.disk_calls={n:0 for n in ['enum','read','write','flush']};self.native_answers=[];self.generic=[]
        self.disks=[bytearray((i*31+seed)&255 for i in range(262144))for seed in [3,7]]
        self.cpu.mem_map(PARENT,65536,5);self.cpu.mem_write(PARENT,Path(module).read_bytes())
        for i,label in enumerate(['abl_a','efisp']):
            at=DISK_INFO+i*4096;io=at+256;media=at+512
            self.cpu.mem_write(at,struct.pack('<IIB7x',0x1000,2,0)+struct.pack('<4Q3Q',1,0,0,0,100,611,0)+label.encode('utf-16le').ljust(72,b'\0'))
            self.cpu.mem_write(io,struct.pack('<6Q',0x20031,media,0,EXTRA['read'],EXTRA['write'],EXTRA['flush']))
            self.cpu.mem_write(media,struct.pack('<I5B3xII4xQ',7,0,1,1,0,1,512,4096,511))
        self.write64(b.BS+0x138,EXTRA['enum'])
        for off,name in [(0xc8,'load'),(0xd0,'start'),(0xe0,'unload')]:self.write64(b.BS+off,EXTRA[name])
        self.cpu.mem_write(GLOBALS,struct.pack('<4Q',0x6789,b.ST,b.BS,0))
        fields=[GLOBALS-PARENT,GLOBALS+8-PARENT,GLOBALS+16-PARENT,GLOBALS+24-PARENT,
                u.USB_CB['native_okay']-PARENT,u.USB_CB['native_fail']-PARENT,0,512,PSTATE-PARENT,1,FACTS-PARENT,EXTRA['getvar']-PARENT,0]
        self.cpu.mem_write(CFG,struct.pack('<QII13Q',0x31544e524150424b,1,120,*fields))
        fields=[1,88,0,u.SLOT-PARENT,u.ACK_SLOT-PARENT,u.RX_SLOT-PARENT,u.STATE-PARENT,0,0,
                u.USB_CB['native_okay']-PARENT,u.USB_CB['native_fail']-PARENT,u.USB_CB['frequency']-PARENT,u.USB_CB['ticks']-PARENT]
        self.cpu.mem_write(FACTS,struct.pack('<II5QII4Q',*fields))

    def hook(self,cpu,address,size,user):
        a=[cpu.reg_read(r)for r in b.REGS]
        if address in [u.USB_CB['native_okay'],u.USB_CB['native_fail']]:self.native_answers.append((address==u.USB_CB['native_okay'],self.string(a[0])))
        if address==b.CB['handle']:
            guid=bytes(cpu.mem_read(a[1],16))
            if a[0]==b.IMAGE and guid==SERVICE:self.write64(a[2],self.protocol);self.ret();return
            if a[0]in [0x900001,0x900002]:
                i=a[0]-0x900001;at=DISK_INFO+i*4096
                if guid==bytes.fromhex('2cf6f28c9bbc2148808dec9ec421a1a0'):self.write64(a[2],at);self.ret();return
                if guid==bytes.fromhex('215b4e965964d2118e3900a0c969723b'):self.write64(a[2],at+256);self.ret();return
                self.ret(b.ERR(14));return
        name=next((n for n,v in EXTRA.items()if v==address),None)
        if not name:return super().hook(cpu,address,size,user)
        if name=='enum':
            assert a[0]==2 and bytes(cpu.mem_read(a[1],16))==bytes.fromhex('215b4e965964d2118e3900a0c969723b')and not a[2]
            p=self.allocate(16,'pool');cpu.mem_write(p,struct.pack('<2Q',0x900001,0x900002));self.write64(a[3],2);self.write64(a[4],p);self.disk_calls[name]+=1;self.ret();return
        if name in ['read','write','flush']:
            i=(a[0]-DISK_INFO-256)//4096;assert i in [0,1] and a[0]==DISK_INFO+i*4096+256
            self.disk_calls[name]+=1
            if name=='flush':self.ret();return
            _,media,lba,n,p=a[:5];assert media==7 and n and n%512==0 and p%4096==0 and lba*512+n<=len(self.disks[i])
            if name=='read':cpu.mem_write(p,bytes(self.disks[i][lba*512:lba*512+n]))
            else:self.disks[i][lba*512:lba*512+n]=cpu.mem_read(p,n)
            self.ret();return
        if name=='load':assert a[:3]==[0,0x6789,0];self.write64(a[5],b.IMAGE);self.ret();return
        if name=='start':assert a[0]==b.IMAGE;self.ret();return
        if name=='unload':raise AssertionError('unexpected driver-image unload')
        if name in ['getvar','fetch']:
            self.generic.append([name,self.string(a[0])]);self.native_answers.append((True,'native-'+name));self.tail_native_ack(True,'native-'+name);return

    def parent(self,op,arg=b'',download=b'',pages=True):
        if isinstance(arg,str):arg=arg.encode()
        prefix={13:b'partition-size:',14:b'has-slot:',12:b'max-fetch-size'}.get(op,b'')
        if self.bound:
            assert not self.usb_events
            wire=(b'fetch:'if op==11 else b'fixture:')+arg
            self.cpu.mem_write(u.RX,wire[:63]);self.usb_events.append((2,struct.pack('<IB3xQQ',1,1,min(63,len(wire)),u.RX)));assert self.poll_native()[0]==2
        self.cpu.mem_write(ARG,prefix+arg+b'\0');self.cpu.mem_write(UPLOAD,download or b'\0');self.native_answers.clear()
        self.call(PARENT,[CFG,PARENT,op,ARG+len(prefix),UPLOAD if download else 0,len(download)])
        self.bound=self.read64(u.SLOT)!=u.DEVICE
        while self.usb_events:self.poll_native()
        assert len(self.native_answers)==1,(op,arg,self.native_answers)
        final=self.native_answers[0];lines=[]
        if pages and op not in [15,16] and op not in [13,14]:
            header=self.parent(15,pages=False)['final'];assert header[0]
            match=re.fullmatch(r'KBLC1 g=([0-9a-f]{16}) n=(\d+)',header[1]);assert match
            for i in range(int(match[2])):
                answer=self.parent(16,f'{match[1]}:{i}',pages=False)['final'];assert answer[0];lines.append(answer[1])
        return {'final':final,'lines':lines}

    def command(self,text):
        data=text.encode()
        if 11+len(data)*2<=63:reply=self.parent(5,b' '+data.hex().encode())
        else:
            reply=self.parent(6,b' B');identifier=next(x[9:]for x in reply['lines']if x.startswith('KBL1 put='))
            for at in range(0,len(data),14):assert self.parent(6,f' D{identifier}:{at:04x}:{data[at:at+14].hex()}')['final'][0]
            reply=self.parent(6,f' E{identifier}:{len(data):04x}')
        assert reply['final'][0],reply
        job=next(x[9:]for x in reply['lines']if x.startswith('KBL1 job='));output=b''
        for polls in range(500):
            reply=self.parent(7,' '+job);assert reply['final'][0],reply
            output+=b''.join(bytes.fromhex(x[9:])for x in reply['lines']if x.startswith('KBL1 out='))
            done=next(x for x in reply['lines']if x.startswith('KBL1 done='))
            if 'done=1' in done:return output,int(done.split('rc=')[1]),polls+1
        raise AssertionError('command poll budget exhausted')


def run(driver,module,report,port_program=None):
    f=StorageFixture(driver,module);f.boot();assert not any(f.disk_calls.values())
    assert not f.parent(11,b'efisp:0:100',pages=False)['final'][0] and not any(f.disk_calls.values())
    assert f.parent(13,b'efisp',pages=False)['final']==(True,'native-getvar') and not any(f.disk_calls.values())
    assert f.parent(1)['final'][0] and not any(f.disk_calls.values())
    f.binary.clear();assert f.parent(11,b'efisp:11:10003')['final'][0]
    assert bytes(f.binary)==bytes(f.disks[1][17:17+65539]) and not f.disk_calls['write']and not f.disk_calls['flush']
    fetched=hashlib.sha256(f.binary).hexdigest()
    before=dict(f.disk_calls);f.write64(CFG+112,EXTRA['fetch']-PARENT)
    assert f.parent(11,b'efisp:0:10',pages=False)['final']==(True,'native-fetch')and f.disk_calls==before
    f.write64(CFG+112,0)
    out,rc,polls=f.command('ls /dev/block/by-name');assert rc==0 and b'abl_a\n'in out and b'efisp\n'in out,(rc,out)
    listing=out.decode()
    payload=bytes(range(31));assert f.parent(8,b' '+b'/payload'.hex().encode(),payload)['final'][0]
    original=bytes(f.disks[0]);writes=f.disk_calls['write'];flushes=f.disk_calls['flush']
    out,rc,write_polls=f.command('dd if=/payload of=/dev/block/by-name/abl_a bs=1 seek=17 count=31 conv=fsync')
    assert rc==0 and out==b'dd: copied=31 bytes\n',(rc,out)
    assert f.disks[0][:17]==original[:17]and f.disks[0][17:48]==payload and f.disks[0][48:]==original[48:]
    assert f.disk_calls['write']==writes+31 and f.disk_calls['flush']==flushes+1
    # READ_OPEN stays immutable RAMFS-only. Partition readback uses explicit
    # chunked FETCH, avoiding an unbounded synchronous whole-disk hash here.
    opened=f.parent(9,b' '+b'/dev/block/by-name/abl_a'.hex().encode());assert not opened['final'][0]
    f.binary.clear();assert f.parent(11,b'abl_a:0:40000')['final'][0]and bytes(f.binary)==bytes(f.disks[0])
    opened=f.parent(9,b' '+b'/payload'.hex().encode());assert opened['final'][0]
    alias=next(x[10:]for x in opened['lines']if x.startswith('KBL1 file='));f.binary.clear()
    assert f.parent(11,alias)['final'][0]and bytes(f.binary)==payload;assert f.parent(10,' '+alias)['final'][0]
    port_result=None
    if port_program:
        executable=Path(port_program).read_bytes();assert f.parent(8,b' '+b'/copy'.hex().encode(),executable)['final'][0]
        writes=f.disk_calls['write'];flushes=f.disk_calls['flush']
        output,code,port_polls=f.command('./copy /dev/block/by-name/efisp /copied')
        assert code==0 and b'copied=262144 'in output and b'verified EL1\n'in output,(code,output)
        opened=f.parent(9,b' '+b'/copied'.hex().encode());assert opened['final'][0]
        alias=next(x[10:]for x in opened['lines']if x.startswith('KBL1 file='));f.binary.clear()
        assert f.parent(11,alias)['final'][0]and bytes(f.binary)==bytes(f.disks[1]);assert f.parent(10,' '+alias)['final'][0]
        assert f.disk_calls['write']==writes and f.disk_calls['flush']==flushes
        port_result={'program_sha256':hashlib.sha256(executable).hexdigest(),'output':output.decode(),'polls':port_polls,
                     'copied_bytes':len(f.binary),'copy_sha256':hashlib.sha256(f.binary).hexdigest(),'no_block_write_or_flush':True}
    before=dict(f.disk_calls);assert f.parent(13,b'efisp',pages=False)['final'][0]and f.disk_calls==before
    assert f.parent(3)['final'][0] and not f.allocations and f.call(f.can_unload,[f.protocol])==1
    assert not f.parent(11,b'efisp:0:100',pages=False)['final'][0]and f.disk_calls==before
    result={'scope':__doc__,'passed':True,'driver_sha256':hashlib.sha256(Path(driver).read_bytes()).hexdigest(),
            'parent_sha256':hashlib.sha256(Path(module).read_bytes()).hexdigest(),'partition_fetch_bytes':65539,'partition_fetch_sha256':fetched,
            'gpt_labels_listed':listing,'rmw_written_bytes':31,'write_command_polls':write_polls,'list_polls':polls,
            'neighbor_bytes_preserved':True,'native_fetch_and_getvar_preserved':True,'provider_calls':f.disk_calls,'live_allocations_after_off':0,
            'ordinary_c_port_program':port_result}
    Path(report).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps({'passed':True,'provider_calls':f.disk_calls}))


if __name__=='__main__':run(*sys.argv[1:])
