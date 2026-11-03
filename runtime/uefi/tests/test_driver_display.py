"""Production ARM64 driver and shell against mock EFI display callbacks.
No device/USB API, physical shutdown or storage writes are performed.
"""
from pathlib import Path
import argparse,importlib.util,json,hashlib
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('display_driver_base',HERE/'test_driver_unicorn.py')
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
GUID=bytes.fromhex('1d0252f393953244bf0467b9f3b76008')
PROTO=b.DATA+0xd000;SET=b.CALLBACK+0xd00;GET=b.CALLBACK+0xd40
class Fixture(b.Fixture):
 def __init__(self,path,base):
  self.display_calls=[];self.hw=2;self.present=True;self.get_error=0;self.set_error=0;self.lie=False
  super().__init__(path,base)
  self.write32(PROTO,0x10000);self.write64(PROTO+8,SET);self.write64(PROTO+16,GET)
 def hook(self,cpu,address,size,user):
  a=[cpu.reg_read(r)for r in b.REGS]
  if address==b.CB['locate']and bytes(cpu.mem_read(a[0],16))==GUID:
   self.display_calls.append(('locate',));self.write64(a[2],PROTO if self.present else 0);self.ret(0 if self.present else b.ERR(3));return
  if address==SET:
   assert a[0]==PROTO and a[1]in [1,2];self.display_calls.append(('set',a[1]))
   if not self.set_error and not self.lie:self.hw=a[1]
   self.ret(self.set_error);return
  if address==GET:
   assert a[0]==PROTO;self.display_calls.append(('get',self.hw));self.write32(a[1],self.hw);self.ret(self.get_error);return
  super().hook(cpu,address,size,user)
def fields(result):
 return dict(line[5:].split('=',1)for line in result['lines']if line.startswith('KBL1 display_'))
def run(driver,address):
 f=Fixture(driver,address);f.boot();assert not f.display_calls and not f.allocations
 status=f.request(20);assert status['final']==[(1,'display=on')]
 assert fields(status)['display_state']=='on'and not f.allocations and status['reply'][1]==1
 off=f.request(18);assert off['final']==[(1,'display=off')]
 assert fields(off)['display_get_status']=='0000000000000000'and fields(off)['display_state']=='off'
 assert fields(off)['display_set_attempted']=='1'and fields(off)['display_set_status']=='0000000000000000'
 assert f.hw==1 and not f.allocations and off['reply'][1]==1
 before=f.display_calls.count(('set',1));assert f.request(18)['final'][0][0]and f.display_calls.count(('set',1))==before
 status=f.request(20);assert fields(status)['display_state']=='off'and fields(status)['display_set_attempted']=='0'
 assert f.request(19)['final']==[(1,'display=on')]and f.hw==2 and not f.allocations
 before=len(f.display_calls);assert not f.request(18,'extra')['final'][0][0]and len(f.display_calls)==before
 assert not f.request(21)['final'][0][0]and len(f.display_calls)==before
 f.present=False;failure=f.request(18);assert not failure['final'][0][0]and fields(failure)['display_provider']=='unsupported'
 f.present=True;f.lie=True;failure=f.request(18);assert not failure['final'][0][0]and fields(failure)['display_state']=='on';f.lie=False
 assert f.request(1)['final'][0][0]and f.allocations
 out,rc,_=f.command('screenoff');assert not rc and b'display_state=off\n'in out and f.hw==1
 out,rc,_=f.command('echo fastboot-shell-alive');assert not rc and out==b'fastboot-shell-alive\n'
 out,rc,_=f.command('screenstatus');assert not rc and b'display_get_status=0000000000000000\n'in out
 out,rc,_=f.command('screenon');assert not rc and b'display_state=on\n'in out and f.hw==2
 out,rc,_=f.command('screenoff extra');assert rc
 assert f.request(4)['final'][0][0]
 assert f.request(3)['final'][0][0]and not f.allocations
 assert not f.calls['info']and not f.calls['abort']and not f.calls['data']and not f.calls['write']
 return {'passed':True,'base':hex(address),'displayCalls':f.display_calls,'beforeStartNoAllocations':True,'screenDoesNotStartAdb':True,'poweroffPhysicallyExecuted':False}
def main():
 p=argparse.ArgumentParser();p.add_argument('--driver',type=Path,default=HERE.parents[2]/'build/kbl-driver/kbl_driver.efi');p.add_argument('--output',type=Path,default=HERE.parents[2]/'build/display-driver-test');a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 report={'passed':True,'driverSha256':hashlib.sha256(a.driver.read_bytes()).hexdigest(),'scope':'actual ARM64 driver/native, explicit fake EFI display + link','results':[run(a.driver,base)for base in [0x10000000,0x18000000]]}
 (a.output/'result.json').write_text(json.dumps(report,indent=2));print(json.dumps({'passed':True,'cases':len(report['results'])}))
if __name__=='__main__':main()
