"""Production ARM64 driver/native multi-session ADB, explicit USB/EFI models."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,struct
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('adb_multi_driver_base',HERE/'test_driver_adb_unicorn.py');t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)

def stdout(f,host):
 data=f.channel_output(host);out=b''
 while data:
  assert len(data)>=5;kind=data[0];n=struct.unpack('<I',data[1:5])[0];assert len(data)>=5+n
  if kind in (1,2):out+=data[5:5+n]
  data=data[5+n:]
 return out

def stdin(f,host,data):f.sync_send(host,b'\0'+struct.pack('<I',len(data))+data);f.settle(20)
def req(kind,path):data=path.encode();return kind+struct.pack('<I',len(data))+data

def run(driver,address):
 f=t.Fixture(driver,address);f.begin();cases=[]
 for host,path in [(91,'/left'),(92,'/right')]:out,rc=f.shell(host,'mkdir '+path);assert rc==0,(out,rc)
 f.open(101,'shell,v2,pty:');f.open(102,'shell,v2,pty:');f.settle()
 stdin(f,101,b'cd /left\n');stdin(f,102,b'cd /right\n');stdin(f,101,b'pwd\n');stdin(f,102,b'pwd\n')
 f.until(lambda:b'/left\n'in stdout(f,101)and b'/right\n'in stdout(f,102));assert b'/right\n'not in stdout(f,101)and b'/left\n'not in stdout(f,102);cases.append('actual-shell-cwd-output-session-isolation')
 f.open(201,'sync:');path=b'/not-yet-present';f.sync_send(201,b'LST2'+struct.pack('<I',len(path)));f.settle()
 out,rc=f.shell(93,'echo partial-path-free');assert rc==0 and out==b'partial-path-free\n';f.sync_send(201,path);f.until(lambda:f.channel_output(201).startswith(b'LST2'));f.close(201);cases.append('partial-sync-header-and-idle-shells-do-not-hold-lease')
 f.open(202,'sync:');path=b'/multi-data,33188';f.sync_send(202,b'SEND'+struct.pack('<I',len(path))+path+b'DATA'+struct.pack('<I',5)+b'first');f.settle(40)
 stdin(f,101,b'echo first-shell-queue\n');stdin(f,102,b'echo second-shell-queue\n');f.open(203,'sync:');f.sync_send(203,req(b'LST2','/multi-data'));f.settle(30)
 assert b'first-shell-queue\n'not in stdout(f,101)and b'second-shell-queue\n'not in stdout(f,102)and not f.channel_output(203)
 f.sync_send(202,b'DATA'+struct.pack('<I',6)+b'second'+b'DONE'+bytes(4))
 f.until(lambda:b'first-shell-queue\n'in stdout(f,101)and b'second-shell-queue\n'in stdout(f,102)and f.channel_output(203).startswith(b'LST2'));cases.append('actual-FIFO-shells-and-sync-wait-for-single-SEND-transaction')
 previous=len(f.channel_output(203));f.sync_send(203,req(b'RECV','/multi-data'));f.until(lambda:b'DONE\0\0\0\0'in f.channel_output(203)[previous:]);assert f.channel_output(203)[previous:]==b'DATA'+struct.pack('<I',11)+b'firstsecond'+b'DONE'+bytes(4)
 out,rc=f.shell(94,'echo idle-sync-free');assert rc==0 and out==b'idle-sync-free\n';cases.append('two-idle-sync-channels-release-owner-after-transfer')
 path=b'/cancel-owner,33188';f.sync_send(202,b'SEND'+struct.pack('<I',len(path))+path+b'DATA'+struct.pack('<I',6)+b'before');f.settle(40)
 f.open(204,'sync:');f.sync_send(204,req(b'LST2','/multi-data'));f.settle(20);f.close(204)
 stdin(f,101,b'echo canceled-native\n');stdin(f,101,b'echo canceled-buffer\n');stdin(f,102,b'echo survives-cancel\n');stdin(f,101,b'\x03');f.until(lambda:f.closed(101))
 f.sync_send(202,b'DATA'+struct.pack('<I',5)+b'after'+b'DONE'+bytes(4));f.until(lambda:b'survives-cancel\n'in stdout(f,102));assert b'canceled-native\n'not in stdout(f,101)and b'canceled-buffer\n'not in stdout(f,101)
 assert f.query(1)and not f.query(6);cases.append('queued-sync-close-and-CtrlC-cancel-only-their-owner')
 # Three ordinary channels remain:102,202,203. Fill five more; OFF is reserved.
 for host in range(300,305):f.open(host,'shell,v2,pty:')
 f.auto_ack=False;f.open(399,'shell,v2,raw:kbl off');f.until(lambda:f.query(21)==1 and f.query(1)==0)
 assert f.phase()=='off-parked'and f.extra_calls['start_ex']==1 and not f.extra_calls['stop']and not f.extra_calls['stall'];cases.append('eight-services-reserved-OFF-native-release-without-peer-ACK')
 return {'passed':True,'loadBase':hex(address),'cases':cases,'state':f.state(),'scope':'Actual ARM64 native sessions/cwd/scheduler/VFS/ADB and driver. EFI services/controller/DMA/span checks remain explicit models; no physical phone.'}

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=t.ROOT/'analysis/kbl-multisession-20261030/adb-driver-multi');a=parser.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
 driver,commands=t.build(out/'build');result=run(driver,0x18000000);result['testDriverSha256']=hashlib.sha256(driver.read_bytes()).hexdigest();result['commands']=[[str(x)for x in c]for c in commands]
 result['sourceHashes']={str(p.relative_to(t.SOURCE)):hashlib.sha256(p.read_bytes()).hexdigest()for p in [t.RUNTIME/'adb/kbl_adb.c',t.RUNTIME/'adb/kbl_adb.h',t.RUNTIME/'native/kbl_native.c',t.RUNTIME/'native/kbl_sessions.inc',t.UEFI/'kbl_adb_driver.inc',Path(__file__)]if p.exists()}
 (out/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'passed':True,'cases':len(result['cases']),'report':str(out/'validation.json')}))
if __name__=='__main__':main()
