"""Run unchanged historical golden/allowlist assertions through the legacy view.
Production VM behavior is verified by test_vm_dtbo_jvm.py and ARM64 suites.
"""
from pathlib import Path
p=Path(__file__).with_name('test_vm_dtbo_jvm.py')
s=p.read_text()
s=s.replace("OUT=ROOT/'build/vm-dtbo-jvm'","OUT=ROOT/'build/vm-legacy-regression'")
s=s.replace("[MODULE/'src/test/kotlin/net/hawyn/ablefisp/patch/VmDtboCompatTest.kt']",
    "[MODULE/'src/test/kotlin/net/hawyn/ablefisp/patch'/name for name in ['LegacyFeatureView.kt','FakeLockPatcherTest.kt','FakeLockUniversalTest.kt','OutputVerifier.kt','OutputVerifierTest.kt']]")
s=s.replace("'net.hawyn.ablefisp.patch.VmDtboCompatTest'",
    "'net.hawyn.ablefisp.patch.FakeLockPatcherTest','net.hawyn.ablefisp.patch.FakeLockUniversalTest','net.hawyn.ablefisp.patch.OutputVerifierTest'")
exec(compile(s,str(p),'exec'),{'__file__':str(p),'__name__':'__main__'})
