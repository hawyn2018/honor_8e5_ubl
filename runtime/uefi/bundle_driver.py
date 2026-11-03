"""Bundle a reproducible driver resource; does not create or flash EFISP."""
from pathlib import Path
import argparse,hashlib,json,pefile

def main():
    root=Path(__file__).resolve().parents[2]
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--driver',type=Path,default=root/'build/kbl-driver/kbl_driver.efi')
    parser.add_argument('--output-dir',type=Path,help='Resource directory; defaults to the project core resource')
    args=parser.parse_args();raw=args.driver.read_bytes();pe=pefile.PE(data=raw)
    assert pe.FILE_HEADER.Machine==0xaa64 and pe.OPTIONAL_HEADER.Subsystem==11
    assert not getattr(pe,'DIRECTORY_ENTRY_IMPORT',None)
    assert all((s.Characteristics&0xa0000000)!=0xa0000000 for s in pe.sections)
    out=args.output_dir or root/'abl-efisp-core/src/main/resources/kbl';out.mkdir(parents=True,exist_ok=True)
    sha=hashlib.sha256(raw).hexdigest()
    for name,data in [('kbl_driver.efi',raw),('kbl_driver.sha256',(sha+'\n').encode('ascii'))]:
        target=out/name;temporary=out/(name+'.tmp');temporary.write_bytes(data);temporary.replace(target)
    print(json.dumps({'resource':str(out/'kbl_driver.efi'),'bytes':len(raw),'sha256':sha}))
if __name__=='__main__':main()
