from pathlib import Path
import subprocess,sys,json,os,argparse
root=Path(__file__).resolve().parents[3]
here=Path(__file__).resolve().parent
parser=argparse.ArgumentParser();parser.add_argument('mode',nargs='?',choices=['host','arm'],default='host');parser.add_argument('--output',type=Path);args=parser.parse_args();mode=args.mode
clang=(Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe') if mode=='arm' else next(Path('C:/Users/happy/AppData/Local/Microsoft/WinGet/Packages').glob('MartinStorsjo.LLVM-MinGW.UCRT_*/llvm-mingw-*/bin/clang.exe')))
out=args.output.resolve() if args.output else here/('arm-objects' if mode=='arm' else 'host-objects');out.mkdir(parents=True,exist_ok=True)
sources=[root/'runtime/fs/kbl_ext4.c']+[p for p in (root/'third_party/lwext4/src').glob('*.c') if p.name not in ['ext4_mbr.c','ext4_debug.c'] and (mode!='arm' or p.name!='ext4_mkfs.c')]
flags=['-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-std=c11','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-unused-function','-Wno-unused-but-set-variable','-I'+str(here/'include'),'-I'+str(root/'third_party/lwext4/include'),'-include',str(here/'include/kbl_ext4_config.h')]
if mode=='arm':flags+=['--target=aarch64-pc-windows-msvc','-ffixed-x18','-mgeneral-regs-only','-mno-outline-atomics']
else:flags+=['-DKBL_EXT4_HOST_TEST']
objects=[]
for p in sources:
 o=out/(p.stem+'.obj');r=subprocess.run([str(clang),*flags,'-c',str(p),'-o',str(o)],capture_output=True,text=True)
 if r.returncode:print(r.stderr);sys.exit(r.returncode)
 objects.append(str(o))
if mode=='host':
 t=out/'test.obj';subprocess.run([str(clang),'-O2','-DKBL_EXT4_HOST_TEST','-DCONFIG_USE_DEFAULT_CFG=1','-DCONFIG_HAVE_OWN_ERRNO=1','-I'+str(root/'third_party/lwext4/include'),'-c',str(here/'test_ext4_backend.c'),'-o',str(t)],check=True)
 exe=out/'test_ext4.exe';subprocess.run([str(clang),*objects,str(t),'-o',str(exe)],check=True)
 env=os.environ.copy();env['MKE2FS_CONFIG']='D:/ai_work/honorubl/platform-tools/mke2fs.conf'
 for typ,journal in [(2,0),(3,1),(4,0),(4,1)]:
  src=out/f'factory-ext{typ}-j{journal}.img'
  with src.open('wb') as f:f.truncate(64*1024*1024)
  features=('extent,64bit,metadata_csum,^orphan_file,^metadata_csum_seed' if typ==4 else '^extent,^64bit,^metadata_csum,^orphan_file,^metadata_csum_seed')+(',has_journal' if journal else ',^has_journal')
  r=subprocess.run(['D:/ai_work/honorubl/platform-tools/mke2fs.exe','-t',f'ext{typ}','-O',features,'-b','4096','-I','256','-F',str(src)],env=env,capture_output=True,text=True)
  if r.returncode:print(r.stdout,r.stderr);r.check_returncode()
  subprocess.run([str(exe),str(typ),str(journal),str(out/f'ext{typ}-j{journal}.img'),'64',str(src)],check=True)
else:
 nm=clang.with_name('llvm-nm.exe');r=subprocess.run([str(nm),'--undefined-only',*objects],capture_output=True,text=True);(out/'undefined.txt').write_text(r.stdout)
 print('ARM compiled',len(objects),'objects; unresolved inventory',out/'undefined.txt')
