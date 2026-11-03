"""Actual ARM64 driver healthy park/resume over unchanged USB DMA models.

No device or USB API is called. EFI, page permissions, DMA completions and
native Fastboot RX rearm are explicit models. Production driver/core/native
machine code executes in Unicorn at EL1.
"""
from pathlib import Path
import argparse,importlib.util,json,struct
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('park_driver',HERE/'test_driver_adb_unicorn.py')
t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)

def assert_park(f,core,usb_counts):
    f.until(lambda:f.query(21)and not f.query(1));f.settle(30)
    assert f.query(0)==core and f.query(7) and not f.query(8)
    assert f.phase()=='off-parked'and f.query(3)==3 and f.query(14)
    assert f.query(17)==0 and not f.query(9)and not f.query(10)
    assert f.extra_calls['stall']==0 and f.extra_calls['start_ex']==usb_counts['start_ex']==1
    assert f.extra_calls['stop']==0 and 2 not in f.aborts and 0x82 not in f.aborts
    assert f.call(f.can_unload,[f.protocol])==0
    assert f.control(4)[0]

def resume(f,core,probe_id,hold_ack=False):
    before=dict(f.extra_calls);pools=f.query(7);rx=f.pending_rx[2]
    # START may recreate native KBL while leaving ADB parked and responsive
    # only to stopped-state protocol traffic. Only kbladb resumes service.
    assert f.control(1)[0];native=f.query(1);f.settle(10)
    assert native and f.query(1)==native and f.query(0)==core and f.query(21)
    assert not f.query(5)and f.phase()=='off-parked'and f.query(7)==pools
    assert f.extra_calls==before and f.pending_rx[2]==rx
    assert f.raw_request(17)[0]
    assert f.query(1)==native
    assert f.query(0)==core and f.query(21) and f.query(1)and f.query(5)
    assert f.extra_calls==before and f.query(7)==pools
    assert f.pending_rx[2]==rx
    if hold_ack:
        # Keep the exact native START OKAY in flight while delivering ADB OPEN.
        # It is rejected without a native callback until ACK ownership returns.
        ack=None
        for i,(kind,payload)in enumerate(f.usb_events):
            if kind==2 and struct.unpack('<IB3xQQ',payload)[1]==0x81:
                ack=f.usb_events[i];del f.usb_events[i];break
        assert ack
        f.send('OPEN',probe_id,0,b'shell,v2,raw:echo forbidden-before-ack\0')
        f.until(lambda:f.closed(probe_id))
        assert f.query(21)and f.query(1)and f.query(5)
        assert not any(p[0]=='OKAY'and p[2]==probe_id for p in f.adb_packets)
        assert not f.channel_output(probe_id)
        f.usb_events.appendleft(ack)
    f.until(lambda:not f.query(5)and not f.query(21)and f.phase()=='active')
    f.settle()
    assert f.query(0)==core and f.query(7)==pools and f.extra_calls==before
    assert f.query(3)==3 and 2 in f.pending_rx and f.query(23)

def cycles(driver,base):
    f=t.Fixture(driver,base);f.begin();f.settle();core=f.query(0);counts=dict(f.extra_calls)
    out,rc=f.shell(101,'touch /old-session');assert rc==0
    for cycle in range(3):
        if cycle==1:
            f.open(120+cycle,'shell,v2,raw:kbl off')
        else:f.control(3)
        assert_park(f,core,counts)
        # While parked a real protocol handshake still works, yet OPEN is
        # statelessly rejected and cannot reacquire native/file resources.
        if cycle==0:
            before=len(f.adb_packets)
            f.send('CNXN',0x01000001,65536,b'host::features=shell_v2,stat_v2,ls_v2\0')
            f.until(lambda:any(p[0]=='CNXN'for p in f.adb_packets[before:]));f.settle()
        denied=140+cycle;f.send('OPEN',denied,0,b'shell,v2,raw:echo denied\0')
        f.until(lambda:f.closed(denied));f.settle()
        assert not f.query(1)and not f.query(8)and not f.channel_output(denied)
        resume(f,core,160+cycle,hold_ack=cycle==0)
        out,rc=f.shell(180+cycle,'el');assert out==b'EL1\n'and rc==0,(out,rc)
        out,rc=f.shell(190+cycle,'cat /old-session');assert rc!=0
        out,rc=f.shell(200+cycle,'echo fresh-session');assert out==b'fresh-session\n'and rc==0
        assert f.control(4)[0]and f.query(0)==core
    f.control(3);assert_park(f,core,counts)
    return {'passed':True,'base':hex(base),'cycles':3,'sameCore':True,'newNativeEachStart':True,
        'nativeFreedWhileTransportAlive':True,'preAckOpenRejected':True,'stoppedCnxnResponder':True,
        'oldRamfsGone':True,'noBulkStallAbortOrReenumeration':True,'state':f.state()}

def split_open(driver):
    f=t.Fixture(driver,0x1c000000);f.begin();f.settle();core=f.query(0);counts=dict(f.extra_calls)
    wire=t.packet('OPEN',240,0,b'shell,v2,raw:echo stale-must-not-run\0')
    f.host_pending.append(bytearray(wire[:17]));f.step();f.settle(3)
    f.control(3);assert_park(f,core,counts)
    resume(f,core,241)
    f.host_pending.append(bytearray(wire[17:]));f.until(lambda:f.closed(240));f.settle()
    assert not any(p[0]=='OKAY'and p[2]==240 for p in f.adb_packets)
    assert not f.channel_output(240)
    out,rc=f.shell(242,'echo after-split');assert out==b'after-split\n'and rc==0
    f.control(3);assert_park(f,core,counts)
    return {'passed':True,'case':'17-byte OPEN header straddles native OFF/START',
        'staleRequestRejected':True,'newCommandAfterward':True,'state':f.state()}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--driver',type=Path)
    p.add_argument('--output',type=Path,default=t.ROOT/'analysis/kbl-usb-enumeration-20261030/park-resume')
    a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    driver=a.driver
    if not driver:driver,commands=t.build(out/'build')
    results=[cycles(driver,base)for base in [0x10000000,0x18000000]]+[split_open(driver)]
    sources=[t.UEFI/'kbl_driver.c',t.UEFI/'kbl_adb_driver.inc',t.UEFI/'kbl_adb_usb.c',t.UEFI/'kbl_adb_usb.h',
             t.RUNTIME/'adb/kbl_adb.c',t.RUNTIME/'adb/kbl_adb.h',Path(__file__)]
    report={'passed':True,'scope':__doc__,'testDriverSha256':t.sha(driver),'results':results,
        'sourceSha256':{str(s.relative_to(t.SOURCE)):t.sha(s)for s in sources},
        'limits':['No physical USB/host pipe state validation','EFI/page attributes/USB DMA/native Fastboot consumer are explicit models']}
    (out/'validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'passed':True,'cases':len(results),'report':str(out/'validation.json')},indent=2))
if __name__=='__main__':main()
