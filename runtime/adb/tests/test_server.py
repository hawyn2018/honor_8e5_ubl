"""Actual portable C ADB server + actual native external VFS, host providers.

The localhost socket is test-only ADB wire transport (not a Fastboot bridge),
and the shell-command executor is an explicit host model. No phone/USB test.
"""
from pathlib import Path
import ctypes as C,hashlib,json,shutil,socket,struct,subprocess,threading,time,select,os
HERE=Path(__file__).resolve().parent;RUNTIME=HERE.parents[1];ROOT=RUNTIME.parents[3]
OUT=ROOT/'analysis/kbl-services-20261030/adb';OUT.mkdir(parents=True,exist_ok=True)
DLL=OUT/'adb-fixture.dll'
def build():
    cmd=[shutil.which('clang'),'-std=c11','-O2','-Wall','-Wextra','-Werror','-shared',str(HERE/'host_fixture.c'),str(RUNTIME/'native/tests/fs_unavailable.c'),str(RUNTIME/'adb/kbl_adb.c'),str(RUNTIME/'kbl_elf.c'),str(RUNTIME/'kbl_sha256.c'),str(RUNTIME/'native/kbl_efi_bootloader.c'),'-o',str(DLL)]
    r=subprocess.run(cmd,capture_output=True,text=True);(OUT/'build.log').write_text(r.stdout+r.stderr);r.check_returncode();return cmd
def lib():
    d=C.CDLL(str(DLL));d.fixture_start.argtypes=[C.c_uint];d.fixture_feed.argtypes=[C.c_void_p,C.c_size_t,C.POINTER(C.c_size_t)]
    d.fixture_output.argtypes=[C.POINTER(C.c_void_p),C.POINTER(C.c_size_t)];d.fixture_done.argtypes=[C.c_size_t,C.c_int];d.fixture_level.argtypes=[C.c_uint]
    return d
def packet(cmd,a=0,b=0,data=b'',legacy=False):
    word=int.from_bytes(cmd,'little');return struct.pack('<6I',word,a,b,len(data),sum(data)&0xffffffff if legacy else 0,word^0xffffffff)+data
class Fixture:
    def __init__(self,d):self.d=d;self.trace=[];self.buf=b''
    def feed(self,data,fragment=0):
        while data:
            chunk=data[:fragment or len(data)];n=C.c_size_t();rc=self.d.fixture_feed(chunk,len(chunk),C.byref(n))
            assert rc in (0,1),(rc,n.value);data=data[n.value:];assert self.d.fixture_poll()==0
            if not n.value and not data:break
    def drain(self):
        for _ in range(64):
            assert self.d.fixture_poll()==0;p=C.c_void_p();n=C.c_size_t();assert self.d.fixture_output(C.byref(p),C.byref(n))==0
            if not n.value:break
            span=C.string_at(p,n.value);self.buf+=span;assert self.d.fixture_done(n.value,0)==0
        packets=[]
        while len(self.buf)>=24:
            cmd,a,b,n,checksum,magic=struct.unpack('<6I',self.buf[:24]);assert magic==cmd^0xffffffff
            if len(self.buf)<24+n:break
            data=self.buf[24:24+n];self.buf=self.buf[24+n:];packets.append((cmd.to_bytes(4,'little'),a,b,data))
        self.trace+=packets;return packets
    def connect(self,legacy=False):
        self.feed(packet(b'CNXN',0x1000000 if legacy else 0x1000001,65536,b'host::features=shell_v2,stat_v2,ls_v2;',legacy),fragment=1)
        p=self.drain();assert any(x[0]==b'CNXN'for x in p),p
    def acknowledge(self,packets):
        for cmd,a,b,data in packets:
            if cmd==b'WRTE':self.feed(packet(b'OKAY',b,a))
            elif cmd==b'CLSE':self.feed(packet(b'CLSE',b,a))
    def exchange(self,data):self.feed(data);p=self.drain();self.acknowledge(p);return p

def late_close_stdin(d):
    cases=[]
    for index,(command,expected,code)in enumerate(((b'echo completed',b'completed\n',0),(b'exit7',b'status-seven\n',7))):
        assert d.fixture_start(0)==0;f=Fixture(d);f.connect();remote=41+index
        first=f.exchange(packet(b'OPEN',remote,0,b'shell,v2,raw:'+command+b'\0'))
        local=next(a for cmd,a,b,data in first if cmd==b'OKAY')
        assert b''.join(data[5:]for cmd,a,b,data in first if cmd==b'WRTE')==expected,first
        # The model job is done, stdout is acknowledged, and EXIT is waiting
        # for output. The host can still deliver its stdin EOF or buffered data.
        f.feed(packet(b'WRTE',remote,local,b'\x04\0\0\0\0'))
        f.feed(packet(b'WRTE',remote,local,b'\0\x01\0\0\0x'))
        tail=[]
        for _ in range(20):
            p=f.drain();tail+=p;f.acknowledge(p)
            if not p:break
        exit_frame=b'\x03\x01\0\0\0'+bytes([code])
        exits=[i for i,p in enumerate(tail)if p[0]==b'WRTE'and p[3]==exit_frame]
        closes=[i for i,p in enumerate(tail)if p[0]==b'CLSE']
        assert len(exits)==1 and closes and exits[0]<closes[0],tail
        assert sum(p[0]==b'OKAY'for p in tail)==2,tail
        assert not any(p[0]==b'WRTE'and p[3]!=exit_frame for p in tail),tail
        assert d.fixture_finish()==0
        cases.append({'case':'late-close-stdin-before-exit','exitCode':code,
                      'stdout':expected.decode(),'tailCommands':[p[0].decode()for p in tail],
                      'exitFrameHex':exit_frame.hex()})
    return cases

def unit(d):
    cases=[]
    assert d.fixture_start(0)==0;f=Fixture(d);f.connect()
    p=f.exchange(packet(b'OPEN',1,0,b'shell,v2,raw:echo protocol\0'));output=b''
    for _ in range(20):
        for cmd,a,b,data in p:
            if cmd==b'WRTE':output+=data
        p=f.drain();f.acknowledge(p)
        if not p:break
    assert b'protocol\n'in output and b'\x03\x01\0\0\0\0'in output,output;cases.append('fragmented-shell-v2-and-exit')
    # Open an interactive shell, stop it from a second channel while busy.
    p=f.exchange(packet(b'OPEN',2,0,b'shell,v2,pty:\0'));local=next(x[1]for x in p if x[0]==b'OKAY')
    f.exchange(packet(b'WRTE',2,local,b'\0\x05\0\0\0hang\n'))
    f.exchange(packet(b'OPEN',3,0,b'shell,v2,raw:kbl off\0'))
    for _ in range(30):p=f.drain();f.acknowledge(p)
    assert d.fixture_ready()==1 and d.fixture_notifications()==1;cases.append('busy-shell-out-of-band-stop-drained')
    assert d.fixture_finish()==0
    assert d.fixture_start(1)==0;f=Fixture(d);f.feed(packet(b'CNXN',0x1000001,65536,b'host::'))
    p=f.drain();assert p[0][0]==b'AUTH'and p[0][1]==1 and len(p[0][3])==20
    f.feed(packet(b'AUTH',2,0,b'yes'));assert any(x[0]==b'CNXN'for x in f.drain());cases.append('optional-native-auth-callback-model')
    assert d.fixture_finish()==0
    assert d.fixture_start(0)==0;f=Fixture(d);f.connect(legacy=True);cases.append('legacy-checksum-handshake')
    # Caller owns returned TX span until terminal completion, including partial spans.
    f.feed(packet(b'OPEN',4,0,b'shell,v2,raw:echo hold\0',legacy=True));p=C.c_void_p();n=C.c_size_t();assert d.fixture_output(C.byref(p),C.byref(n))==0 and n.value
    assert d.fixture_finish()==1;assert d.fixture_done(1,0)==0
    assert d.fixture_output(C.byref(p),C.byref(n))==0;assert d.fixture_done(n.value,0)==0
    assert d.fixture_disconnect()==0 and d.fixture_finish()==0;cases.append('tx-span-retained-until-terminal-and-partial-completion')
    assert d.fixture_start(0)==0;f=Fixture(d);f.connect()
    p=f.exchange(packet(b'OPEN',10,0,b'sync:\0'));local=next(x[1]for x in p if x[0]==b'OKAY')
    path=b'/dev/block/by-name/giant';request=b'LST2'+struct.pack('<I',len(path))+path
    p=f.exchange(packet(b'WRTE',10,local,request));data=b''.join(x[3]for x in p if x[0]==b'WRTE')
    assert data[:4]==b'LST2'and len(data)==72 and struct.unpack('<Q',data[40:48])[0]==(1<<33)+17
    cases.append('stat-v2-real-external-vfs-64bit-size')
    path=b'/stopped-partial,33206';request=b'SEND'+struct.pack('<I',len(path))+path
    f.exchange(packet(b'WRTE',10,local,request));f.exchange(packet(b'WRTE',10,local,b'DATA'+struct.pack('<I',30)+b'A'*30))
    assert d.fixture_io_active()==1
    f.exchange(packet(b'OPEN',11,0,b'shell,v2,raw:kbl off\0'))
    for _ in range(40):p=f.drain();f.acknowledge(p)
    assert d.fixture_ready()==1 and d.fixture_io_active()==0;assert d.fixture_finish()==0
    cases.append('busy-sync-stop-releases-owner-without-more-writes')
    assert d.fixture_start(0)==0;f=Fixture(d);f.connect();p=f.exchange(packet(b'OPEN',12,0,b'sync:\0'));assert d.fixture_io_active()==0
    local=next(x[1]for x in p if x[0]==b'OKAY');path=b'/malformed-live-send,33188'
    f.exchange(packet(b'WRTE',12,local,b'SEND'+struct.pack('<I',len(path))+path));assert d.fixture_io_active()==1
    bad=bytearray(packet(b'WRTE',12,1,b''));bad[20]^=1;n=C.c_size_t();assert d.fixture_feed(bytes(bad),len(bad),C.byref(n))==0
    assert d.fixture_poll()==-1 and d.fixture_finish()==0;cases.append('malformed-header-cleans-live-files-session')
    assert d.fixture_start(0)==0;f=Fixture(d);f.connect();d.fixture_level(2);assert d.fixture_poll()==-1
    d.fixture_level(1);assert d.fixture_finish()==0;cases.append('el-change-fails-without-lowering-and-cleanup-retained')
    for sync in (False,True):
        assert d.fixture_start(0)==0;f=Fixture(d);f.connect()
        f.exchange(packet(b'OPEN',20,0,b'sync:\0'if sync else b'shell,v2,pty:\0'))
        assert d.fixture_request_stop()==0 and d.fixture_ready()==0
        for _ in range(40):
            assert d.fixture_request_stop()==0
            p=f.drain();f.acknowledge(p)
        assert d.fixture_ready()==1 and d.fixture_reentrant_result()==1 and not d.fixture_io_active()
        assert d.fixture_finish()==0;cases.append('external-stop-'+('sync'if sync else 'shell')+'-drains-and-blocks-self-destroy')
    cases.extend('late-close-stdin-before-exit-'+str(case['exitCode'])for case in late_close_stdin(d))
    return cases

def stock(d):
    adb=Path('D:/platform-tools-latest-windows/platform-tools/adb.exe');assert adb.is_file(),str(adb)
    listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen();port=listener.getsockname()[1]
    probe=socket.socket();probe.bind(('127.0.0.1',0));server_port=probe.getsockname()[1];probe.close()
    stop=threading.Event();errors=[];wire=[]
    assert d.fixture_start(0)==0
    def serve():
        listener.settimeout(.2)
        try:
            connection=None;pending=bytearray()
            while not stop.is_set():
                if connection is None:
                    try:connection,_=listener.accept();connection.setblocking(False)
                    except socket.timeout:continue
                readable,_,_=select.select([connection],[],[],.002)
                if readable:
                    data=connection.recv(131072)
                    if not data:d.fixture_disconnect();connection.close();connection=None;pending.clear();continue
                    pending+=data
                for _ in range(8):
                    if not pending:break
                    n=C.c_size_t();chunk=bytes(pending);rc=d.fixture_feed(chunk,len(chunk),C.byref(n));assert rc in (0,1),('feed',rc)
                    del pending[:n.value];assert d.fixture_poll()==0
                    if not n.value:break
                assert d.fixture_poll()==0
                for _ in range(8):
                    p=C.c_void_p();n=C.c_size_t();assert d.fixture_output(C.byref(p),C.byref(n))==0
                    if not n.value:break
                    data=C.string_at(p,n.value);connection.setblocking(True);connection.sendall(data);connection.setblocking(False)
                    wire.append(len(data));assert d.fixture_done(len(data),0)==0
        except Exception as e:errors.append(repr(e))
        finally:
            if connection:connection.close()
    thread=threading.Thread(target=serve,daemon=True);thread.start();creation=0x08000000 if os.name=='nt'else 0
    steps=[]
    def command(args,expected=0):
        r=subprocess.run([str(adb),'-P',str(server_port),*args],capture_output=True,timeout=25,creationflags=creation)
        steps.append({'args':args,'rc':r.returncode,'stdout':r.stdout.decode(errors='replace'),'stderr':r.stderr.decode(errors='replace')})
        assert r.returncode==expected,(steps[-1],errors);return r
    try:
        command(['start-server']);target=f'127.0.0.1:{port}';command(['connect',target])
        args=['-s',target];r=command(args+['shell','echo','stock-adb']);assert r.stdout.replace(b'\r\n',b'\n')==b'stock-adb\n',(r.stdout,steps,errors)
        command(args+['shell','false'],7)
        r=command(args+['shell','big']);assert r.stdout==b'X'*131072
        payload=bytes(range(256))*1024+b'END\0';source=OUT/'push-input.bin';source.write_bytes(payload)
        command(args+['push','-Z',str(source),'/directory/blob.bin'])
        pulled=OUT/'pulled.bin';command(args+['pull','-Z','/directory/blob.bin',str(pulled)]);assert pulled.read_bytes()==payload
        directory=OUT/'directory-pull';directory.mkdir(exist_ok=True);command(args+['pull','-Z','/directory',str(directory)])
        paths=list(directory.rglob('blob.bin'));assert paths and paths[0].read_bytes()==payload
        command(args+['shell','kbl','off'])
        deadline=time.time()+3
        while not d.fixture_ready()and time.time()<deadline:time.sleep(.01)
        assert d.fixture_ready()and d.fixture_notifications()==1,(errors,steps)
        command(['disconnect',target])
    finally:
        (OUT/'stock-progress.json').write_text(json.dumps({'steps':steps,'errors':errors},indent=2)+'\n')
        subprocess.run([str(adb),'-P',str(server_port),'kill-server'],capture_output=True,timeout=10,creationflags=creation)
        stop.set();thread.join(2);listener.close()
        assert d.fixture_finish()==0
    assert not errors,errors
    return {'steps':steps,'wireFrames':len(wire),'pushedBytes':len(payload),'sha256':hashlib.sha256(payload).hexdigest(),'scope':'Stock adb.exe via isolated localhost ADB wire fixture; actual portable C server/native VFS, explicitly modeled shell executor and platform; no USB, phone or Fastboot bridge.'}
def main():
    cmd=build();d=lib();cases=unit(d);result={'passed':True,'unitCases':cases,'stock':stock(d),'buildCommand':cmd,
        'sourceHashes':{str(p.relative_to(RUNTIME)):hashlib.sha256(p.read_bytes()).hexdigest()for p in [RUNTIME/'adb/kbl_adb.c',RUNTIME/'adb/kbl_adb.h',RUNTIME/'native/kbl_io.inc',RUNTIME/'native/kbl_native.c',RUNTIME/'native/kbl_vfs.inc',RUNTIME/'native/kbl_efi_bootloader.c',RUNTIME/'native/kbl_efi_bootloader.h']}}
    (OUT/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'passed':True,'unitCases':len(cases),'stockSteps':len(result['stock']['steps']),'report':str(OUT/'validation.json')}))
if __name__=='__main__':main()
