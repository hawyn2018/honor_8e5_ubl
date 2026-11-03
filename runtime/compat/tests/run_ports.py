"""Validate the C subset on the host and execute actual linked ARM64 programs.

The ARM64 test loader and SDK callbacks are explicit Python models. Production
program code, libc glue, formatting, allocator and CurrentEL instructions execute.
This runner does not open USB or establish phone/UEFI storage behavior.
"""
from pathlib import Path
import argparse,hashlib,json,re,shutil,struct,subprocess,sys
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE,UC_PROT_READ,UC_PROT_WRITE,UC_PROT_EXEC
import unicorn.arm64_const as a
HERE=Path(__file__).resolve().parent;COMPAT=HERE.parent;RUNTIME=COMPAT.parent
NAMES='errno stdin stdout stderr memcpy memmove memset memcmp memchr strlen strnlen strcmp strncmp strcpy strncpy strcat strncat strchr strrchr strstr strdup strerror isspace isdigit isxdigit isalpha isalnum islower isupper isprint tolower toupper malloc free calloc realloc atoi atol strtol strtoul strtoll strtoull qsort bsearch abs labs llabs getenv setenv unsetenv system read write open lseek close fsync unlink stat mkdir fork execve usleep sleep remove printf fprintf vprintf vfprintf snprintf vsnprintf puts putchar fputs fputc fgetc fopen fclose fflush fread fwrite fseek ftell fseeko ftello rewind feof ferror clearerr fgets fileno perror'.split()
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def host(out,heap_bytes=65536):
    rename=out/'rename.h';rename.write_text('\n'.join(f'#define {n} kbl_test_{n}'for n in NAMES)+'\n')
    sources=[COMPAT/f'{n}.c'for n in ['core','io','format']]+[HERE/'host.c']
    command=[shutil.which('clang')or'clang','-std=c11','-O2','-fno-builtin','-Wall','-Wextra','-Werror',
             '-I',str(COMPAT/'include'),'-I',str(RUNTIME/'sdk'),'-include',str(rename),f'-DKBL_COMPAT_HEAP_BYTES={heap_bytes}',*map(str,sources),'-o',str(out/'host.exe')]
    r=subprocess.run(command,capture_output=True,text=True)
    if r.returncode:raise RuntimeError(r.stdout+r.stderr)
    r=subprocess.run([str(out/'host.exe')],capture_output=True,text=True)
    assert r.returncode==0,(r.returncode,r.stdout,r.stderr)
    checks=int(re.search(r'checks=(\d+)',r.stdout).group(1))
    return {'passed':True,'checks':checks,'stdout':r.stdout,'command':command,'privateHeapBytes':heap_bytes,'scope':'Host C subset and explicitly modeled SDK file callbacks; standard symbols renamed to avoid host CRT interposition'}
def program(out,name,files=False):
    extra=['--extra-source',str(RUNTIME/'kbl_sha256.c')]if name=='hash'else[]
    result=subprocess.run([sys.executable,str(RUNTIME/'sdk/build_port.py'),'--source',str(RUNTIME/f'examples/port_{name}.c'),'--output',str(out/name),*(['--require-files']if files else[]),*extra],capture_output=True,text=True)
    if result.returncode:raise RuntimeError(result.stdout+result.stderr)
    return out/name/'program.kbl'
def execute(path,argv,api_bytes=136,base=0x10000000,scenario='ok',expected_output=None):
    cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    with path.open('rb')as f:
        elf=ELFFile(f);segments=[s for s in elf.iter_segments()if s['p_type']=='PT_LOAD']
        end=max(s['p_vaddr']+s['p_memsz']for s in segments);mapped=(end+4095)&~4095
        cpu.mem_map(base,mapped,UC_PROT_READ|UC_PROT_WRITE)
        for s in segments:cpu.mem_write(base+s['p_vaddr'],s.data())
        for section in elf.iter_sections():
            if section['sh_type']=='SHT_RELA':
                for rel in section.iter_relocations():
                    assert rel['r_info_type']==1027 and rel['r_info_sym']==0
                    cpu.mem_write(base+rel['r_offset'],struct.pack('<Q',base+rel['r_addend']))
        for s in segments:
            lo=s['p_vaddr']&~4095;hi=(s['p_vaddr']+s['p_memsz']+4095)&~4095;fl=s['p_flags']
            cpu.mem_protect(base+lo,hi-lo,(UC_PROT_READ if fl&4 else 0)|(UC_PROT_WRITE if fl&2 else 0)|(UC_PROT_EXEC if fl&1 else 0))
        entry=base+elf['e_entry']
    stack=0x40000000;cpu.mem_map(stack,0x20000,UC_PROT_READ|UC_PROT_WRITE)
    stop=0x41000000;cpu.mem_map(stop,4096,UC_PROT_READ|UC_PROT_EXEC)
    data=0x33000000;cpu.mem_map(data,0x20000,UC_PROT_READ|UC_PROT_WRITE);cursor=data
    def put(raw):
        nonlocal cursor
        p=cursor;cpu.mem_write(p,raw);cursor=(cursor+len(raw)+7)&~7;return p
    strings=[put(x.encode()+b'\0')for x in argv];av=put(struct.pack('<'+'Q'*(len(strings)+1),*strings,0))
    hookbase=0x30000000;cpu.mem_map(hookbase,4096,UC_PROT_READ|UC_PROT_EXEC)
    tablebase=0x32000000;cpu.mem_map(tablebase,4096,UC_PROT_READ|UC_PROT_WRITE)
    api=tablebase+4096-api_bytes # no mapped page after tail: catches old-ABI OOB reads.
    raw=bytearray(136);struct.pack_into('<IIQ',raw,0,1,api_bytes,0xcafe)
    names=['write','yield','el','open','read','file_write','seek','close','stat','mkdir','unlink','sync']
    offsets=[16,24,32,64,72,80,88,96,104,112,120,128]
    for i,off in enumerate(offsets):struct.pack_into('<Q',raw,off,hookbase+i*16)
    struct.pack_into('<i',raw,40,len(argv));struct.pack_into('<Q',raw,48,av)
    cpu.mem_write(api,bytes(raw[:api_bytes]));cpu.reg_write(a.UC_ARM64_REG_X0,api);cpu.reg_write(a.UC_ARM64_REG_X1,0)
    cpu.reg_write(a.UC_ARM64_REG_SP,stack+0x1f000);cpu.reg_write(a.UC_ARM64_REG_X30,stop)
    cpu.reg_write(a.UC_ARM64_REG_PSTATE,0x3c5);cpu.reg_write(a.UC_ARM64_REG_CPACR_EL1,0);cpu.reg_write(a.UC_ARM64_REG_X18,0x187654321)
    source=bytes(range(256))*39+b'port-source-tail\x00\xff'
    files={'/source':bytearray(source)};handles={};state={'output':bytearray(),'yields':0,'calls':{},'next':1,'syncs':0,'faults':[],'injected':False}
    regs=[getattr(a,f'UC_ARM64_REG_X{i}')for i in range(6)]
    def string(p):
        b=bytearray()
        for i in range(1024):
            c=bytes(cpu.mem_read(p+i,1))
            if c==b'\0':return b.decode()
            b.extend(c)
        raise AssertionError('unterminated string')
    def ret(value=0):cpu.reg_write(a.UC_ARM64_REG_X0,value&((1<<64)-1));cpu.reg_write(a.UC_ARM64_REG_PC,cpu.reg_read(a.UC_ARM64_REG_X30))
    def callback(_,address,size,index):
        name=names[index];args=[cpu.reg_read(r)for r in regs];state['calls'][name]=state['calls'].get(name,0)+1
        try:
            assert args[0]==0xcafe
            if name=='write':state['output'].extend(cpu.mem_read(args[1],args[2]));ret();return
            if name=='yield':state['yields']+=1;ret();return
            if name=='el':ret(1);return
            assert api_bytes>=136,'old ABI attempted VFS callback'
            if name=='open':
                label,fl=string(args[1]),args[2]
                if label in files and fl&32:ret(-17);return
                if label not in files:
                    if not fl&4:ret(-2);return
                    files[label]=bytearray()
                if fl&8:files[label].clear()
                h=0x100000000+state['next'];state['next']+=1;handles[h]={'path':label,'at':len(files[label])if fl&16 else 0,'flags':fl}
                cpu.mem_write(args[3],struct.pack('<Q',h));ret();return
            if name in ['mkdir','unlink']:ret(-38);return
            if name=='stat':
                label=string(args[1]);ret(-2)if label not in files else(cpu.mem_write(args[2],struct.pack('<QIIII',len(files[label]),1,0,1,0)),ret());return
            h=handles.get(args[1]);assert h is not None,(name,args[1])
            if name=='close':del handles[args[1]];ret();return
            if name=='sync':state['syncs']+=1;ret();return
            if name=='seek':
                off=args[2]if args[2]<(1<<63)else args[2]-(1<<64);origin=[0,h['at'],len(files[h['path']])][args[3]]
                if origin+off<0:ret(-22);return
                h['at']=origin+off;cpu.mem_write(args[4],struct.pack('<Q',h['at']));ret();return
            count=args[3];at=h['at'];content=files[h['path']];done=0;rc=0
            if name=='read':
                if not h['flags']&1:ret(-9);return
                done=min(count,max(0,len(content)-at),509)
                if scenario=='partial-read-error'and not state['injected']and done:
                    done=min(done,50);rc=-5;state['injected']=True
                if done:cpu.mem_write(args[2],bytes(content[at:at+done]))
            elif name=='file_write':
                if not h['flags']&2:ret(-9);return
                if scenario=='write-protected':cpu.mem_write(args[4],struct.pack('<Q',0));ret(-30);return
                done=min(count,257)
                if at+done>len(content):content.extend(b'\0'*(at+done-len(content)))
                content[at:at+done]=cpu.mem_read(args[2],done)
            else:raise AssertionError(name)
            h['at']+=done;cpu.mem_write(args[4],struct.pack('<Q',done));ret(rc)
        except Exception as e:state['faults'].append(str(e));cpu.emu_stop()
    for index in range(len(names)):cpu.hook_add(UC_HOOK_CODE,callback,user_data=index,begin=hookbase+index*16,end=hookbase+index*16)
    cpu.emu_start(entry,stop,count=30000000)
    rc=cpu.reg_read(a.UC_ARM64_REG_X0)&0xffffffff;rc=rc if rc<(1<<31)else rc-(1<<32)
    assert not state['faults']and cpu.reg_read(a.UC_ARM64_REG_PC)==stop,(state['faults'],hex(cpu.reg_read(a.UC_ARM64_REG_PC)))
    assert cpu.reg_read(a.UC_ARM64_REG_X18)==0x187654321,'X18 clobbered'
    assert not handles,handles
    text=bytes(state['output']).decode(errors='replace')
    if expected_output is not None:
        assert rc==0 and text==expected_output,(rc,text)
    elif 'text' in path.parent.name:
        expected='port_text EL1 argc=3\n1: HELLO WORLD (11 bytes)\n2: 42 (2 bytes)\nunsupported process API: ENOSYS\n'
        assert rc==0 and text==expected,(rc,text)
        assert not any(k in state['calls']for k in names[3:])
    elif 'hash' in path.parent.name:
        if scenario=='ok':
            expected=f'{hashlib.sha256(source).hexdigest()}  /source bytes={len(source)} EL1\n'
            assert rc==0 and text==expected,(rc,text)
        else:assert rc!=0 and 'hash failed' in text,(rc,text)
    elif scenario=='ok':
        expected_hash=14695981039346656037
        for value in source:expected_hash=((expected_hash^value)*1099511628211)&((1<<64)-1)
        expected=f'copied={len(source)} fnv1a64={expected_hash:016x} verified EL1\n'
        assert rc==0 and text==expected and bytes(files['/dest'])==source and state['syncs']==1,(rc,text,state['syncs'])
    else:assert rc!=0 and 'verified EL'not in text,(rc,text)
    return {'passed':True,'base':hex(base),'apiBytes':api_bytes,'scenario':scenario,'programSha256':sha(path),'returnCode':rc,'output':text,
            'callbackCounts':state['calls'],'modeledYields':state['yields'],'modelFiles':{k:{'bytes':len(v),'sha256':hashlib.sha256(v).hexdigest()}for k,v in files.items()},
            'limits':'Python ET_DYN loader/SDK file callbacks; production ARM64 C program/libc executed with CPACR disabled and actual CurrentEL instruction. No real scheduler/UEFI/USB.'}
def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);aargs=p.parse_args();out=aargs.output.resolve();out.mkdir(parents=True,exist_ok=True)
    report={'scope':__doc__,'passed':False}
    try:
        report['host']=host(out)
        text=program(out,'text');copy=program(out,'copy',True);hash_program=program(out,'hash',True)
        report['arm64']=[execute(text,['./text','hello world','42'],64),execute(text,['./text','hello world','42'],136,0x18000000),
                         execute(copy,['./copy','/source','/dest']),execute(copy,['./copy','/source','/dest'],136,0x18000000),
                         execute(copy,['./copy','/source','/dest'],136,scenario='partial-read-error'),execute(copy,['./copy','/source','/dest'],136,scenario='write-protected'),
                         execute(hash_program,['./hash','/source']),execute(hash_program,['./hash','/source'],136,scenario='partial-read-error')]
        report['sourceSha256']={str(f.relative_to(RUNTIME)):sha(f)for f in sorted(COMPAT.rglob('*'))if f.is_file()and f.suffix in ['.c','.h','.py']}
        report['passed']=True
    finally:(out/'validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'passed':report['passed'],'arm64Cases':len(report['arm64']),'report':str(out/'validation.json')},indent=2))
if __name__=='__main__':main()
