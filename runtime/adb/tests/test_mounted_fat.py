"""ADB wire sync SEND/RECV on production native VFS and a real FatFs image."""
from pathlib import Path
import ctypes as C,hashlib,importlib.util,json,shutil,struct,subprocess
HERE=Path(__file__).resolve().parent;RUNTIME=HERE.parents[1];ROOT=RUNTIME.parents[3]
OUT=ROOT/'analysis/kbl-adb-mounted-fat';OUT.mkdir(parents=True,exist_ok=True)
spec=importlib.util.spec_from_file_location('adb_wire_fixture',HERE/'test_server.py');t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)
DLL=OUT/'mounted-fat.dll'
sources=[HERE/'host_fat_fixture.c',RUNTIME/'adb/kbl_adb.c',RUNTIME/'kbl_elf.c',RUNTIME/'kbl_sha256.c',RUNTIME/'fs/fat.c',RUNTIME.parent/'third_party/fatfs/ff.c',RUNTIME.parent/'third_party/fatfs/ffunicode.c']
cmd=[shutil.which('clang'),'-std=c11','-O2','-Wall','-Wextra','-Werror','-fno-builtin','-shared','-DKBL_FATFS_TEST_MKFS=1','-I',str(RUNTIME.parent/'third_party/fatfs'),*map(str,sources),'-o',str(DLL)]
r=subprocess.run(cmd,capture_output=True,text=True);(OUT/'build.log').write_text(r.stdout+r.stderr);r.check_returncode()
t.DLL=DLL;d=t.lib();d.fixture_mount_fat.argtypes=[C.c_uint];d.fixture_parent_fault.argtypes=[C.c_uint];d.fixture_parent_counts.argtypes=[C.c_uint];d.fixture_parent_counts.restype=C.c_uint

def sync_session(ro=False):
 assert d.fixture_start(0)==0;assert d.fixture_mount_fat(int(ro))==0;f=t.Fixture(d);f.connect();p=f.exchange(t.packet(b'OPEN',71,0,b'sync:\0'));local=next(a for op,a,b,payload in p if op==b'OKAY');return f,local

def exchange(f,local,payload):return b''.join(p[3]for p in f.exchange(t.packet(b'WRTE',71,local,payload))if p[0]==b'WRTE')
def settle(f):
 data=b''
 for _ in range(100):
  p=f.drain();data+=b''.join(x[3]for x in p if x[0]==b'WRTE');f.acknowledge(p)
  if not p:break
 return data

def finish(f,local):
 f.exchange(t.packet(b'CLSE',71,local));settle(f);assert not d.fixture_io_active();assert d.fixture_unmount_fat()==0;assert d.fixture_finish()==0

def send(f,local,path,data,expect=True):
 encoded=(path+',33188').encode();reply=exchange(f,local,b'SEND'+struct.pack('<I',len(encoded))+encoded)+settle(f)
 if not expect:return reply
 assert b'FAIL'not in reply,reply
 for start in range(0,len(data),4093):
  piece=data[start:start+4093];reply+=exchange(f,local,b'DATA'+struct.pack('<I',len(piece))+piece)+settle(f)
 reply+=exchange(f,local,b'DONE'+struct.pack('<I',0))+settle(f);assert reply==b'OKAY\0\0\0\0',reply
 return reply

def recv(f,local,path):
 encoded=path.encode();wire=exchange(f,local,b'RECV'+struct.pack('<I',len(encoded))+encoded)+settle(f);out=b''
 while wire:
  assert len(wire)>=8;kind,n=wire[:4],struct.unpack('<I',wire[4:8])[0];wire=wire[8:]
  if kind==b'DONE':assert not n and not wire;return out
  assert kind==b'DATA'and len(wire)>=n,(kind,n,len(wire));out+=wire[:n];wire=wire[n:]
 raise AssertionError('Missing sync DONE')

cases=[];payload=bytes(range(256))*300+b'actual-mounted-fat\0'
f,local=sync_session();send(f,local,'/mnt/roundtrip/existing.bin',payload);assert d.fixture_parent_counts(0)==0;assert recv(f,local,'/mnt/roundtrip/existing.bin')==payload;cases.append('existing-mount-root-and-parent-no-mkdir-wire-roundtrip')
d.fixture_parent_fault(0);send(f,local,'/mnt/new/a/blob.bin',b'new directories');assert d.fixture_parent_counts(0)==2;assert recv(f,local,'/mnt/new/a/blob.bin')==b'new directories';cases.append('only-ENOENT-parents-created-and-re-statted')
finish(f,local)
for fault,path,needle,expected_mkdir in ((0,'/mnt/roundtrip/blocker/file',b'parent directory unavailable',0),(1,'/mnt/probe/file',b'parent directory unavailable',0),(2,'/mnt/race/file',None,1),(3,'/mnt/racerfile/file',b'parent directory unavailable',1),(4,'/mnt/denied/file',b'parent directory unavailable',1)):
 f,local=sync_session();d.fixture_parent_fault(fault)
 if needle:reply=send(f,local,path,b'',False);assert reply.startswith(b'FAIL')and needle in reply,reply
 else:send(f,local,path,b'EEXIST directory race');assert recv(f,local,path)==b'EEXIST directory race'
 assert d.fixture_parent_counts(0)==expected_mkdir,(fault,d.fixture_parent_counts(0));finish(f,local);cases.append(f'parent-boundary-{fault}')
f,local=sync_session(True);reply=send(f,local,'/mnt/roundtrip/ro.bin',b'',False);assert reply.startswith(b'FAIL')and b'read-only partition'in reply and b'parent directory unavailable'not in reply;assert not d.fixture_parent_counts(0);finish(f,local);cases.append('RO-existing-parents-reach-leaf-open-without-mkdir')
result={'passed':True,'cases':cases,'wirePayloadBytes':len(payload),'sha256':hashlib.sha256(payload).hexdigest(),'scope':'Production ADB sync wire codec, native VFS and real host-backed FatFs; no phone or USB','sourceHashes':{str(p.relative_to(RUNTIME.parent)):hashlib.sha256(p.read_bytes()).hexdigest()for p in sources}}
(OUT/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'passed':True,'cases':len(cases),'report':str(OUT/'validation.json')}))
