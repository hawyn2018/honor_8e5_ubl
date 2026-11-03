"""Execute the actual ARM64 PIC parent module with modeled UEFI/native callbacks.
This is not device boot/USB validation. No EFI protocol table is fabricated for apps.
"""
from pathlib import Path
import struct,json,hashlib,importlib.util
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import *
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('build',HERE/'build_parent_shim.py')
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b);b.main()
CODE=0x100000;BASE=0x400000;CFG=BASE+0x1000;STATE=BASE+0x2000;GLOBALS=BASE+0x3000
SYS=BASE+0x4000;BOOT=BASE+0x5000;PROTO=0x800000;CALL=0x900000;STOP=CALL+0xf000
def p64(x):return struct.pack('<Q',x)
class Model:
 def __init__(self,usb=False):
  self.u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
  for addr,size in [(CODE,0x10000),(BASE,0x10000),(PROTO,0x10000),(CALL,0x10000),(0xa00000,0x10000)]:self.u.mem_map(addr,size)
  self.u.mem_write(CODE,(b.OUT/'parent.bin').read_bytes())
  self.calls=[];self.answers=[];self.requests=[];self.load_error=0;self.load_handle=True;self.start_error=0;self.unload_error=0;self.protocol_error=0
  self.bind_error=0;self.binary_error=False;self.native_binary_final=False;self.generic=[];self.service_start_error=False
  fields=[0x3000,0x3008,0x3010,0x3018,CALL-BASE,CALL+4-BASE,0x7000,512,0x2000,0,0x8000 if usb else 0,CALL+0x300-BASE if usb else 0,CALL+0x304-BASE if usb else 0]
  self.u.mem_write(CFG,struct.pack('<QII13Q',0x31544e524150424b,1,120,*fields))
  self.u.mem_write(GLOBALS,struct.pack('<QQQQ',0x600001,SYS,BOOT,0x600002))
  self.u.mem_write(SYS+0x60,p64(BOOT))
  for off,fn in [(0xc8,CALL+0x100),(0xd0,CALL+0x104),(0x98,CALL+0x108),(0xe0,CALL+0x10c)]:self.u.mem_write(BOOT+off,p64(fn))
  self.u.mem_write(PROTO,struct.pack('<QIIQQQ',0x31454649534c424b,1,40 if usb else 32,CALL+0x200,CALL+0x204,CALL+0x208 if usb else 0))
  if usb:self.u.mem_write(BASE+0x8000,struct.pack('<II5QII4Q',1,88,0,0x9000,0x9010,0x9020,0x9030,0,0,0x9040,0x9050,0x9060,0x9070))
  self.u.mem_write(CALL+0x800,b'accepted\0')
  self.u.hook_add(UC_HOOK_CODE,self.hook)
 def reg(self,n):return self.u.reg_read(UC_ARM64_REG_X0+n)
 def cstr(self,p):
  if not p:return b''
  a=bytearray()
  for i in range(4097):
   v=self.u.mem_read(p+i,1)[0]
   if not v:return bytes(a)
   a.append(v)
  raise AssertionError('unbounded string')
 def ret(self,value=0):self.u.reg_write(UC_ARM64_REG_X0,value);self.u.reg_write(UC_ARM64_REG_PC,self.u.reg_read(UC_ARM64_REG_LR))
 def hook(self,u,address,size,opaque):
  if address==STOP:u.emu_stop();return
  if address in [CALL,CALL+4]:self.answers.append((address==CALL,self.cstr(self.reg(0))));self.ret();return
  if address==CALL+0x100:
   self.calls.append('LoadImage');assert self.reg(0)==0 and self.reg(1)==0x600001 and self.reg(2)==0
   assert self.reg(3)==BASE+0x7000 and self.reg(4)==512
   if self.load_handle:u.mem_write(self.reg(5),p64(0x600003))
   self.ret(self.load_error);return
  if address==CALL+0x104:self.calls.append('StartImage');assert self.reg(0)==0x600003;self.ret(self.start_error);return
  if address==CALL+0x108:
   self.calls.append('HandleProtocol');assert self.reg(0)==0x600003
   assert bytes(u.mem_read(self.reg(1),16))==bytes.fromhex('a816937f315c524badc328d266049dc1')
   u.mem_write(self.reg(2),p64(PROTO));self.ret(self.protocol_error);return
  if address==CALL+0x10c:self.calls.append('UnloadImage');self.ret(self.unload_error);return
  if address==CALL+0x200:
   op=self.reg(1);arg=bytes(u.mem_read(self.reg(2),self.reg(3)))
   self.requests.append((op,arg,self.reg(4),self.reg(5)))
   link=self.reg(6);reply=self.reg(7)
   abi,n,ctx=struct.unpack('<IIQ',u.mem_read(link,16));assert abi==1 and n==72
   info,final,data,write,abort,quiet,flags=struct.unpack('<7Q',u.mem_read(link+16,56))
   assert not data and not write and not abort and quiet and flags==1
   if op==11 and self.binary_error:
    u.mem_write(reply+12,struct.pack('<I',1));self.ret((-2)&((1<<64)-1));return
   if op==11 and self.native_binary_final:
    u.mem_write(reply+8,struct.pack('<I',1));self.answers.append((True,b'native-binary-final'));self.ret();return
   u.mem_write(reply+8,struct.pack('<I',1))
   u.reg_write(UC_ARM64_REG_X0,ctx);u.reg_write(UC_ARM64_REG_X1,0 if op==1 and self.service_start_error else 1);u.reg_write(UC_ARM64_REG_X2,CALL+0x800)
   u.reg_write(UC_ARM64_REG_PC,final);return
  if address==CALL+0x204:self.ret(1)
  if address==CALL+0x208:
   self.calls.append('bind_usb');assert self.reg(0)==PROTO
   fields=struct.unpack('<II5QII4Q',u.mem_read(self.reg(1),88))
   assert fields==(1,88,BASE,0x9000,0x9010,0x9020,0x9030,0,0,0x9040,0x9050,0x9060,0x9070)
   assert struct.unpack('<Q',u.mem_read(BASE+0x8000+8,8))[0]==0 # immutable template not modified
   self.ret(self.bind_error);return
  if address in [CALL+0x300,CALL+0x304]:
   text=self.cstr(self.reg(0));self.generic.append(text);self.answers.append((True,b'native:'+text));self.ret();return
 def invoke(self,op,suffix=b'',download=0x12345678,n=123,no_final=False):
  prefix={12:b'max-fetch-size',13:b'partition-size:',14:b'has-slot:'}.get(op,b'')
  self.u.mem_write(BASE+0x6000,prefix+suffix+b'\0');self.answers=[]
  for r,v in enumerate([CFG,BASE,op,BASE+0x6000+len(prefix),download,n]):self.u.reg_write(UC_ARM64_REG_X0+r,v)
  self.u.reg_write(UC_ARM64_REG_SP,0xa0fff0);self.u.reg_write(UC_ARM64_REG_LR,STOP)
  self.u.emu_start(CODE,STOP,count=100000)
  assert len(self.answers)==(0 if no_final else 1),(op,suffix,self.answers)
  return self.answers[0] if self.answers else None
 def invoke_lazy(self,op,suffix=b''):
  # Model the native prefix wrapper after its command name was registered.
  # An invalid suffix must return before native registration or driver load.
  at=BASE+0xa000
  self.u.mem_write(at,struct.pack('<QII3Q4I',0x31595a414c4c424b,56,1,0x300,0xb000,0xb100,8,16,24,32))
  self.u.mem_write(STATE+32,struct.pack('<II',0x7fffffff,0))
  self.u.mem_write(BASE+0x6000,suffix+b'\0');self.answers=[]
  for r,v in enumerate([CFG,BASE,at,BASE+0x6000,op]):self.u.reg_write(UC_ARM64_REG_X0+r,v)
  self.u.reg_write(UC_ARM64_REG_SP,0xa0fff0);self.u.reg_write(UC_ARM64_REG_LR,STOP)
  offset=json.loads((b.OUT/'manifest.json').read_text())['lazy_start_offset']
  before=bytes(self.u.mem_read(STATE,40));self.u.emu_start(CODE+offset,STOP,count=100000)
  assert bytes(self.u.mem_read(STATE,40))==before
  assert len(self.answers)==1
  return self.answers[0]
 def phase(self):return struct.unpack('<Q',self.u.mem_read(STATE,8))[0]
 def final_boundary(self,payload):
  with (b.OUT/'parent.elf').open('rb')as f:
   e=ELFFile(f);offset=next(s['st_value']for s in e.get_section_by_name('.symtab').iter_symbols()if s.name=='send_final')
  self.u.mem_write(BASE+0xa000,struct.pack('<QQII',CFG,BASE,0,0))
  self.u.mem_write(BASE+0xb000,payload+b'\0');self.answers=[]
  for r,v in enumerate([BASE+0xa000,1,BASE+0xb000]):self.u.reg_write(UC_ARM64_REG_X0+r,v)
  self.u.reg_write(UC_ARM64_REG_SP,0xa0fff0);self.u.reg_write(UC_ARM64_REG_LR,STOP)
  self.u.emu_start(CODE+offset,STOP,count=10000)
  return self.u.reg_read(UC_ARM64_REG_X0),self.answers,struct.unpack('<I',self.u.mem_read(BASE+0xa010,4))[0]
def main():
 cases=[]
 m=Model()
 for suffix in [b'XYZ',b' ',b' extra',b'\n',b'x'*4097]:
  assert m.invoke(1,suffix)[0] is False and not m.calls
 cases.append('strict start prefix/arguments reject without LoadImage')
 for suffix in [b'XYZ',b' ',b' extra',b'\n',b'x'*4097]:
  assert not m.invoke(17,suffix)[0] and not m.calls
 adb=Model(usb=True);assert adb.invoke(17)[0]
 assert adb.calls==['LoadImage','StartImage','HandleProtocol','bind_usb']and adb.requests[-1][0]==17
 assert adb.invoke(17)[0] and adb.calls.count('LoadImage')==1
 assert adb.invoke(1)[0] and adb.calls.count('LoadImage')==1 and adb.requests[-1][0]==1
 cases.append('explicit ADB op validates empty arguments, lazy-loads once and preserves operation 17')
 for op in [18,19,20]:
  display=Model(usb=True)
  for suffix in [b' ',b' extra',b'x',b'\n',b'x'*64]:
   assert not display.invoke(op,suffix)[0] and not display.calls and not display.requests
  assert display.invoke(op)[0]
  assert display.calls==['LoadImage','StartImage','HandleProtocol']
  assert display.requests[-1][0]==op and display.phase()==1
  assert struct.unpack('<II',display.u.mem_read(STATE+24,8))==(0,0)
  assert display.invoke(op)[0] and display.calls.count('LoadImage')==1
  assert display.invoke(1)[0] and display.calls.count('LoadImage')==1 and display.calls[-1]=='bind_usb'
  before=display.u.mem_read(STATE+24,8);binds=display.calls.count('bind_usb')
  assert display.invoke(op)[0] and display.u.mem_read(STATE+24,8)==before
  assert display.calls.count('bind_usb')==binds
 cases.append('display op18/19/20 exact suffix loads service only; no USB bind/native started flag; active runtime flags retained')
 registered=Model(usb=True)
 for op in [1,17,18,19,20]:
  for suffix in [b'x',b' ',b' extra',b'\n']:
   assert not registered.invoke_lazy(op,suffix)[0] and not registered.calls and not registered.requests
 cases.append('registered native-prefix wrappers reject lookalike and extra argument suffixes before registration/load/state mutation')


 for op,suffix in [(2,b''),(3,b''),(4,b''),(5,b' 656c'),(7,b' 1'),(15,b''),(16,b'0:0')]:
  assert not m.invoke(op,suffix)[0] and not m.calls
 cases.append('nonstart commands never lazy-load absent driver')
 assert m.invoke(1)[0] and m.calls==['LoadImage','StartImage','HandleProtocol'] and m.phase()==1
 assert m.invoke(1)[0] and len(m.calls)==3
 for op,suffix in [(2,b''),(3,b''),(4,b''),(5,b' 656c'),(6,b' 1:2'),(7,b' 1'),(9,b' 2f61'),(10,b' kbl.0000000000000001'),(15,b''),(16,b'0:0')]:assert m.invoke(op,suffix)[0]
 assert not m.invoke(10,b'')[0]
 assert len(m.calls)==3
 cases.append('own-handle protocol/cached driver/deferred INFO single native final')
 before=len(m.requests);assert not m.invoke(8,b' 2f61')[0] and len(m.requests)==before
 nonce=0x123456789abcdef0;envelope_arg=f' env1:{nonce:016x}'.encode();long_path=b'/'+b'long_readback_filename_'*2
 envelope=struct.pack('<8sQIIQ32s',b'KBLPUT01',nonce,len(long_path),0,0,hashlib.sha256(b'').digest())+long_path
 m.u.mem_write(BASE+0xc000,envelope)
 assert not m.invoke(9,envelope_arg,BASE+0xc000,len(envelope))[0] and len(m.requests)==before
 m.u.mem_write(CFG+88,p64(1));assert m.invoke(8,b' 2f61',0x12345678,123)[0]
 assert m.requests[-1]==(8,b'2f61',0x12345678,123)
 assert m.invoke(9,envelope_arg,BASE+0xc000,len(envelope))[0] and m.requests[-1]==(9,envelope_arg[1:],BASE+0xc000,len(envelope))
 for wrong in [b' env1',b' env1:1234',b' env1:123456789abcdeff0',b' env1:123456789abcdeffg']:
  assert m.invoke(9,wrong,BASE+0xc000,len(envelope))[0] and m.requests[-1][2:]==(0,0)
 assert m.invoke(9,b' 2f61',BASE+0xc000,len(envelope))[0]and m.requests[-1]==(9,b'2f61',0,0)
 cases.append('STORE proof gate and real env1:16hex READ_OPEN nonce/path download forwarding; malformed/legacy arguments do not borrow download')
 for mode in ['start','protocol']:
  m=Model();m.unload_error=1
  if mode=='start':m.start_error=1
  else:m.protocol_error=1
  assert not m.invoke(1)[0] and m.phase()==2 and m.calls[-1]=='UnloadImage'
  count=len(m.calls);assert not m.invoke(1)[0] and len(m.calls)==count
  assert not m.invoke(3)[0] and m.phase()==2
  m.unload_error=0;assert m.invoke(3)[0] and m.phase()==0
  m.start_error=m.protocol_error=0;assert m.invoke(1)[0]
 cases.append('failed startup retains dirty image; exact off retries cleanup before restart')
 m=Model(usb=True)
 for op,suffix,expected in [(13,b'system',b'partition-size:system'),(14,b'boot',b'has-slot:boot'),(12,b'-other',b'max-fetch-size-other'),(12,b'',b'max-fetch-size'),(11,b'boot',b'boot')]:
  assert m.invoke(op,suffix)==(True,b'native:'+expected) and not m.calls
 cases.append('ordinary getvar and fetch targets forward original native handlers without load')
 assert m.invoke(1)[0] and m.calls==['LoadImage','StartImage','HandleProtocol','bind_usb']
 assert m.invoke(12)[0] and m.invoke(11,b'kbl.0000000000000001:0:1')[0]
 m.native_binary_final=True;assert m.invoke(11,b'kbl.0000000000000001:0:1')==(True,b'native-binary-final')
 m.binary_error=True;m.invoke(11,b'kbl.0000000000000001:0:1',no_final=True)
 m.binary_error=False;assert m.invoke(3)[0] and m.invoke(12)==(True,b'native:max-fetch-size')
 cases.append('start-only USB bind uses copied facts, gates binary transport, no final injection after DATA error')
 m=Model(usb=True);m.bind_error=1
 assert m.invoke(1)[0] and m.invoke(12)==(True,b'native:max-fetch-size') and not m.invoke(11,b'kbl.1:0:1')[0]
 cases.append('USB bind failure retains control and native max query without advertising private fetch')
 m=Model(usb=True);m.u.mem_write(CFG+112,p64(0))
 assert m.invoke(11,b'efisp:0:200000')==(False,b'unknown fetch target') and not m.calls and not m.requests
 assert m.invoke(1)[0]
 assert m.invoke(11,b'efisp:0:200000')[0] and m.requests[-1]==(11,b'efisp:0:200000',0,0)
 m.service_start_error=True;assert not m.invoke(1)[0]
 before=len(m.requests);assert not m.invoke(11,b'efisp:0:200000')[0]and len(m.requests)==before
 m.service_start_error=False;assert m.invoke(1)[0];assert m.invoke(3)[0]
 before=len(m.requests);assert not m.invoke(11,b'efisp:0:200000')[0]and len(m.requests)==before
 cases.append('absent native partition FETCH is forwarded only after successful START+USB; failed START and OFF disable it; offset:length unchanged')
 m=Model()
 for n in [0,56,58,59]:
  rc,answers,sent=m.final_boundary(b'x'*n);assert rc==0 and sent==1 and answers==[(True,b'x'*n)]
 for n in [60,61,128]:
  rc,answers,sent=m.final_boundary(b'x'*n);assert rc!=0 and sent==0 and not answers
 cases.append('native 64-byte formatter boundary accepts <=59 payload and rejects >=60 before final')
 for handle in [False,True]:
  m=Model();m.load_error=0x800000000000001a;m.load_handle=handle;m.unload_error=1
  assert not m.invoke(1)[0] and m.phase()==(2 if handle else 0)
  assert m.calls==(['LoadImage','UnloadImage']if handle else ['LoadImage'])
  if handle:
   count=len(m.calls);assert not m.invoke(1)[0] and len(m.calls)==count
   m.unload_error=0;assert m.invoke(3)[0] and m.phase()==0
 cases.append('LoadImage error with returned image handle is accounted and cleanup is retryable')
 result={'scope':'actual ARM64 parent PIC instructions; modeled native/EFI callbacks; no device or USB execution',
 'cases':cases,'caseGroups':len(cases),'sha256':hashlib.sha256((b.OUT/'parent.bin').read_bytes()).hexdigest()}
 (b.OUT/'validation.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
if __name__=='__main__':main()
