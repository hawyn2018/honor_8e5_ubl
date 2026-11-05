"""Real ARM64 ADB clearstart: DMA fences, original descriptors, independent EFI.

All EFI/USB/DMA providers are local models. No physical device operations.
"""
from pathlib import Path
import argparse,importlib.util,json,struct
HERE=Path(__file__).resolve().parent
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
c=module('cs_images',HERE/'test_clearstart_unicorn.py')
t=module('cs_adb',HERE/'test_driver_adb_unicorn.py')

class Fixture(c.Fixture,t.Fixture):
    def __init__(self,path,base):
        self.images={};self.frames=[];self.loaded=[];self.started=[];self.fail_load=0;self.fail_unload=False;self.target_status=0
        self.restored=False
        t.Fixture.__init__(self,path,base)
        self.write64(t.b.BS+0xc8,c.LOAD);self.write64(t.b.BS+0xd0,c.START);self.write64(t.b.BS+0xe0,c.UNLOAD)
    def hook(self,cpu,address,size,user):
        a=[cpu.reg_read(r)for r in t.b.REGS]
        if address in [c.LOAD,c.START,c.UNLOAD,c.RETURN]or(address==t.b.CB['handle']and a[0]in self.images):
            if address==c.START:
                assert not self.pending_rx and self.restored
                assert not self.query_during_call # probes must not reenter the CPU
            return c.Fixture.hook(self,cpu,address,size,user)
        if address==t.u.USB_CB['usb_abort']and a[0]==2 and 2 not in self.pending_rx:
            assert any(kind==2 and struct.unpack('<IB3xQQ',payload)[1]==2 for kind,payload in self.usb_events)
            # Controller completion is queued but not yet returned to observer;
            # abort can fail and must still wait for that exact terminal event.
            self.usb_calls['usb_abort']+=1;self.ret(t.b.ERR(2));return
        if address==t.EX['start_ex']and a[0]==t.DESC:
            assert not self.pending_rx,'all parent and ADB RX must be terminal before restore'
            self.extra_calls['start_ex']+=1;self.events_log.append('restore-original')
            cfg=self.read64(self.read64(a[0]+8));raw=bytes(cpu.mem_read(cfg,32));assert raw[4]==1
            self.start_configs.append(raw);self.restored=True;self.ret();return
        return t.Fixture.hook(self,cpu,address,size,user)

def run(driver,target,base):
    f=Fixture(driver,base);f.query_during_call=False;f.begin()
    # Stage through the real ADB sync protocol, leaving another sync channel
    # open to prove native stop closes session/file ownership before release.
    f.open(20,'sync:');path=b'/r,33206'
    f.sync_send(20,b'SEND'+struct.pack('<I',len(path))+path+b'DATA'+struct.pack('<I',len(target))+target+b'DONE'+struct.pack('<I',0))
    f.sync_response(20,b'OKAY\0\0\0\0');assert f.query(1)and f.query(0)
    disks=[bytes(d)for d in f.disks]
    # Delay the exact parent RX abort completion. A successful abort request
    # must neither restore descriptors nor launch the target.
    f.defer_abort.add(1)
    f.open(21,'shell,v2,raw:efi -cs /r')
    f.until(lambda:1 in f.held_aborts)
    assert not f.restored and not f.started and not f.query(1)
    assert f.query(12)and f.read64(t.FN+0x48)!=t.EX['fn_event']
    for _ in range(5):f.step()
    assert not f.restored and not f.started
    f.complete_abort(1);f.until(lambda:len(f.started)==2)
    assert not f.query(0)and not f.query(1)and not f.query(7)and not f.query(8)
    assert not f.query(11)and not f.query(12)and not f.query(4)
    assert f.read64(t.FN+0x48)==t.EX['fn_event']and f.read64(t.u.SLOT)==t.u.DEVICE
    assert not f.allocations and f.extra_calls['start_ex']==2 and not f.extra_calls['stop']
    assert [bytes(d)for d in f.disks]==disks and not f.disk_calls['write']
    assert 1 in f.pending_rx,'returning child must resume original Fastboot RX'
    # Native Fastboot still answers after the independent target returns.
    assert f.control(4)[0]
    return {'passed':True,'base':hex(base),'targetStarts':len(f.started),'originalDescriptorsRestored':True,
        'parentRxTerminalFence':True,'ep0RouterRestored':True,'observerDetached':True,'allKblPoolsReleased':True,
        'partitionWrites':f.disk_calls['write'],'driverAndParentRemainMapped':True,'fastbootRespondsAfterReturn':True}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--target',type=Path,required=True);p.add_argument('--driver',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    driver=a.driver
    if not driver:driver,_=t.build(a.output/'build')
    report={'passed':True,'scope':__doc__,'results':[run(driver,a.target.read_bytes(),base)for base in [0x10000000,0x18000000]],
        'physicalDeviceIO':False,'driverSha256':t.sha(driver)}
    (a.output/'adb-clearstart.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
