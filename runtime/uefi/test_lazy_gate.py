"""Execute emitted exact-command AArch64 gates with relocated synthetic PE fixtures.
The original unknown-edge and native callbacks are explicit models. No phone,
firmware write or real UEFI protocol is involved.
"""
from pathlib import Path
import argparse, hashlib, json
import pefile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE, UC_HOOK_MEM_READ
from unicorn import arm64_const as a

SOURCE=Path(__file__).resolve().parents[2]
BASE=0x10000000; COMMAND=0x30000000; STACK=0x40000000; STOP=0x50000000
REGS=[getattr(a,"UC_ARM64_REG_X%d"%i) for i in range(31)]
NAMES={"oem kblstart":1,"oem kbladb":17,"oem screenoff":18,"oem screenon":19,"oem screenstatus":20}

def execute(path, facts, wire, expected, guard=False):
    image=pefile.PE(data=path.read_bytes())
    mapped=image.get_memory_mapped_image()
    u=Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    u.mem_map(BASE,(len(mapped)+4095)&~4095); u.mem_write(BASE,mapped)
    for address,size in [(COMMAND,4096),(STACK,65536),(STOP,4096)]:u.mem_map(address,size)
    at=COMMAND+4096-len(wire) if guard else COMMAND
    u.mem_write(at,wire)
    before=[0x5100000000000000+i*4096 for i in range(31)]
    before[facts["commandRegister"]]=at; before[30]=STOP
    for reg,value in zip(REGS,before):u.reg_write(reg,value)
    u.reg_write(a.UC_ARM64_REG_SP,STACK+0xf000);u.reg_write(a.UC_ARM64_REG_NZCV,0xa0000000)
    calls=[];ended=[];writes=[];reads=[]
    def memory_write(cpu,access,address,size,value,user):
        writes.append((address,size)); assert STACK<=address and address+size<=STACK+65536
    def memory_read(cpu,access,address,size,value,user):
        if COMMAND<=address<COMMAND+4096: reads.append((address-at,size))
    def hook(cpu,address,size,user):
        if address==BASE+facts["moduleLazy"]:
            op=cpu.reg_read(a.UC_ARM64_REG_W4)
            assert expected is not None and op==expected,(wire,op,expected)
            assert cpu.reg_read(a.UC_ARM64_REG_X1)==BASE
            assert cpu.mem_read(cpu.reg_read(a.UC_ARM64_REG_X3),1)==b"\0"
            calls.append(op);ret=cpu.reg_read(a.UC_ARM64_REG_LR)
            # Treat every caller register and flags as clobbered to verify the
            # gate's preserved context independently of a friendly callback.
            for i,reg in enumerate(REGS):cpu.reg_write(reg,0x6200000000000000+i)
            cpu.reg_write(a.UC_ARM64_REG_NZCV,0x50000000)
            cpu.reg_write(a.UC_ARM64_REG_PC,ret);return
        if address in [BASE+facts["fail"],BASE+facts["continue"]]:
            ended.append("continue" if address==BASE+facts["continue"] else "fail")
            assert [cpu.reg_read(r)for r in REGS]==before,wire
            assert cpu.reg_read(a.UC_ARM64_REG_SP)==STACK+0xf000
            assert cpu.reg_read(a.UC_ARM64_REG_NZCV)==0xa0000000
            cpu.emu_stop()
    u.hook_add(UC_HOOK_CODE,hook);u.hook_add(UC_HOOK_MEM_WRITE,memory_write);u.hook_add(UC_HOOK_MEM_READ,memory_read)
    u.emu_start(BASE+facts["hook"],STOP,count=10000)
    assert ended==["continue" if expected is not None else "fail"],(wire,ended)
    assert calls==([] if expected is None else [expected])
    assert writes and all(0<=offset and offset+size<=wire.index(b"\0")+1 for offset,size in reads)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--fixtures",type=Path,default=SOURCE/"abl-efisp-core/build/kbl-gate-fixtures")
    p.add_argument("--report",type=Path,default=SOURCE/"build/kbl-lazy-gate-validation.json")
    args=p.parse_args(); total=0;fixtures=[]
    for fixture in sorted(args.fixtures.glob("*.json")):
        facts=json.loads(fixture.read_text()); image=fixture.with_suffix(".img");assert image.is_file()
        cases=[]
        for name,op in NAMES.items():
            cases.extend([(name.encode()+b"\0",op,False),(name.encode()+b"\0",op,True),
                          (name.encode()+b"\0ignored",op,False)])
            for malformed in [name[:-1],name+" ",name+" extra",name+"x"," "+name,name.upper(),name.replace(" ","\t",1)]:
                cases.append((malformed.encode()+b"\0",None,False))
        for other in ["oem kblstatus","oem kbloff","getvar:current-slot","flash:abl_a","oem superunlock","o","", "oem screen"]:
            cases.append((other.encode()+b"\0",None,True))
        for wire,expected,guard in cases:execute(image,facts,wire,expected,guard);total+=1
        fixtures.append({"name":fixture.stem,"cases":len(cases),"sha256":hashlib.sha256(image.read_bytes()).hexdigest(),"codeRva":facts["baseCode"]})
    assert len(fixtures)>=2
    result={"scope":"Actual emitted AArch64 gate on base+relocated synthetic PE; modeled native fail/continue/helper; no device", "passed":True,"cases":total,"fixtures":fixtures,"verified":["exact five command operations and no auto-start for lookalikes","byte reads bounded through first NUL including end-of-page strings","all GPR/SP/NZCV preserved","nonmatches return original native FAIL edge","only stack writes before model callback"]}
    args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps(result,indent=2))
if __name__=="__main__":main()
