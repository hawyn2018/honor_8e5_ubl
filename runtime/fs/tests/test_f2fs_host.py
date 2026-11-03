"""Exercise real mkfs F2FS plus explicit reader-only inode fixtures, no USB."""
import hashlib
import json
import pathlib
import shutil
import struct
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
OUT = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT.parent / "f2fs-test-output"
OUT.mkdir(parents=True, exist_ok=True)
MKFS = pathlib.Path(r"D:/ai_work/honorubl/platform-tools/make_f2fs.exe")
clang = shutil.which("clang")
u32 = lambda b, o: struct.unpack_from("<I", b, o)[0]
def put32(b,o,n): struct.pack_into("<I",b,o,n)
def put64(b,o,n): struct.pack_into("<Q",b,o,n)
def put16(b,o,n): struct.pack_into("<H",b,o,n)

images=[]
for name,opts in [("f2fs-basic-128m",[]),("f2fs-extra-128m",["-O","extra_attr,inode_checksum,flexible_inline_xattr"])]:
    p=OUT/(name+".img")
    with p.open("wb") as f: f.truncate(128*1024*1024)
    result=subprocess.run([str(MKFS),"-f","-t","0","-r","-T","1760000000",*opts,str(p)],capture_output=True,text=True)
    (OUT/(name+"-mkfs.txt")).write_text(result.stdout+result.stderr,encoding="utf-8")
    if result.returncode: raise SystemExit(result.stdout+result.stderr)
    images.append(p)

# Start with the actual clean formatter output. Add deliberately isolated
# reader fixtures in spare blocks, with NAT/root directory references. They
# validate parsing and IO only: SIT/space-accounting/checksum metadata is not
# rewritten, so this derivative is NOT a mountable/writable production image.
b=bytearray(images[0].read_bytes())
sb=1024; nat=u32(b,sb+84)*4096; main=u32(b,sb+92)
root_ino=u32(b,sb+96); root_block=u32(b,nat+root_ino*9+5)
cp=u32(b,sb+76)*4096
summary=(u32(b,sb+76)+u32(b,cp+140))*4096
for j in range(struct.unpack_from("<H",b,summary)[0]):
    if u32(b,summary+2+j*13)==root_ino: root_block=u32(b,summary+2+j*13+9)
assert root_block
root_off=root_block*4096; root_dir=u32(b,root_off+360)*4096
assert not b[root_off+3]&4
entries=[]
def node(nid,mode,size,inline=0):
    block=main+100+nid
    off=block*4096
    b[off:off+4096]=bytes(4096)
    put16(b,off,mode);b[off+3]=inline;put32(b,off+12,1);put64(b,off+16,size)
    put32(b,nat+nid*9+1,nid);put32(b,nat+nid*9+5,block)
    put32(b,off+4072,nid);put32(b,off+4076,nid)
    return off
payload=bytes((i*17+3)&255 for i in range(8192))
off=node(4,0o100644,len(payload));put32(b,off+360,main+80);put32(b,off+364,main+81)
b[(main+80)*4096:(main+82)*4096]=payload;entries.append(("payload.bin",4,1))
off=node(5,0o100644,12,2);b[off+364:off+376]=b"inline hello";entries.append(("inline.txt",5,1))
directory=node(6,0o040755,3688,4);entries.append(("子目录",6,2))
# Inline directory with no xattrs/extraattrs: 192 slots, bitmap24, reserved16.
base=directory+364;b[base]=1;put32(b,base+40+4,5);put16(b,base+40+8,len("子文件".encode()));b[base+40+10]=1
b[base+40+192*11:base+40+192*11+len("子文件".encode())]="子文件".encode()
off=node(7,0o120777,len(b"payload.bin"),2);b[off+364:off+375]=b"payload.bin";entries.append(("alias",7,7))
off=node(8,0o100644,924*4096);put32(b,off+4052,9);entries.append(("large.bin",8,1))
direct=node(9,0o100644,0);put32(b,direct,main+80)
off=node(10,0o100644,12,2);b[off+2]=4;entries.append(("encrypted",10,1))
off=node(11,0o100644,12,2);put32(b,off+80,4);entries.append(("compressed",11,1))
indirect_offset=(923+2*1018)*4096
off=node(12,0o100644,indirect_offset+4096);put32(b,off+4052+8,14);entries.append(("indirect.bin",12,1))
ind=node(14,0o100644,0);put32(b,ind,15)
direct=node(15,0o100644,0);put32(b,direct,main+80)
double_offset=(923+2*1018+2*1018*1018)*4096
off=node(13,0o100644,double_offset+4096);put32(b,off+4052+16,16);entries.append(("double.bin",13,1))
ind=node(16,0o100644,0);put32(b,ind,17)
ind=node(17,0o100644,0);put32(b,ind,18)
direct=node(18,0o100644,0);put32(b,direct,main+80)
for idx in range(24):
    name=f"alias{idx+1:02d}";target=f"alias{idx+2:02d}" if idx<23 else "../payload.bin"
    raw=target.encode();off=node(80+idx,0o120777,len(raw),2);b[off+364:off+364+len(raw)]=raw;entries.append((name,80+idx,7))
for nid,name,target in [(28,"loop-a","loop-b"),(29,"loop-b","loop-a")]:
    raw=target.encode();off=node(nid,0o120777,len(raw),2);b[off+364:off+364+len(raw)]=raw;entries.append((name,nid,7))
# A >4 KiB full path composed of valid 200-byte names, including a symlink
# whose parent prefix alone exceeds the old fixed combined buffer.
component='d'*200;entries.append((component,30,2))
for index in range(25):
    directory=node(30+index,0o040755,3688,4);base=directory+364
    children=[(component,31+index,2)]if index<24 else [('deepfile',5,1),('alias',55,7)]
    at=0
    for name,nid,kind in children:
        raw=name.encode();slots=(len(raw)+7)//8
        for bit in range(at,at+slots):b[base+bit//8]|=1<<(bit%8)
        dent=base+40+at*11;put32(b,dent+4,nid);put16(b,dent+8,len(raw));b[dent+10]=kind
        b[base+40+192*11+at*8:base+40+192*11+at*8+len(raw)]=raw;at+=slots
off=node(55,0o120777,8,2);b[off+364:off+372]=b'deepfile'
for nid,name,target in [(56,'root-link','/'),(57,'grow','grow/x')]:
    raw=target.encode();off=node(nid,0o120777,len(raw),2);b[off+364:off+364+len(raw)]=raw;entries.append((name,nid,7))
slot=2
for name,nid,kind in entries:
    raw=name.encode();slots=(len(raw)+7)//8
    for s in range(slot,slot+slots): b[root_dir+s//8] |= 1<<(s%8)
    off=root_dir+30+slot*11;put32(b,off+4,nid);put16(b,off+8,len(raw));b[off+10]=kind
    b[root_dir+2384+slot*8:root_dir+2384+slot*8+len(raw)]=raw
    slot+=slots
fixture=OUT/"f2fs-reader-fixture-128m.img";fixture.write_bytes(b);images.append(fixture)
for nid in [4,5,6,7]:
    off=u32(b,nat+nid*9+5)*4096
    b[off+3]|=0x20
    if nid==4:
        put32(b,off+360+28,main+80);put32(b,off+364+28,main+81)
    elif nid in [5,7]:
        raw=b"inline hello" if nid==5 else b"payload.bin"
        b[off+360:off+4052]=bytes(3692)
        b[off+392:off+392+len(raw)]=raw
    else:
        b[off+360:off+4052]=bytes(3692)
        size=4*(923-7-1);count=size*8//153;bitmap=(count+7)//8;reserved=size-(19*count+bitmap)
        base=off+392;dent=base+bitmap+reserved;b[base]=1;put32(b,dent+4,5);put16(b,dent+8,len("子文件".encode()));b[dent+10]=1
        b[dent+11*count:dent+11*count+len("子文件".encode())]="子文件".encode()
    put16(b,off+360,28)
fixture=OUT/"f2fs-extra-reader-fixture-128m.img";fixture.write_bytes(b);images.append(fixture)

exe=OUT/"f2fs_host_test.exe"
subprocess.run([clang,"-std=c11","-O2","-fno-builtin","-Wall","-Wextra","-Werror",str(ROOT/"runtime/fs/f2fs.c"),str(ROOT/"runtime/fs/tests/f2fs_host_test.c"),"-o",str(exe)],check=True)
r=subprocess.run([str(exe),*map(str,images)],capture_output=True,text=True)
print(r.stdout,end="");print(r.stderr,end="",file=sys.stderr)
(OUT/"f2fs-host-result.txt").write_text(r.stdout+r.stderr,encoding="utf-8")
if r.returncode: raise SystemExit(r.returncode)
meta={p.name:{"bytes":p.stat().st_size,"sha256":hashlib.sha256(p.read_bytes()).hexdigest(),"valid_mkfs_image":"reader-fixture" not in p.name}for p in images}
(OUT/"f2fs-fixtures-sha256.json").write_text(json.dumps(meta,indent=2)+"\n",encoding="utf-8")
