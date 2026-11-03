"""Build real AArch64 returning EFI + loader model. No device operations."""
from pathlib import Path
import subprocess,json,hashlib,os,sys,argparse
ROOT=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,default=ROOT/'build/kbl-efi')
OUT=parser.parse_args().output.resolve();OUT.mkdir(parents=True,exist_ok=True)
LLVM=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin')
HOST=next(Path('C:/Users/happy/AppData/Local/Microsoft/WinGet/Packages').glob('MartinStorsjo.LLVM-MinGW.UCRT_*/llvm-mingw-*/bin/clang.exe'))
FLAGS=['--target=aarch64-pc-windows-msvc','-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-mno-stack-arg-probe','-ffixed-x18','-mgeneral-regs-only','-mno-outline-atomics','-Wall','-Wextra','-Werror','-std=c11']
def run(args):
 r=subprocess.run(list(map(str,args)),check=True,capture_output=True,text=True);return r.stdout
sys.path.insert(0,str(ROOT/'runtime/uefi'))
from build_apps import build
apps=build(LLVM,OUT,ROOT)
run([LLVM/'clang.exe',*FLAGS,'-c',ROOT/'runtime/uefi/kbl_efi_run.c','-o',OUT/'launcher.obj'])
run([HOST,'-std=c11','-O2','-Wall','-Wextra','-Werror',ROOT/'runtime/uefi/tests/test_efi_run.c',ROOT/'runtime/uefi/kbl_efi_run.c','-o',OUT/'test_efi_run.exe'])
r=json.loads(run([OUT/'test_efi_run.exe',OUT/'return.efi']))
run([HOST,'-std=c11','-O2','-Wall','-Wextra','-Werror',ROOT/'runtime/uefi/tests/test_efi_protocols.c','-o',OUT/'test_efi_protocols.exe'])
protocol_model=json.loads(run([OUT/'test_efi_protocols.exe']))
import pefile
pe=pefile.PE(str(OUT/'return.efi'));assert pe.FILE_HEADER.Machine==0xaa64 and pe.OPTIONAL_HEADER.Subsystem==10 and not getattr(pe,'DIRECTORY_ENTRY_IMPORT',[])
report={'model':r,'protocolModel':protocol_model,'sample':str(OUT/'return.efi'),'sampleBytes':(OUT/'return.efi').stat().st_size,'sampleSha256':hashlib.sha256((OUT/'return.efi').read_bytes()).hexdigest(),'machine':hex(pe.FILE_HEADER.Machine),'subsystem':pe.OPTIONAL_HEADER.Subsystem,'imports':0,'gprOnly':True,'x18Reserved':True,'sampleBehavior':'Direct MRS CurrentEL before/after, optional console output, immediate return; no ExitBootServices/USB hooks','appsManifest':apps,'physicalDeviceIO':False,'sourceSha256':{n:hashlib.sha256((ROOT/n).read_bytes()).hexdigest()for n in ['runtime/kbl_efi.h','runtime/uefi/kbl_efi_run.c','runtime/uefi/tests/test_efi_run.c','runtime/uefi/tests/run_efi.py']},'sizePolicy':'No fixed source-byte or SizeOfImage cap; field width, address arithmetic, valid PE mapping and firmware allocation results remain enforced.'}
(OUT/'validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
