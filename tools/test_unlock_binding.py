"""Exercise current unlock source bindings on host text fixtures, without device access."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import argparse
import json
import os
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SHELL = Path(os.environ.get('BASH', r'C:\Program Files\Git\bin\bash.exe'))


def run_backend(name):
    text = (ROOT/'app/src/main/assets/recovery'/name).read_text(encoding='utf-8')
    syntax = subprocess.run([str(SHELL), '-n'], input=text, text=True, capture_output=True)
    assert syntax.returncode == 0, (name, syntax.stderr)
    functions = text.split('# BEGIN payload source binding\n', 1)[1].split('# END payload source binding', 1)[0]
    other_body = text.split('verify_other_payload_source() {\n', 1)[1].split('\n}\n', 1)[0]
    functions += '\nverify_other_payload_source() {\n'+other_body+'\n}\n'
    base = {'REQUEST_MODE':'CUSTOM', 'EFI_SOURCE':'BUILTIN_ABL', 'PATCH_MODE':'FAKE_LOCK', 'ABL_WRITE_TARGET':'BOTH'}
    current = {'SOURCE_SLOT':'a', 'SOURCE_ABL_DEVICE':'/dev/block/testcurrent', 'SOURCE_ABL_MM':'8:1',
               'SOURCE_ABL_CAPACITY':'1048576', 'SOURCE_ABL_SHA256':'a'*64}
    opposite = {'SOURCE_SLOT':'b', 'SOURCE_ABL_DEVICE':'/dev/block/testother', 'SOURCE_ABL_MM':'8:2',
                'SOURCE_ABL_CAPACITY':'1048576', 'SOURCE_ABL_SHA256':'b'*64}
    builtin_hash = '509e8f9655184f69c147c494f8cfabe795f2cacb4e5e6b7a1fb0a48ecc08376c'
    checks = []
    with tempfile.TemporaryDirectory(prefix='hnubl-unlock-binding-') as temp:
        source = Path(temp)/'payload-source.txt'

        def case(label, fields, *, slot='a', device='/dev/block/testcurrent', major_minor='8:1',
                 capacity='1048576', digest='a'*64, other_slot='b', other_device='/dev/block/testother',
                 other_major_minor='8:2', other_capacity='1048576', other_digest='b'*64,
                 extra='', mutate=False, blocked=False, expected_source=None):
            source.write_text(''.join(f'{key}={value}\n' for key,value in fields.items())+extra,
                              encoding='utf-8', newline='\n')
            program = '''set -u
die() { printf '%s\n' "BLOCK=$1"; exit 77; }
hash_file() { sha256sum "$1" | awk '{print $1}'; }
is_hash() { printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{64}$'; }
'''+functions
            values = dict(ACTION='--execute', MODE_ARG='--execute', PHASE='prepare',
                SLOT=slot, ABL=device, ABL_MM=major_minor, ABL_OTHER_SLOT=other_slot,
                OTHER_ABL=other_device, OTHER_ABL_MM=other_major_minor, OTHER_ABL_CAPACITY=other_capacity)
            program += '\n'+''.join(f'{key}={shlex.quote(value)}\n' for key,value in values.items())
            program += f'load_payload_source {shlex.quote(source.as_posix())}\n'
            if expected_source is not None:
                program += f'[ "$SOURCE_EFI_KIND" = {shlex.quote(expected_source)} ] || die "wrong effective source"\n'
            if mutate: program += f"printf '\\n' >> {shlex.quote(source.as_posix())}\n"
            program += f'verify_payload_source_identity {shlex.quote(capacity)}\n'
            program += f'verify_payload_source_abl {shlex.quote(digest)}\n'
            program += f'verify_other_payload_source {shlex.quote(other_digest)}\nprintf "ADMITTED\\n"\n'
            result = subprocess.run([str(SHELL), '-s'], input=program, text=True, capture_output=True, timeout=20)
            assert result.returncode == (77 if blocked else 0), (name, label, result.stdout, result.stderr)
            assert ('ADMITTED' in result.stdout) != blocked, (name, label, result.stdout)
            checks.append(dict(backend=name, case=label, blocked=blocked, passed=True))

        # Exercise every mode/source/target combination against existing backend
        # parameter rules; application defaults are separately tested in Kotlin.
        kinds = ('UPLOADED_EFISP','UPLOADED_ABL','CURRENT_SLOT_ABL','OTHER_SLOT_ABL','BUILTIN_ABL')
        for mode in ('FAKE_LOCK','DIRECT','CUSTOM'):
            for kind in kinds:
                for target in ('CURRENT','OTHER','BOTH'):
                    identity = current if kind=='CURRENT_SLOT_ABL' else opposite if kind=='OTHER_SLOT_ABL' else {}
                    fields = base|identity|{'REQUEST_MODE':mode,'EFI_SOURCE':kind,'ABL_WRITE_TARGET':target,
                        'PATCH_MODE':'FREEDOM' if mode=='DIRECT' else 'FAKE_LOCK'}
                    blocked = mode!='CUSTOM' and (kind=='OTHER_SLOT_ABL' or target=='OTHER')
                    case('matrix-'+mode+'-'+kind+'-'+target, fields, blocked=blocked)
        # Non-slot sources do not gain an unrelated current-partition dependency.
        for kind in ('UPLOADED_EFISP','UPLOADED_ABL','BUILTIN_ABL'):
            case('independent-source-'+kind, base|{'EFI_SOURCE':kind}, slot='b',
                 device='/dev/block/changedcurrent', digest='f'*64)
        cur = base|current|{'EFI_SOURCE':'CURRENT_SLOT_ABL'}
        for label, override in (
            ('slot',dict(slot='b')), ('device',dict(device='/dev/block/other')),
            ('major-minor',dict(major_minor='8:3')), ('capacity',dict(capacity='2097152')),
            ('hash',dict(digest='c'*64))):
            case('current-source-wrong-'+label, cur, blocked=True, **override)
        for key in current:
            case('current-source-missing-'+key, {k:v for k,v in cur.items() if k!=key}, blocked=True)
        case('current-source-dot-path',cur|{'SOURCE_ABL_DEVICE':'/dev/block/../wrong'},blocked=True)
        case('current-source-invalid-slot',cur|{'SOURCE_SLOT':'_a'},blocked=True)
        case('current-source-invalid-device',cur|{'SOURCE_ABL_DEVICE':'/tmp/image'},blocked=True)
        case('current-source-invalid-size',cur|{'SOURCE_ABL_CAPACITY':'0'},blocked=True)
        case('current-source-invalid-device-number',cur|{'SOURCE_ABL_MM':'8-x'},blocked=True)
        case('current-source-short-hash',cur|{'SOURCE_ABL_SHA256':'a'*63},blocked=True)
        case('current-source-nonhex-hash',cur|{'SOURCE_ABL_SHA256':'g'*64},blocked=True)
        other = base|opposite|{'EFI_SOURCE':'OTHER_SLOT_ABL'}
        for label, override in (
            ('slot',dict(other_slot='a')), ('device',dict(other_device='/dev/block/other')),
            ('major-minor',dict(other_major_minor='8:3')), ('capacity',dict(other_capacity='2097152')),
            ('hash',dict(other_digest='c'*64))):
            case('opposite-source-wrong-'+label,other,blocked=True,**override)
        for key in opposite:
            case('opposite-source-missing-'+key,{k:v for k,v in other.items() if k!=key},blocked=True)
        for key in ('REQUEST_MODE','EFI_SOURCE','PATCH_MODE'):
            case('required-field-missing-'+key,{k:v for k,v in base.items() if k!=key},blocked=True)
        for key in ('REQUEST_MODE','EFI_SOURCE','PATCH_MODE','ABL_WRITE_TARGET'):
            case('invalid-enum-'+key,base|{key:'UNKNOWN'},blocked=True)
        case('unknown-field-rejected',base|{'OBSOLETE_FIELD':'1'},blocked=True)
        case('duplicate-field-rejected',base,extra='PATCH_MODE=FREEDOM\n',blocked=True)
        case('empty-field-rejected',base|{'PATCH_MODE':''},blocked=True)
        case('unexpected-equals-rejected',base|{'PATCH_MODE':'FAKE_LOCK=extra'},blocked=True)
        case('data-not-shell-code',base|{'PATCH_MODE':'$(exit 99)'},blocked=True)
        case('binding-file-changed',base,mutate=True,blocked=True)
        # The original frozen selection remains EFI_SOURCE; the explicitly
        # accepted retry is independently bound to the actual built-in bytes.
        retry_base = {'PATCH_RETRY_SOURCE':'BUILTIN_ABL', 'SOURCE_ABL_SHA256':builtin_hash}
        for mode in ('FAKE_LOCK','DIRECT','CUSTOM'):
            for kind in ('UPLOADED_ABL','CURRENT_SLOT_ABL','OTHER_SLOT_ABL'):
                for target in ('CURRENT','OTHER','BOTH'):
                    for patch in ('FAKE_LOCK','FREEDOM'):
                        fields = base|retry_base|{'REQUEST_MODE':mode,'EFI_SOURCE':kind,
                            'ABL_WRITE_TARGET':target,'PATCH_MODE':patch}
                        blocked = mode!='CUSTOM' and (kind=='OTHER_SLOT_ABL' or target=='OTHER')
                        case('retry-matrix-'+mode+'-'+kind+'-'+target+'-'+patch, fields,
                             blocked=blocked, expected_source='BUILTIN_ABL' if not blocked else None,
                             slot='b', device='/dev/block/changedcurrent', digest='f'*64,
                             other_slot='a', other_device='/dev/block/changedother', other_digest='e'*64)
        retry = base|retry_base|{'EFI_SOURCE':'CURRENT_SLOT_ABL'}
        for kind in ('UPLOADED_EFISP','BUILTIN_ABL'):
            case('retry-ineligible-'+kind,retry|{'EFI_SOURCE':kind},blocked=True)
        for selected_source in ('CURRENT_SLOT_ABL','OTHER_SLOT_ABL','UPLOADED_ABL'):
            candidate = retry|{'EFI_SOURCE':selected_source}
            for key in ('SOURCE_SLOT','SOURCE_ABL_DEVICE','SOURCE_ABL_MM','SOURCE_ABL_CAPACITY'):
                case('retry-stale-identity-'+selected_source+'-'+key,candidate|{key:current[key]},blocked=True)
        for key in ('PATCH_RETRY_SOURCE','SOURCE_ABL_SHA256'):
            case('retry-empty-'+key,retry|{key:''},blocked=True)
            case('retry-duplicate-'+key,retry,extra=key+'='+retry[key]+'\n',blocked=True)
        case('retry-missing-source-hash',{k:v for k,v in retry.items() if k!='SOURCE_ABL_SHA256'},blocked=True)
        case('retry-original-source-hash',retry|{'SOURCE_ABL_SHA256':'a'*64},blocked=True)
        case('retry-unknown-destination',retry|{'PATCH_RETRY_SOURCE':'UNKNOWN'},blocked=True)
        case('retry-invalid-destination',retry|{'PATCH_RETRY_SOURCE':'CURRENT_SLOT_ABL'},blocked=True)
        case('retry-injected-destination',retry|{'PATCH_RETRY_SOURCE':'$(exit 99)'},blocked=True)
        case('retry-binding-file-changed',retry,mutate=True,blocked=True)
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path)
    args = parser.parse_args()
    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(run_backend,('unlock.sh','online-create.sh')))
    checks = [check for result in results for check in result]
    report = dict(passed=True,checks=checks,count=len(checks),matrixCasesPerBackend=45,retryMatrixCasesPerBackend=54,
        scope='Production source-binding functions on host ordinary files; no full backend execution, device access or partition writes.')
    if args.report: args.report.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))


if __name__ == '__main__':
    main()
