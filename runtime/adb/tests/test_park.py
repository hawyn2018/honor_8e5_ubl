"""Portable ADB park/resume with explicit host native-backend models.

No device, USB, socket, ADB server, firmware write or physical storage access.
The ARM64 production driver integration is tested by the separate UEFI suite.
"""
from pathlib import Path
import argparse,ctypes as C,hashlib,importlib.util,json
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('park_adb_server',HERE/'test_server.py')
t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)

def state(d):return {k:d.fixture_core_status(i)for i,k in enumerate(['healthy','quiescent','parked','wireReady','stop','submits','polls','fileBegins','fileEnds','afterStopCallbacks','backendStops'])}
def fresh(d):assert d.fixture_start(0)==0;f=t.Fixture(d);f.connect();return f
def drain(f):
    out=[]
    for _ in range(100):
        p=f.drain();out+=p;f.acknowledge(p)
        if not p:break
    return out
def stop_and_resume(d):
    assert d.fixture_core_status(1)==1
    assert d.fixture_stop_native_model()==0 and d.fixture_core_status(2)==1
    assert d.fixture_note_native_stopped()==0 # Caller confirmation is idempotent.
    assert d.fixture_start_native_model()==0 and d.fixture_resume()==0
    assert not d.fixture_core_status(2)and not d.fixture_core_status(4)

def flood_with_borrowed_tx(d):
    f=fresh(d);f.exchange(t.packet(b'OPEN',10,0,b'shell,v2,raw:hang\0'))
    assert d.fixture_resume()==-1 and d.fixture_note_native_stopped()==-1
    assert d.fixture_request_stop()==0 and d.fixture_poll()==0
    assert d.fixture_core_status(1)==1 and not d.fixture_ready()
    assert d.fixture_resume()==-1 # Caller has not destroyed the old backend.
    p=C.c_void_p();n=C.c_size_t();assert d.fixture_output(C.byref(p),C.byref(n))==0 and n.value
    borrowed=C.string_at(p,n.value)
    # The borrowed stop reply never needs to return before native release.
    for remote in range(100,133):
        raw=t.packet(b'OPEN',remote,0,b'shell,v2,raw:echo forbidden\0');used=C.c_size_t()
        assert d.fixture_feed(raw,len(raw),C.byref(used))==0 and used.value==len(raw)
        assert d.fixture_poll()==0
    # Stateless rejection records expand beyond the former16-entry queue.
    # The borrowed TX span remains stable while more replies are enqueued.
    used=C.c_size_t();raw=t.packet(b'OPEN',999,0,b'sync:\0')
    assert d.fixture_feed(raw,len(raw),C.byref(used))==0 and used.value==len(raw)
    assert d.fixture_poll()==0
    before=state(d);assert before['healthy']and before['quiescent']and not before['wireReady']
    assert before['submits']==1 and before['fileBegins']==0
    stop_and_resume(d)
    assert C.string_at(p,n.value)==borrowed
    assert d.fixture_done(n.value,0)==0
    packets=drain(f)
    # The borrowed wire record was completed outside Fixture.drain; finish its
    # channel ACK here so the old channel can finish normally.
    cmd,a,b,sz,cs,magic=__import__('struct').unpack('<6I',borrowed[:24])
    if cmd==int.from_bytes(b'WRTE','little'):f.feed(t.packet(b'OKAY',b,a))
    packets+=drain(f)
    rejects=[r for cmd,a,r,data in packets if cmd==b'CLSE'and 100<=r<133]
    assert sorted(rejects)==list(range(100,133)),(rejects,packets)
    assert d.fixture_core_status(5)==1 and not d.fixture_core_status(7)and not d.fixture_core_status(9)
    p=f.exchange(t.packet(b'OPEN',200,0,b'shell,v2,raw:echo resumed\0'));p+=drain(f)
    assert any(k==b'WRTE'and data==b'\x03\x01\0\0\0\0'for k,a,b,data in p)
    assert any(b'resumed\n'in data for k,a,b,data in p if k==b'WRTE')
    after=state(d);assert after['submits']==2 and after['healthy']and not after['afterStopCallbacks']
    assert d.fixture_finish()==0
    return {'case':'native-release-despite-rejection-flood-and-borrowed-tx','passed':True,'beforeRelease':before,'afterResume':after,'rejections':len(rejects)}

def partial_open(d,started_while_stopped,split):
    f=fresh(d)
    if started_while_stopped:assert d.fixture_request_stop()==0
    raw=t.packet(b'OPEN',300,0,b'shell,v2,raw:echo old-generation\0');used=C.c_size_t()
    assert d.fixture_feed(raw[:split],split,C.byref(used))==0 and used.value==split
    if not started_while_stopped:assert d.fixture_request_stop()==0
    stop_and_resume(d)
    f.feed(raw[split:]);packets=drain(f)
    assert any(k==b'CLSE'and b==300 for k,a,b,data in packets)
    assert d.fixture_core_status(5)==0 and d.fixture_core_status(7)==0
    p=f.exchange(t.packet(b'OPEN',301,0,b'shell,v2,raw:echo new-generation\0'));p+=drain(f)
    assert any(b'new-generation\n'in data for k,a,b,data in p if k==b'WRTE')
    assert d.fixture_core_status(5)==1 and not d.fixture_core_status(9)
    assert d.fixture_finish()==0
    return {'case':'partial-open-crosses-stop-resume','passed':True,'startedWhileStopped':started_while_stopped,'prefixBytes':split}

def file_cleanup_without_peer_ack(d):
    f=fresh(d);packets=f.exchange(t.packet(b'OPEN',400,0,b'sync:\0'));assert not d.fixture_io_active()
    local=next(x[1]for x in packets if x[0]==b'OKAY');path=b'/park-live-send,33188'
    f.exchange(t.packet(b'WRTE',400,local,b'SEND'+__import__('struct').pack('<I',len(path))+path));assert d.fixture_io_active()
    d.fixture_fail_file_ends(3);assert d.fixture_request_stop()==0
    assert not d.fixture_core_status(1)and d.fixture_stop_native_model()==1
    # No peer ACK and no drain/output calls. Session cleanup must still retry.
    for _ in range(4):assert d.fixture_poll()==0
    before=state(d);assert before['quiescent']and before['fileEnds']==4 and not d.fixture_io_active()
    assert not before['wireReady'];stop_and_resume(d);drain(f)
    assert not d.fixture_core_status(9)and d.fixture_finish()==0
    return {'case':'file-end-retries-without-host-ack','passed':True,'beforeRelease':before}

def parked_connection_and_fault(d):
    f=fresh(d);assert d.fixture_request_stop()==0
    assert d.fixture_stop_native_model()==0
    for remote,service in [(500,b'sync:'),(501,b'shell,v2,raw:echo rejected'),(502,b'shell,v2,raw:kbl off')]:
        p=f.exchange(t.packet(b'OPEN',remote,0,service+b'\0'))
        assert p==[(b'CLSE',0,remote,b'')],p
    # A new transport handshake can be answered while native stays destroyed.
    f.connect();assert d.fixture_core_status(2)and not d.fixture_core_status(5)and not d.fixture_core_status(7)
    assert not d.fixture_core_status(9)
    d.fixture_level(2);assert d.fixture_poll()==-1
    d.fixture_level(1);assert not d.fixture_core_status(0)and not d.fixture_core_status(1)
    assert d.fixture_resume()==-1 and d.fixture_note_native_stopped()==-1
    assert d.fixture_disconnect()==0 and d.fixture_finish()==0
    return {'case':'parked-handshake-rejects-services-and-sticky-el-fault-refuses-resume','passed':True}

def parked_wire_fault(d,kind):
    f=fresh(d)
    if kind=='partial-tx':f.exchange(t.packet(b'OPEN',600,0,b'shell,v2,raw:echo before-fault\0'))
    assert d.fixture_request_stop()==0 and d.fixture_stop_native_model()==0
    if kind=='partial-tx':
        p=C.c_void_p();n=C.c_size_t();assert d.fixture_output(C.byref(p),C.byref(n))==0 and n.value>1
        assert d.fixture_done(n.value-1,-1)==-1
    else:
        bad=bytearray(t.packet(b'CNXN',0x1000001,65536,b'host::'));bad[20]^=1;used=C.c_size_t()
        assert d.fixture_feed(bytes(bad),len(bad),C.byref(used))==0 and used.value==len(bad)
        assert d.fixture_poll()==-1
    assert not d.fixture_core_status(0)and not d.fixture_core_status(1)and not d.fixture_core_status(2)
    assert d.fixture_start_native_model()==0 and d.fixture_resume()==-1 and d.fixture_note_native_stopped()==-1
    assert d.fixture_request_stop()==0 and d.fixture_disconnect()==0 and d.fixture_finish()==0
    return {'case':'parked-'+kind+'-fault-refuses-resume','passed':True}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,default=t.ROOT/'analysis/kbl-usb-enumeration-20261030/park-core')
    args=p.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True);t.OUT=out;t.DLL=out/'park-fixture.dll'
    command=t.build();d=t.lib();d.fixture_core_status.argtypes=[C.c_uint];d.fixture_fail_file_ends.argtypes=[C.c_uint]
    cases=[flood_with_borrowed_tx(d),*[partial_open(d,stopped,split)for stopped in [False,True]for split in [10,29]],file_cleanup_without_peer_ack(d),parked_connection_and_fault(d),*[parked_wire_fault(d,kind)for kind in ['partial-tx','malformed-packet']]]
    regression=t.unit(d)
    report={'passed':True,'scope':__doc__,'cases':cases,'regressionCases':regression,'buildCommand':command,
            'sourceSha256':{str(x.relative_to(t.RUNTIME)):hashlib.sha256(x.read_bytes()).hexdigest()for x in [t.RUNTIME/'adb/kbl_adb.c',t.RUNTIME/'adb/kbl_adb.h',HERE/'host_fixture.c',Path(__file__)]}}
    (out/'validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'passed':True,'parkCases':len(cases),'regressionCases':len(regression),'report':str(out/'validation.json')}))
if __name__=='__main__':main()
