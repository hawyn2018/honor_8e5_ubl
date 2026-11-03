"""Stock Fastboot + actual native-USB C adapter, synthetic localhost provider.

Only framing/observer lifecycle is tested. No phone or physical USB is used.
"""
from pathlib import Path
import argparse
import ctypes as C
import hashlib
import json
import socket
import struct
import subprocess
import threading

HERE=Path(__file__).resolve().parent
SOURCE=HERE.parents[2]
WORKSPACE=SOURCE.parents[2]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--clang',default='clang')
    p.add_argument('--fastboot',type=Path,default=WORKSPACE/'platform-tools/fastboot.exe')
    p.add_argument('--output',type=Path,default=WORKSPACE/'analysis/kbl-services-20261030/storage/native-usb-tcp')
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    report={'scope':'SYNTHETIC localhost TCP with stock Fastboot and actual native USB observer/sender C DLL. USB controller/DMA/events/native decoded consumer are model fixtures. No device or firmware access.',
            'attempts':1,'passed':False,'errors':[],'commands':[],'packets':[]}
    def progress():(a.output/'progress.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    sources=[HERE/'test_native_usb_dll.c',HERE/'test_native_usb.c',HERE.parent/'kbl_usb_native.c',HERE.parent/'kbl_usb.c']
    report['source_sha256']={str(s.relative_to(SOURCE)):hashlib.sha256(s.read_bytes()).hexdigest()for s in sources}
    dll=a.output/'native-usb-fixture.dll'
    build=[a.clang,'-std=c11','-O2','-Wall','-Wextra','-Werror','-shared','-Wl,--export-all-symbols',str(sources[0]),str(sources[-1]),'-o',str(dll)]
    subprocess.run(build,check=True,capture_output=True);report['build']=build
    lib=C.CDLL(str(dll));lib.fixture_start.restype=C.c_int
    SINK=C.CFUNCTYPE(C.c_int,C.c_void_p,C.c_size_t)
    lib.fixture_set_sink.argtypes=[SINK];lib.fixture_response.argtypes=[C.c_int,C.c_char_p]
    lib.fixture_fetch.argtypes=[C.c_void_p,C.c_size_t];lib.fixture_stop.argtypes=[C.POINTER(C.c_uint64)]
    assert lib.fixture_start()==0
    state={'socket':None}
    @SINK
    def sink(data,n):
        try:
            value=C.string_at(data,n);state['socket'].sendall(struct.pack('>Q',n)+value)
            report['packets'].append({'bytes':n,'prefix':value[:12].hex()})
            progress()
            return 0
        except Exception as e:report['errors'].append('sink:'+repr(e));return -1
    lib.fixture_set_sink(sink)
    payload=bytes((i*17)&255 for i in range(65536+257))
    report['expected_bytes']=len(payload);report['expected_sha256']=hashlib.sha256(payload).hexdigest()
    def exact(sock,n):
        data=bytearray()
        while len(data)<n:
            chunk=sock.recv(n-len(data))
            if not chunk:raise EOFError()
            data+=chunk
        return bytes(data)
    listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(2);listener.settimeout(15)
    port=listener.getsockname()[1]
    def server():
        try:
            for session in range(2):
                conn,peer=listener.accept();assert peer[0]=='127.0.0.1';conn.settimeout(15);state['socket']=conn
                with conn:
                    assert exact(conn,4)==b'FB01';conn.sendall(b'FB01')
                    while True:
                        try:n=struct.unpack('>Q',exact(conn,8))[0]
                        except EOFError:break
                        assert n<=4096;command=exact(conn,n);report['commands'].append(command.decode());progress()
                        if command.startswith(b'getvar:'):
                            values={b'max-fetch-size':b'0x100000',b'partition-size:kbl.fixture':f'0x{len(payload):x}'.encode(),
                                    b'has-slot:kbl.fixture':b'no',b'is-userspace':b'yes',b'version':b'0.4',b'product':b'native-usb-synthetic'}
                            key=command[7:];assert lib.fixture_response(key in values,values.get(key,b'Unknown fixture variable'))==0
                        elif command.startswith(b'fetch:kbl.fixture'):
                            pieces=command.split(b':');offset=int(pieces[2],16)if len(pieces)>2 else 0
                            count=int(pieces[3],16)if len(pieces)>3 else len(payload)-offset
                            assert offset<=len(payload)and 0<count<=len(payload)-offset
                            buffer=C.create_string_buffer(payload[offset:offset+count]);assert lib.fixture_fetch(buffer,count)==0
                        else:assert lib.fixture_response(0,b'Unknown fixture command')==0
        except Exception as e:report['errors'].append('server:'+repr(e));progress()
        finally:listener.close()
    thread=threading.Thread(target=server,daemon=True);thread.start()
    output=a.output/'fetched.bin';client_results=[]
    for command in [['fetch','kbl.fixture',str(output)],['getvar','product']]:
        invocation=[str(a.fastboot),'-s',f'tcp:127.0.0.1:{port}',*command]
        try:result=subprocess.run(invocation,capture_output=True,timeout=25)
        except subprocess.TimeoutExpired as e:
            report['errors'].append('client timeout:'+repr(e));progress();break
        client_results.append({'command':invocation,'exit':result.returncode,'stdout':result.stdout.decode(errors='replace'),'stderr':result.stderr.decode(errors='replace')})
    thread.join(17);stats=(C.c_uint64*6)();stopped=lib.fixture_stop(stats)
    report['clients']=client_results;report['server_thread_ended']=not thread.is_alive();report['fixture_stop']=stopped
    report['fixture_stats']=dict(zip(['allocations','frees','sends','rx_rearms','enters','leaves'],list(stats)))
    report['fetched_bytes']=output.stat().st_size if output.exists()else 0
    report['fetched_sha256']=hashlib.sha256(output.read_bytes()).hexdigest()if output.exists()else None
    report['fastboot_sha256']=hashlib.sha256(a.fastboot.read_bytes()).hexdigest();report['dll_sha256']=hashlib.sha256(dll.read_bytes()).hexdigest()
    body_packets=[x['bytes']for x in report['packets']if x['bytes']>64]
    report['body_packet_lengths']=body_packets
    report['passed']=all(r['exit']==0 for r in client_results)and report['fetched_sha256']==report['expected_sha256']and body_packets==[65536,257]and stats[0]==stats[1]==3 and stats[4]==stats[5]==1 and stopped==0 and not report['errors']and not thread.is_alive()
    (a.output/'validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:report[k]for k in ['passed','expected_bytes','fetched_bytes','expected_sha256','fetched_sha256','body_packet_lengths','errors']},indent=2))
    return 0 if report['passed']else 1
if __name__=='__main__':raise SystemExit(main())
