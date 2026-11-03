"""Compile the real read-only F2FS backend as an import-free ARM64 EFI object."""
import hashlib,json,pathlib,re,subprocess,sys
import pefile
ROOT=pathlib.Path(__file__).resolve().parents[3]
LLVM=pathlib.Path(r"D:/AndroidSDK/ndk/android-ndk-r29/toolchains/llvm/prebuilt/windows-x86_64/bin")
OUT=pathlib.Path(sys.argv[1]).resolve()if len(sys.argv)>1 else ROOT.parent/"f2fs-test-output"
OUT.mkdir(exist_ok=True,parents=True)
obj=OUT/"f2fs-arm64.obj";target=OUT/"f2fs-noimports.efi"
cmd=[str(LLVM/"clang.exe"),"--target=aarch64-pc-windows-msvc","-std=c11","-O2","-ffreestanding","-fno-builtin","-fno-stack-protector","-ffixed-x18","-mno-outline-atomics","-mgeneral-regs-only","-mno-stack-arg-probe","-fstack-usage","-Wall","-Wextra","-Werror","-c",str(ROOT/"runtime/fs/f2fs.c"),"-o",str(obj)]
subprocess.run(cmd,check=True)
undefined=subprocess.check_output([str(LLVM/"llvm-nm.exe"),"--undefined-only",str(obj)],text=True)
assert not undefined.strip(),undefined
dis=subprocess.check_output([str(LLVM/"llvm-objdump.exe"),"-d",str(obj)],text=True)
ops="\n".join(l.split("\t")[-1]for l in dis.splitlines()if"\t"in l)
assert not re.search(r"\b(?:[vqsdh][0-9]+|fpcr|fpsr|cpacr_el1)\b",ops,re.I)
stack=[]
for line in obj.with_suffix(".su").read_text().splitlines():
    fields=line.split("\t")
    if len(fields)>=3 and fields[-2].isdigit():stack.append({"function":fields[0],"bytes":int(fields[-2]),"kind":fields[-1]})
assert stack and max(r["bytes"]for r in stack)<16384
assert any("resolve" in r["function"] for r in stack)
link=[str(LLVM/"ld.lld.exe"),"-flavor","link","/subsystem:efi_boot_service_driver","/entry:kbl_f2fs_mount","/nodefaultlib","/machine:arm64","/dynamicbase","/nxcompat","/opt:ref","/timestamp:0","/out:"+str(target),str(obj)]
subprocess.run(link,check=True)
pe=pefile.PE(str(target));assert not getattr(pe,"DIRECTORY_ENTRY_IMPORT",[])
assert all(not(s.Characteristics&0x80000000 and s.Characteristics&0x20000000)for s in pe.sections)
meta={"note":"Link-only PE; not an EFI application entrypoint; never deploy test PE.","imports":0,"fp_simd_operands":0,"bytes":target.stat().st_size,"sha256":hashlib.sha256(target.read_bytes()).hexdigest(),"max_function_stack":max(r["bytes"]for r in stack),"stack_usage":stack,"commands":[cmd,link]}
(OUT/"f2fs-arm-build.json").write_text(json.dumps(meta,indent=2)+"\n",encoding="utf-8")
print("PASS ARM64 F2FS production reader: GPR-only, zero undefined symbols/imports, W^X PE sections")
