"""Host DLL + relocated ARM64 execution of the same bounded DTBO selector.
No device, EFI storage callback, partition writer or kernel is involved.
"""
from pathlib import Path
import ctypes,hashlib,json,shutil,struct,subprocess,sys
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM
from unicorn import arm64_const as a
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
OUT=ROOT/'build/vm-dtbo';OUT.mkdir(parents=True,exist_ok=True)
WORK=next(p for p in ROOT.parents if (p/'analysis/downgrade-fastboot-20261104/qtvm_dtbo_b.raw').is_file())
sys.stdout.reconfigure(encoding='utf-8')
be=lambda n:struct.pack('>I',n)
def tree(role,board=0x8877,revision=0,domain=None,vmid=None,duplicate=False,depth=0,optional=True):
    domain=(45 if role==0 else 49) if domain is None else domain
    strings=bytearray();tokens=bytearray()
    def node(s):
        tokens.extend(be(1)+s.encode()+b'\0');tokens.extend(bytes(-len(tokens)%4))
    def end():tokens.extend(be(2))
    def prop(n,v):
        k=len(strings);strings.extend(n.encode()+b'\0');tokens.extend(be(3)+be(len(v))+be(k)+v);tokens.extend(bytes(-len(tokens)%4))
    board_bytes=b''.join(be(b)+be(r)for b,r in board) if isinstance(board,list)else be(board)+be(revision)
    node('');prop('qcom,board-id',board_bytes);node('qcom,vm-config')
    prop('image-name',b'qcom,'+(b'trustedvm' if role==0 else b'oemvm')+b'\0')
    if duplicate:prop('image-name',b'qcom,oemvm\0')
    prop('qcom,secdomain-ids',be(domain))
    if optional:
        prop('qcom,pasid',be(0)+be(28 if role==0 else 34));prop('vm-uri',b'test/guest\0');prop('vm-guid',b'guest-guid\0')
    end()
    for n in range(depth):node('nested'+str(n))
    for n in range(depth):end()
    node('fragment@19');node('__overlay__');node('qcom,gunyah-vm');prop('qcom,vmid',be(domain if vmid is None else vmid));end();end();end();end();tokens.extend(be(9))
    start=56;total=start+len(tokens)+len(strings)
    return b''.join(map(be,[0xd00dfeed,total,start,start+len(tokens),40,17,16,0,len(strings),len(tokens)]))+bytes(16)+tokens+strings
def table(trees):
    offset=32+len(trees)*32;entries=bytearray();data=bytearray()
    for i,t in enumerate(trees):
        entries.extend(b''.join(map(be,[len(t),offset+len(data),0xdead0000+i,0,0,0,0,0])));data.extend(t)
    return b''.join(map(be,[0xd7b7ab1e,offset+len(data),32,32,len(trees),32,4096,0]))+entries+data
def cases():
    raw=(WORK/'analysis/downgrade-fastboot-20261104/qtvm_dtbo_b.raw').read_bytes()
    assert hashlib.sha256(raw).hexdigest()=='109842365bf3e3d356319f4b4852b31ce8cb3f2e4268eb6e2ba7110a33f1a089'
    yield 'capture-old-fallback',raw,0x2262,0,[0,1],[45,49],1,(1,[0,2])
    yield 'capture-valid',raw,0x2262,0,[0,2],[45,49],1,(0,[0,2])
    yield 'capture-qrd',raw,11,0,[-1,-1],[45,49],1,(1,[1,3])
    yield 'capture-foreign-board-without-anchor',raw,0xdead,0,[-1,-1],[45,49],1,(-3,[-1,-1])
    yield 'actual-hw-old-native-pair',raw,0x22a6,0x10000,[0,1],[45,49],1,(1,[0,2])
    yield 'actual-hw-valid-native-pair',raw,0x22a6,0x10000,[0,2],[45,49],1,(0,[0,2])
    yield 'conflicting-native-boards',raw,0x22a6,0x10000,[0,3],[45,49],1,(-4,[0,3])
    yield 'no-cross-board-complement',table([tree(0,board=1),tree(1,board=2)]),0x22a6,0x10000,[0,-1],[45,49],1,(-3,[0,-1])
    yield 'anchored-multiple-complements',table([tree(0),tree(1),tree(1)]),0x22a6,0x10000,[0,-1],[45,49],1,(-4,[0,-1])
    yield 'no-anchor-does-not-guess-unique-pair',table([tree(0),tree(1)]),0x22a6,0x10000,[-1,-1],[45,49],1,(-3,[-1,-1])
    yield 'partial-hw-no-cross-board',table([tree(0),tree(1,board=2)]),0x8877,0,[0,-1],[45,49],1,(-3,[0,-1])
    yield 'hardware-pair-prioritized',table([tree(0,board=1),tree(1,board=1),tree(0,board=2),tree(1,board=2)]),2,0,[0,1],[45,49],1,(1,[2,3])
    yield 'hardware-ambiguity-no-anchor-escape',table([tree(0,board=1),tree(1,board=1),tree(0,board=2),tree(1,board=2),tree(1,board=2)]),2,0,[0,1],[45,49],1,(-4,[0,1])
    yield 'multi-tuples-one-entry-counted-once',table([tree(0,board=[(1,0),(2,0)]),tree(1,board=[(1,0),(2,0)])]),0x22a6,0x10000,[0,-1],[45,49],1,(1,[0,1])
    yield 'multi-tuples-conflicting-complements',table([tree(0,board=[(1,0),(2,0)]),tree(1,board=1),tree(1,board=2)]),0x22a6,0x10000,[0,-1],[45,49],1,(-4,[0,-1])
    yield 'multi-tuples-preserve-native-pair',table([tree(0,board=[(1,0),(2,0)]),tree(1,board=2)]),0x22a6,0x10000,[0,1],[45,49],1,(0,[0,1])
    basic=table([tree(1),tree(0)])
    yield 'reordered-new-board',basic,0x8877,0,[-1,-1],[45,49],1,(1,[1,0])
    yield 'valid-tui-only',basic,0x8877,0,[1,999],[45,49],1,(1,[1,0])
    yield 'duplicate-match',table([tree(0),tree(1),tree(1)]),0x8877,0,[-1,-1],[45,49],1,(-4,[-1,-1])
    yield 'preserve-valid-despite-duplicate',table([tree(0),tree(1),tree(1)]),0x8877,0,[0,1],[45,49],1,(0,[0,1])
    yield 'security-domain-mismatch',table([tree(0),tree(1,domain=45)]),0x8877,0,[-1,-1],[45,49],1,(-3,[-1,-1])
    yield 'host-vmid-mismatch',table([tree(0),tree(1,vmid=45)]),0x8877,0,[0,1],[45,49],1,(-3,[0,1])
    yield 'revision-mismatch',basic,0x8877,5,[-1,-1],[45,49],1,(-3,[-1,-1])
    yield 'duplicate-identity-property',table([tree(0),tree(1,duplicate=True)]),0x8877,0,[-1,-1],[45,49],1,(-2,[-1,-1])
    yield 'depth-from-actual-tree',table([tree(0,depth=65),tree(1)]),0x8877,0,[-1,-1],[45,49],1,(1,[0,1])
    yield 'optional-fields-absent',table([tree(0,optional=False),tree(1,optional=False)]),0x8877,0,[-1,-1],[45,49],1,(1,[0,1])
    version1=bytearray(basic);version1[28:32]=be(1)
    yield 'version1-uncompressed',bytes(version1),0x8877,0,[-1,-1],[45,49],1,(1,[1,0])
    version1[48:52]=be(1)
    yield 'version1-compressed-explicit-skip',bytes(version1),0x8877,0,[-1,-1],[45,49],1,(-5,[-1,-1])
    # Extend both header and entries with actual bytes, preserving offsets.
    extended=bytearray(table([tree(0),tree(1)]));first=bytes(extended[32:64]);second=bytes(extended[64:96]);tail=bytes(extended[96:])
    extended=bytearray(extended[:32])+bytearray(32)+bytearray(first)+bytearray(32)+bytearray(second)+bytearray(32)+bytearray(tail)
    extended[4:8]=be(len(extended));extended[8:12]=be(64);extended[12:16]=be(64);extended[20:24]=be(64)
    extended[68:72]=be(192);extended[132:136]=be(192+len(tree(0)))
    yield 'extended-header-and-entry',bytes(extended),0x8877,0,[-1,-1],[45,49],1,(1,[0,1])
    yield 'mode-before-key-decision',basic,0x8877,0,[0,1],[45,49],0,(-1,[0,1])
    yield 'fastboot-does-not-read-any-pointer',None,0,0,[0,1],[45,49],2,(-1,[0,1])
    for name,at,val in [('total-wrap',4,0xffffffff),('entries-wrap',20,0xfffffff0),('entry-size-wrap',32,0xfffffff0),('entry-offset-wrap',36,0xfffffff0),('fdt-size-wrap',32+64+4,0xffffffff)]:
        mutated=bytearray(basic);mutated[at:at+4]=be(val)
        yield name,bytes(mutated),0x8877,0,[-1,-1],[45,49],1,(-2,[-1,-1])
    yield 'truncated-tail',basic[:-1],0x8877,0,[-1,-1],[45,49],1,(-2,[-1,-1])
def host(case,fn):
    name,data,board,rev,idx,domains,mode,expected=case
    b=ctypes.create_string_buffer(data) if data is not None else None
    indices=(ctypes.c_int32*2)(*idx);ids=(ctypes.c_uint32*2)(*domains)
    result=fn(b if b else 1,len(data or b''),board,rev,indices,ids,mode)
    assert (result,list(indices))==expected,(name,result,list(indices),expected)
def arm(case,blob,base):
    name,data,board,rev,idx,domains,mode,expected=case
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(base,(len(blob)+4095)&~4095,5);u.mem_write(base,blob)
    data_base=0x20000000;stack=0x30000000;stop=0x40000000
    u.mem_map(data_base,0x600000,3);u.mem_map(stack,0x10000,3);u.mem_map(stop,4096,5)
    u.mem_write(data_base,data or b'');u.mem_write(data_base+0x580000,struct.pack('<2i',*idx));u.mem_write(data_base+0x580010,struct.pack('<2I',*domains))
    u.reg_write(a.UC_ARM64_REG_SP,stack+0xf000);u.reg_write(a.UC_ARM64_REG_X30,stop)
    saved={getattr(a,f'UC_ARM64_REG_X{i}'):0x12340000+i for i in range(18,30)}
    for r,v in saved.items():u.reg_write(r,v)
    args=[data_base if data is not None else 1,len(data or b''),board,rev,data_base+0x580000,data_base+0x580010,mode]
    if mode==2:args[4]=args[5]=1 # invalid output/config pointers must also remain unread
    for i,v in enumerate(args):u.reg_write(getattr(a,f'UC_ARM64_REG_X{i}'),v)
    u.emu_start(base,stop,count=20000000)
    result=ctypes.c_int32(u.reg_read(a.UC_ARM64_REG_X0)).value
    selected=list(struct.unpack('<2i',u.mem_read(data_base+0x580000,8)))
    assert (result,selected)==expected,(name,result,selected,expected)
    assert u.reg_read(a.UC_ARM64_REG_SP)==stack+0xf000
    assert all(u.reg_read(r)==v for r,v in saved.items()),name
def main():
    compiler=shutil.which('clang');assert compiler
    dll=OUT/'vm_dtbo_host.dll'
    subprocess.run([compiler,'-O2','-shared','-Wall','-Wextra','-Werror',str(HERE/'vm_dtbo.c'),'-o',str(dll)],check=True,capture_output=True)
    library=ctypes.CDLL(str(dll));fn=library.vm_dtbo_select
    fn.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_uint32,ctypes.c_uint32,ctypes.POINTER(ctypes.c_int32),ctypes.POINTER(ctypes.c_uint32),ctypes.c_uint32];fn.restype=ctypes.c_int
    blob=(OUT/'vm_dtbo.bin').read_bytes();results=[]
    for case in cases():
        host(case,fn)
        for base in [0x10000000,0x123450000]:arm(case,blob,base)
        results.append({'name':case[0],'host':True,'arm64Bases':2})
    capture=WORK/'analysis/downgrade-fastboot-20261104/qtvm_dtbo_b.raw'
    report={'passed':True,'cases':results,'moduleSha256':hashlib.sha256(blob).hexdigest(),'capturedDtbo':{'path':str(capture),'bytes':capture.stat().st_size,'sha256':hashlib.sha256(capture.read_bytes()).hexdigest()},'deviceOperations':False,'scope':'Actual host DLL and relocated ARM64 PIC execute. No physical firmware, native hypervisor or Linux executes.'}
    (OUT/'test-results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main()
