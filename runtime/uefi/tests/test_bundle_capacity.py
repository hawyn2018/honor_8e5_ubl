"""Real bundle CLI size/PE checks, isolated resource output; no device."""
from pathlib import Path
import hashlib,json,struct,subprocess,sys,tempfile,unittest
ROOT=Path(__file__).resolve().parents[3]
RESOURCE=ROOT/"abl-efisp-core/src/main/resources/kbl"
SCRIPT=ROOT/"runtime/uefi/bundle_driver.py"
class BundleCapacity(unittest.TestCase):
    def test_2MiB_plus_one_valid_driver_overlay_and_isolated_output(self):
        before={p.name:hashlib.sha256(p.read_bytes()).hexdigest()for p in RESOURCE.iterdir()if p.is_file()}
        with tempfile.TemporaryDirectory() as directory:
            top=Path(directory);raw=(RESOURCE/"kbl_driver.efi").read_bytes();raw+=bytes(max(0,2*1024*1024+1-len(raw)))
            image=top/"large.efi";image.write_bytes(raw);out=top/"resource"
            p=subprocess.run([sys.executable,str(SCRIPT),"--driver",str(image),"--output-dir",str(out)],capture_output=True,text=True)
            self.assertEqual(0,p.returncode,p.stderr);metadata=json.loads(p.stdout)
            self.assertEqual(len(raw),metadata["bytes"]);self.assertEqual(raw,(out/"kbl_driver.efi").read_bytes())
            self.assertEqual(hashlib.sha256(raw).hexdigest()+"\n",(out/"kbl_driver.sha256").read_text())
        self.assertEqual(before,{p.name:hashlib.sha256(p.read_bytes()).hexdigest()for p in RESOURCE.iterdir()if p.is_file()})
    def test_machine_and_wx_checks_remain(self):
        source=(RESOURCE/"kbl_driver.efi").read_bytes();pe=struct.unpack_from("<I",source,60)[0]
        section=pe+24+struct.unpack_from("<H",source,pe+20)[0]
        for mode in ("machine","wx"):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as directory:
                top=Path(directory);raw=bytearray(source)
                if mode=="machine":struct.pack_into("<H",raw,pe+4,0x8664)
                else:struct.pack_into("<I",raw,section+36,struct.unpack_from("<I",raw,section+36)[0]|0xa0000000)
                image=top/"invalid.efi";image.write_bytes(raw);out=top/"resource"
                p=subprocess.run([sys.executable,str(SCRIPT),"--driver",str(image),"--output-dir",str(out)],capture_output=True,text=True)
                self.assertNotEqual(0,p.returncode);self.assertFalse(out.exists())
if __name__=="__main__":unittest.main()
