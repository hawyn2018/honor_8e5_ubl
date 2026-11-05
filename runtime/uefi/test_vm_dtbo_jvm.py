"""Focused JVM firmware/real API pipeline checks without Android or device I/O."""
from pathlib import Path
import os,re,shutil,subprocess,sys
ROOT=Path(__file__).resolve().parents[2];MODULE=ROOT/'abl-efisp-core';OUT=ROOT/'build/vm-dtbo-jvm'
if '--text-expansion-only' in sys.argv:OUT=ROOT/'build/vm-dtbo-jvm-text-expansion'
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
CACHE=Path.home()/'.gradle/caches/modules-2/files-2.1';OUT.mkdir(parents=True,exist_ok=True)
def jar(g,a,v):
    found=list((CACHE/g/a/v).glob('*/'+a+'-'+v+'.jar'));assert len(found)==1,(g,a,v,found);return found[0]
k=lambda a:jar('org.jetbrains.kotlin',a,'2.4.20')
stdlib=k('kotlin-stdlib');annotations=jar('org.jetbrains','annotations','23.0.0')
junit=jar('junit','junit','4.13.2');hamcrest=jar('org.hamcrest','hamcrest-core','1.3');xz=jar('org.tukaani','xz','1.12')
compiler=[k('kotlin-compiler-embeddable'),k('kotlin-build-tools-api'),k('kotlin-daemon-embeddable'),k('kotlin-script-runtime'),stdlib,jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),annotations]
cp=lambda paths:os.pathsep.join(map(str,paths))
sources=list((MODULE/'src/main/kotlin').rglob('*.kt'))+[MODULE/'src/test/kotlin/net/hawyn/ablefisp/patch/VmDtboCompatTest.kt']
cmd=[shutil.which('java'),'-Xmx2g','-cp',cp(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp([stdlib,junit,hamcrest,annotations,xz]),'-d',str(OUT/'classes'),*map(str,sources)]
r=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace')
log=r.stdout+r.stderr
if r.returncode==0:
    r=subprocess.run([shutil.which('java'),'-Dvm.textExpansionOnly='+str('--text-expansion-only' in sys.argv).lower(),'-Dvm.factsOnly='+str('--facts-only' in sys.argv).lower(),'-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Xmx3g','-cp',cp([OUT/'classes',MODULE/'src/main/resources',stdlib,junit,hamcrest,annotations,xz]),'org.junit.runner.JUnitCore','net.hawyn.ablefisp.patch.VmDtboCompatTest'],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace')
    log+='\n'+r.stdout+r.stderr
(OUT/('facts-only.log' if '--facts-only' in sys.argv else 'run.log')).write_text(log,encoding='utf-8');print(log);sys.exit(r.returncode)
