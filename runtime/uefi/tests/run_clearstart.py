"""Reproducible offline clearstart host ownership and actual ARM64 PE tests."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,sys
HERE=Path(__file__).resolve().parent;UEFI=HERE.parent;SOURCE=UEFI.parents[1];RUNTIME=UEFI.parent
def run(args):
    p=subprocess.run(list(map(str,args)),capture_output=True,text=True)
    if p.returncode:raise RuntimeError(p.stdout+p.stderr)
    return p.stdout
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--driver',type=Path);p.add_argument('--target',type=Path);a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    if not a.target:
        run([sys.executable,HERE/'run_efi.py','--output',out/'efi']);a.target=out/'efi/return.efi'
    if not a.driver:
        run([sys.executable,UEFI/'build_driver.py','--output',out/'driver']);a.driver=out/'driver/kbl_driver.efi'
    sources=[HERE/'test_clearstart.c',RUNTIME/'kbl_control.c',RUNTIME/'kbl_fetch.c',RUNTIME/'kbl_sha256.c',
        *[UEFI/n for n in ['kbl_partition.c','kbl_usb.c','kbl_usb_native.c','kbl_adb_usb.c','kbl_dxe_memory.c','kbl_arch_memory.c','kbl_efi_run.c','kbl_errorcount.c','kbl_display.c','kbl_handoff.c']],RUNTIME/'adb/kbl_adb.c']
    exe=out/'test_clearstart.exe';cmd=[shutil.which('clang')or'clang','-std=c11','-O2','-Wall','-Wextra','-Werror','-DKBL_HANDOFF_TEST',*sources,'-o',exe]
    run(cmd);host=json.loads(run([exe,a.target]));run([sys.executable,HERE/'test_clearstart_unicorn.py','--driver',a.driver,'--target',a.target,'--output',out])
    arm=json.loads((out/'arm64-clearstart.json').read_text());report={'passed':True,'host':host,'arm64':arm,'hostCommand':list(map(str,cmd)),
        'driverSha256':hashlib.sha256(a.driver.read_bytes()).hexdigest(),'physicalDeviceIO':False,
        'limitations':['Parent EFISP and driver mappings remain resident because outer firmware/event frames may still execute them.',
            'A child can take over boot and never return; driver does not preemptively call ExitBootServices.',
            'Firmware services, DMA and actual hardware acceptance remain modeled; no deployment or flashing performed.']}
    (out/'validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'passed':True,'hostCases':host['cases'],'hostAssertions':host['assertions'],'arm64Cases':len(arm['results'])+len(arm['failures']),'report':str(out/'validation.json')},indent=2))
if __name__=='__main__':main()
