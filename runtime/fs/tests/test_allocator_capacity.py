"""Exercise production allocation paths beyond former budgets, without a disk."""
from pathlib import Path
import argparse,ctypes as C,hashlib,json,shutil,subprocess
ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent

def command(args):
    result=subprocess.run(list(map(str,args)),capture_output=True,text=True)
    if result.returncode:raise RuntimeError(result.stdout+result.stderr)

def build(out,kind):
    cc=shutil.which('clang')or'clang';target=out/(kind+'.dll')
    flags=['-std=gnu11','-O2','-fno-builtin','-shared']
    sources=[HERE/'allocator_fixture.c']
    if kind=='f2fs':flags+=['-DTEST_F2FS_ALLOCATOR=1']
    else:
        flags+=['-I',ROOT/'runtime/fs/erofs_port/include','-I',ROOT/'third_party/erofs-utils/include','-I',ROOT/'third_party/lz4','-include','config.h',
                '-Wno-unused-parameter','-Wno-unused-variable','-Wno-unused-but-set-variable','-Wno-sign-compare']
        sources += [ROOT/'third_party/erofs-utils/lib'/n for n in ('data.c','namei.c','zmap.c','decompress.c','dir.c')]
        # LZ4 needs its explicit freestanding memory macros independently.
        obj=out/'allocator-lz4.obj'
        command([cc,*[f for f in flags if f!='-shared'],'-include',ROOT/'runtime/fs/erofs_port/include/string.h',
                 '-DLZ4_FREESTANDING=1','-DLZ4_memcpy=kbl_erofs_memcpy','-DLZ4_memmove=kbl_erofs_memmove','-DLZ4_memset=kbl_erofs_memset',
                 '-c',ROOT/'third_party/lz4/lz4.c','-o',obj])
        sources.append(obj)
    cmd=[cc,*flags,*sources,'-Wl,--export-all-symbols','-o',target];command(cmd)
    return target,cmd

def check_allocator(path,kind):
    lib=C.CDLL(str(path));P=C.c_void_p;Z=C.c_size_t
    Alloc=C.CFUNCTYPE(P,P,Z,Z);Free=C.CFUNCTYPE(None,P,P,Z)
    class Host(C.Structure):_fields_=[('opaque',P),('allocate',Alloc),('release',Free)]
    blocks={};requests=[];bad=[];fail=[False]
    @Alloc
    def allocate(_,n,alignment):
        requests.append(n)
        if fail[0]:return None
        b=C.create_string_buffer(n+alignment);address=(C.addressof(b)+alignment-1)&~(alignment-1)
        blocks[address]=(b,n);return address
    @Free
    def release(_,address,n):
        found=blocks.pop(address,None)
        if not found or found[1]!=n:bad.append((address,n))
    host=Host(None,allocate,release)
    lib.test_allocator_setup.argtypes=[C.POINTER(Host)];lib.test_allocator_setup(C.byref(host))
    lib.test_allocator_malloc.argtypes=[Z];lib.test_allocator_malloc.restype=P
    lib.test_allocator_calloc.argtypes=[Z,Z];lib.test_allocator_calloc.restype=P
    lib.test_allocator_free.argtypes=[P]
    big=(1 if kind=='f2fs'else 16)*1024*1024+1
    pointers=[lib.test_allocator_malloc(big),lib.test_allocator_malloc(big)]
    assert all(pointers)and pointers[0]!=pointers[1]
    for address in pointers:
        assert address%16==0
        C.c_ubyte.from_address(address).value=0x55;C.c_ubyte.from_address(address+big-1).value=0xaa
        assert C.c_ubyte.from_address(address).value==0x55 and C.c_ubyte.from_address(address+big-1).value==0xaa
    if kind=='erofs':
        lib.test_allocator_total.restype=Z;lib.test_allocator_set_total.argtypes=[Z]
        assert lib.test_allocator_total()==2*big
    for address in pointers:lib.test_allocator_free(address)
    assert not blocks and not bad
    zero=lib.test_allocator_calloc(big,1);assert zero
    assert not any(C.string_at(zero,big));lib.test_allocator_free(zero)
    before=len(requests);assert not lib.test_allocator_malloc(Z(-1).value)
    assert not lib.test_allocator_calloc(Z(-1).value,2)and len(requests)==before
    if kind=='erofs':
        lib.test_allocator_set_total(Z(-1).value-10);before=len(requests)
        assert not lib.test_allocator_malloc(11)and len(requests)==before
        lib.test_allocator_set_total(0)
    fail[0]=True;assert not lib.test_allocator_malloc(big);assert not blocks
    lib.test_allocator_disable();before=len(requests)
    assert not lib.test_allocator_malloc(1)and len(requests)==before
    return {'passed':True,'allocationBytes':big,'simultaneousPayloadBytes':2*big,'zeroInitializedBytes':big,
            'headerAndCounterOverflowRejected':True,'hostFailurePropagated':True,'balancedOwnership':not blocks and not bad}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True);report={'passed':True,'deviceOperations':False,'backends':{}}
    for kind in ('f2fs','erofs'):
        lib,cmd=build(out,kind);report['backends'][kind]={**check_allocator(lib,kind),'command':list(map(str,cmd))}
    report['sourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()for p in
        [ROOT/'runtime/fs/f2fs.c',ROOT/'runtime/fs/kbl_erofs.c',HERE/'allocator_fixture.c',Path(__file__)]}
    (out/'verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
