"""Build and validate driver logic plus actual relocatable ARM64 COFF execution."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import subprocess
import sys

HERE=Path(__file__).resolve().parent
RUNTIME=HERE.parents[1]
SOURCE=RUNTIME.parent
WORKSPACE=SOURCE.parents[2]

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);obj=importlib.util.module_from_spec(spec);spec.loader.exec_module(obj);return obj

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--llvm-bin',type=Path,default=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin'))
    p.add_argument('--host-clang',default='clang')
    p.add_argument('--build-dir',type=Path,default=SOURCE/'build/kbl-driver')
    p.add_argument('--report-dir',type=Path,default=WORKSPACE/'analysis/kbl-services-20261030/storage/driver-regression')
    a=p.parse_args();a.report_dir.mkdir(parents=True,exist_ok=True);a.build_dir.mkdir(parents=True,exist_ok=True)
    builder=module('kbl_driver_builder',HERE.parent/'build_driver.py');artifact=builder.build(a.llvm_bin,a.build_dir)
    sources=[HERE/'test_driver.c',RUNTIME/'kbl_control.c',RUNTIME/'kbl_fetch.c',RUNTIME/'kbl_sha256.c',HERE.parent/'kbl_partition.c',HERE.parent/'kbl_usb.c',HERE.parent/'kbl_usb_native.c',HERE.parent/'kbl_adb_usb.c',RUNTIME/'adb/kbl_adb.c',HERE.parent/'kbl_dxe_memory.c',HERE.parent/'kbl_arch_memory.c',HERE.parent/'kbl_efi_run.c',HERE.parent/'kbl_display.c']
    executable=a.build_dir/'test_driver.exe'
    command=[a.host_clang,'-std=c11','-O2','-Wall','-Wextra','-Werror',*[str(s)for s in sources],'-o',str(executable)]
    subprocess.run(command,check=True)
    result=subprocess.run([str(executable)],capture_output=True,text=True,check=True);host=json.loads(result.stdout)
    subprocess.run([sys.executable,str(RUNTIME/'sdk/build_demo.py'),'--output',str(a.build_dir/'demo')],check=True,capture_output=True)
    uc=module('kbl_driver_unicorn',HERE/'test_driver_unicorn.py')
    emulation=uc.run(artifact['target'],a.build_dir/'demo/program.kbl',a.report_dir/'unicorn.json')
    usb_uc=module('kbl_driver_usb_unicorn',HERE/'test_driver_usb_unicorn.py')
    usb_emulation=usb_uc.run(artifact['target'],a.build_dir/'demo/program.kbl',a.report_dir/'usb-unicorn.json')
    files=list(builder.SOURCES)+[HERE.parent/'kbl_efi_types.h',HERE.parent/'kbl_service.h',HERE.parent/'kbl_partition.h',
             RUNTIME/'kbl_api.h',RUNTIME/'kbl_fetch.h',RUNTIME/'kbl_elf.h',RUNTIME/'kbl_sha256.h',RUNTIME/'kbl_block.h',RUNTIME/'kbl_file.h',RUNTIME/'native/kbl_vfs.inc',
             RUNTIME/'native/kbl_native.h',RUNTIME/'sdk/kbl_program.h',HERE.parent/'kbl_usb.h',HERE.parent/'kbl_usb_native.h',HERE.parent/'kbl_dxe_memory.h',HERE.parent/'kbl_arch_memory.h',HERE/'test_driver.c',HERE/'test_driver_unicorn.py',HERE/'test_driver_usb_unicorn.py',Path(__file__),HERE.parent/'build_driver.py']
    report={'scope':'Host FakeEFI/fake-native failure model and actual ARM64 PE execution with FakeEFI/link providers. No phone, USB DMA, parent-shim, physical EL, boot guarantee or isolation claim.',
            'passed':True,'host':host,'arm64_emulator_cases':emulation['cases'],'arm64_native_usb_cases':usb_emulation['cases'],'artifact':artifact,
            'sources_sha256':{str(s.relative_to(SOURCE)):hashlib.sha256(s.read_bytes()).hexdigest()for s in files},
            'host_command':command,'limitations':['Custom ELF requires native MemoryAttribute or a supported PI DXE GCD permission provider; no RWX fallback.',
            'Raw UEFI environment getter is absent by default; arbitrary firmware callbacks are not brokered.',
            'kbloff cleans environment, readers and tracked allocations; it does not itself remove the retained driver image.',
            'Binary fetch requires parent-owned synchronous/copying DMA transport and reliable quiescent query; unbound parent integration remains separate.']}
    (a.report_dir/'validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':True,'host_cases':host['cases'],'host_assertions':host['assertions'],
                      'actual_arm64_pe_cases':emulation['cases'],'actual_arm64_native_usb_cases':usb_emulation['cases'],'driver_sha256':artifact['sha256'],
                      'report':str(a.report_dir/'validation.json')},indent=2))
if __name__=='__main__':main()
