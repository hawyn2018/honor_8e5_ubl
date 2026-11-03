"""Build a relocatable ARM64 EFI boot-service driver; no device deployment."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import os

HERE=Path(__file__).resolve().parent
RUNTIME=HERE.parent
SOURCES=[RUNTIME/'kbl_control.c',RUNTIME/'kbl_fetch.c',RUNTIME/'kbl_elf.c',
         RUNTIME/'kbl_sha256.c',HERE/'kbl_partition.c',HERE/'kbl_usb.c',HERE/'kbl_usb_native.c',
         HERE/'kbl_adb_usb.c',RUNTIME/'adb/kbl_adb.c',HERE/'kbl_dxe_memory.c',HERE/'kbl_arch_memory.c',HERE/'kbl_display.c',RUNTIME/'native/kbl_native.c',
         RUNTIME/'native/kbl_context_switch.S',RUNTIME/'native/kbl_efi_bootloader.c',HERE/'kbl_efi_run.c',HERE/'kbl_driver.c']
THIRD=RUNTIME.parent/'third_party'
FAT_SOURCES=[RUNTIME/'fs/fat.c',THIRD/'fatfs/ff.c',THIRD/'fatfs/ffunicode.c']
EXT_SOURCES=[RUNTIME/'fs/kbl_ext4.c',*[s for s in sorted((THIRD/'lwext4/src').glob('*.c'))
            if s.name not in ('ext4_mbr.c','ext4_mkfs.c','ext4_debug.c')]]
ER_SOURCES=[RUNTIME/'fs/kbl_erofs.c',*[THIRD/'erofs-utils/lib'/n for n in
            ('data.c','namei.c','zmap.c','decompress.c','dir.c')],THIRD/'lz4/lz4.c']
SOURCES += [RUNTIME/'fs/kbl_fs.c',RUNTIME/'fs/f2fs.c',RUNTIME/'fs/f2fs_rw.c',RUNTIME/'fs/f2fs_rw_core.c',*FAT_SOURCES,*EXT_SOURCES,*ER_SOURCES]

def compile_flags(source):
    cmd=['--target=aarch64-pc-windows-msvc','-O2','-ffreestanding',
         '-fno-builtin','-fno-stack-protector','-mno-stack-arg-probe','-ffixed-x18','-mno-outline-atomics',
         '-Wall','-Wextra','-Werror','-DKBL_DRIVER_FREESTANDING']
    if source.suffix=='.c':cmd+=['-std=c11','-mgeneral-regs-only']
    if source in FAT_SOURCES:
        cmd+=['-I',RUNTIME/'compat/include']
    if source in EXT_SOURCES:
        cmd+=['-I',RUNTIME/'fs/ext4_port/include','-I',THIRD/'lwext4/include',
              '-include',RUNTIME/'fs/ext4_port/include/kbl_ext4_config.h',
              '-Wno-unused-function','-Wno-unused-but-set-variable','-Wno-unused-parameter']
    if source in ER_SOURCES:
        cmd+=['-std=gnu11','-I',RUNTIME/'fs/erofs_port/include','-I',THIRD/'erofs-utils/include',
              '-I',THIRD/'lz4','-include','config.h','-Wno-unused-parameter',
              '-Wno-unused-variable','-Wno-unused-but-set-variable','-Wno-sign-compare']
        if source.name=='lz4.c':
            cmd+=['-include',RUNTIME/'fs/erofs_port/include/string.h','-DLZ4_FREESTANDING=1',
                  '-DLZ4_memcpy=kbl_erofs_memcpy','-DLZ4_memmove=kbl_erofs_memmove','-DLZ4_memset=kbl_erofs_memset']
    return cmd

def build(llvm_bin,output):
    output=output.resolve();output.mkdir(parents=True,exist_ok=True)
    tool=lambda n:llvm_bin/(n+'.exe' if (llvm_bin/(n+'.exe')).exists() else n)
    commands=[];objects=[]
    for source in SOURCES:
        # Vendored libraries contain common stems (data.c, etc.). Keep object
        # names unique without changing their C symbol namespace.
        obj=output/(str(source.relative_to(RUNTIME.parent)).replace('\\','_').replace('/','_')+'.obj');objects.append(obj)
        cmd=[tool('clang'),*compile_flags(source)]
        cmd+=['-c',source,'-o',obj];commands.append(cmd)
    target=output/'kbl_driver.efi'
    commands.append([tool('ld.lld'),'-flavor','link','/subsystem:efi_boot_service_driver',
                     '/entry:EfiMain','/nodefaultlib','/machine:arm64','/dynamicbase','/nxcompat',
                     '/opt:ref','/opt:icf','/timestamp:0',f'/out:{target}',*objects])
    for cmd in commands:subprocess.run([str(x)for x in cmd],check=True)
    import pefile
    pe=pefile.PE(str(target));relocations=[]
    for block in getattr(pe,'DIRECTORY_ENTRY_BASERELOC',[]):
        relocations.extend({'rva':e.rva,'type':e.type}for e in block.entries if e.type)
    sections=[{'name':s.Name.rstrip(b'\0').decode(),'rva':s.VirtualAddress,
               'virtual_bytes':s.Misc_VirtualSize,'raw_bytes':s.SizeOfRawData,
               'read':bool(s.Characteristics&0x40000000),'write':bool(s.Characteristics&0x80000000),
               'execute':bool(s.Characteristics&0x20000000)}for s in pe.sections]
    assert pe.FILE_HEADER.Machine==0xaa64 and pe.OPTIONAL_HEADER.Subsystem==11
    assert relocations and all(r['type']==10 for r in relocations)
    assert not getattr(pe,'DIRECTORY_ENTRY_IMPORT',[])
    assert all(not(s['write']and s['execute'])for s in sections)
    assert any(s['execute']and s['rva']<=pe.OPTIONAL_HEADER.AddressOfEntryPoint<s['rva']+s['virtual_bytes']for s in sections)
    data=target.read_bytes()
    result={'scope':'Actual ARM64 PE/COFF EFI_BOOT_SERVICE_DRIVER; parent source/USB binding and hardware validation remain separate',
            'target':str(target),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),
            'machine':hex(pe.FILE_HEADER.Machine),'subsystem':pe.OPTIONAL_HEADER.Subsystem,
            'image_base':pe.OPTIONAL_HEADER.ImageBase,'entry_rva':pe.OPTIONAL_HEADER.AddressOfEntryPoint,
            'size_of_image':pe.OPTIONAL_HEADER.SizeOfImage,'imports':0,'relocations':relocations,'sections':sections,
            'source_sha256':{os.path.relpath(s,RUNTIME):hashlib.sha256(s.read_bytes()).hexdigest()for s in SOURCES},
            'dependencies_sha256':{os.path.relpath(s,RUNTIME):hashlib.sha256(s.read_bytes()).hexdigest()for s in
                [*sorted(RUNTIME.glob('*.h')),*sorted((RUNTIME/'uefi').glob('*.h')),*sorted((RUNTIME/'native').glob('*.h')),
                 *sorted((RUNTIME/'native').glob('*.inc')),*sorted((RUNTIME/'adb').glob('*.h')),
                 *sorted((THIRD).rglob('*.inc')),
                 *sorted((RUNTIME/'fs').rglob('*.h')),*sorted((THIRD).rglob('*.h')),
                 HERE/'kbl_adb_driver.inc',RUNTIME/'sdk/kbl_program.h',RUNTIME/'sdk/kbl_file.h']if s.exists()},
            'commands':[[str(x)for x in cmd]for cmd in commands]}
    (output/'manifest.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--llvm-bin',type=Path,default=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin'))
    p.add_argument('--output',type=Path,default=RUNTIME.parent/'build/kbl-driver')
    a=p.parse_args();r=build(a.llvm_bin,a.output)
    print(json.dumps({k:r[k]for k in('target','bytes','sha256','machine','subsystem','imports')},indent=2))
if __name__=='__main__':main()
