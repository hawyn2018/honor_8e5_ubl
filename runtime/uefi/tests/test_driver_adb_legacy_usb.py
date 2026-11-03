"""Actual ARM64 core/driver/USB adapter with packet-aware legacy ADB USB reads.

The host reader models the AOSP MPS-sized header read and rounded payload read.
It completes on a short USB packet or requested byte count, instead of treating
all device writes as an arbitrary byte stream. EFI/USB controller are models;
no device or host ADB server is contacted.
"""
from pathlib import Path
import argparse,hashlib,importlib.util,json,struct
from collections import deque

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('legacy_usb_driver',HERE/'test_driver_adb_unicorn.py')
t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)
b=t.b;u=t.u;ar=t.ar

class LegacyHost:
    def __init__(self,mps):
        self.mps=mps;self.stage='header';self.requested=mps;self.buffer=bytearray();self.header=None
        self.completed=deque();self.reads=[];self.transfers=[];self.current_transfers=[]
    def transfer(self,data):
        self.transfers.append(len(data));self.current_transfers.append(len(data))
        packets=[data[i:i+self.mps]for i in range(0,len(data),self.mps)]if data else[b'']
        for packet in packets:
            assert len(packet)<=self.requested-len(self.buffer),('USB overflow',self.stage,len(packet),self.requested,len(self.buffer))
            self.buffer.extend(packet)
            if len(packet)<self.mps or len(self.buffer)==self.requested:self.read_done()
    def read_done(self):
        data=bytes(self.buffer);self.buffer.clear()
        record={'stage':self.stage,'requested':self.requested,'actual':len(data),'transferBytes':self.current_transfers}
        self.current_transfers=[];self.reads.append(record)
        if self.stage=='header':
            assert len(data)==24,('legacy header expects24',len(data),record)
            cmd,a0,a1,n,checksum,magic=struct.unpack('<6I',data);assert magic==cmd^0xffffffff and checksum==0
            self.header=(cmd,a0,a1,n)
            if n:self.stage='body';self.requested=(n+self.mps-1)//self.mps*self.mps
            else:self.finish_packet(b'')
        else:
            assert len(data)==self.header[3],('legacy payload read length',len(data),self.header[3],record)
            # Any UEFI body transfer before the final one must end on a USB
            # packet boundary, otherwise a legacy bulk read completes early.
            assert all(n and n%self.mps==0 for n in record['transferBytes'][:-1]),record
            self.finish_packet(data)
    def finish_packet(self,data):
        cmd,a0,a1,n=self.header;assert len(data)==n
        kind=next((s for s,v in t.CMD.items()if v==cmd),None);assert kind
        self.completed.append((kind,a0,a1,data));self.header=None;self.stage='header';self.requested=self.mps

class Fixture(t.Fixture):
    def __init__(self,driver,address,mps,max_transfer):
        self.legacy=LegacyHost(mps);self.max_transfer=max_transfer;self.zlp_out_completions=0
        self.partial_next=False;self.partial_count=0
        super().__init__(driver,address)
        if mps==1024:
            dev=t.DESC+1024;cfg=t.DESC+1280;arr=t.DESC+1792
            self.cpu.mem_write(dev,bytes.fromhex('1201000300000009d118d1ee000100000001'))
            self.cpu.mem_write(cfg,bytes.fromhex('09022c0001010080320904000002ff4203000705010200040006300000000007058102000400063000000000'))
            self.write64(arr,cfg);self.write64(t.DESC+16,dev);self.write64(t.DESC+24,arr)
    def hook(self,cpu,address,size,user):
        args=[cpu.reg_read(r)for r in b.REGS]
        if address==t.EX['fn_max']:
            self.extra_calls['fn_max']+=1;assert args[0]==t.FN;self.write64(args[1],self.max_transfer);self.ret();return
        if address==u.USB_CB['usb_send']and args[0]==0x82:
            ep,n,p=args[:3]
            if self.partial_next:
                self.partial_next=False;self.partial_count+=1;self.usb_calls['usb_send']+=1
                confirmed=max(1,n-1);assert confirmed<n
                # A real short successful terminal must become a private
                # transport fault; never replay the remainder as a new header.
                self.usb_events.append((2,struct.pack('<IB3xQQ',1,ep,confirmed,p)));self.ret();return
            self.legacy.transfer(bytes(cpu.mem_read(p,n)))
        return super().hook(cpu,address,size,user)
    def send(self,kind,a0=0,a1=0,data=b''):
        raw=t.packet(kind,a0,a1,data);self.host_pending.append({'data':bytearray(raw[:24]),'zlp':False})
        if data:self.host_pending.append({'data':bytearray(data),'zlp':len(data)%self.legacy.mps==0})
    def step(self):
        if self.host_pending and 2 in self.pending_rx:
            p,capacity=self.pending_rx.pop(2);q=self.host_pending[0];data=q['data'];n=min(capacity,len(data))
            piece=bytes(data[:n]);del data[:n]
            if not data:
                self.host_pending.popleft()
                if q['zlp']and n==capacity:self.host_pending.appendleft({'data':bytearray(),'zlp':False})
            if not piece:self.zlp_out_completions+=1
            self.cpu.mem_write(p,piece or b'\0');self.usb_events.append((2,struct.pack('<IB3xQQ',1,2,n,p)))
        self.poll_native();self.parse_frames()
    def parse_frames(self):
        # The common fixture also keeps a byte stream for diagnostic hashes.
        # It is deliberately not parsed here: only completed legacy USB reads
        # are allowed to create an ADB packet.
        self.adb_frames.clear()
        while self.legacy.completed:
            kind,a0,a1,payload=self.legacy.completed.popleft();self.adb_packets.append((kind,a0,a1,payload))
            if kind=='OKAY':self.channels[a1]=a0
            elif kind=='WRTE'and self.auto_ack:self.send('OKAY',a1,a0)
            elif kind=='CLSE'and self.auto_ack:self.send('CLSE',a1,a0)
    def sync_write_record(self,host,payload):
        before=sum(p[0]=='OKAY'and p[2]==host for p in self.adb_packets)
        self.sync_send(host,payload)
        self.until(lambda:sum(p[0]=='OKAY'and p[2]==host for p in self.adb_packets)>before)

def run_one(driver,mps,limit):
    f=Fixture(driver,0x10000000 if mps==512 else 0x18000000,mps,limit);f.begin();f.settle()
    assert f.legacy.transfers[:2]==[24,113],f.legacy.transfers[:3]
    out,rc=f.shell(40,'echo legacy-usb-framing');assert out==b'legacy-usb-framing\n'and rc==0
    content=bytes((i*73+5)&255 for i in range(65528+4113));f.open(50,'sync:');path=b'/legacy.bin,33206'
    f.sync_write_record(50,b'SEND'+struct.pack('<I',len(path))+path)
    for at in range(0,len(content),16384):
        chunk=content[at:at+16384];f.sync_write_record(50,b'DATA'+struct.pack('<I',len(chunk))+chunk)
    f.sync_write_record(50,b'DONE'+struct.pack('<I',0));f.sync_response(50,b'OKAY\0\0\0\0')
    before=len(f.channel_output(50));path=b'/legacy.bin';f.sync_write_record(50,b'RECV'+struct.pack('<I',len(path))+path)
    f.until(lambda:b'DONE\0\0\0\0' in f.channel_output(50)[before:]);f.settle()
    stream=f.channel_output(50)[before:];pulled=bytearray()
    while stream:
        kind,n=struct.unpack('<4sI',stream[:8]);stream=stream[8:]
        if kind==b'DONE':assert n==0 and not stream;break
        assert kind==b'DATA'and len(stream)>=n;pulled.extend(stream[:n]);stream=stream[n:]
    assert bytes(pulled)==content
    f.close(50);out,rc=f.shell(41,'cat /legacy.bin');assert out==content and rc==0
    # Explicit host OUT ZLP is tolerated and causes a clean RX rearm, while
    # no device IN zero packet is ever emitted between payload and next header.
    f.host_pending.append({'data':bytearray(),'zlp':False});f.settle(5);assert f.zlp_out_completions
    assert all(n>0 for n in f.legacy.transfers)
    assert any(r['stage']=='body'and r['actual']==65536 for r in f.legacy.reads)
    f.control(3);f.until(lambda:f.query(21)and not f.query(1));f.settle()
    assert f.query(0)and f.query(3)==3 and not f.extra_calls['stall']
    assert f.control(4)[0]and f.extra_calls['start_ex']==1 and not f.extra_calls['stop']
    return {'passed':True,'maxPacketBytes':mps,'maxTransferBytes':limit,'cnxnTransfers':f.legacy.transfers[:2],
            'fileBytes':len(content),'fileSha256':hashlib.sha256(content).hexdigest(),
            'hostOutZeroCompletions':f.zlp_out_completions,'deviceInZeroTransfers':0,
            'bulkReads':f.legacy.reads,'state':f.state()}

def partial_terminal(driver):
    f=Fixture(driver,0x10000000,512,1024);f.begin();f.settle();f.partial_next=True
    f.send('OPEN',80,0,b'shell,v2,raw:echo partial\0')
    f.until(lambda:f.partial_count==1);f.settle(30)
    sends=len(f.legacy.transfers);f.settle(30);assert len(f.legacy.transfers)==sends and f.query(13)
    assert f.control(4)[0];assert not f.control(3)[0];f.until(lambda:not f.query(0)and not f.query(1))
    return {'passed':True,'case':'short successful ADB TX terminal becomes fault; no remainder replay or new frame retry',
            'partialTerminals':f.partial_count,'fastbootAfterFault':True,'state':f.state()}

def negative_model_checks():
    h=LegacyHost(1024);combined=t.packet('CNXN',0x1000001,65536,b'x'*113)
    try:h.transfer(combined)
    except AssertionError as e:assert e.args[0][0]=='legacy header expects24'and e.args[0][1]==137
    else:raise AssertionError('combined header/body was accidentally accepted')
    h=LegacyHost(512);h.transfer(t.packet('WRTE',1,2,b'x'*512)[:24]);h.transfer(b'x'*512)
    try:h.transfer(b'')
    except AssertionError as e:assert e.args[0][0]=='legacy header expects24'and e.args[0][1]==0
    else:raise AssertionError('extra aligned-body ZLP was accidentally accepted')
    return ['combined CNXN137 rejected by header24 read','extra device ZLP after aligned body rejected as next header0']

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,default=t.ROOT/'analysis/kbl-usb-enumeration-20261030/legacy-usb')
    args=p.parse_args();out=args.output.resolve();driver,commands=t.build(out/'build')
    try:results=[run_one(driver,mps,limit)for mps in [512,1024]for limit in [1024,65536]]+[partial_terminal(driver)]
    except Exception as e:
        (out/'failure.json').write_text(json.dumps({'passed':False,'error':str(e),'driverSha256':t.sha(driver)},indent=2)+'\n');raise
    report={'passed':True,'scope':__doc__,'testDriverSha256':t.sha(driver),'results':results,'negativeControls':negative_model_checks(),
            'primarySources':['https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/android15-qpr2-s8-release/client/transport_usb.cpp',
                              'https://android.googlesource.com/platform/system/adb/+/refs/heads/main/client/usb_windows.cpp'],
            'sourceSha256':{str(p.relative_to(t.SOURCE)):t.sha(p)for p in [t.UEFI/'kbl_adb_usb.c',t.UEFI/'kbl_adb_usb.h',t.RUNTIME/'adb/kbl_adb.c',HERE/'test_driver_adb_unicorn.py',Path(__file__)]},
            'limits':['USB read completion/short-packet behavior is a documented host model, not WinUSB hardware','No device, socket, real storage or firmware action']}
    (out/'validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'passed':True,'cases':len(results),'report':str(out/'validation.json')},indent=2))
if __name__=='__main__':main()
