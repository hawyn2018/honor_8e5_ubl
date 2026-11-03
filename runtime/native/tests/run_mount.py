from pathlib import Path
import argparse,subprocess,shutil,json,hashlib
S=Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser();p.add_argument('--output',type=Path);args=p.parse_args()
OUT=args.output.resolve()if args.output else S.parents[2]/'analysis/kbl-mount-native';OUT.mkdir(parents=True,exist_ok=True)
sources=['runtime/native/tests/test_mount.c','runtime/kbl_elf.c','runtime/kbl_sha256.c','runtime/native/kbl_efi_bootloader.c','runtime/fs/fat.c','third_party/fatfs/ff.c','third_party/fatfs/ffunicode.c']
cmd=[shutil.which('clang'),'-std=c11','-O2','-fno-builtin','-Wall','-Wextra','-Werror','-DKBL_FATFS_TEST_MKFS=1','-I',str(S/'third_party/fatfs'),*[str(S/p)for p in sources],'-o',str(OUT/'mount.exe')]
r=subprocess.run(cmd,capture_output=True,text=True);(OUT/'build.log').write_text(r.stdout+r.stderr);r.check_returncode()
r=subprocess.run([str(OUT/'mount.exe')],capture_output=True,text=True);(OUT/'run.log').write_text(r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
result=json.loads(r.stdout)
result['scope']='Production native VFS, real FatFs/exFAT, explicit host RAM and block-media models; no USB/device IO.'
result['sourceHashes']={str(Path(p)):hashlib.sha256((S/p).read_bytes()).hexdigest()for p in sources+['runtime/native/kbl_native.c','runtime/native/kbl_vfs.inc','runtime/native/kbl_mount.inc','runtime/native/kbl_io.inc','runtime/fs/kbl_fs.h']}
(OUT/'validation.json').write_text(json.dumps(result,indent=2)+'\n')
