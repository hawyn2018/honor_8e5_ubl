"""Build standalone AArch64 UEFI return/protocol-inventory apps. No device IO."""
from pathlib import Path
import argparse,hashlib,json,struct,subprocess
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
DEFAULT_LLVM=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin')
FLAGS=['--target=aarch64-pc-windows-msvc','-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-mno-stack-arg-probe','-ffixed-x18','-mgeneral-regs-only','-mno-outline-atomics','-Wall','-Wextra','-Werror','-std=c11']
SOURCES={'return':'runtime/examples/efi_return/main.c','protocols':'runtime/examples/efi_protocols/main.c'}
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def pe_info(path):
 b=Path(path).read_bytes();pe=struct.unpack_from('<I',b,60)[0];machine,sections=struct.unpack_from('<HH',b,pe+4);op=pe+24;subsystem=struct.unpack_from('<H',b,op+68)[0];count=struct.unpack_from('<I',b,op+108)[0];imp=struct.unpack_from('<II',b,op+120) if count>1 else (0,0);rel=struct.unpack_from('<II',b,op+152) if count>5 else (0,0)
 assert b[:2]==b'MZ' and b[pe:pe+4]==b'PE\0\0' and machine==0xaa64 and subsystem==10 and not any(imp) and all(rel)
 return {'machine':hex(machine),'subsystem':subsystem,'sections':sections,'imports':0,'relocations':True,'bytes':len(b),'sha256':sha(path)}
def build(llvm_bin=DEFAULT_LLVM,output=None,source_root=ROOT):
 root=Path(source_root).resolve();out=Path(output or root/'build/kbl-efi').resolve();out.mkdir(parents=True,exist_ok=True);llvm=Path(llvm_bin);tool=lambda n:llvm/(n+'.exe' if (llvm/(n+'.exe')).exists() else n);commands=[];apps={}
 for name,relative in SOURCES.items():
  source=root/relative;obj=out/(name+'.obj');target=out/(name+'.efi');compile=[tool('clang'),*FLAGS,'-c',source,'-o',obj];link=[tool('ld.lld'),'-flavor','link','/subsystem:efi_application','/entry:EfiMain','/machine:arm64','/nodefaultlib','/base:0','/dynamicbase','/nxcompat','/fixed:no','/timestamp:0','/out:'+str(target),obj]
  for command in [compile,link]:subprocess.run(list(map(str,command)),check=True);commands.append(list(map(str,command)))
  apps[name]={'file':str(target),**pe_info(target),'source':relative,'sourceSha256':sha(source)}
 manifest={'passed':True,'apps':apps,'flags':FLAGS,'header':'runtime/uefi/kbl_efi_types.h','headerSha256':sha(root/'runtime/uefi/kbl_efi_types.h'),'commands':commands,'physicalDeviceIO':False}
 (out/'apps-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');return manifest
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--llvm-bin',type=Path,default=DEFAULT_LLVM);p.add_argument('--output',type=Path);p.add_argument('--source-root',type=Path,default=ROOT);a=p.parse_args();print(json.dumps(build(a.llvm_bin,a.output,a.source_root),indent=2))
