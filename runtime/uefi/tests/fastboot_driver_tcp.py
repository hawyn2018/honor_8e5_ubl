"""Stock Fastboot/PC frontend <-> actual ARM64 driver over explicit FakeEFI/TCP.
No native parent dispatcher, physical USB, storage or device is accessed.
"""
from pathlib import Path
import argparse,hashlib,importlib.util,io,json,socket,struct,sys,tempfile,threading
HERE=Path(__file__).resolve().parent
SOURCE=HERE.parents[2]
WORKSPACE=SOURCE.parents[2]
sys.path.insert(0,str(HERE));sys.path.insert(0,str(SOURCE/'tools/kbl_console'))
import test_driver_unicorn as model
from kbl_console import NativeFastboot,Console

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--driver',type=Path,default=SOURCE/'build/kbl-driver/kbl_driver.efi')
    p.add_argument('--program',type=Path,default=SOURCE/'build/kbl-driver/demo/program.kbl')
    p.add_argument('--fastboot',type=Path,default=WORKSPACE/'platform-tools/fastboot.exe')
    p.add_argument('--output',type=Path,default=WORKSPACE/'analysis/kbl-services-20261030/storage/fastboot-driver-tcp')
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
    report={'scope':__doc__,'passed':False,'checks':{},'errors':[],'commands':[],
        'driver_sha256':sha(a.driver),'program_sha256':sha(a.program),'fastboot_sha256':sha(a.fastboot),
        'frontend_sha256':sha(SOURCE/'tools/kbl_console/kbl_console.py')}
    state={'socket':None,'staged':b'','connections':0,'fetches':0}
    def exact(s,n):
        data=bytearray()
        while len(data)<n:
            part=s.recv(n-len(data))
            if not part:raise EOFError
            data.extend(part)
        return bytes(data)
    def packet(b):state['socket'].sendall(struct.pack('>Q',len(b))+b)
    class WireFixture(model.Fixture):
        def hook(self,cpu,address,size,user):
            if address==model.CB['data']:
                n=cpu.reg_read(model.ar.UC_ARM64_REG_X1);packet(b'DATA'+f'{n:08x}'.encode());self.calls['data']+=1;self.ret();return
            if address==model.CB['write']:
                q=cpu.reg_read(model.ar.UC_ARM64_REG_X1);n=min(cpu.reg_read(model.ar.UC_ARM64_REG_X2),37)
                packet(bytes(cpu.mem_read(q,n)));self.write64(cpu.reg_read(model.ar.UC_ARM64_REG_X3),n)
                self.calls['write']+=1;self.ret();return
            return super().hook(cpu,address,size,user)
    f=WireFixture(a.driver,0x10000000);f.boot();stop=threading.Event()
    listener=socket.socket(socket.AF_INET,socket.SOCK_STREAM);listener.bind(('127.0.0.1',0));listener.listen(8);listener.settimeout(.25)
    port=listener.getsockname()[1]
    operations={b'kblstart':1,b'kblin':2,b'kbloff':3,b'kblstatus':4,b'kblcmd':5,b'kblput':6,b'kblpoll':7,b'kblstore':8,b'kblreadopen':9,b'kblreadclose':10}
    def response(r):
        assert len(r['final'])==1,r
        okay,text=r['final'][0];packet((b'OKAY'if okay else b'FAIL')+text.encode('ascii'))
    def server():
        try:
            while not stop.is_set():
                try:c,peer=listener.accept()
                except socket.timeout:continue
                state['connections']+=1;state['socket']=c;c.settimeout(15)
                with c:
                    assert peer[0]=='127.0.0.1'and exact(c,4)==b'FB01';c.sendall(b'FB01')
                    while not stop.is_set():
                        try:n=struct.unpack('>Q',exact(c,8))[0]
                        except EOFError:break
                        assert n<=4096;cmd=exact(c,n);report['commands'].append(cmd.decode('ascii'))
                        if cmd.startswith(b'oem '):
                            name,_,arg=cmd[4:].partition(b' ')
                            if name in operations:response(f.request(operations[name],arg,state['staged']if operations[name]in[8,9]else b'',pages=False))
                            else:packet(b'FAILUnknown fixture OEM command')
                        elif cmd.startswith(b'getvar:'):
                            key=cmd[7:]
                            if key==b'kbl-reply-header':response(f.request(15,pages=False))
                            elif key.startswith(b'kbl-reply:'):response(f.request(16,key[10:],pages=False))
                            elif key==b'max-fetch-size':response(f.request(12,pages=False))
                            elif key.startswith(b'partition-size:kbl.'):response(f.request(13,key[15:],pages=False))
                            elif key.startswith(b'has-slot:kbl.'):response(f.request(14,key[9:],pages=False))
                            else:packet(b'OKAY'+{b'version':b'0.4',b'is-userspace':b'yes',b'max-download-size':b'0x01000000',b'current-slot':b'a'}.get(key,b'no'))
                        elif cmd.startswith(b'download:'):
                            length=int(cmd[9:],16);assert length<0xe00000;packet(b'DATA'+f'{length:08x}'.encode());data=bytearray()
                            while len(data)<length:
                                n=struct.unpack('>Q',exact(c,8))[0];assert n<=length-len(data);data.extend(exact(c,n))
                            state['staged']=bytes(data);packet(b'OKAY')
                        elif cmd.startswith(b'fetch:'):
                            state['fetches']+=1;response(f.request(11,cmd[6:],pages=False))
                        else:packet(b'FAILUnsupported explicit fixture command')
        except Exception as e:
            if not stop.is_set():report['errors'].append(repr(e))
    thread=threading.Thread(target=server,daemon=True);thread.start()
    def require(k,c):report['checks'][k]=bool(c);assert c,k
    def console(transport,*lines):
        it=iter(lines);out=io.StringIO();err=io.StringIO();prompts=[]
        def reader(prompt):
            prompts.append(prompt)
            try:v=next(it)
            except StopIteration:raise EOFError
            if isinstance(v,BaseException):raise v
            return v
        x=Console(transport,reader=reader,output=out,error=err,poll_interval=0,sleeper=lambda _:None)
        return x,out,err,prompts
    try:
        t=NativeFastboot(a.fastboot,['-s',f'tcp:127.0.0.1:{port}'],timeout=15)
        c,out,err,prompts=console(t,'exit');require('start_and_exit_retains',c.attach('kblstart')==0 and len(f.allocations)>0 and prompts==['# '])
        identity=c.session_id;count=len(f.allocations)
        c,_,_,_=console(t,KeyboardInterrupt());require('ctrl_c_and_reattach_same_environment',c.attach('kblin')==130 and c.session_id==identity and len(f.allocations)==count)
        line='echo '+('中文变量'*8)
        c,out,_,_=console(t,line,'exit');require('real_native_utf8_multipart',c.attach('kblin')==0 and out.getvalue()==line[5:]+'\n')
        with tempfile.TemporaryDirectory(prefix='model-',dir=a.output)as folder:
            work=Path(folder).resolve();assert work.is_relative_to(a.output.resolve())
            target=work/'program-readback.kbl'
            uploaded=t.store_file(a.program,'/hello');require('real_store_digest',uploaded.integer('size')==a.program.stat().st_size)
            require('real_snapshot_binary_readback',t.fetch_kbl_file('/hello',target).read_bytes()==a.program.read_bytes())
            c,out,_,_=console(t,'./hello test-argument','exit');require('actual_sdk_program_yield_output',c.attach('kblin')==0 and 'CurrentEL=1\n'in out.getvalue()and'KBL demo after yield\n'in out.getvalue())
            require('elf_pages_released_after_program',not f.pages)
            before=dict(f.calls);gv=t._capture(['getvar','current-slot']);require('ordinary_getvar_zero_driver_calls',gv.returncode==0 and f.calls==before)
            stopped=t.request('kbloff');require('only_explicit_off_frees_environment',stopped.fields.get('status')=='stopped'and not f.allocations)
        require('control_uses_deferred_pages_not_blind_info',f.calls['info']==0 and any(x.startswith('getvar:kbl-reply:')for x in report['commands']))
        require('one_real_binary_fetch',state['fetches']>=1)
        require('source_artifacts_unchanged',report['driver_sha256']==sha(a.driver)and report['program_sha256']==sha(a.program))
        report['passed']=not report['errors']and all(report['checks'].values())
    except Exception as e:report['errors'].append(repr(e))
    finally:
        stop.set();thread.join(2);listener.close();report['connections']=state['connections'];report['provider_calls']=f.calls
        report['live_allocations']=len(f.allocations)
        (a.output/'validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:report[k]for k in ['passed','checks','errors','connections','live_allocations']},indent=2))
    return 0 if report['passed']else 1
if __name__=='__main__':raise SystemExit(main())
