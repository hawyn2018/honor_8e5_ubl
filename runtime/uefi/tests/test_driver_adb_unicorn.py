"""Production ARM64 EFI driver + native shell/VFS + ADB + composite USB model.

Only the span validator and EFI/controller/DMA/native-loop boundaries are
explicit models. No device, sockets, firmware write or USB API is used. The
span injection is test-only; it is not part of the shipping driver image.
"""
from pathlib import Path
import argparse, hashlib, importlib.util, json, struct, subprocess
from collections import deque
import pefile
from unicorn import UC_HOOK_CODE

HERE=Path(__file__).resolve().parent;UEFI=HERE.parent;RUNTIME=UEFI.parent
SOURCE=RUNTIME.parent;ROOT=SOURCE.parents[2]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
u=module('adb_driver_usb_base',HERE/'test_driver_usb_unicorn.py');b=u.base;ar=b.ar
LLVM=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin')
XBASE=0x42000000
NAMES=['start_ex','stop','stall','fn_event','fn_transfer','fn_abort','fn_max','enum','read','write','flush']
EX={n:XBASE+i*0x40 for i,n in enumerate(NAMES)}
FN=b.DATA+0x200000;DESC=FN+4096;DEV=DESC+256;CFG=DESC+512;CONFIGS=DESC+768
DISK_INFO=b.DATA+0x800000
CMD={x:int.from_bytes(x.encode(),'little')for x in ['CNXN','OPEN','WRTE','OKAY','CLSE']}

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def packet(kind,a0=0,a1=0,data=b''):
    cmd=CMD[kind];return struct.pack('<6I',cmd,a0,a1,len(data),0,cmd^0xffffffff)+data

def build(output):
    """Include the unmodified production driver; add only read-only probes."""
    output.mkdir(parents=True,exist_ok=True)
    builder=module('adb_driver_builder',UEFI/'build_driver.py')
    wrapper=output/'driver_test_include.c'
    wrapper.write_text('#define KBL_DRIVER_ADB_TEST 1\n#include "'+(UEFI/'kbl_driver.c').as_posix()+'"\n'
        'uintptr_t kbl_driver_test_query(unsigned q){switch(q){\n'
        'case 0:return (uintptr_t)g.adb;case 1:return (uintptr_t)g.kernel.native;\n'
        'case 2:return (uintptr_t)g.adb_phase;case 3:return g.adb_usb.phase;\n'
        'case 4:return g.adb_usb.composite_started;case 5:return g.adb_pending_init;\n'
        'case 6:return g.adb_stop;case 7:return (uintptr_t)g.adb_pools;\n'
        'case 8:return (uintptr_t)g.pools;case 9:return g.adb_call;\n'
        'case 10:return g.in_request;case 11:return g.adb_router;\n'
        'case 12:return g.usb.attached;case 13:return g.adb_error;\n'
        'case 14:return g.adb_usb.rx_pending;case 15:return g.adb_usb.tx_pending;\n'
        'case 16:return g.adb_usb.configuration_uncertain;case 17:return g.session.phase;\n'
        'case 18:return g.reply_count;case 19:return g.reply_overflow;case 20:return (uintptr_t)(g.reply_lines?g.reply_lines:g.reply_inline);\n'
        'case 21:return kbl_adb_parked(g.adb);case 22:return kbl_adb_native_quiescent(g.adb);\n'
        'case 23:return kbl_adb_healthy(g.adb);case 24:return g.session.generation;case 25:return g.session.job;default:return 0;}}\n',encoding='utf-8')
    span_source=output/'span_model.c'
    span_source.write_text('#include <stddef.h>\nint kbl_driver_test_span(const void*p,size_t n,unsigned f){(void)p;(void)n;(void)f;return 0;}\n')
    blob,_=builder.build_handoff(LLVM,output)
    commands=[];objects=[]
    for index,s in enumerate([*builder.SOURCES,blob,span_source]):
        source=wrapper if s.name=='kbl_driver.c'else s
        obj=output/(str(index)+'-'+s.stem+'.obj');objects.append(obj)
        cmd=[LLVM/'clang.exe',*builder.compile_flags(s)]
        cmd+=['-c',source,'-o',obj];commands.append(cmd)
    target=output/'kbl_driver_adb_test.efi'
    commands.append([LLVM/'ld.lld.exe','-flavor','link','/subsystem:efi_boot_service_driver','/entry:EfiMain',
        '/nodefaultlib','/machine:arm64','/dynamicbase','/nxcompat','/opt:ref','/opt:icf','/timestamp:0',
        '/export:kbl_driver_test_span','/export:kbl_driver_test_query',f'/out:{target}',*objects])
    log=[]
    for cmd in commands:
        r=subprocess.run(list(map(str,cmd)),capture_output=True,text=True);log.append(r.stdout+r.stderr)
        if r.returncode:raise RuntimeError(r.stdout+r.stderr)
    (output/'build.log').write_text('\n'.join(log))
    return target,commands

class Fixture(u.BoundFixture):
    def __init__(self,driver,address):
        self.extra_calls={n:0 for n in NAMES};self.events_log=[];self.pending_rx={};self.aborts=[]
        self.defer_abort=set();self.held_aborts={};self.start_configs=[];self.host_pending=deque()
        self.start_identities=[]
        self.adb_wire=bytearray();self.adb_packets=[];self.adb_frames=bytearray();self.channels={}
        self.auto_ack=True;self.fn_original_events=0;self.span_checks=0;self.scope_queries=[]
        self.fail_next_adb_tx=False;self.failed_adb_tx=0
        self.status_checks=[]
        self.fail_readonly_segment=False
        self.disks=[bytearray((i*31+seed)&255 for i in range(262144))for seed in [3,7]]
        self.disk_calls={n:0 for n in ['enum','read','write','flush']}
        super().__init__(driver,address)
        self.cpu.mem_map(XBASE,4096,5);self.cpu.mem_write(XBASE,b'\xc0\x03\x5f\xd6'*1024)
        self.cpu.hook_add(UC_HOOK_CODE,self.hook,begin=XBASE,end=XBASE+4095)
        exports={s.name.decode():address+s.address for s in self.pe.DIRECTORY_ENTRY_EXPORT.symbols}
        self.span_ptr=exports['kbl_driver_test_span'];self.query_ptr=exports['kbl_driver_test_query']
        self.cpu.hook_add(UC_HOOK_CODE,self.span_hook,begin=self.span_ptr,end=self.span_ptr)
        self.write64(u.DEVICE+8,0);self.write64(u.DEVICE+0x30,EX['stop'])
        self.write64(u.DEVICE+0x40,EX['stall']);self.write64(u.DEVICE+0x48,EX['start_ex'])
        self.write32(FN,0x10001)
        for off,n in [(0x30,'fn_abort'),(0x48,'fn_event'),(0x50,'fn_transfer'),(0x58,'fn_max')]:self.write64(FN+off,EX[n])
        device=bytes.fromhex('1201000200000040d118d1ee000100000001')
        config=bytes.fromhex('0902200001010080320904000002ff4203000705010200020007058102000200')
        self.cpu.mem_write(DEV,device);self.cpu.mem_write(CFG,config);self.write64(CONFIGS,CFG)
        self.cpu.mem_write(DESC,struct.pack('<8Q',DEV,CONFIGS,0,0,0,0,0,0))
        for i,label in enumerate(['abl_a','efisp']):
            at=DISK_INFO+i*4096;io=at+256;media=at+512
            self.cpu.mem_write(at,struct.pack('<IIB7x',0x1000,2,0)+struct.pack('<4Q3Q',1,0,0,0,100,611,0)+label.encode('utf-16le').ljust(72,b'\0'))
            self.cpu.mem_write(io,struct.pack('<6Q',0x20031,media,0,EX['read'],EX['write'],EX['flush']))
            self.cpu.mem_write(media,struct.pack('<I5B3xII4xQ',7,0,1,1,0,1,512,4096,511))
        self.write64(b.BS+0x138,EX['enum'])
    def span_hook(self,cpu,address,size,user):
        p,n,denied=[cpu.reg_read(r)for r in b.REGS[:3]];self.span_checks+=1
        try:
            regions=list(cpu.mem_regions());end=p+n;at=p
            while at<end:
                match=next((r for r in regions if r[0]<=at<=r[1]),None)
                if not match or not match[2]&1 or denied&0x20000 and not match[2]&2 or denied&0x4000 and not match[2]&4:
                    self.ret(0);return
                at=min(end,match[1]+1)
            self.ret(1)
        except Exception:self.ret(0)
    def query(self,q):return self.call(self.query_ptr,[q])
    def phase(self):return self.string(self.query(2))
    def hook(self,cpu,address,size,user):
        a=[cpu.reg_read(r)for r in b.REGS]
        if address==b.CB['set']and self.fail_readonly_segment and a[3]&0x20000:
            self.fail_readonly_segment=False;self.calls['set']+=1;self.ret(b.ERR(15));return
        if address==b.CB['locate'] and bytes(cpu.mem_read(a[0],16))==bytes.fromhex('3a96d2325dfe304fb6336e5dc55803cc'):
            self.calls['locate']+=1;self.events_log.append('locate-usbfn');self.write64(a[2],FN);self.ret();return
        if address==b.CB['handle'] and a[0]in [0x900001,0x900002]:
            i=a[0]-0x900001;at=DISK_INFO+i*4096;guid=bytes(cpu.mem_read(a[1],16))
            if guid==bytes.fromhex('2cf6f28c9bbc2148808dec9ec421a1a0'):self.write64(a[2],at);self.ret();return
            if guid==bytes.fromhex('215b4e965964d2118e3900a0c969723b'):self.write64(a[2],at+256);self.ret();return
            self.ret(b.ERR(14));return
        if address==u.USB_CB['usb_send']:
            self.usb_calls['usb_send']+=1;ep,n,p=a[:3];self.events_log.append(f'send-{ep:02x}')
            if ep in [1,2]:
                assert ep not in self.pending_rx,('RX rearm before terminal',ep)
                self.pending_rx[ep]=(p,n)
                if ep==1:self.native_rx_rearms+=1
            elif ep in [0x81,0x82]:
                if ep==0x82 and self.fail_next_adb_tx:
                    self.fail_next_adb_tx=False;self.failed_adb_tx+=1
                    self.usb_events.append((2,struct.pack('<IB3xQQ',3,ep,0,p)));self.ret();return
                data=bytes(cpu.mem_read(p,n));self.wire.append(data)
                if ep==0x82:self.adb_wire.extend(data);self.adb_frames.extend(data)
                self.usb_events.append((2,struct.pack('<IB3xQQ',1,ep,n,p)))
            else:raise AssertionError(('endpoint',ep))
            self.ret();return
        if address==u.USB_CB['usb_abort']:
            self.usb_calls['usb_abort']+=1;ep=a[0];self.aborts.append(ep);self.events_log.append(f'abort-{ep:02x}')
            assert ep in self.pending_rx,('abort unknown RX',ep)
            p,n=self.pending_rx[ep]
            if ep in self.defer_abort:self.held_aborts[ep]=(p,n)
            else:self.complete_abort(ep)
            self.ret();return
        name=next((n for n,v in EX.items()if v==address),None)
        if not name:return super().hook(cpu,address,size,user)
        self.extra_calls[name]+=1
        if name=='start_ex':
            self.events_log.append('start-ex');assert not self.pending_rx.get(1), 'native RX must terminate before re-enumeration'
            d=a[0];device=bytes(cpu.mem_read(self.read64(d),18))
            identity={'vid':hex(int.from_bytes(device[8:10],'little')),'pid':hex(int.from_bytes(device[10:12],'little')),
                      'bcdDevice':hex(int.from_bytes(device[12:14],'little'))}
            assert identity['pid']=='0xd0d1'and identity['bcdDevice']=='0x102';self.start_identities.append(identity)
            cfg=self.read64(self.read64(d+8));raw=bytes(cpu.mem_read(cfg,struct.unpack('<H',cpu.mem_read(cfg+2,2))[0]))
            assert raw[4]==2 and b'\xff\x42\x01' in raw;self.start_configs.append(raw);self.ret()
        elif name=='stop':raise AssertionError('StopController/Device.Stop is forbidden after composite startup')
        elif name=='stall':
            assert a[0]in [2,0x82]and a[1]in [0,1]
            if a[1]:
                assert 2 not in self.pending_rx,'Abort request alone is not RX terminal ownership return before STALL'
                assert not any(kind==2 and struct.unpack('<IB3xQQ',payload)[1]in [2,0x82]
                               for kind,payload in self.usb_events),'STALL must await owned endpoint terminal outcomes'
            self.events_log.append(f'stall-{a[0]:02x}-{a[1]}');self.ret()
        elif name=='fn_event':self.fn_original_events+=1;self.write32(a[1],0);self.write64(a[2],0);self.ret()
        elif name=='fn_max':assert a[0]==FN;self.write64(a[1],1024);self.ret()
        elif name in ['fn_transfer','fn_abort']:raise AssertionError('unsolicited EP0 operation')
        elif name=='enum':
            assert a[0]==2 and bytes(cpu.mem_read(a[1],16))==bytes.fromhex('215b4e965964d2118e3900a0c969723b')and not a[2]
            p=self.allocate(16,'pool');cpu.mem_write(p,struct.pack('<2Q',0x900001,0x900002));self.write64(a[3],2);self.write64(a[4],p);self.disk_calls[name]+=1;self.ret()
        elif name in ['read','write','flush']:
            i=(a[0]-DISK_INFO-256)//4096;assert i in [0,1]and a[0]==DISK_INFO+i*4096+256
            self.disk_calls[name]+=1
            if name=='flush':self.ret();return
            _,media,lba,n,p=a[:5];assert media==7 and n and n%512==0 and p%4096==0 and lba*512+n<=len(self.disks[i])
            if name=='read':cpu.mem_write(p,bytes(self.disks[i][lba*512:lba*512+n]))
            else:self.disks[i][lba*512:lba*512+n]=cpu.mem_read(p,n)
            self.ret()
    def complete_abort(self,ep):
        p,n=self.pending_rx.pop(ep);self.held_aborts.pop(ep,None)
        self.usb_events.append((2,struct.pack('<IB3xQQ',2,ep,0,p)))
    def bind(self):
        f=[1,88,b.DATA,u.SLOT-b.DATA,u.ACK_SLOT-b.DATA,u.RX_SLOT-b.DATA,u.STATE-b.DATA,0,DESC-b.DATA,
           u.USB_CB['native_okay']-b.DATA,u.USB_CB['native_fail']-b.DATA,u.USB_CB['frequency']-b.DATA,u.USB_CB['ticks']-b.DATA]
        self.cpu.mem_write(u.FACTS,struct.pack('<II5QII4Q',*f));before=dict(self.usb_calls)
        assert self.call(self.read64(self.protocol+32),[self.protocol,u.FACTS])==0
        self.bound=True;assert self.read64(u.SLOT)!=u.DEVICE
        assert self.usb_calls['frequency']==before['frequency']+1
        assert not any(self.extra_calls.values()) and not self.span_checks and self.calls['locate']==0
    def poll_native(self):
        kind_p=u.EVENT;count_p=u.EVENT+8;payload_p=u.EVENT+16
        self.write32(kind_p,0);self.write64(count_p,0)
        assert self.call(self.read64(self.read64(u.SLOT)+0x18),[kind_p,count_p,payload_p])==0
        kind=struct.unpack('<I',self.cpu.mem_read(kind_p,4))[0];count=self.read64(count_p)
        if kind==2:
            status,ep,n,p=struct.unpack('<IB3xQQ',self.cpu.mem_read(payload_p,24))
            if ep==0x81 and status==1:
                # Explicit original Fastboot final-TX consumer model.
                assert 1 not in self.pending_rx
                assert self.call(self.read64(self.read64(u.SLOT)+0x10),[1,511,u.RX])==0
        return kind,count
    def raw_request(self,op,arg=b'',download=b''):
        if isinstance(arg,str):arg=arg.encode()
        if 1 in self.pending_rx:
            p,n=self.pending_rx.pop(1);wire=(b'fixture:'+arg)[:min(n,63)]
            self.cpu.mem_write(p,wire);self.usb_events.append((2,struct.pack('<IB3xQQ',1,1,len(wire),p)))
            assert self.poll_native()[0]==2
        self.cpu.mem_write(b.ARG,arg or b'\0');self.cpu.mem_write(b.DOWNLOAD,download or b'\0')
        self.cpu.mem_write(b.REPLY,struct.pack('<4I',16,0,0,0));self.final=[];self.output=[]
        before=(dict(self.usb_calls),dict(self.extra_calls),dict(self.disk_calls),dict(self.calls))
        rc=self.call(self.request_ptr,[self.protocol,op,b.ARG if arg else 0,len(arg),b.DOWNLOAD if download else 0,len(download),b.LINK,b.REPLY])
        assert rc in [0,0xfffffffe];assert len(self.final)==1,(op,self.final)
        if op==4:
            count=self.query(18);assert count<=24 and self.query(19)==0
            pointer=self.query(20);lines=[self.string(pointer+i*57)for i in range(count)]
            assert all(len(line)<=56 for line in lines)
            for prefix in ['KBL1 adb_usb=','KBL1 adb_flags=','KBL1 adb_fw=','KBL1 usb_errors=']:
                assert len([line for line in lines if line.startswith(prefix)])==1,lines
            usb,extra,disk,calls=before
            assert self.extra_calls==extra and self.disk_calls==disk
            assert all(self.usb_calls[k]==usb[k]+(1 if k=='usb_send'else 0)for k in usb)
            assert all(self.calls[k]==calls[k]+(1 if k=='final'else 0)for k in calls),(calls,self.calls)
            self.status_checks.append({'lines':count,'maxLineBytes':max(map(len,lines)),
                'overflow':False,'extraUsbCalls':'Only one original native Fastboot final Send','reply':lines})
        return self.final[0]
    def control(self,op,arg=b''):
        final=self.raw_request(op,arg)
        while self.usb_events:self.poll_native()
        return final
    def begin(self,native_first=True,busy=False):
        self.boot();self.bind();assert self.query(0)==self.query(1)==0
        before=(dict(self.extra_calls),self.calls['locate'],self.span_checks)
        # Plain START creates only native KBL. Repeated START, read-only
        # STATUS and attach may neither allocate ADB nor arm a USB change.
        native=0;generation=0;job=0
        if native_first:
            assert self.control(1)[0];native=self.query(1);generation=self.query(24)
            assert native and not self.query(0)and not self.query(5)
            for op in [1,2,4]:assert self.control(op)[0]
            self.settle(5)
            assert self.query(1)==native and not self.query(0)and not self.query(5)
            assert (self.extra_calls,self.calls['locate'],self.span_checks)==before
            assert self.query(2)==0
            if busy:
                assert self.control(5,b'echo pending-optin'.hex())[0]
                job=self.query(25);assert job and self.query(17)==3
        assert self.raw_request(17)[0]
        if native:
            assert self.query(1)==native and self.query(24)==generation and self.query(25)==job
        assert self.query(1)and not self.query(0)and self.query(5)==1
        assert (self.extra_calls,self.calls['locate'],self.span_checks)==before
        assert self.phase()=='waiting-start-ack'
        assert self.usb_events and not self.pending_rx
        self.defer_abort.add(1);assert self.poll_native()[0]==2 # forwards START ACK + native rearm
        assert (self.extra_calls,self.calls['locate'],self.span_checks)==before
        assert self.poll_native()[0]==0 # initializes ADB only now, requests RX abort
        assert self.calls['locate']==1 and self.span_checks and self.held_aborts.get(1)
        assert self.extra_calls['start_ex']==0
        for _ in range(3):self.poll_native()
        assert self.extra_calls['start_ex']==0,'Abort success alone is not a terminal fence'
        self.complete_abort(1);self.poll_native();self.poll_native()
        assert self.extra_calls['start_ex']==1 and self.query(4)==1
        self.usb_events.append((1,struct.pack('<I',0)));self.poll_native();self.poll_native()
        assert self.phase()=='active'and 2 in self.pending_rx and self.query(9)==self.query(10)==0
        # A newly configured original Fastboot loop owns and rearms EP1.
        assert self.call(self.read64(self.read64(u.SLOT)+0x10),[1,511,u.RX])==0
        self.send('CNXN',0x01000001,65536,b'host::features=shell_v2,stat_v2,ls_v2\0')
        self.until(lambda:any(p[0]=='CNXN'for p in self.adb_packets))
        if job:
            self.settle()
            assert self.query(25)==job and self.query(17)==3
            assert self.control(7,f'{job:016x}')[0]
            assert self.query(17)==2
    def send(self,kind,a0=0,a1=0,data=b''):self.host_pending.append(bytearray(packet(kind,a0,a1,data)))
    def parse_frames(self):
        while len(self.adb_frames)>=24:
            cmd,a0,a1,n,check,magic=struct.unpack('<6I',self.adb_frames[:24]);assert magic==cmd^0xffffffff and check==0
            if len(self.adb_frames)<24+n:return
            payload=bytes(self.adb_frames[24:24+n]);del self.adb_frames[:24+n]
            kind=next((s for s,v in CMD.items()if v==cmd),None);assert kind
            self.adb_packets.append((kind,a0,a1,payload))
            if kind=='OKAY':self.channels[a1]=a0
            elif kind=='WRTE'and self.auto_ack:self.send('OKAY',a1,a0)
            elif kind=='CLSE'and self.auto_ack:self.send('CLSE',a1,a0)
    def step(self):
        if self.host_pending and 2 in self.pending_rx:
            p,n=self.pending_rx.pop(2);q=self.host_pending[0];piece=bytes(q[:min(17,n)]);del q[:len(piece)]
            if not q:self.host_pending.popleft()
            self.cpu.mem_write(p,piece);self.usb_events.append((2,struct.pack('<IB3xQQ',1,2,len(piece),p)))
        self.poll_native();self.parse_frames()
    def until(self,predicate,limit=1000):
        for _ in range(limit):
            if predicate():return
            self.step()
        raise AssertionError(('timeout',self.phase(),self.query(3),self.query(0),len(self.host_pending),list(self.usb_events),self.adb_packets[-8:]))
    def settle(self,steps=30):
        for _ in range(steps):self.step()
    def open(self,host_id,service):
        begin=len(self.adb_packets);self.send('OPEN',host_id,0,service.encode()+b'\0')
        self.until(lambda:host_id in self.channels)
        return begin,self.channels[host_id]
    def closed(self,host):return any(p[0]=='CLSE'and p[2]==host for p in self.adb_packets)
    def channel_output(self,host):return b''.join(p[3]for p in self.adb_packets if p[0]=='WRTE'and p[2]==host)
    def shell(self,host,command):
        self.open(host,'shell,v2,raw:'+command);self.until(lambda:self.closed(host));self.settle()
        data=self.channel_output(host);out=bytearray();exits=[]
        while data:
            assert len(data)>=5;kind=data[0];n=struct.unpack('<I',data[1:5])[0];payload=data[5:5+n];assert len(payload)==n;data=data[5+n:]
            if kind in [1,2]:out.extend(payload)
            elif kind==3:assert len(payload)==1;exits.append(payload[0])
        assert len(exits)==1,(command,out,exits)
        return bytes(out),exits[0]
    def sync_send(self,host,payload):self.send('WRTE',host,self.channels[host],payload)
    def sync_response(self,host,needle):self.until(lambda:needle in self.channel_output(host));self.settle()
    def close(self,host):self.send('CLSE',host,self.channels[host]);self.until(lambda:self.closed(host));self.settle()
    def state(self):
        return {'phase':self.phase(),'native':bool(self.query(1)),'adb':bool(self.query(0)),
                'nativePools':bool(self.query(8)),'adbPools':bool(self.query(7)),
                'resident':bool(self.query(4)),'usbPhase':self.query(3),'router':bool(self.query(11)),
                'observer':bool(self.query(12)),'usbCalls':self.usb_calls,'extraCalls':self.extra_calls,
                'diskCalls':self.disk_calls,'spanChecks':self.span_checks,'startIdentities':self.start_identities,'liveAllocations':len(self.allocations)}

def run_one(driver,address,stop_kind):
    print('driver-adb',hex(address),stop_kind,'activation',flush=True)
    f=Fixture(driver,address);f.begin();counts=dict(f.extra_calls)
    out,rc=f.shell(11,'echo actual-driver-adb');assert out==b'actual-driver-adb\n'and rc==0,(out,rc)
    out,rc=f.shell(12,'el');assert b'1' in out and rc==0
    print('driver-adb shell passed; sync',flush=True);f.open(20,'sync:')
    content=bytes((i*17)&255 for i in range(137));path=b'/adb-test/file,33206'
    f.sync_send(20,b'SEND'+struct.pack('<I',len(path))+path+b'DATA'+struct.pack('<I',len(content))+content+b'DONE'+struct.pack('<I',0))
    f.sync_response(20,b'OKAY\0\0\0\0')
    idle_output,idle_rc=f.shell(16,'echo idle-sync-unblocked');assert idle_rc==0 and idle_output==b'idle-sync-unblocked\n', 'idle sync must release its transaction lease'
    previous=len(f.channel_output(20));path=b'/adb-test/file'
    f.sync_send(20,b'STA2'+struct.pack('<I',len(path))+path)
    f.until(lambda:len(f.channel_output(20))>=previous+72);stat=f.channel_output(20)[previous:]
    assert stat[:4]==b'STA2'and struct.unpack('<Q',stat[40:48])[0]==len(content)
    previous=len(f.channel_output(20));f.sync_send(20,b'RECV'+struct.pack('<I',len(path))+path)
    f.until(lambda:b'DONE\0\0\0\0' in f.channel_output(20)[previous:]);data=f.channel_output(20)[previous:]
    assert data==b'DATA'+struct.pack('<I',len(content))+content+b'DONE\0\0\0\0'
    previous=len(f.channel_output(20));path=b'/adb-test'
    f.sync_send(20,b'LIS2'+struct.pack('<I',len(path))+path)
    f.until(lambda:b'DONE' in f.channel_output(20)[previous:]);assert b'file' in f.channel_output(20)[previous:]
    f.close(20);print('driver-adb sync passed; block dd',flush=True)
    if stop_kind=='external':
        # A real ELF permission failure retains six additional diagnostics.
        # Verify their coexistence with all four new ADB status lines.
        program=ROOT/'analysis/kbl-usb-enumeration-20261030/stop-final-driver/demo/program.kbl'
        data=program.read_bytes();name=b'/status-demo';nonce=0x12345567899abcde
        env=struct.pack('<8sQIIQ32s',b'KBLPUT01',nonce,len(name),0,len(data),hashlib.sha256(data).digest())+name+data
        assert f.raw_request(8,f'env1:{nonce:016x}',env)[0]
        while f.usb_events:f.poll_native()
        f.fail_readonly_segment=True;output,rc=f.shell(15,'./status-demo');assert rc==126 and b'ELF load failed' in output
        assert f.control(4)[0]and f.status_checks[-1]['lines']>=13
    # Actual native command -> VFS -> actual UEFI BlockIO provider callback.
    before=bytes(f.disks[0]);out,rc=f.shell(13,'dd if=/adb-test/file of=/dev/block/by-name/abl_a bs=31 count=1 seek=1 conv=fsync')
    assert rc==0,(out,rc);assert f.disks[0][:31]==before[:31]and f.disks[0][31:62]==content[:31]and f.disks[0][62:]==before[62:]
    assert f.disk_calls['write'] and f.disk_calls['flush']
    # Leave an owned sync write open when OFF is requested. Its descriptor and
    # external owner must be closed while g.adb_call is set, never fake in_job.
    print('driver-adb dd passed; stop',flush=True);f.open(21,'sync:');path=b'/partial,33206'
    f.sync_send(21,b'SEND'+struct.pack('<I',len(path))+path+b'DATA'+struct.pack('<I',3)+b'abc');f.settle(60)
    if stop_kind=='external':
        f.auto_ack=False;f.control(3);f.settle(50)
        assert f.query(0)and not f.query(1)and f.query(21),'Native STOP must not wait for peer CLSE acknowledgement'
        assert f.extra_calls['start_ex']==1 and not f.extra_calls['stop']
        f.auto_ack=True
        for kind,a0,a1,payload in f.adb_packets:
            if kind=='WRTE'and a1==21:f.send('OKAY',a1,a0)
            if kind=='CLSE'and a1==21:f.send('CLSE',a1,a0)
    else:
        # Separate control channel can request stop even while sync owns VFS.
        f.open(22,'shell,v2,raw:kbl off')
    f.until(lambda:f.query(21)==1 and f.query(1)==0);f.settle(20)
    assert f.phase()=='off-parked'and f.query(4)==f.query(11)==f.query(12)==1
    assert f.query(7)and f.query(8)==f.query(9)==f.query(10)==0
    assert f.query(3)==3 and 2 in f.pending_rx and not f.extra_calls['stall']
    assert 2 not in f.aborts and 0x82 not in f.aborts
    assert f.extra_calls['start_ex']==1 and not f.extra_calls['stop']
    assert f.read64(FN+0x48)!=EX['fn_event'],'descriptor/control router must remain resident'
    # Original Fastboot still sends its one native ACK and consumes terminal
    # completion through the retained forwarder; no controller restart occurs.
    assert f.control(4)[0];assert f.native_rx_rearms>=3 and 1 in f.pending_rx
    assert f.extra_calls['start_ex']==1 and not f.extra_calls['stop']
    assert f.call(f.can_unload,[f.protocol])==0,'retained core/descriptor/router references forbid unload'
    return {'base':hex(address),'stop':stop_kind,'passed':True,'shell':out.decode(),
            'ramFileBytes':len(content),'ramFileSha256':hashlib.sha256(content).hexdigest(),
            'originalFastbootReplyAfterOff':True,'extraCallsAtActivation':counts,'statusChecks':f.status_checks,'state':f.state(),
            'modelBoundaries':['EFI allocation/attributes/span proof','USB DMA/controller/events','Original native final-TX RX-rearm'],
            'realCode':['ARM64 EFI driver glue','ADB framing/shell-v2/sync','Native shell/fiber/VFS','UEFI partition provider','Composite USB ledger']}

def run_fault_stop(driver):
    print('driver-adb fault terminal cleanup',flush=True)
    f=Fixture(driver,0x10000000);f.begin();f.open(31,'sync:');path=b'/fault-partial,33206'
    f.sync_send(31,b'SEND'+struct.pack('<I',len(path))+path+b'DATA'+struct.pack('<I',3)+b'xyz');f.settle(60)
    f.fail_next_adb_tx=True;f.control(3)
    f.until(lambda:f.query(0)==0 and f.query(1)==0);f.settle(20)
    assert f.failed_adb_tx==1 and f.phase()=='off-resident'
    assert not f.query(7)and not f.query(8)and f.query(12)==1
    assert f.extra_calls['start_ex']==1 and not f.extra_calls['stop']
    assert f.control(4)[0]and 1 in f.pending_rx
    return {'passed':True,'fault':'Exact borrowed ADB TX terminal failure with a live partial SYNC file/session',
            'faultTerminalCount':f.failed_adb_tx,'cleanup':'Core fault-disconnect closes the native external IO owner, then native stop; USB resident',
            'statusChecks':f.status_checks,'state':f.state()}

def run_optin_edges(driver):
    results=[]
    for native_first,busy in [(False,False),(True,True)]:
        f=Fixture(driver,0x10000000);f.begin(native_first=native_first,busy=busy);f.settle()
        native=f.query(1);generation=f.query(24);adb=f.query(0);counts=dict(f.extra_calls)
        for op in [17,1,2,4]:assert f.control(op)[0]
        f.settle()
        assert f.query(1)==native and f.query(24)==generation and f.query(0)==adb
        assert f.extra_calls==counts and f.extra_calls['start_ex']==1
        out,rc=f.shell(401,'echo optin-preserved');assert rc==0 and out==b'optin-preserved\n'
        f.control(3);f.until(lambda:f.query(21)and not f.query(1))
        results.append({'nativeFirst':native_first,'busyJobPreserved':busy,'repeatedEnablePreservesContext':True,'state':f.state()})
    return {'passed':True,'case':'Direct cold ADB entry and enable while native job is busy','results':results}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,default=ROOT/'analysis/kbl-services-20261030/adb-driver')
    a=p.parse_args();out=a.output.resolve();driver,commands=build(out/'build')
    results=[run_one(driver,base,kind)for base,kind in [(0x10000000,'external'),(0x18000000,'shell')]]
    results.append(run_fault_stop(driver))
    results.append(run_optin_edges(driver))
    sources=[UEFI/'kbl_driver.c',UEFI/'kbl_adb_driver.inc',UEFI/'kbl_adb_usb.c',UEFI/'kbl_adb_usb.h',UEFI/'kbl_usb_native.c',UEFI/'kbl_usb_native.h',
             RUNTIME/'adb/kbl_adb.c',RUNTIME/'native/kbl_native.c',RUNTIME/'native/kbl_io.inc',Path(__file__)]
    report={'passed':True,'scope':__doc__,'testDriverSha256':sha(driver),'results':results,
            'sourceSha256':{str(s.relative_to(SOURCE)):sha(s)for s in sources},
            'commands':[[str(c)for c in cmd]for cmd in commands],
            'limits':['No physical USB or Windows driver enumeration','Span proof is explicitly injected, not a hardware page-table test',
                      'No real partition/device writes','No Android services, Linux syscalls or EL transition']}
    (out/'validation.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'passed':True,'cases':len(results),'report':str(out/'validation.json')},indent=2))
if __name__=='__main__':main()

