"""Run the real application adapter/probe on the built-in expansion fixture.
This reuses the release probe's complete final-patch byte readback, VM strict
receipt/rebuild checks and actual application label; there is no device I/O.
"""
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[2]
p=ROOT.parent/'run_release_compat.py'
s=p.read_text(encoding='utf-8').replace('    manifest = {','    cases = cases[:1]\n    manifest = {')
sys.argv=[str(p),'--quick','--output',str(ROOT/'build/vm-dtbo-actual-adapter')]
exec(compile(s,str(p),'exec'),{'__file__':str(p),'__name__':'__main__'})
