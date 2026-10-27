"""Offline JVM verification of the supplied engine and the host file-only adapter."""
from pathlib import Path
import hashlib,json,os,re,shutil,subprocess,sys
MODULE=Path(__file__).resolve().parents[1]
WORKSPACE=MODULE.parents[2]
APP=MODULE.parent/'app'
CACHE=Path.home()/'.gradle/caches/modules-2/files-2.1'
OUT=MODULE/'build/host-tests'
OUT.mkdir(parents=True,exist_ok=True)
(OUT/'tmp').mkdir(parents=True,exist_ok=True)
def jar(group,artifact,version):
 matches=list((CACHE/group/artifact/version).glob('*/'+artifact+'-'+version+'.jar'))
 if len(matches)!=1:raise RuntimeError((group,artifact,version,matches))
 return matches[0]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
kotlin=lambda a:jar('org.jetbrains.kotlin',a,'2.4.20')
stdlib=kotlin('kotlin-stdlib')
junit=jar('junit','junit','4.13.2')
hamcrest=jar('org.hamcrest','hamcrest-core','1.3')
annotations=jar('org.jetbrains','annotations','23.0.0')
xz=jar('org.tukaani','xz','1.12')
compiler=[kotlin('kotlin-compiler-embeddable'),kotlin('kotlin-build-tools-api'),kotlin('kotlin-daemon-embeddable'),kotlin('kotlin-script-runtime'),stdlib,jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),annotations]
cp=lambda x:os.pathsep.join(map(str,x))
sources=sorted((MODULE/'src/main/kotlin').rglob('*.kt'))+sorted((MODULE/'src/test/kotlin').rglob('*.kt'))
sources += [APP/'src/main/java/dev/honor/bootforge/core/EfispImagePatcher.kt',APP/'src/test/java/dev/honor/bootforge/core/EfispImagePatcherTest.kt']
classes=[]
for p in sources:
 text=p.read_text(encoding='utf-8')
 package=re.search(r'^package ([\w.]+)',text,re.M)
 for cls in re.findall(r'^class (\w+Test)\b',text,re.M):classes.append(package.group(1)+'.'+cls)
source_hashes={str(p.relative_to(WORKSPACE)):sha(p) for p in sources}
compile=[shutil.which('java'),'-Xmx2g','-cp',cp(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp([stdlib,junit,hamcrest,annotations,xz]),'-d',str(OUT/'classes'),*map(str,sources)]
run=[shutil.which('java'),'-Xmx2g','-Djava.io.tmpdir='+str(OUT/'tmp'),'-cp',cp([OUT/'classes',stdlib,junit,hamcrest,annotations,xz]),'org.junit.runner.JUnitCore',*classes]
compiled=subprocess.run(compile,cwd=WORKSPACE,capture_output=True,text=True,encoding='utf-8',errors='replace')
log='COMPILE\n'+compiled.stdout+compiled.stderr
report={'scope':'Unchanged supplied ABL-EFISP production engine plus file-only host wrapper; no Android/device/root commands','productionAndTestSources':source_hashes,'testClasses':classes,'compileExitCode':compiled.returncode,'compileCommand':compile,'testCommand':run}
if compiled.returncode==0:
 result=subprocess.run(run,cwd=WORKSPACE,capture_output=True,text=True,encoding='utf-8',errors='replace')
 output=result.stdout+result.stderr
 log+='\nTEST\n'+output
 report['testExitCode']=result.returncode
 m=re.search(r'OK \((\d+) tests\)',output)
 report['passedTests']=int(m.group(1)) if m else 0
 report['success']=result.returncode==0 and m is not None
else:report['success']=False
report['sourcesUnchanged']=all(sha(WORKSPACE/n)==h for n,h in source_hashes.items())
(OUT/'run.log').write_text(log,encoding='utf-8')
(OUT/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(log)
print(json.dumps({'success':report['success'],'passedTests':report.get('passedTests',0),'report':str(OUT/'result.json')},ensure_ascii=False))
sys.exit(0 if report['success'] and report['sourcesUnchanged'] else 1)
