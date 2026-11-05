"""Execute rebuilt and published ARM64 shims against native command-list fixtures.

No device, USB, firmware image or production-source mutation. Python only models
native allocator/EFI callbacks; list reconciliation runs the actual ARM64 code.
"""
from pathlib import Path
import base64, hashlib, importlib.util, json, re, struct
from unicorn import UC_HOOK_MEM_READ
from unicorn.arm64_const import *

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('existing_tests',HERE/'test_parent_shim.py')
t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)
NAMES=[b'oem kblstart',b'oem kbladb',b'oem screenoff',b'oem screenon',
 b'oem screenstatus',b'oem kblin',b'oem kbloff',b'oem kblstop',
 b'oem kblstatus',b'oem kblcmd',b'oem kblput',b'oem kblpoll',b'oem kblstore',
 b'oem kblreadopen',b'oem kblreadclose',b'getvar:kbl-reply-header',
 b'getvar:kbl-reply:',b'fetch:',b'getvar:max-fetch-size',
 b'getvar:partition-size:',b'getvar:has-slot:']
LZY=t.BASE+0xa000;HEAD=t.BASE+0xb000;ENTRIES=t.BASE+0xb100
ALL=(1<<len(NAMES))-1
MANIFEST=json.loads((t.b.OUT/'manifest.json').read_text())

class RegistrationModel(t.Model):
 def __init__(self,old_bytes=None,old_lazy=None):
  self.register_attempts=[];self.allocations=[];self.fail_at=None;self.next_node=t.BASE+0xc000
  self.old_lazy=old_lazy
  super().__init__(usb=True)
  if old_bytes is not None:self.u.mem_write(t.CODE,old_bytes)
  self.u.mem_write(LZY,struct.pack('<QII3Q4I',0x31595a414c4c424b,56,len(NAMES),
    t.CALL+0x400-t.BASE,HEAD-t.BASE,ENTRIES-t.BASE,0,8,16,24))
  p=t.BASE+0xe000;self.expected=[]
  for i,name in enumerate(NAMES):
   handler=t.CALL+0x500+i*4
   self.u.mem_write(p,name+b'\0')
   self.u.mem_write(ENTRIES+16*i,struct.pack('<QQ',p-t.BASE,handler-t.BASE))
   self.expected.append((p,len(name),handler));p+=len(name)+1
 def head(self):return struct.unpack('<Q',self.u.mem_read(HEAD,8))[0]
 def set_head(self,p):self.u.mem_write(HEAD,t.p64(p))
 def add_node(self,name,n,handler,next_node=None):
  at=self.next_node;self.next_node+=32
  assert self.next_node<t.BASE+0xe000
  if next_node is None:next_node=self.head()
  self.u.mem_write(at,struct.pack('<QQIIQ',next_node,name,n,0,handler))
  self.set_head(at);return at
 def foreign(self,text=b'getvar:version',handler=t.CALL+0x600):
  p=t.BASE+0xf000;self.u.mem_write(p,text+b'\0');return self.add_node(p,len(text),handler)
 def hook(self,u,address,size,opaque):
  if address==t.CALL+0x400:
   pair=(self.reg(0),self.reg(1));self.register_attempts.append(pair)
   if self.fail_at!=len(self.register_attempts):
    node=self.add_node(pair[0],len(self.cstr(pair[0])),pair[1]);self.allocations.append(node)
   self.ret();return
  return super().hook(u,address,size,opaque)
 def invoke_lazy(self,op=1,suffix=b''):
  self.u.mem_write(t.BASE+0x6000,suffix+b'\0');self.answers=[]
  for i,v in enumerate([t.CFG,t.BASE,LZY,t.BASE+0x6000,op]):self.u.reg_write(UC_ARM64_REG_X0+i,v)
  self.u.reg_write(UC_ARM64_REG_SP,0xa0fff0);self.u.reg_write(UC_ARM64_REG_LR,t.STOP)
  off=MANIFEST['lazy_start_offset']if self.old_lazy is None else self.old_lazy
  self.u.emu_start(t.CODE+off,t.STOP,count=500000)
  assert len(self.answers)==1,self.answers
  return self.answers[0]
 def mask(self):return struct.unpack('<I',self.u.mem_read(t.STATE+32,4))[0]
 def set_mask(self,n):self.u.mem_write(t.STATE+32,struct.pack('<I',n))
 def nodes(self):
  result=[];p=self.head()
  while p:
   assert p not in [x[0]for x in result]
   nxt,name,n,_,handler=struct.unpack('<QQIIQ',self.u.mem_read(p,32))
   result.append((p,name,n,handler));p=nxt
  return result
 def owned(self):return [n for n in self.nodes()if(n[1],n[2],n[3])in self.expected]
 def remove_owned(self,index):
  victim=self.expected[index];p=self.head();prev=0
  while p:
   nxt,name,n,_,handler=struct.unpack('<QQIIQ',self.u.mem_read(p,32))
   if(name,n,handler)==victim:
    self.u.mem_write(prev if prev else HEAD,t.p64(nxt));return p
   prev=p;p=nxt
  raise AssertionError('missing victim')

def published_module():
 root=next(p for p in HERE.parents if(p/'repairs/hnubl-3.0.1-module-boundary-20261104').exists())
 path=root/'repairs/hnubl-3.0.1-module-boundary-20261104/source/abl-efisp-core/src/main/kotlin/net/hawyn/ablefisp/patch/KblParentModule.kt'
 text=path.read_text();section=text.split('getDecoder().decode(',1)[1]
 data=base64.b64decode(''.join(re.findall(r'"([A-Za-z0-9+/=]+)"',section)))
 sha=re.search(r'SHA256 = "([a-f0-9]+)"',text).group(1)
 assert hashlib.sha256(data).hexdigest()==sha
 return data,int(re.search(r'LAZY_START_OFFSET = (\d+)L',text).group(1)),sha,str(path)

def main():
 cases=[]
 data,offset,sha,path=published_module()
 m=RegistrationModel(data,offset);m.set_mask(ALL);m.foreign()
 assert m.invoke_lazy()[0]and len(m.register_attempts)==0 and len(m.owned())==0
 cases.append({'case':'published ARM64 reproduction: stale full mask + rebuilt native-only list skips all registration',
  'original_sha256':sha,'registration_attempts':0,'reply_header_present':False})

 m=RegistrationModel();m.foreign()
 assert m.invoke_lazy()[0]and len(m.owned())==21 and m.mask()==ALL
 for op in[1,17,18,19,20,1]:assert m.invoke_lazy(op)[0]
 assert len(m.allocations)==21 and len(m.owned())==21 and m.calls.count('LoadImage')==1
 cases.append({'case':'first explicit lazy request registers 21, repeated START/ADB/display requests allocate zero duplicates',
   'initial_allocations':21,'repeated_allocations':0})

 # Model native continue freeing every node then Fastboot init rebuilding only
 # its own list; the private appended parent state intentionally survives.
 m.set_head(0);m.foreign(b'getvar:product');before=len(m.allocations)
 assert m.mask()==ALL and m.invoke_lazy(18)[0]
 assert len(m.allocations)-before==21 and len(m.owned())==21 and m.mask()==ALL
 before=len(m.allocations);assert m.invoke_lazy()[0]and len(m.allocations)==before
 cases.append({'case':'native head rebuilt with original handlers while private mask survives: all missing entries recovered once',
  'recovery_allocations':21,'next_call_allocations':0})

 m.remove_owned(15);before=len(m.allocations)
 assert m.mask()==ALL and m.invoke_lazy()[0]and len(m.allocations)-before==1 and len(m.owned())==21
 cases.append({'case':'one reply-header node removed: exactly one replacement, existing commands retained','allocations':1})

 m=RegistrationModel();m.foreign();m.fail_at=5
 assert not m.invoke_lazy()[0]and len(m.allocations)==4 and m.mask()==15 and not m.requests
 assert struct.unpack('<I',m.u.mem_read(t.STATE+36,4))[0]==0
 m.fail_at=None;assert m.invoke_lazy()[0]
 assert len(m.allocations)==21 and len(m.owned())==21 and len(m.register_attempts)==22
 cases.append({'case':'native allocation attempt 5 fails: 4 valid nodes retained and next request adds remaining 17 only',
  'initial_successes':4,'retry_allocations':17,'total_native_attempts':22})

 m=RegistrationModel();m.foreign(NAMES[0]);p,n,h=m.expected[1];m.add_node(p,n,h+4);m.set_mask(ALL)
 assert m.invoke_lazy()[0]and len(m.owned())==21 and len(m.allocations)==21
 assert len(m.nodes())==23
 cases.append({'case':'foreign equal text and foreign handler using owned name pointer are not counted as owned',
  'foreign_nodes_preserved':2,'owned_nodes':21})

 for kind in['cycle','unaligned']:
  m=RegistrationModel();p=m.foreign()
  if kind=='cycle':m.u.mem_write(p,t.p64(p))
  else:m.set_head(p+1)
  assert not m.invoke_lazy()[0]and not m.allocations and not m.requests
  assert struct.unpack('<I',m.u.mem_read(t.STATE+36,4))[0]==0
 cases.append({'case':'cycle and unaligned head rejected without allocation, load or service dispatch','subcases':2})

 for op in[1,17,18,19,20]:
  m=RegistrationModel();m.set_head(0xdeadbeef);m.set_mask(ALL)
  before=bytes(m.u.mem_read(t.STATE,40))
  for suffix in[b'x',b' ',b' extra',b'\n']:
   assert not m.invoke_lazy(op,suffix)[0]and not m.calls and not m.allocations
  assert bytes(m.u.mem_read(t.STATE,40))==before
 cases.append({'case':'lookalike and argument suffix rejected before live-list read or any state change','subcases':20})

 m=RegistrationModel();m.set_head(0xdeadbeef);reads=[]
 m.u.hook_add(UC_HOOK_MEM_READ,lambda u,a,p,n,v,o:reads.append((p,n)),begin=HEAD,end=HEAD+7)
 for op,arg,expected in[(13,b'system',b'partition-size:system'),(14,b'boot',b'has-slot:boot'),
   (12,b'',b'max-fetch-size'),(11,b'boot',b'boot')]:
  assert m.invoke(op,arg)==(True,b'native:'+expected)
 assert not reads and not m.allocations and not m.calls and not m.requests
 cases.append({'case':'ordinary getvar/fetch perform no native-head reads, registration, service load or dispatch','subcases':4})

 result={'scope':'actual published/fixed ARM64 instructions in Unicorn; modeled native/EFI callbacks; no device execution',
  'published_module_path':path,'published_sha256':sha,'fixed_sha256':MANIFEST['sha256'],
  'case_groups':len(cases),'cases':cases,'all_passed':True}
 (t.b.OUT/'registration-rebuild-validation.json').write_text(json.dumps(result,indent=2))
 print(json.dumps(result,indent=2))

if __name__=='__main__':main()
