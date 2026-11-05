"""Small offline semantic-matcher diagnostic for extracted PE fixtures."""
from pathlib import Path
import struct,sys
import capstone
def inspect(path):
    d=path.read_bytes();p=struct.unpack_from('<I',d,60)[0];s=p+24+struct.unpack_from('<H',d,p+20)[0]
    sections=[struct.unpack_from('<IIII',d,s+i*40+8) for i in range(struct.unpack_from('<H',d,p+6)[0])]
    md=capstone.Cs(capstone.CS_ARCH_ARM64,capstone.CS_MODE_ARM);md.detail=True
    instructions={}
    for v,r,n,f in sections:
        if struct.unpack_from('<I',d,s+sections.index((v,r,n,f))*40+36)[0]&0x20000000:
            instructions.update({i.address:i for i in md.disasm(d[f:f+n],r)})
    def text(addr):
        for v,r,n,f in sections:
            if r<=addr<r+n:return d[f+addr-r:f+addr-r+300].split(b'\0')[0].decode('ascii','replace')
        return ''
    refs=[]
    for pc,i in instructions.items():
        if i.mnemonic!='adrp':continue
        reg=i.operands[0].reg;page=i.operands[1].imm
        for n in range(1,5):
            j=instructions.get(pc+n*4)
            if j is None:break
            if j.mnemonic=='add' and j.operands[1].type==capstone.CS_OP_REG and j.operands[1].reg==reg and j.operands[2].type==capstone.CS_OP_IMM:
                refs.append((pc,page+j.operands[2].imm,text(page+j.operands[2].imm)));break
            if j.mnemonic in ('b','bl','adrp'):break
    result=[str(path)]
    for pc,addr,t in refs:
        if any(x in t for x in ['Find TVM -','qcom,board-id','BoardPlatformHlossubtype']):
            result.append(f'REF {pc:x} -> {addr:x} {t}')
            if 'Find TVM -' in t:
                result.extend(f'{q:08x}: {instructions[q].mnemonic:8s} {instructions[q].op_str}' for q in range(pc-0x380,pc+24,4) if q in instructions)
    out=path.with_suffix('.vm.txt');out.write_text('\n'.join(result),encoding='utf-8');print(out)
if __name__=='__main__':
    for p in sys.argv[1:]:inspect(Path(p))
