"""Actual ARM64 display and ResetSystem code; protocol callbacks are local C mocks.
Never connects to a device and never executes a physical shutdown.
"""
from pathlib import Path
import argparse,json,subprocess
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM
from unicorn.arm64_const import UC_ARM64_REG_SP,UC_ARM64_REG_X30,UC_ARM64_REG_X0,UC_ARM64_REG_PSTATE,UC_ARM64_REG_CPACR_EL1
HERE=Path(__file__).resolve().parent
LLVM=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin')
def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=HERE.parents[2]/'build/display-test');a=p.parse_args();out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
 objects=[]
 for i,src in enumerate([HERE.parent/'kbl_display.c',HERE/'display_fixture.c']):
  obj=out/f'{i}.o';objects.append(obj)
  subprocess.run([str(LLVM/'clang.exe'),'--target=aarch64-none-elf','-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-ffixed-x18','-mgeneral-regs-only','-fno-unwind-tables','-fno-asynchronous-unwind-tables','-Wall','-Wextra','-Werror','-c',str(src),'-o',str(obj)],check=True)
 ld=out/'test.ld';ld.write_text('ENTRY(display_test_entry)\nSECTIONS { . = 0x10000000; .text : { *(.text*) } .rodata : { *(.rodata*) } .data : { *(.data*) } .bss : { *(.bss*) *(COMMON) } /DISCARD/ : { *(.comment) *(.note*) } }\n')
 elf=out/'display.elf';subprocess.run([str(LLVM/'ld.lld.exe'),'-T',str(ld),'--no-undefined','-o',str(elf),*map(str,objects)],check=True)
 results=[]
 for el in [1,2]:
  cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
  with elf.open('rb') as f:
   image=ELFFile(f);loads=[s for s in image.iter_segments()if s['p_type']=='PT_LOAD'];start=min(s['p_vaddr']for s in loads)&~4095;end=max(s['p_vaddr']+s['p_memsz']for s in loads)
   cpu.mem_map(start,(end-start+4095)&~4095)
   for s in loads:cpu.mem_write(s['p_vaddr'],s.data())
   symbols={s.name:s['st_value']for s in image.get_section_by_name('.symtab').iter_symbols()};entry=image['e_entry']
  cpu.mem_map(0x40000000,0x10000);cpu.mem_map(0x41000000,4096);cpu.reg_write(UC_ARM64_REG_SP,0x4000f000);cpu.reg_write(UC_ARM64_REG_X30,0x41000000)
  cpu.reg_write(UC_ARM64_REG_PSTATE,0x3c0|(el<<2)|1);cpu.reg_write(UC_ARM64_REG_CPACR_EL1,0)
  cpu.emu_start(entry,0x41000000,count=2000000);rc=cpu.reg_read(UC_ARM64_REG_X0);assert not rc,{'el':el,'failedLine':rc}
  checks=int.from_bytes(cpu.mem_read(symbols['checks'],4),'little')
  cpu.reg_write(UC_ARM64_REG_X30,0x41000000);cpu.emu_start(symbols['display_test_current_el'],0x41000000,count=100)
  observed=cpu.reg_read(UC_ARM64_REG_X0)
  results.append({'passed':True,'requestedEl':el,'observedEl':observed,'elRequestRealized':observed==el,'checks':checks,'physicalShutdown':False})
 report={'passed':True,'results':results,'scope':'actual ARM64 production display/reset ABI with local protocol mocks'};(out/'result.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
if __name__=='__main__':main()
