"""Compile ordinary C main against the documented freestanding KBL C subset."""
from pathlib import Path
import argparse,hashlib,json,subprocess
SDK=Path(__file__).resolve().parent
RUNTIME=SDK.parent
COMPAT=RUNTIME/'compat'
DEFAULT_LLVM=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin')

def build(source,output,llvm=DEFAULT_LLVM,require_files=False,extra_sources=(),heap_bytes=65536):
    source=Path(source).resolve();out=Path(output).resolve();out.mkdir(parents=True,exist_ok=True)
    # AArch64 objects and pointer differences use the signed 64-bit address
    # range. Actual reservation is made by the loader, not a builder budget.
    if not isinstance(heap_bytes,int) or heap_bytes<4096 or heap_bytes>(1<<63)-1:
        raise ValueError('Private heap must be at least 4096 bytes and fit an AArch64 object')
    tool=lambda name:llvm/(name+'.exe'if(llvm/(name+'.exe')).exists()else name)
    inputs=[COMPAT/'entry.c',COMPAT/'core.c',COMPAT/'io.c',COMPAT/'format.c',source,*map(Path,extra_sources)]
    commands=[];objects=[]
    for index,path in enumerate(inputs):
        obj=out/f'{index:02d}-{path.stem}.o';objects.append(obj)
        cmd=[tool('clang'),'--target=aarch64-none-elf','-std=c11','-O2','-ffreestanding','-fno-builtin',
             '-fno-stack-protector','-fPIC','-fvisibility=hidden','-mgeneral-regs-only','-ffixed-x18',
             '-fno-unwind-tables','-fno-asynchronous-unwind-tables','-Wall','-Wextra','-Werror',
             '-I',COMPAT/'include','-I',SDK,'-I',RUNTIME,f'-DKBL_COMPAT_HEAP_BYTES={heap_bytes}']
        if require_files:cmd+=['-DKBL_PORT_REQUIRED_BYTES=KBL_PROGRAM_API_FILE_BYTES']
        commands.append([str(x)for x in cmd+['-c',path,'-o',obj]])
    target=out/'program.kbl'
    commands.append([str(x)for x in [tool('ld.lld'),'-shared','-Bsymbolic','--no-undefined','-z','max-page-size=4096',
                                    '-z','norelro','-T',SDK/'kbl.ld','-e','kbl_main','-o',target,*objects]])
    for command in commands:
        result=subprocess.run(command,capture_output=True,text=True)
        if result.returncode:raise RuntimeError(result.stdout+result.stderr)
    from elftools.elf.elffile import ELFFile
    with target.open('rb')as stream:
        elf=ELFFile(stream)
        assert elf['e_type']=='ET_DYN'and elf['e_machine']=='EM_AARCH64'
        loads=[dict(va=s['p_vaddr'],bytes=s['p_memsz'],flags=s['p_flags'])for s in elf.iter_segments()if s['p_type']=='PT_LOAD']
        assert all(s['flags']&6!=6 or not(s['flags']&1)for s in loads)
        assert all(not(s['flags']&2 and s['flags']&1)for s in loads)
        relocations=[{'type':r['r_info_type'],'symbol':r['r_info_sym'],'offset':r['r_offset']}for s in elf.iter_sections()if s['sh_type']=='SHT_RELA'for r in s.iter_relocations()]
        assert all(r['type']==1027 and r['symbol']==0 for r in relocations)
    raw=target.read_bytes()
    report={'scope':'Ordinary C main linked with independently implemented KBL C subset; no Linux syscall ABI, no EL change',
            'source':str(source),'program':str(target),'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),
            'requiredApiBytes':136 if require_files else 56,'privateHeapBytes':heap_bytes,'commands':commands,'loads':loads,'relocations':relocations,
            'sourceSha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest()for p in inputs}}
    (out/'manifest.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    return report
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,default=RUNTIME/'examples/port_text.c')
    p.add_argument('--output',type=Path,default=RUNTIME.parent/'build/kbl-port');p.add_argument('--llvm-bin',type=Path,default=DEFAULT_LLVM)
    p.add_argument('--require-files',action='store_true');p.add_argument('--extra-source',type=Path,action='append',default=[])
    p.add_argument('--heap-bytes',type=int,default=65536)
    a=p.parse_args();report=build(a.source,a.output,a.llvm_bin,a.require_files,a.extra_source,a.heap_bytes)
    print(json.dumps({k:report[k]for k in ['program','bytes','sha256','requiredApiBytes']},indent=2))
if __name__=='__main__':main()
