"""Build the actual readonly adapter as a host DLL (never opens a device)."""
from pathlib import Path
import argparse,json,subprocess,shutil,hashlib
SRC=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser();p.add_argument('--output',type=Path);args=p.parse_args()
OUT=args.output.resolve()if args.output else SRC.parent/'erofs-test-output';OUT.mkdir(exist_ok=True,parents=True)
CC=shutil.which('clang')
if not CC:raise SystemExit('host clang required')
sources=[SRC/'runtime/fs/kbl_erofs.c',*[SRC/'third_party/erofs-utils/lib'/n for n in ('data.c','namei.c','zmap.c','decompress.c','dir.c')],SRC/'third_party/lz4/lz4.c']
flags=['-std=gnu11','-O2','-fno-builtin','-I',str(SRC/'runtime/fs/erofs_port/include'),'-I',str(SRC/'third_party/erofs-utils/include'),'-I',str(SRC/'third_party/lz4'),'-include','config.h','-Wno-unused-parameter','-Wno-unused-variable','-Wno-unused-but-set-variable','-Wno-sign-compare']
objects=[]
for source in sources:
    obj=OUT/(source.stem+'.obj');objects.append(obj)
    extra=[]
    if source.name=='lz4.c':extra=['-include',str(SRC/'runtime/fs/erofs_port/include/string.h'),'-DLZ4_FREESTANDING=1','-DLZ4_memcpy=kbl_erofs_memcpy','-DLZ4_memmove=kbl_erofs_memmove','-DLZ4_memset=kbl_erofs_memset']
    subprocess.run([CC,*flags,*extra,'-c',str(source),'-o',str(obj)],check=True)
subprocess.run([CC,'-shared',*map(str,objects),'-Wl,--export-all-symbols','-o',str(OUT/'erofs-host.dll')],check=True)
(OUT/'build.json').write_text(json.dumps({'sources':{str(p.relative_to(SRC)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'compiler':CC},indent=2)+'\n')
print(OUT/'erofs-host.dll')
