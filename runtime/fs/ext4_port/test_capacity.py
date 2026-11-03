"""Compile real lwext4 and exercise dynamic mount/path/cleanup on RAM disks."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def main():
    a=argparse.ArgumentParser();a.add_argument('--output',type=Path,required=True);args=a.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    clang=shutil.which('clang')or'clang';arm=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe')
    sources=[ROOT/'runtime/fs/kbl_ext4.c']+[p for p in (ROOT/'third_party/lwext4/src').glob('*.c')if p.name not in ['ext4_mbr.c','ext4_debug.c']]
    flags=['-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-std=c11','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-unused-function','-Wno-unused-but-set-variable','-I'+str(HERE/'include'),'-I'+str(ROOT/'third_party/lwext4/include'),'-include',str(HERE/'include/kbl_ext4_config.h')]
    objects=[]
    for p in sources:
        target=out/(p.stem+'.obj');subprocess.run([clang,*flags,'-DKBL_EXT4_HOST_TEST','-c',str(p),'-o',str(target)],check=True);objects.append(target)
    t=out/'test.obj';subprocess.run([clang,'-O2','-DKBL_EXT4_HOST_TEST','-DCONFIG_USE_DEFAULT_CFG=1','-DCONFIG_HAVE_OWN_ERRNO=1','-I'+str(ROOT/'third_party/lwext4/include'),'-c',str(HERE/'test_ext4_capacity.c'),'-o',str(t)],check=True)
    exe=out/'test_ext4_capacity.exe';subprocess.run([clang,*map(str,objects),str(t),'-o',str(exe)],check=True)
    result=subprocess.run([str(exe)],capture_output=True,text=True);print(result.stdout+result.stderr);result.check_returncode();host=json.loads(result.stdout)
    for p in [ROOT/'runtime/fs/kbl_ext4.c',ROOT/'third_party/lwext4/src/ext4.c']:
        subprocess.run([str(arm),*flags,'--target=aarch64-pc-windows-msvc','-ffixed-x18','-mgeneral-regs-only','-mno-outline-atomics','-c',str(p),'-o',str(out/(p.stem+'-arm64.obj'))],check=True)
    files=sources+[HERE/'include/kbl_ext4_config.h',HERE/'test_ext4_capacity.c',HERE/'test_ext4_backend.c',Path(__file__)]
    report={'passed':True,'host':host,'arm64Compile':True,'scope':'Real lwext4 reads/writes on six in-memory ext4 disk images; no hardware/firmware/device calls.','sourceHashes':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()for p in files}}
    (out/'validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'passed':True,'host':host,'arm64Compile':True},indent=2))
if __name__=='__main__':main()
