"""Actual ARM64 driver + native USB binding; explicit modeled EFI/USB hardware."""
from pathlib import Path
import hashlib
import importlib.util
import json
import struct
from collections import deque
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('kbl_driver_base_uc',HERE/'test_driver_unicorn.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
ar=base.ar
USB_NAMES=['usb_send','usb_event','usb_allocate','usb_free','usb_abort','native_okay','native_fail','frequency','ticks','dxe_get','dxe_set']
USB_CB={n:base.CALLBACK+0x800+i*0x40 for i,n in enumerate(USB_NAMES)}
SLOT=base.DATA+0x5000;ACK_SLOT=SLOT+8;RX_SLOT=SLOT+16;STATE=SLOT+24
FACTS=base.DATA+0x6000;DEVICE=base.DATA+0x7000;ACK=base.DATA+0x8000;RX=base.DATA+0x9000;EVENT=base.DATA+0xa000

class BoundFixture(base.Fixture):
    def __init__(self,path,address,fallback=False):
        self.usb_events=deque();self.usb_calls={n:0 for n in USB_NAMES};self.usb_tick=0
        self.wire=[];self.binary=bytearray();self.binary_left=0;self.native_rx_rearms=0;self.bound=False
        super().__init__(path,address)
        table=[0x10000001,0,USB_CB['usb_send'],USB_CB['usb_event'],USB_CB['usb_allocate'],USB_CB['usb_free'],0,USB_CB['usb_abort'],0,0]
        self.cpu.mem_write(DEVICE,struct.pack('<10Q',*table));self.write64(SLOT,DEVICE);self.write64(ACK_SLOT,ACK);self.write64(RX_SLOT,RX);self.cpu.mem_write(STATE,b'\0')
        self.dxe_fallback=fallback
        if fallback:
            self.missing_attributes=True;table=base.DATA+0xb000;config=base.DATA+0xc000
            self.cpu.mem_write(config,bytes.fromhex('ba34ad05026f1442952e4da0398e2bb9')+struct.pack('<Q',table))
            self.write64(base.ST+0x68,1);self.write64(base.ST+0x70,config)
            self.write64(table,0x565245535f455844);self.write32(table+12,0x48)
            self.write64(table+0x38,USB_CB['dxe_get']);self.write64(table+0x40,USB_CB['dxe_set'])
    def tail_native_ack(self,okay,text):
        data=(b'OKAY'if okay else b'FAIL')+text.encode();assert len(data)<=64
        self.cpu.mem_write(ACK,data)
        for r,v in zip(base.REGS,[0x81,len(data),ACK]):self.cpu.reg_write(r,v)
        self.cpu.reg_write(ar.UC_ARM64_REG_PC,self.read64(self.read64(SLOT)+0x10))
    def hook(self,cpu,address,size,user):
        args=[cpu.reg_read(r)for r in base.REGS]
        if address==base.CB['final'] and self.bound:
            self.calls['final']+=1;text=self.string(args[2]);self.final.append((args[1],text));self.tail_native_ack(args[1],text);return
        name=next((n for n,v in USB_CB.items()if v==address),None)
        if not name:return super().hook(cpu,address,size,user)
        self.usb_calls[name]+=1
        if name=='usb_send':
            ep,n,p=args[:3]
            if ep==0x81:
                data=bytes(cpu.mem_read(p,n));self.wire.append(data)
                if p!=ACK:
                    if data.startswith(b'DATA')and len(data)==12:self.binary_left=int(data[4:],16)
                    else:assert n<=self.binary_left;self.binary.extend(data);self.binary_left-=n
                self.usb_events.append((2,struct.pack('<IB3xQQ',1,ep,n,p)))
            else:assert ep==1;self.native_rx_rearms+=1
            self.ret()
        elif name=='usb_event':
            kind,payload=self.usb_events.popleft()if self.usb_events else(0,b'')
            self.write32(args[0],kind);self.write64(args[1],len(payload))
            if payload:cpu.mem_write(args[2],payload)
            self.ret()
        elif name=='usb_allocate':
            n,out=args[:2];mapped=(n+4095)&~4095;p=self.cursor;self.cursor+=mapped+4096
            cpu.mem_map(p,mapped,3);self.allocations[p]=(n,mapped,'dma');self.write64(out,p);self.ret()
        elif name=='usb_free':self.ret(self.release(args[0],'dma'))
        elif name=='usb_abort':assert args[0]==0x81;self.ret()
        elif name in ['native_okay','native_fail']:self.tail_native_ack(name=='native_okay',self.string(args[0]))
        elif name=='frequency':self.ret(1000000)
        elif name=='ticks':self.usb_tick+=100;self.ret(self.usb_tick)
        elif name=='dxe_get':
            address,out=args[:2];raw,flags,at,n=self.attributes(address&~4095,4096)
            descriptor=struct.pack('<4QII2Q',address&~4095,4096,0x26008,flags[at]|8,2,0,0,0)
            cpu.mem_write(out,descriptor);self.ret()
        elif name=='dxe_set':
            address,n,bits=args[:3];raw,flags,at,count=self.attributes(address,n)
            assert bits&8 and bits&~0x26008==0
            for i in range(at,at+count):
                flags[i]=bits&0x26000;f=flags[i];perms=(0 if f&0x2000 else 1)|(0 if f&0x20000 else 2)|(0 if f&0x4000 else 4)
                assert perms&6!=6;cpu.mem_protect(raw+i*4096,4096,perms)
            self.ret()
    def bind(self):
        fields=[1,88,base.DATA,SLOT-base.DATA,ACK_SLOT-base.DATA,RX_SLOT-base.DATA,STATE-base.DATA,0,0,
                USB_CB['native_okay']-base.DATA,USB_CB['native_fail']-base.DATA,USB_CB['frequency']-base.DATA,USB_CB['ticks']-base.DATA]
        self.cpu.mem_write(FACTS,struct.pack('<II5QII4Q',*fields));before=dict(self.usb_calls)
        assert self.call(self.read64(self.protocol+32),[self.protocol,FACTS])==0
        self.bound=True;assert self.read64(SLOT)!=DEVICE
        assert self.usb_calls['frequency']==before['frequency']+1
        assert all(self.usb_calls[k]==before[k]for k in USB_NAMES if k!='frequency')
    def poll_native(self):
        kind_p=EVENT;count_p=EVENT+8;payload_p=EVENT+16
        self.write32(kind_p,0);self.write64(count_p,0)
        assert self.call(self.read64(self.read64(SLOT)+0x18),[kind_p,count_p,payload_p])==0
        kind=struct.unpack('<I',self.cpu.mem_read(kind_p,4))[0];count=self.read64(count_p)
        if kind==2:
            status,ep,n,p=struct.unpack('<IB3xQQ',self.cpu.mem_read(payload_p,24))
            if ep==0x81 and status==1:
                # Original native decoded TX branch is explicitly modeled.
                assert self.call(self.read64(self.read64(SLOT)+0x10),[1,511,RX])==0
        return kind,count
    def request(self,op,arg=b'',download=b'',pages=True):
        if isinstance(arg,str):arg=arg.encode()
        if self.bound:
            assert not self.usb_events
            wire=(b'fetch:'if op==11 else b'fixture:')+arg
            self.cpu.mem_write(RX,wire[:63]);self.usb_events.append((2,struct.pack('<IB3xQQ',1,1,min(63,len(wire)),RX)))
            assert self.poll_native()[0]==2
        self.cpu.mem_write(base.ARG,arg or b'\0');self.cpu.mem_write(base.DOWNLOAD,download or b'\0')
        self.cpu.mem_write(base.REPLY,struct.pack('<4I',16,0,0,0));self.final=[];self.output=[]
        rc=self.call(self.request_ptr,[self.protocol,op,base.ARG if arg else 0,len(arg),base.DOWNLOAD if download else 0,len(download),base.LINK,base.REPLY])
        reply=struct.unpack('<4I',self.cpu.mem_read(base.REPLY,16));final=self.final[:]
        # Native USB final bypasses the parent control-link callback; inspect
        # the actual queued native ACK when the driver owns FETCH transport.
        if op==11 and not final and reply[2]:
            packet=self.wire[-1];assert packet.startswith((b'OKAY',b'FAIL'));final=[(packet[:4]==b'OKAY',packet[4:].decode())]
        assert rc in [0,0xfffffffe]and len(final)==1
        if self.bound:
            while self.usb_events:self.poll_native()
        lines=[]
        if pages and op not in [15,16]:
            header=self.request(15,pages=False)['final'][0];assert header[0]
            import re
            m=re.fullmatch(r'KBLC1 g=([0-9a-f]{16}) n=(\d+)',header[1]);assert m
            for i in range(int(m[2])):
                page=self.request(16,f'{m[1]}:{i}',pages=False)['final'][0];assert page[0];lines.append(page[1])
        return {'rc':rc,'reply':reply,'final':final,'lines':lines}

def run_one(driver,program,fallback=False,restore_fault=False):
    f=BoundFixture(driver,0x10000000,fallback=fallback);f.boot();f.bind()
    # A loaded observer with no completed start ACK has no idle witness.
    first=f.request(11,'kbl.0000000000000001');assert not first['final'][0][0]
    start=f.request(1);assert start['final'][0][0]
    data=Path(program).read_bytes();path=b'/hello';nonce=0x123456789abcdef0
    envelope=struct.pack('<8sQIIQ32s',b'KBLPUT01',nonce,len(path),0,len(data),hashlib.sha256(data).digest())+path+data
    assert f.request(8,f'env1:{nonce:016x}',envelope)['final'][0][0]
    f.deny_release_restore=restore_fault
    output,rc,polls=f.command('./hello')
    if fallback:
        # This older transport fixture has no enabled MMU or real table model.
        # Production now refuses its software-only GCD permission claims.
        assert rc==126 and output==b'ELF load failed; code=-4\n' and not f.calls['alloc_pages']
    else:assert rc==0 and b'after yield' in output
    if restore_fault:assert f.denied_release_restores and not f.calls['free_pages'] and f.pages
    read=f.request(9,path.hex());alias=next(s[10:]for s in read['lines']if s.startswith('KBL1 file='))
    before=f.native_rx_rearms;f.binary.clear();fetch=f.request(11,alias)
    assert fetch['final'][0][0]and bytes(f.binary)==data and f.binary_left==0
    program_fetch_hash=hashlib.sha256(f.binary).hexdigest()
    assert f.native_rx_rearms>before and f.usb_calls['usb_allocate']==f.usb_calls['usb_free']==2
    assert not f.usb_calls['usb_abort']and not f.calls['data']and not f.calls['write']
    assert f.request(10,alias)['final'][0][0]
    fragmented=None
    if not fallback and not restore_fault:
        blob=bytes((i*17)&255 for i in range(65536+257));blob_path=b'/blob';nonce+=1
        env=struct.pack('<8sQIIQ32s',b'KBLPUT01',nonce,len(blob_path),0,len(blob),hashlib.sha256(blob).digest())+blob_path+blob
        assert f.request(8,f'env1:{nonce:016x}',env)['final'][0][0]
        info=f.request(9,blob_path.hex());blob_alias=next(s[10:]for s in info['lines']if s.startswith('KBL1 file='))
        f.binary.clear();packet_begin=len(f.wire);assert f.request(11,blob_alias)['final'][0][0]
        lengths=[len(p)for p in f.wire[packet_begin:]if len(p)>64]
        assert bytes(f.binary)==blob and lengths==[65536,257]
        assert f.usb_calls['usb_allocate']==f.usb_calls['usb_free']==5
        assert f.request(10,blob_alias)['final'][0][0]
        fragmented={'bytes':len(blob),'sha256':hashlib.sha256(f.binary).hexdigest(),'body_dma_lengths':lengths}
    if restore_fault:
        off=f.request(3);assert not off['final'][0][0]and not off['reply'][1]and not f.calls['free_pages']and f.pages
        f.deny_release_restore=False
    off=f.request(3);assert off['final'][0][0]and off['reply'][1]==1
    assert not f.allocations and f.read64(SLOT)==DEVICE
    assert f.call(f.can_unload,[f.protocol])==1
    assert f.call(f.read64(base.LOADED+0x58),[base.IMAGE])==0
    return {'driver_sha256':hashlib.sha256(Path(driver).read_bytes()).hexdigest(),
            'download_sha256':hashlib.sha256(data).hexdigest(),'native_usb_fetch_sha256':program_fetch_hash,
            'usb_provider_calls':f.usb_calls,'parent_control_calls':f.calls,'native_rx_rearms':f.native_rx_rearms,
            'sdk_output':output.decode(),'sdk_polls':polls,'cpacr_el1':0,'live_allocations':len(f.allocations),
            'fbd_protocol_restored':f.read64(SLOT)==DEVICE,'original_parent_reply_link_binary_calls':f.calls['data']+f.calls['write'],
            'memory_attribute_provider':'DXE with unsupported MMU profile: refused before page allocation'if fallback else'Native MemoryAttribute protocol',
            'permission_restore_denied_then_retried':restore_fault,'denied_permission_restores':f.denied_release_restores,
            'fragmented_readback':fragmented}
def run(driver,program,report):
    result={'scope':'Actual ARM64 COFF driver + live observer/native USB C binding + native kernel/ELF. USB hardware/EFI services and original decoded TX consumer are explicit host models. No phone/physical USB/parent firmware claim.',
            'passed':True,'cases':3,'results':[run_one(driver,program),run_one(driver,program,fallback=True),run_one(driver,program,restore_fault=True)]}
    Path(report).write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');return result
if __name__=='__main__':
    import sys
    r=run(*sys.argv[1:]);print(json.dumps({'passed':r['passed'],'cases':r['cases']}))
