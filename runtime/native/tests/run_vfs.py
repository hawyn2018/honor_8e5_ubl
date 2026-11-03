from pathlib import Path
import hashlib,json,subprocess,shutil
HERE=Path(__file__).resolve().parent;RUNTIME=HERE.parents[1]
ROOT=RUNTIME.parents[3];OUT=ROOT/'analysis/kbl-services-20261030/native';OUT.mkdir(parents=True,exist_ok=True)
cmd=[shutil.which('clang'),'-std=c11','-O2','-Wall','-Wextra','-Werror',str(HERE/'test_vfs.c'),str(HERE/'fs_unavailable.c'),str(RUNTIME/'kbl_elf.c'),str(RUNTIME/'kbl_sha256.c'),str(RUNTIME/'native/kbl_efi_bootloader.c'),'-o',str(OUT/'test_vfs.exe')]
r=subprocess.run(cmd,capture_output=True,text=True);(OUT/'vfs-build.log').write_text(r.stdout+r.stderr);r.check_returncode()
r=subprocess.run([str(OUT/'test_vfs.exe')],capture_output=True,text=True);(OUT/'vfs-run.log').write_text(r.stdout+r.stderr);r.check_returncode()
v=json.loads(r.stdout)
reference=hashlib.sha256();chunk=bytes(range(256))*256
for _ in range(1024):reference.update(chunk)
reference.update(bytes(range(31)));assert v['largeDigest']==reference.hexdigest()
v['largeStreamBytes']=64*1024*1024+31;v['referenceHashMatched']=True
v['scope']='Actual production C VFS and commands with explicit host block, allocation, clock and EL models.'
v['sourceHashes']={str(p.relative_to(RUNTIME)):hashlib.sha256(p.read_bytes()).hexdigest()for p in [RUNTIME/'native/kbl_native.c',RUNTIME/'native/kbl_vfs.inc',RUNTIME/'native/kbl_efi_bootloader.c',RUNTIME/'native/kbl_efi_bootloader.h',RUNTIME/'sdk/kbl_program.h',RUNTIME/'sdk/kbl_file.h']}
v['buildCommand']=cmd;(OUT/'vfs-validation.json').write_text(json.dumps(v,indent=2)+'\n');print(json.dumps(v))
