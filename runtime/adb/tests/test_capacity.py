"""Old ADB service/command/path thresholds plus one, real portable core only.

Uses fake native shell and RAMFS providers; no device or network transport.
"""
from pathlib import Path
import ctypes as C,hashlib,importlib.util,json,struct
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("capacity_wire_base",HERE/"test_server.py")
t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)
OUT=HERE.parents[5]/"analysis/hnubl-2.6.4-efi-unlimited-20261031/adb-capacity"
OUT.mkdir(parents=True,exist_ok=True)

class Wire(t.Fixture):
    def __init__(self,d):super().__init__(d);self.locals={};self.data={};self.closed=set()
    def pump(self,rounds=80):
        for _ in range(rounds):
            for op,a,b,payload in self.drain():
                if op==b"OKAY":self.locals[b]=a
                elif op==b"WRTE":self.data.setdefault(b,bytearray()).extend(payload);self.feed(t.packet(b"OKAY",b,a))
                elif op==b"CLSE":self.closed.add(b);self.feed(t.packet(b"CLSE",b,a))
        return self
    def send(self,op,a=0,b=0,payload=b""):self.feed(t.packet(op,a,b,payload));return self.pump()
    def open(self,remote,service):return self.send(b"OPEN",remote,0,service+b"\0")
    def stream(self,remote,payload):
        for at in range(0,len(payload),65536):self.send(b"WRTE",remote,self.locals[remote],payload[at:at+65536])
        return self
    def stdout(self,remote):
        raw=bytes(self.data.get(remote,b""));out=b""
        while raw:
            assert len(raw)>=5;kind=raw[0];n=struct.unpack("<I",raw[1:5])[0];assert len(raw)>=5+n
            if kind==1:out+=raw[5:5+n]
            raw=raw[5+n:]
        return out

def fresh(d):assert d.fixture_start(0)==0;f=Wire(d);f.connect();return f

def finish(d):assert d.fixture_disconnect()==0 and d.fixture_finish()==0

def sync(kind,path):return kind+struct.pack("<I",len(path))+path

def main():
    t.build();d=t.lib();cases=[]
    f=fresh(d);command=b"length "+b"x"*4097
    f.open(1,b"shell,v2,raw:"+command);assert f.stdout(1)==str(len(command)).encode()+b"\n";finish(d)
    cases.append("OPEN-and-pending-command-cross-old-1024-and-512")
    f=fresh(d);f.open(2,b"shell,v2,raw:");command=b"length "+b"x"*65537+b"\n"
    f.stream(2,b"\0"+struct.pack("<I",len(command))+command)
    assert str(len(command)-1).encode()+b"\n"in f.stdout(2);finish(d)
    cases.append("interactive-line-crosses-old-512-and-4096-and-packet-boundary")
    f=fresh(d);f.open(3,b"shell,v2,raw:");frame=b"\x05"+struct.pack("<I",1024*1024+1)+b"x"*(1024*1024+1)
    f.stream(3,frame);command=b"echo after-large-frame\n";f.stream(3,b"\0"+struct.pack("<I",len(command))+command)
    assert b"after-large-frame\n"in f.stdout(3);finish(d)
    cases.append("shell-v2-frame-1MiB-plus-one-consumed-across-real-packets")
    f=fresh(d);f.open(4,b"sync:");path=b"/"+b"p"*(2*65536+17);body=b"D"*65537
    f.stream(4,sync(b"SEND",path+b",33188"));f.stream(4,b"DATA"+struct.pack("<I",len(body))+body);f.stream(4,b"DONE"+bytes(4))
    assert bytes(f.data[4]).startswith(b"OKAY"),bytes(f.data[4])[:100]
    before=len(f.data[4]);f.stream(4,sync(b"LST2",path));stat=bytes(f.data[4][before:]);assert stat[:4]==b"LST2"and struct.unpack("<Q",stat[40:48])[0]==len(body)
    before=len(f.data[4]);f.stream(4,sync(b"LIST",b"/"));listing=bytes(f.data[4][before:]);assert listing[:4]==b"DENT"and struct.unpack("<I",listing[16:20])[0]==len(path)-1 and listing[20:20+len(path)-1]==path[1:]
    before=len(f.data[4]);f.stream(4,sync(b"RECV",path));raw=bytes(f.data[4][before:]);received=b""
    while raw:
        kind,n=raw[:4],struct.unpack("<I",raw[4:8])[0];raw=raw[8:]
        if kind==b"DONE":assert n==0 and not raw;break
        assert kind==b"DATA"and len(raw)>=n;received+=raw[:n];raw=raw[n:]
    assert received==body;finish(d)
    cases.append("sync-path-input-name-and-LIST-output-cross-old-1024-130KiB-and-64KiB")
    f=fresh(d)
    for remote in range(10,28):f.open(remote,b"shell,v2,raw:")
    assert not f.closed and len(f.locals)==18
    for remote in range(10,28):
        command=f"echo owner-{remote}\n".encode();f.stream(remote,b"\0"+struct.pack("<I",len(command))+command);assert f"owner-{remote}\n".encode()in f.stdout(remote)
    f.open(30,b"shell,v2,raw:kbl off");assert d.fixture_notifications()==1 and d.fixture_ready()==1;finish(d)
    cases.append("eighteen-independent-channels-expand-twice-and-preserve-off")
    result={"passed":True,"cases":cases,"scope":"Real portable ADB core; fake shell + real native RAMFS; no device/network","sha256":{str(p.relative_to(HERE.parents[1])):hashlib.sha256(p.read_bytes()).hexdigest()for p in [HERE.parent/"kbl_adb.c",HERE/"host_fixture.c",Path(__file__)]}}
    (OUT/"validation.json").write_text(json.dumps(result,indent=2)+"\n");print(json.dumps({"passed":True,"cases":len(cases),"report":str(OUT/"validation.json")}))
if __name__=="__main__":main()
