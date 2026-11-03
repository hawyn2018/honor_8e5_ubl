"""Real ADB wire multi-channel core; explicit session/transaction scheduler models."""
from pathlib import Path
import ctypes as C,hashlib,importlib.util,json,shutil,struct,subprocess
HERE=Path(__file__).resolve().parent;RUNTIME=HERE.parents[1];ROOT=RUNTIME.parents[3]
OUT=ROOT/'analysis/kbl-multisession-20261030/adb-multi';OUT.mkdir(parents=True,exist_ok=True)
spec=importlib.util.spec_from_file_location('multi_adb_wire_base',HERE/'test_server.py');t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)
DLL=OUT/'multi-fixture.dll';sources=[HERE/'host_multi_fixture.c',RUNTIME/'native/tests/fs_unavailable.c',RUNTIME/'adb/kbl_adb.c',RUNTIME/'kbl_elf.c',RUNTIME/'kbl_sha256.c',RUNTIME/'native/kbl_efi_bootloader.c']
cmd=[shutil.which('clang'),'-std=c11','-O2','-Wall','-Wextra','-Werror','-shared',*map(str,sources),'-o',str(DLL)]
r=subprocess.run(cmd,capture_output=True,text=True);(OUT/'build.log').write_text(r.stdout+r.stderr);r.check_returncode();t.DLL=DLL;d=t.lib();d.fixture_multi_state.argtypes=[C.c_uint];d.fixture_multi_state.restype=C.c_uint;d.fixture_core_status.argtypes=[C.c_uint];d.fixture_fail_file_ends.argtypes=[C.c_uint]
class Wire(t.Fixture):
 def __init__(self):super().__init__(d);self.locals={};self.data={};self.closed=set();self.held=set();self.held_packets=[]
 def pump(self,rounds=80):
  for _ in range(rounds):
   p=self.drain()
   if not p:break
   for op,a,b,payload in p:
    if op==b'OKAY':self.locals[b]=a
    elif op==b'WRTE':
     self.data.setdefault(b,bytearray()).extend(payload)
     if b in self.held:self.held_packets.append((op,a,b,payload))
     else:self.feed(t.packet(b'OKAY',b,a))
    elif op==b'CLSE':self.closed.add(b);self.feed(t.packet(b'CLSE',b,a))
  return self
 def send(self,op,a=0,b=0,payload=b''):self.feed(t.packet(op,a,b,payload));return self.pump()
 def open(self,remote,service):return self.send(b'OPEN',remote,0,service+b'\0')
 def stdin(self,remote,payload):return self.send(b'WRTE',remote,self.locals[remote],b'\0'+struct.pack('<I',len(payload))+payload)
 def sync(self,remote,payload):return self.send(b'WRTE',remote,self.locals[remote],payload)
 def close(self,remote):return self.send(b'CLSE',remote,self.locals[remote])
 def stdout(self,remote):
  raw=bytes(self.data.get(remote,b''));out=b''
  while raw:
   assert len(raw)>=5,(remote,raw);kind=raw[0];n=struct.unpack('<I',raw[1:5])[0];assert len(raw)>=5+n
   if kind==1:out+=raw[5:5+n]
   raw=raw[5+n:]
  return out

def fresh():
 assert d.fixture_start(0)==0;f=Wire();f.connect();return f

def finish(f):
 assert d.fixture_disconnect()==0;assert d.fixture_multi_state(0)==0;assert d.fixture_finish()==0

def sync_request(kind,path):p=path.encode();return kind+struct.pack('<I',len(p))+p

def start_send(f,remote,path):f.sync(remote,sync_request(b'SEND',path+',33188'))
def data_send(f,remote,data):f.sync(remote,b'DATA'+struct.pack('<I',len(data))+data)
def done_send(f,remote):f.sync(remote,b'DONE'+bytes(4))
def read_sync(f,remote,path):
 before=len(f.data.get(remote,b''));f.sync(remote,sync_request(b'RECV',path));raw=bytes(f.data[remote][before:]);result=b''
 while raw:
  assert len(raw)>=8;kind,n=raw[:4],struct.unpack('<I',raw[4:8])[0];raw=raw[8:]
  if kind==b'DONE':assert n==0 and not raw;return result
  assert kind==b'DATA'and len(raw)>=n;result+=raw[:n];raw=raw[n:]
 raise AssertionError('No RECV DONE')

cases=[]
f=fresh();f.open(1,b'shell,v2,pty:').open(2,b'shell,v2,pty:').open(3,b'sync:').open(4,b'sync:');assert d.fixture_multi_state(0)==2 and d.fixture_io_active()==0
# Partial path headers hold no world lease and cannot starve ready commands.
begins=d.fixture_multi_state(3);path=b'/incomplete';f.sync(3,b'LST2'+struct.pack('<I',len(path)));f.stdin(1,b'echo partial-path-unblocked\n');assert b'partial-path-unblocked\n'in f.stdout(1)and d.fixture_multi_state(3)==begins
f.sync(3,path);assert bytes(f.data[3]).startswith(b'LST2');cases.append('partial-sync-path-header-does-not-acquire-native-lease')
f.stdin(1,b'cd /one\n').stdin(2,b'cd /two\n').stdin(1,b'pwd\n').stdin(2,b'pwd\n');assert b'/one\n'in f.stdout(1)and b'/two\n'not in f.stdout(1);assert b'/two\n'in f.stdout(2)and b'/one\n'not in f.stdout(2);cases.append('two-shell-session-routing-cwd-and-idle-sync-no-lease')
start_send(f,3,'/directory/blob');payload=bytes(range(256))*20;data_send(f,3,payload[:2000]);before=d.fixture_multi_state(5)
f.stdin(1,b'echo queued-one\n').stdin(2,b'echo queued-two\n');f.sync(4,sync_request(b'LST2','/directory/blob'));assert d.fixture_multi_state(5)==before and b'queued-one\n'not in f.stdout(1)
data_send(f,3,payload[2000:]);done_send(f,3);f.pump();assert b'queued-one\n'in f.stdout(1)and b'queued-two\n'in f.stdout(2);assert bytes(f.data[4]).startswith(b'LST2');assert read_sync(f,4,'/directory/blob')==payload
assert d.fixture_multi_state(3)==d.fixture_multi_state(4)and not d.fixture_io_active();cases.append('SEND-transaction-queues-two-shells-and-stat-fairly-without-replay')
# A closing queued sync channel must not block the active SEND DATA receiver.
start_send(f,3,'/close-queue');data_send(f,3,b'first');f.sync(4,sync_request(b'LST2','/directory/blob'));f.close(4);data_send(f,3,b'second');done_send(f,3);assert read_sync(f,3,'/close-queue')==b'firstsecond';cases.append('CLSE-cancels-only-queued-owner-and-does-not-block-other-wire-DATA')
# Completed commit must not replay DATA when capability cleanup needs retries.
d.fixture_fail_file_ends(3);start_send(f,3,'/end-retry');data_send(f,3,b'once-only');done_send(f,3);f.pump();assert read_sync(f,3,'/end-retry')==b'once-only';cases.append('files-end-retries-after-commit-without-write-or-command-replay')
finish(f)

f=fresh();f.open(10,b'shell,v2,pty:').open(11,b'shell,v2,pty:').open(12,b'sync:');f.stdin(10,b'hang\n').stdin(11,b'echo other-owner\n');assert b'other-owner\n'not in f.stdout(11)
f.close(10);f.pump();assert b'other-owner\n'in f.stdout(11)and d.fixture_notifications()==0;f.sync(12,sync_request(b'LST2','/'));assert bytes(f.data[12]).startswith(b'LST2');cases.append('closing-active-shell-cancels-own-job-and-preserves-queued-shell-sync')
f.stdin(11,b'hang\n').stdin(11,b'echo pending-must-cancel\n').open(13,b'shell,v2,raw:echo after-ctrl-c');f.stdin(11,b'\x03');f.pump();assert b'after-ctrl-c\n'in f.stdout(13)and d.fixture_notifications()==0;cases.append('Ctrl-C-detaches-own-channel-with-world-and-other-jobs-retained')
finish(f)

f=fresh();f.open(21,b'sync:');start_send(f,21,'/persist');data_send(f,21,b'world survives channel disconnect');done_send(f,21);assert d.fixture_disconnect()==0;assert d.fixture_multi_state(0)==0
f=Wire();f.connect();f.open(22,b'sync:');assert read_sync(f,22,'/persist')==b'world survives channel disconnect';assert d.fixture_core_status(10)==0;cases.append('physical-ADB-disconnect-closes-wire-owners-but-keeps-native-world')
finish(f)

f=fresh()
for remote in range(30,38):f.open(remote,b'shell,v2,pty:')
assert d.fixture_multi_state(0)==8;f.open(38,b'shell,v2,pty:');assert 38 not in f.closed and d.fixture_multi_state(0)==9
f.held.update(range(30,40));f.open(39,b'shell,v2,raw:kbl off');assert d.fixture_notifications()==1 and d.fixture_multi_state(0)==0 and d.fixture_core_status(1)==1
assert d.fixture_stop_native_model()==0 and d.fixture_core_status(2)==1 and d.fixture_core_status(9)==0
for op,a,b,payload in f.held_packets:f.feed(t.packet(b'OKAY',b,a))
f.held.clear();f.pump();assert d.fixture_core_status(9)==0;cases.append('dynamic-ninth-channel-plus-reserved-OFF-broadcast-native-cleanup-before-peer-ACK')
assert d.fixture_finish()==0
result={'passed':True,'cases':cases,'sourceHashes':{str(p.relative_to(RUNTIME)):hashlib.sha256(p.read_bytes()).hexdigest()for p in sources+[RUNTIME/'adb/kbl_adb.h',HERE/'host_fixture.c']},'scope':'Actual portable ADB core/wire and native VFS. Explicit host session/scheduler/shell models; not physical USB or instruction-level native session proof.'}
(OUT/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'passed':True,'cases':len(cases),'report':str(OUT/'validation.json')}))
