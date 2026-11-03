"""Check configurable large heaps and natural printf count boundaries."""
from pathlib import Path
import argparse,hashlib,importlib.util,json
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
    ports=module('capacity_ports',HERE/'run_ports.py');sdk=module('capacity_sdk',ROOT/'runtime/sdk/build_port.py')
    host=[]
    for n in (65536,20*1024*1024):
        target=out/str(n);target.mkdir(exist_ok=True);host.append(ports.host(target,n))
    built=sdk.build(HERE/'large_heap_program.c',out/'arm64-large',heap_bytes=20*1024*1024)
    assert max(x['bytes']for x in built['loads'])>16*1024*1024
    runs=[ports.execute(Path(built['program']),['capacity'],base=base,expected_output='large heap allocation passed\n')for base in (0x10000000,0x17000000)]
    assert all(x['returnCode']==0 for x in runs),runs
    io_built=sdk.build(HERE/'large_io_program.c',out/'arm64-large-io',require_files=True)
    io_runs=[ports.execute(Path(io_built['program']),['capacity-io'],base=base,expected_output='dynamic descriptor and stream capacity passed\n')for base in (0x10000000,0x17000000)]
    invalid=[]
    for n in (-1,4095,1<<63):
        try:sdk.build(HERE/'large_heap_program.c',out/'invalid',heap_bytes=n)
        except ValueError:invalid.append(n)
        else:raise AssertionError(('invalid target object size accepted',n))
    sources=[ROOT/'runtime/sdk/build_port.py',ROOT/'runtime/compat/core.c',ROOT/'runtime/compat/io.c',ROOT/'runtime/compat/format.c',HERE/'host.c',HERE/'run_ports.py',HERE/'large_heap_program.c',HERE/'large_io_program.c',Path(__file__)]
    report={'passed':True,'host':host,'arm64Program':built,'arm64Runs':runs,'arm64IoProgram':io_built,'arm64IoRuns':io_runs,'dynamicIo':{'simultaneousDescriptors':120,'simultaneousStreams':40,'explicitRawDescriptors':80,'automaticTeardownCloses':120},'invalidObjectSizesRejected':invalid,
            'printfCases':['width 1000001','precision 1000001','string precision 10000010','width INT_MAX','precision INT_MAX',
                           'decimal width INT_MAX+1 rejected','decimal precision INT_MAX+1 rejected','total count overflow rejected','INT_MIN star width rejected'],
            'largeAllocationBytes':16*1024*1024+1,'deviceOperations':False,
            'sourceHashes':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()for p in sources}}
    (out/'verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps({'passed':True,'hostChecks':[h['checks']for h in host],'arm64Runs':runs,'arm64IoRuns':io_runs},indent=2))
if __name__=='__main__':main()
