"""Exercise real upstream-mkfs EROFS images against the production adapter.

Usage: python test_host.py FIXTURE_DIR (expected.json and *.img).
No USB, block devices, filesystem repairs or on-disk writes are used.
"""
from pathlib import Path
import argparse,ctypes as C,hashlib,json,sys,subprocess
SRC=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser();p.add_argument('fixtures',type=Path);p.add_argument('--output',type=Path);args=p.parse_args()
OUT=args.output.resolve()if args.output else SRC.parent/'erofs-test-output'
subprocess.run([sys.executable,str(Path(__file__).with_name('build_test.py')),'--output',str(OUT)],check=True)
lib=C.CDLL(str(OUT/'erofs-host.dll'))
P=C.c_void_p;Z=C.c_size_t;U=C.c_uint;Q=C.c_uint64;I=C.c_int
Alloc=C.CFUNCTYPE(P,P,Z,Z);Free=C.CFUNCTYPE(None,P,P,Z)
Read=C.CFUNCTYPE(I,P,Q,P,Z,C.POINTER(Z));Write=Read;Flush=C.CFUNCTYPE(I,P)
class Host(C.Structure):_fields_=[('opaque',P),('allocate',Alloc),('release',Free)]
class Dev(C.Structure):_fields_=[('opaque',P),('bytes',Q),('sector_bytes',U),('flags',U),('read',Read),('write',Write),('flush',Flush)]
class Stat(C.Structure):_fields_=[('bytes',Q),('type',U),('flags',U),('block_bytes',U),('reserved',U)]
OPNAMES=['unmount','open','close','read','write','fstat','stat','list','mkdir','unlink','rename','sync']
class Ops(C.Structure):_fields_=[(n,P)for n in OPNAMES]
class FS(C.Structure):_fields_=[('ops',C.POINTER(Ops)),('opaque',P),('flags',U),('type',C.c_char_p)]
lib.kbl_erofs_mount.argtypes=[C.POINTER(Host),C.POINTER(Dev),C.c_char_p,U,C.POINTER(FS)]
lib.kbl_erofs_mount.restype=I
count=0
def check(cond,what):
 global count
 count+=1
 if not cond:raise AssertionError(what)
def op(fs,name,restype,*types):return C.CFUNCTYPE(restype,P,*types)(getattr(fs.ops.contents,name))
def utf(s):return s.encode('utf-8')

class Case:
 def __init__(self,data):
  self.data=bytearray(data);self.memory={};self.writes=self.flushes=0;self.limit=0;self.bad=False;self.fail=False;self.allocation_budget=-1;self.allocation_calls=0
  @Alloc
  def alloc(_,n,alignment):
   self.allocation_calls+=1
   if self.fail or self.allocation_budget==0:return None
   if self.allocation_budget>0:self.allocation_budget-=1
   b=C.create_string_buffer(n+alignment);p=(C.addressof(b)+alignment-1)&~(alignment-1);self.memory[p]=(b,n);return p
  @Free
  def free(_,p,n):
   item=self.memory.pop(p,None)
   if not item or item[1]!=n:self.bad=True
  @Read
  def read(_,off,buf,n,done):
   done[0]=0
   if off>len(self.data)or n>len(self.data)-off:return -5
   if self.limit:n=min(n,self.limit)
   C.memmove(buf,bytes(self.data[off:off+n]),n);done[0]=n;return 0
  @Write
  def write(_,off,buf,n,done):self.writes+=1;done[0]=0;return -5
  @Flush
  def flush(_):self.flushes+=1;return -5
  self.callbacks=(alloc,free,read,write,flush)
  self.host=Host(None,alloc,free);self.dev=Dev(None,len(data),512,0,read,write,flush);self.fs=FS()
 def mount(self,flags=1):return lib.kbl_erofs_mount(C.byref(self.host),C.byref(self.dev),b'erofs',flags,C.byref(self.fs))
 def stat(self,path):
  s=Stat();r=op(self.fs,'stat',I,C.c_char_p,C.POINTER(Stat))(self.fs.opaque,utf(path),C.byref(s));return r,s
 def contents(self,path,expected):
  h=P();f=self.fs;r=op(f,'open',I,C.c_char_p,U,C.POINTER(P))(f.opaque,utf(path),1,C.byref(h));check(r==0,('open',path,r))
  check(op(f,'unmount',I)(f.opaque)==-16,'open unmount busy')
  stat=Stat();check(op(f,'fstat',I,P,C.POINTER(Stat))(f.opaque,h,C.byref(stat))==0,'fstat')
  check(stat.bytes==expected['bytes'],'fstat size');result=bytearray();buf=C.create_string_buffer(65536)
  # Arbitrary offsets/short device reads exercise cluster trimming.
  for offset in range(0,stat.bytes,7919):
   done=Z();n=min(7919,stat.bytes-offset);r=op(f,'read',I,P,Q,P,Z,C.POINTER(Z))(f.opaque,h,offset,buf,n,C.byref(done));check(r==0 and done.value==n,('read',path,offset,r,done.value));result.extend(buf.raw[:n])
  check(hashlib.sha256(result).hexdigest()==expected['sha256'],('digest',path))
  done=Z(99);check(op(f,'read',I,P,Q,P,Z,C.POINTER(Z))(f.opaque,h,stat.bytes,buf,9,C.byref(done))==0 and done.value==0,'EOF')
  check(op(f,'write',I,P,Q,P,Z,C.POINTER(Z))(f.opaque,h,0,buf,1,C.byref(done))==-30 and done.value==0,'readonly write')
  check(op(f,'sync',I,P)(f.opaque,h)==0,'readonly sync')
  check(op(f,'close',I,P)(f.opaque,h)==0,'close')
 def finish(self):
  check(op(self.fs,'sync',I,P)(self.fs.opaque,None)==0,'volume sync')
  check(op(self.fs,'unmount',I)(self.fs.opaque)==0,'unmount');check(not self.memory and not self.bad,'ownership');check(self.writes==self.flushes==0,'strict no device write/flush')

fixtures=args.fixtures;expected=json.loads((fixtures/'expected.json').read_text(encoding='utf-8'));reports=[]
images=sorted(fixtures.glob('*.img'));check(bool(images),'real upstream images required')
for image in images:
 before=count;data=image.read_bytes();c=Case(data)
 if data[1024+12]>12:
  check(c.mount()==-38 and not c.memory,'unsupported blocksize explicit');reports.append({'image':image.name,'negativeCase':'blocksize exceeds current EFI stack profile','checks':count-before});continue
 check(c.mount(0)==-30 and not c.memory,'RW rejected before allocation');check(c.mount()==0,('mount',image.name))
 # A full lookup path has no adapter-owned 4096-byte cap. Repeated separators
 # are valid lookup syntax and allow real upstream fixtures to exceed it.
 r,st=c.stat('/'*5000+'hello.txt');check(r==0 and st.bytes==expected['files']['/hello.txt']['bytes'],'lookup path exceeds 4 KiB')
 baseline=set(c.memory);complete=False;link_path='/chain-00'if '/chain-00'in expected['symlinks']else next(iter(expected['symlinks']))
 begin=c.allocation_calls;r,_=c.stat(link_path);check(r==0,'successful lookup for derived allocation count');needed=c.allocation_calls-begin
 for budget in range(needed+1):
  c.allocation_budget=budget;r,_=c.stat(link_path);c.allocation_budget=-1
  check(set(c.memory)==baseline,'path/link OOM ownership cleanup')
  if r==0:complete=True;break
  check(r==-12,'path/link OOM status')
 check(complete,'path/link succeeds with sufficient allocator budget')
 # Force partial successful byte reads only after the superblock probe. Native
 # providers deliver full spans; the reader still honors generic short reads.
 c.limit=997
 for directory in expected['directories']:
  r,s=c.stat(directory);check(r==0 and s.type==2,('directory',directory,r))
  found=set()
  for index in range(128):
   name=C.create_string_buffer(512);st=Stat();end=I()
   r=op(c.fs,'list',I,C.c_char_p,Q,P,Z,C.POINTER(Stat),C.POINTER(I))(c.fs.opaque,utf(directory),index,name,len(name),C.byref(st),C.byref(end));check(r==0,('list',directory,index,r))
   if end.value:break
   decoded=name.value.decode('utf-8');check(decoded not in found,'no duplicates');found.add(decoded)
  else:raise AssertionError('list never ends')
  children={Path(p).name for p in [*expected['directories'],*expected['files'],*expected['symlinks']] if p!='/' and str(Path(p).parent).replace('\\','/')==directory}
  check(found==children,('directory contents',directory,found,children))
 for path,info in expected['files'].items():c.contents(path,info)
 for path,target in expected['symlinks'].items():
  if path not in expected.get('symlinkSpecial',{}):c.contents(path,expected['files']['/hello.txt'])
 if 'symlinkSpecial'in expected:
  c.contents('/root-link'*20+'/hello.txt',expected['files']['/hello.txt'])
  for path,kind in expected['symlinkSpecial'].items():
   if kind=='cycle':r,_=c.stat(path);check(r==-40,'true or growing symlink cycle returns ELOOP')
 r,_=c.stat('/hello.txt/illegal');check(r==-20,'parent must be directory')
 r,_=c.stat('/missing');check(r==-2,'missing')
 for fn in ['mkdir','unlink']:check(op(c.fs,fn,I,C.c_char_p)(c.fs.opaque,b'/hello.txt')==-30,fn)
 check(op(c.fs,'rename',I,C.c_char_p,C.c_char_p)(c.fs.opaque,b'/hello.txt',b'/renamed')==-30,'rename readonly')
 c.finish()
 # Probe allocation failure and checksum damage without leaving a mounted vol.
 f=Case(data);f.fail=True;check(f.mount()==-12 and not f.memory,'allocation failure')
 corrupt=Case(data);corrupt.data[1024+4]^=1
 if int.from_bytes(data[1032:1036],'little')&1:check(corrupt.mount()==-5 and not corrupt.memory,'bad super checksum')
 reports.append({'image':image.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'checks':count-before,'strictReadonly':True})
result={'passed':True,'checks':count,'images':reports,'scope':'Real official mkfs.erofs images, production adapter+upstream reader+LZ4, host byte-device callbacks, no USB/device writes'}
(OUT/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
