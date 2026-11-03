"""Read-only lifecycle audit using the actual ARM64 production driver.

All USB bus, DMA and EFI callbacks are explicit models; no phone is contacted.
The pending-TX case records an existing sticky-fault boundary, not a claimed
automatic recovery guarantee. The descriptor/PID change does not alter it.
"""
from pathlib import Path
import argparse,hashlib,importlib.util,json,struct

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('adb_lifecycle_base',HERE/'test_driver_adb_unicorn.py')
t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)
b=t.b;u=t.u;ar=t.ar
LOWER=t.FN+0x3000

class Lifecycle(t.Fixture):
    def __init__(self,driver,address):
        self.hold_tx=False;self.held_tx=None;self.lower_events=[];self.control_tx=None;self.control_aborts=[]
        self.high_event_error=0
        super().__init__(driver,address)
    def hook(self,cpu,address,size,user):
        a=[cpu.reg_read(r)for r in b.REGS]
        if address==u.USB_CB['usb_event']and self.high_event_error:
            status=self.high_event_error;self.high_event_error=0;self.usb_calls['usb_event']+=1;self.ret(status);return
        if address==u.USB_CB['usb_send']and a[0]==0x82 and self.hold_tx:
            assert not self.held_tx;self.usb_calls['usb_send']+=1;self.held_tx=(a[2],a[1]);self.ret();return
        if address==u.USB_CB['usb_abort']and a[0]==0x82 and self.held_tx:
            self.usb_calls['usb_abort']+=1;p,n=self.held_tx;self.held_tx=None
            self.usb_events.append((2,struct.pack('<IB3xQQ',2,0x82,0,p)));self.ret();return
        if address==t.EX['fn_event']:
            self.extra_calls['fn_event']+=1;self.fn_original_events+=1
            kind,payload=self.lower_events.pop(0)if self.lower_events else(0,b'')
            self.write32(a[1],kind);self.write64(a[2],len(payload))
            if payload:cpu.mem_write(a[3],payload)
            self.ret();return
        if address==t.EX['fn_transfer']:
            self.extra_calls['fn_transfer']+=1;assert a[0]==t.FN and a[1]==0
            n=self.read64(a[3]);self.control_tx=(a[4],n,a[2]);self.ret();return
        if address==t.EX['fn_abort']:
            self.extra_calls['fn_abort']+=1;assert a[0]==t.FN and a[1]==0
            self.control_aborts.append(a[2]);self.ret();return
        return super().hook(cpu,address,size,user)
    def lower(self,kind,payload=b''):
        self.lower_events.append((kind,payload));self.write32(LOWER,0);self.write64(LOWER+8,32)
        rc=self.call(self.read64(t.FN+0x48),[t.FN,LOWER,LOWER+8,LOWER+16]);assert rc==0
        return int.from_bytes(self.cpu.mem_read(LOWER,4),'little'),self.read64(LOWER+8)
    def disconnect(self):
        self.usb_events.append((1,struct.pack('<I',1)));self.poll_native();self.settle(30)
        assert not self.held_tx and 2 not in self.pending_rx
    def reconnect(self):
        self.usb_events.append((1,struct.pack('<I',0)));self.poll_native();self.settle(10)
        self.channels.clear();self.adb_frames.clear();self.host_pending.clear()

def idle_rx_rebind(driver):
    f=Lifecycle(driver,0x10000000);f.begin();f.settle()
    assert 2 in f.pending_rx and not f.query(15)
    f.open(60,'sync:');path=b'/before-rebind,33206'
    f.sync_send(60,b'SEND'+struct.pack('<I',len(path))+path+b'DATA'+struct.pack('<I',3)+b'abc');f.settle(60)
    assert not f.query(15)
    before=len(f.adb_packets);f.disconnect();f.reconnect()
    f.send('CNXN',0x01000001,65536,b'host::features=shell_v2,stat_v2,ls_v2\0')
    f.until(lambda:any(p[0]=='CNXN'for p in f.adb_packets[before:]));f.settle()
    out,rc=f.shell(61,'cat /before-rebind');assert out==b'abc'and rc==0
    assert f.control(4)[0]and f.extra_calls['start_ex']==1 and not f.extra_calls['stop']
    return {'passed':True,'case':'Disconnect/reconfigure while only ADB RX is pending and a partial SYNC file owns VFS',
            'automaticNewCnxn':True,'partialFilePreserved':True,'nativeFastbootReply':True,'state':f.state()}

def pending_tx_rebind(driver):
    f=Lifecycle(driver,0x18000000);f.begin();f.settle();f.open(70,'sync:')
    f.hold_tx=True;path=b'/'
    f.sync_send(70,b'STA2'+struct.pack('<I',len(path))+path)
    f.until(lambda:f.held_tx is not None)
    assert f.query(15),'Borrowed TX must stay pending until exact terminal'
    f.disconnect();f.hold_tx=False;before=len(f.adb_packets);f.reconnect()
    f.send('CNXN',0x01000001,65536,b'host::features=shell_v2,stat_v2,ls_v2\0');f.settle(80)
    recovered=any(p[0]=='CNXN'for p in f.adb_packets[before:])
    assert not recovered and f.query(13),f.state()
    assert f.control(4)[0],'Sticky private ADB fault must not break ordinary Fastboot'
    # Explicit OFF is a cleanup route; it does not replay the interrupted work.
    assert not f.control(3)[0];f.until(lambda:not f.query(0)and not f.query(1))
    assert f.phase()=='off-resident'and f.extra_calls['start_ex']==1 and not f.extra_calls['stop']
    return {'passed':True,'case':'Disconnect with borrowed ADB TX followed by exact CANCELLED terminal',
            'existingBoundary':'output_done(ERROR) sets permanent core/transport fault for that session; reconnect alone does not reset it',
            'automaticNewCnxn':recovered,'nativeFastbootReply':True,'explicitOffCleanup':True,'state':f.state()}

def control_reset(driver):
    f=Lifecycle(driver,0x10000000);f.begin();f.settle()
    # The actual Qualcomm lower implementation reports the 32-byte payload
    # union for SETUP even though only the first eight bytes are meaningful.
    poison=bytes((0xa5+i*7)&255 for i in range(24))
    ordinary=bytes([0x80,6,1,3,9,4,0xff,0])+poison
    assert f.lower(1,ordinary)==(1,32)
    assert bytes(f.cpu.mem_read(LOWER+16,32))==ordinary
    assert not f.extra_calls['fn_transfer'],'Normal string requests must reach the saved provider unchanged'
    before=f.extra_calls['fn_transfer'];q=bytes([0xc0,0x20,0,0,7,0,0xff,0xff])+poison
    assert f.lower(1,q)==(0,0);assert f.control_tx and f.control_tx[2]==1
    p,n,direction=f.control_tx;assert n>10 and f.extra_calls['fn_transfer']==before+1
    assert f.lower(3,struct.pack('<QIB3xI4xQ',n,1,0,1,p))==(0,0)
    assert f.control_tx==(p,0,0)
    assert f.lower(2,struct.pack('<QIB3xI4xQ',0,1,0,0,p))==(0,0)
    assert f.lower(1,ordinary)==(1,32)and bytes(f.cpu.mem_read(LOWER+16,32))==ordinary
    before=f.extra_calls['fn_transfer'];assert f.lower(1,q)==(0,0)
    p,n,direction=f.control_tx
    assert f.lower(6)==(6,0),'Bus reset must still reach the original Fastboot state machine'
    assert f.control_aborts==[1]
    # Reset requests cancellation; no descriptor/control buffer can be freed
    # until the identified lower Usbfn outcome returns ownership.
    assert p in {a for a,(n,m,k)in f.allocations.items()if k=='dma'}
    payload=struct.pack('<QIB3xI4xQ',0,2,0,1,p)
    assert f.lower(3,payload)==(0,0)
    assert f.lower(1,q)==(0,0)and f.extra_calls['fn_transfer']==before+2
    assert f.extra_calls['start_ex']==1 and not f.extra_calls['stop']
    return {'passed':True,'case':'32-byte poisoned SETUP union: OS2 DATA/status consume, ordinary GET_STRING pass-through, reset/abort/retry',
            'setupPayloadBytes':32,'inputCapacityBytes':32,'tailPoisoned':True,'standardStringUnchangedBeforeAndAfter':True,
            'resetForwardedToFastboot':True,'controlReplyBytes':n,'descriptorBufferRetained':True,'state':f.state()}

def provider_error_stale_output(driver):
    f=Lifecycle(driver,0x18000000);f.begin();f.settle()
    statuses=[0x8000000000000015,0x8000000000000007]
    for status in statuses:
        f.write32(u.EVENT,2);f.write64(u.EVENT+8,24)
        stale=struct.pack('<IB3xQQ',3,1,0,u.RX);f.cpu.mem_write(u.EVENT+16,stale)
        f.high_event_error=status
        rc=f.call(f.read64(f.read64(u.SLOT)+0x18),[u.EVENT,u.EVENT+8,u.EVENT+16])
        assert rc==status
        assert int.from_bytes(f.cpu.mem_read(u.EVENT,4),'little')==0 and f.read64(u.EVENT+8)==0
        assert bytes(f.cpu.mem_read(u.EVENT+16,24))==stale
    assert f.control(4)[0]and f.query(0)and f.query(1)
    f.control(3);f.until(lambda:f.query(21)and not f.query(1));f.settle()
    assert f.phase()=='off-parked'and f.extra_calls['start_ex']==1 and not f.extra_calls['stop']
    # Provider errors do not fault the healthy private core. OFF parks it and
    # leaves the bulk pipes untouched rather than issuing STALL/clear-STall.
    stalls=f.extra_calls['stall'];f.settle(50);assert f.extra_calls['stall']==stalls==0
    return {'passed':True,'case':'Actual driver clone preserves provider EFI errors and clears caller Event/Size seeded with old EP1 FAILED',
            'providerErrors':[hex(s)for s in statuses],'originalFastbootReplyAfterError':True,
            'stopStallCalls':stalls,'state':f.state()}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--driver',type=Path)
    p.add_argument('--output',type=Path,default=t.ROOT/'analysis/kbl-usb-enumeration-20261030/lifecycle')
    a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    driver=a.driver
    if not driver:driver,commands=t.build(out/'build')
    results=[idle_rx_rebind(driver),pending_tx_rebind(driver),control_reset(driver),provider_error_stale_output(driver)]
    report={'passed':True,'scope':__doc__,'testDriverSha256':t.sha(driver),'results':results,
            'sourceSha256':{str(s.relative_to(t.SOURCE)):t.sha(s)for s in [t.UEFI/'kbl_driver.c',t.UEFI/'kbl_adb_driver.inc',t.UEFI/'kbl_adb_usb.c',t.RUNTIME/'adb/kbl_adb.c',Path(__file__)]},
            'limits':['Host restart/reset and USB DMA are explicit models','No physical composite enumeration or Windows binding validation',
                      'Pending-TX fault retains full teardown; healthy native OFF now parks the unchanged USB/core transport']}
    (out/'validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'passed':True,'cases':len(results),'report':str(out/'validation.json')},indent=2))
if __name__=='__main__':main()
