"""Offline native ADB USB tests: real C transport and actual Qualcomm parser.

All controller, DMA and boot-service actions are explicit models. No device,
USB port, driver installation, firmware write or networking is performed.
"""
from pathlib import Path
import argparse,ctypes,hashlib,json,re,shutil,struct,subprocess
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE,UC_PROT_ALL
import unicorn.arm64_const as a
import pefile
HERE=Path(__file__).resolve().parent;UEFI=HERE.parent;SOURCE=UEFI.parents[1];ROOT=SOURCE.parents[2]
LLVM=Path('D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin')
def run(cmd):
    r=subprocess.run(list(map(str,cmd)),capture_output=True,text=True)
    if r.returncode:raise RuntimeError(r.stdout+r.stderr)
    return r.stdout
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load_elf(path,base):
    cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM);symbols={}
    with path.open('rb')as f:
        elf=ELFFile(f);segs=[s for s in elf.iter_segments()if s['p_type']=='PT_LOAD'];end=max(s['p_vaddr']+s['p_memsz']for s in segs)
        cpu.mem_map(base,(end+4095)&~4095,UC_PROT_ALL)
        for s in segs:cpu.mem_write(base+s['p_vaddr'],s.data())
        for section in elf.iter_sections():
            if section['sh_type']=='SHT_RELA':
                for rel in section.iter_relocations():
                    assert rel['r_info_type']==1027 and rel['r_info_sym']==0
                    cpu.mem_write(base+rel['r_offset'],struct.pack('<Q',base+rel['r_addend']))
            if section['sh_type']in ['SHT_DYNSYM','SHT_SYMTAB']:
                for s in section.iter_symbols():
                    if s['st_value']:symbols[s.name]=base+s['st_value']
    return cpu,symbols
def call(cpu,pc,args=()):
    stack=0x40000000;stop=0x41000000
    if not getattr(cpu,'_kbl_mapped',False):cpu.mem_map(stack,0x20000);cpu.mem_map(stop,4096);cpu._kbl_mapped=True
    cpu.reg_write(a.UC_ARM64_REG_SP,stack+0x1f000);cpu.reg_write(a.UC_ARM64_REG_X30,stop)
    cpu.reg_write(a.UC_ARM64_REG_PSTATE,0x3c5);cpu.reg_write(a.UC_ARM64_REG_CPACR_EL1,0);cpu.reg_write(a.UC_ARM64_REG_X18,0x187654321)
    for i,n in enumerate(args):cpu.reg_write(getattr(a,f'UC_ARM64_REG_X{i}'),n)
    cpu.emu_start(pc,stop,count=50000000)
    assert cpu.reg_read(a.UC_ARM64_REG_PC)==stop,hex(cpu.reg_read(a.UC_ARM64_REG_PC))
    assert cpu.reg_read(a.UC_ARM64_REG_X18)==0x187654321
    return cpu.reg_read(a.UC_ARM64_REG_X0)
def provider_parser(device,config,ss):
    path=ROOT/'analysis/kbl-runtime-20261029/usb-current-provider/uefi-106484-206324.pe'
    assert sha(path)=='25d75b4c33cc93f93e8049924e5733a82840a98ed1b171a74b38a36551ab41d7'
    pe=pefile.PE(str(path));cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM);base=0x10000000
    cpu.mem_map(base,0x10000,UC_PROT_ALL);cpu.mem_write(base,pe.get_memory_mapped_image())
    data=0x20000000;cpu.mem_map(data,0x200000,UC_PROT_ALL);dev=data;cfg=data+0x100;arr=data+0x1000;out=data+0x1100
    cpu.mem_write(dev,device);cpu.mem_write(cfg,config);cpu.mem_write(arr,struct.pack('<Q',cfg))
    boot=data+0x2000;copy_hook=data+0x3000;cpu.mem_write(base+0xb4a8,struct.pack('<Q',boot));cpu.mem_write(boot+0x160,struct.pack('<Q',copy_hook))
    state={'cursor':data+0x10000,'allocations':0,'copies':0,'debug':0}
    def callback(uc,pc,size,_):
        def ret(value=0):uc.reg_write(a.UC_ARM64_REG_X0,value);uc.reg_write(a.UC_ARM64_REG_PC,uc.reg_read(a.UC_ARM64_REG_X30))
        if pc==base+0x4718:
            n=uc.reg_read(a.UC_ARM64_REG_X0);p=state['cursor'];state['cursor']=(p+n+15)&~15;state['allocations']+=1;assert state['cursor']<data+0x200000;ret(p)
        elif pc==copy_hook:
            p,q,n=[uc.reg_read(getattr(a,f'UC_ARM64_REG_X{i}'))for i in range(3)];uc.mem_write(p,bytes(uc.mem_read(q,n)));state['copies']+=1;ret()
        elif pc==base+0x47f0:state['debug']+=1;ret()
        elif pc==base+0x4784:ret()
    for p in [base+0x4718,copy_hook,base+0x47f0,base+0x4784]:cpu.hook_add(UC_HOOK_CODE,callback,begin=p,end=p)
    rc=call(cpu,base+0x40d0,[dev,arr,out]);assert rc==0,(hex(rc),state)
    table=struct.unpack('<Q',cpu.mem_read(out,8))[0];cfginfo=struct.unpack('<Q',cpu.mem_read(table,8))[0]
    cfgcopy,interfaces=struct.unpack('<QQ',cpu.mem_read(cfginfo,16));assert bytes(cpu.mem_read(cfgcopy,9))==config[:9]
    parsed=[]
    for i in range(config[4]):
        ptr=struct.unpack('<Q',cpu.mem_read(interfaces+i*8,8))[0];desc,eps=struct.unpack('<QQ',cpu.mem_read(ptr,16));raw=bytes(cpu.mem_read(desc,9));addresses=[]
        for j in range(raw[4]):
            ep=struct.unpack('<Q',cpu.mem_read(eps+j*8,8))[0]
            if ss:ep,comp=struct.unpack('<QQ',cpu.mem_read(ep,16));assert bytes(cpu.mem_read(comp,2))==b'\x06\x30'
            addresses.append(bytes(cpu.mem_read(ep,7))[2])
        parsed.append({'interface':raw[2],'class':raw[5:8].hex(),'endpoints':addresses})
    assert parsed==[{'interface':0,'class':'ff4203','endpoints':[1,129]},{'interface':1,'class':'ff4201','endpoints':[2,130]}],parsed
    return {'passed':True,'superSpeed':ss,'providerSha256':sha(path),'entryRva':'0x40d0','interfaces':parsed,**state,
            'limits':'Actual firmware ARM64 parser, modeled AllocatePool/CopyMem/Debug/FreePool; controller and host enumeration not executed.'}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,default=ROOT/'analysis/kbl-usb-enumeration-20261030/usb-tests');args=p.parse_args();out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    sources=[UEFI/'kbl_adb_usb.c',HERE/'test_adb_usb.c',UEFI.parent/'adb/kbl_adb.c'];dll=out/'test_adb_usb.dll'
    commands=[[shutil.which('clang')or'clang','-std=c11','-O2','-fno-builtin','-Wall','-Wextra','-Werror','-shared',*sources,'-o',dll]];run(commands[-1]);d=ctypes.CDLL(str(dll))
    rc=d.kbl_adb_usb_test_run();assert rc==0,f'host test failed line {rc}';host_checks=d.kbl_adb_usb_test_checks()
    class DescriptorSet(ctypes.Structure):
        _fields_=[(n,ctypes.c_void_p)for n in ['device','configs','ss_device','ss_configs','qualifier','bos']]+[('count',ctypes.c_ubyte),('strings',ctypes.c_void_p)]
    d.kbl_adb_usb_test_descriptors.restype=ctypes.c_void_p;ds=DescriptorSet.from_address(d.kbl_adb_usb_test_descriptors());descriptor_json={}
    for name,size in [('device',18),('ss_device',18),('qualifier',10),('bos',None)]:
        ptr=getattr(ds,name);n=size if size else int.from_bytes(ctypes.string_at(ptr+2,2),'little');descriptor_json[name]=ctypes.string_at(ptr,n).hex()
    for name,count in [('configs',1),('ss_configs',1),('strings',ds.count)]:
        pointers=(ctypes.c_void_p*count).from_address(getattr(ds,name));values=[]
        for ptr in pointers:
            n=ctypes.string_at(ptr,1)[0]if name=='strings'else int.from_bytes(ctypes.string_at(ptr+2,2),'little');values.append(ctypes.string_at(ptr,n).hex())
        descriptor_json[name]=values
    (out/'composite-descriptors.json').write_text(json.dumps(descriptor_json,indent=2)+'\n')
    override=out/'test_adb_usb_pid_override.dll';commands.append([shutil.which('clang')or'clang','-std=c11','-O2','-fno-builtin','-Wall','-Wextra','-Werror','-shared','-DKBL_ADB_USB_DEBUG_PID=0xd0d2u',*sources,'-o',override]);run(commands[-1])
    custom=ctypes.CDLL(str(override));assert custom.kbl_adb_usb_test_run()==0;override_checks=custom.kbl_adb_usb_test_checks();assert override_checks==host_checks
    fixtures=[]
    for ss in [0,1]:
        d.kbl_adb_usb_test_config.restype=ctypes.c_void_p;d.kbl_adb_usb_test_device.restype=ctypes.c_void_p
        c=d.kbl_adb_usb_test_config(ss);length=int.from_bytes(ctypes.string_at(c+2,2),'little');config=ctypes.string_at(c,length);device=ctypes.string_at(d.kbl_adb_usb_test_device(ss),18)
        (out/f'composite-{ss}-device.bin').write_bytes(device);(out/f'composite-{ss}-config.bin').write_bytes(config);fixtures.append((device,config,bool(ss)))
    elf=out/'test_adb_usb.elf';commands.append([LLVM/'clang.exe','--target=aarch64-none-elf','-std=c11','-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-fPIC','-ffixed-x18','-mgeneral-regs-only','-Wall','-Wextra','-Werror','-fuse-ld=lld','-nostdlib','-Wl,-shared,-Bsymbolic,--no-undefined',*sources,'-o',elf]);run(commands[-1])
    disassembly=run([LLVM/'llvm-objdump.exe','-d',elf]);assert not re.search(r'\b(?:svc|hvc|smc|eret|fmov|fadd|fsub|fmul|fdiv)\b',disassembly)
    rc=d.kbl_adb_usb_core_test_run();assert rc==0,f'host core+USB integration failed line {rc}';host_core_checks=d.kbl_adb_usb_test_checks()
    rc=d.kbl_adb_usb_legacy_test_run();assert rc==0,f'host legacy Windows packet contract failed line {rc}';host_legacy_checks=d.kbl_adb_usb_test_checks();cases=[]
    for base in [0x10000000,0x18000000]:
        cpu,syms=load_elf(elf,base);rc=call(cpu,syms['kbl_adb_usb_test_run']);assert rc==0,f'ARM64 test failure line {rc}'
        n=call(cpu,syms['kbl_adb_usb_test_checks']);assert n==host_checks;rc=call(cpu,syms['kbl_adb_usb_core_test_run']);assert rc==0,f'ARM64 core+USB integration failed line {rc}'
        cn=call(cpu,syms['kbl_adb_usb_test_checks']);assert cn==host_core_checks
        rc=call(cpu,syms['kbl_adb_usb_legacy_test_run']);assert rc==0,f'ARM64 legacy packet contract failed line {rc}'
        ln=call(cpu,syms['kbl_adb_usb_test_checks']);assert ln==host_legacy_checks;cases.append({'base':hex(base),'checks':n,'coreUsbChecks':cn,'legacyWindowsChecks':ln,'passed':True})
    coff=out/'usb-windows.obj';commands.append([LLVM/'clang.exe','--target=aarch64-pc-windows-msvc','-std=c11','-O2','-ffreestanding','-fno-builtin','-fno-stack-protector','-ffixed-x18','-mgeneral-regs-only','-Wall','-Wextra','-Werror','-c',sources[0],'-o',coff]);run(commands[-1]);undefined=run([LLVM/'llvm-nm.exe','--undefined-only',coff]).strip();assert not undefined,undefined
    report={'passed':True,'scope':__doc__,'hostChecks':host_checks,'hostCoreUsbChecks':host_core_checks,'hostLegacyWindowsChecks':host_legacy_checks,'hostCustomPidChecks':override_checks,'windowsArm64Undefined':undefined,'actualArm64ModelCases':cases,'actualProviderParser':[provider_parser(*f)for f in fixtures],
            'sourceSha256':{str(f.relative_to(SOURCE)):sha(f)for f in sources+[UEFI/'kbl_adb_usb.h',Path(__file__)]},'arm64Sha256':sha(elf),
            'commands':[[str(c)for c in cmd]for cmd in commands],
            'remaining':['Root postACK shared UsbfnIo.EventHandler hook/fence integration','Real controller EP2 capacity and ownership','Physical re-enumeration and Windows composite WinUSB binding','Physical Fastboot continuity after ADB kbl off']}
    (out/'validation.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'passed':True,'hostChecks':host_checks,'arm64Cases':len(cases),'providerParserCases':2,'report':str(out/'validation.json')},indent=2))
if __name__=='__main__':main()
