"""Host-only GPT range and legacy regression fixtures; never contact a device.

Run from any directory: python tests/native/test_gpttool_range.py
Requires clang on PATH. Executables and regular-file disks are temporary.
Use --report PATH to retain the JSON results outside the clean source tree.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SOURCE = ROOT / 'app/src/main/cpp/gpttool.c'
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('gpt_legacy_fixtures', HERE / '_gpt_legacy_fixtures.py')
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)
MIB = 1024 * 1024
GUID = 'd4052c9a-8972-4dda-982f-9ce47cb21217'
results = legacy.results


def check(name, condition):
    legacy.check(name, condition)


def fields(output):
    return dict(line.split('=', 1) for line in output.decode().splitlines())


def make_range_disk(path, sector, successor=True):
    meta = legacy.make_disk(path, sector)
    raw = bytearray(legacy.run(sector, 'export', path).stdout)
    offsets = legacy.snap_fields(raw)
    old_end = 64 * MIB // sector - 1
    for role in ('pe', 'be'):
        table = offsets[role]
        struct.pack_into('<Q', raw, table + 40, old_end)
        if successor:
            # Entry 7 physically precedes entry 2. Numerical index order is
            # deliberately different from physical order.
            for index, start_bytes in ((7, 96 * MIB), (2, 112 * MIB)):
                start = start_bytes // sector
                struct.pack_into('<QQ', raw, table + (index - 1) * 128 + 32, start, start + 15)
    legacy.recalc_snap(raw)
    legacy.put_snapshot_on_disk(path, raw)
    # Data in the former/expanded userdata region and next partition must not
    # be changed by a GPT-only transaction.
    sentinels = ((48 * MIB, b'USERDATA-PAYLOAD'),
                 (72 * MIB, b'EXTERNAL-GAP-PAYLOAD'),
                 (96 * MIB + sector, b'NEXT-PARTITION-PAYLOAD'))
    with path.open('r+b') as stream:
        for offset, data in sentinels:
            stream.seek(offset)
            stream.write(data)
    legacy.run(sector, 'verify', path)
    meta.update(old_end=old_end, limit=(96 * MIB // sector - 1 if successor else meta['old_end']))
    return meta, bytes(raw), sentinels


def payload_hash(path, snapshot):
    """Hash every byte outside all five original GPT metadata regions."""
    f = legacy.snap_fields(snapshot)
    regions = [(0, f['sector']), (f['sector'], 2 * f['sector'])]
    for container_offset, length in ((32, f['padded']), (40, f['sector']), (48, f['padded'])):
        start = struct.unpack_from('<Q', snapshot, container_offset)[0] * f['sector']
        regions.append((start, start + length))
    digest = hashlib.sha256()
    end = 0
    with path.open('rb') as stream:
        for start, stop in sorted(regions) + [(path.stat().st_size, path.stat().st_size)]:
            stream.seek(end)
            remain = start - end
            while remain:
                data = stream.read(min(remain, MIB))
                if not data:
                    raise AssertionError('short fixture read')
                digest.update(data)
                remain -= len(data)
            end = stop
    return digest.hexdigest()


def preservation(before, after, new_end, efi_end, label):
    f = legacy.snap_fields(before)
    expected = bytearray(before)
    for role in ('pe', 'be'):
        table = f[role]
        struct.pack_into('<Q', expected, table + 40, new_end)
        start = table + 45 * 128
        entry = bytearray(128)
        entry[:16] = legacy.EFI_TYPE
        entry[16:32] = uuid.UUID(GUID).bytes_le
        struct.pack_into('<QQ', entry, 32, new_end + 1, efi_end)
        entry[56:66] = 'efisp'.encode('utf-16le')
        expected[start:start + 128] = entry
        check(label + ':' + role + ':userdata independent attributes preserved',
              after[table + 48:table + 56] == before[table + 48:table + 56])
    legacy.recalc_snap(expected)
    check(label + ':all target bytes equal independently reconstructed range plan', after == expected)
    check(label + ':common EFI profile', legacy.common_efi_profile(after, f))


def accept_case(temp, sector, mode, successor=True, bounded_end=False):
    label = f'{sector}:range:{mode}' + (':last-usable' if not successor else '')
    disk = temp / f'range-{sector}.img'
    meta, before_data, sentinels = make_range_disk(disk, sector, successor)
    inspected = fields(legacy.run(sector, 'inspect', disk, 1).stdout)
    check(label + ':physical next partition bounds inspect limit', int(inspected['EFI_LIMIT_END']) == meta['limit'])
    new_end = meta['old_end'] + {'shrink': -8 * MIB // sector,
                                'unchanged': 0, 'expand': 16 * MIB // sector}[mode]
    efi_end = 88 * MIB // sector - 1 if bounded_end else meta['limit']
    before = temp / 'range-before.snapshot'
    after = temp / 'range-after.snapshot'
    before.unlink(missing_ok=True)
    after.unlink(missing_ok=True)
    original_payload_hash = payload_hash(disk, before_data)
    output = legacy.run(sector, 'plan-range', disk, 1, new_end, 46, efi_end, GUID, before, after)
    check(label + ':read-only plan output', b'RESULT=success' in output.stdout and before.read_bytes() == before_data)
    check(label + ':planning does not modify disk', legacy.run(sector, 'export', disk).stdout == before_data)
    target = after.read_bytes()
    preservation(before_data, target, new_end, efi_end, label)
    output = legacy.run(sector, 'apply', disk, before, after, '--execute')
    check(label + ':native exact apply success', b'READBACK=match' in output.stdout and b'WRITE_ORDER=backup-then-primary' in output.stdout)
    check(label + ':post-apply exact raw target', legacy.run(sector, 'export', disk).stdout == target)
    inspected_after = fields(legacy.run(sector, 'inspect', disk, 1).stdout)
    check(label + ':post-apply userdata and EFI bounds',
          int(inspected_after['UD_END']) == new_end and int(inspected_after['EFI_START']) == new_end + 1
          and int(inspected_after['EFI_END']) == efi_end and inspected_after['EFI_COUNT'] == '1')
    check(label + ':existing EFI is occupied, not exposed as free external tail',
          int(inspected_after['EFI_LIMIT_END']) == new_end)
    check(label + ':every non-GPT byte preserved', payload_hash(disk, before_data) == original_payload_hash)
    with disk.open('rb') as stream:
        for offset, data in sentinels:
            stream.seek(offset)
            check(label + f':data sentinel at {offset} preserved', stream.read(len(data)) == data)
    legacy.run(sector, 'verify', disk)
    before.unlink()
    after.unlink()
    disk.unlink()


def rejection_cases(temp, sector):
    disk = temp / f'range-reject-{sector}.img'
    meta, exported, _ = make_range_disk(disk, sector)
    initial_hash = legacy.hashfile(disk)
    before = temp / 'rejected-before.snapshot'
    after = temp / 'rejected-after.snapshot'
    invalid = [
        ('invalid userdata index', 2, meta['old_end'], 46, meta['limit'], GUID),
        ('userdata before original start', 1, 4095, 46, meta['limit'], GUID),
        ('no room for EFI at limit', 1, meta['limit'], 46, meta['limit'], GUID),
        ('EFI end equals next partition start', 1, meta['old_end'], 46, meta['limit'] + 1, GUID),
        ('EFI end crosses the next occupied partition', 1, meta['old_end'], 46, meta['limit'] + 16, GUID),
        ('userdata expansion crosses next partition', 1, meta['limit'] + 1, 46, meta['limit'] + 32, GUID),
        ('occupied new entry', 1, meta['old_end'], 7, meta['limit'], GUID),
        ('same userdata and EFI index', 1, meta['old_end'], 1, meta['limit'], GUID),
        ('empty EFI range', 1, meta['old_end'], 46, meta['old_end'], GUID),
        ('reversed EFI range', 1, meta['old_end'], 46, meta['old_end'] - 1, GUID),
        ('duplicate GUID', 1, meta['old_end'], 46, meta['limit'], str(uuid.UUID(int=101))),
        ('zero GUID', 1, meta['old_end'], 46, meta['limit'], str(uuid.UUID(int=0))),
        ('maximum numeric EFI end', 1, meta['old_end'], 46, (1 << 64) - 1, GUID),
    ]
    for name, ud, end, efi, efi_end, guid in invalid:
        legacy.run(sector, 'plan-range', disk, ud, end, efi, efi_end, guid, before, after, success=False)
        check(f'{sector}:invalid range:{name}:reject before producing snapshots', not before.exists() and not after.exists())
    check(f'{sector}:all invalid range plans leave disk unchanged', legacy.hashfile(disk) == initial_hash)
    # A positive-size partition named efisp remains a hard creation conflict.
    f = legacy.snap_fields(exported)
    preexisting = bytearray(exported)
    for role in ('pe', 'be'):
        off = f[role] + 6 * 128
        preexisting[off + 56:off + 128] = 'efisp'.encode('utf-16le') + bytes(62)
    legacy.recalc_snap(preexisting)
    legacy.put_snapshot_on_disk(disk, preexisting)
    legacy.run(sector, 'plan-range', disk, 1, meta['old_end'], 46, meta['limit'], GUID, before, after, success=False)
    check(f'{sector}:existing EFI name rejects range creation', not before.exists() and not after.exists())
    # Type GUID zero does not make residual bytes safe for overwriting.
    dirty_empty = bytearray(exported)
    for role in ('pe', 'be'):
        dirty_empty[f[role] + 45 * 128 + 56] = ord('x')
    legacy.recalc_snap(dirty_empty)
    legacy.put_snapshot_on_disk(disk, dirty_empty)
    legacy.run(sector, 'plan-range', disk, 1, meta['old_end'], 46, meta['limit'], GUID, before, after, success=False)
    check(f'{sector}:residual empty-entry bytes reject range creation', not before.exists() and not after.exists())
    legacy.put_snapshot_on_disk(disk, exported)
    legacy.run(sector, 'plan-range', disk, 1, meta['old_end'], 46, meta['limit'], GUID, before, after)
    planned = after.read_bytes()
    bad = temp / 'tampered-after.snapshot'
    mutations = [
        ('EFI label', lambda a, ep: a.__setitem__(ep + 45 * 128 + 56, ord('x'))),
        ('EFI type', lambda a, ep: a.__setitem__(ep + 45 * 128, a[ep + 45 * 128] ^ 1)),
        ('EFI attributes', lambda a, ep: struct.pack_into('<Q', a, ep + 45 * 128 + 48, 1)),
        ('EFI start leaves nonadjacent hole', lambda a, ep: struct.pack_into('<Q', a, ep + 45 * 128 + 32, meta['old_end'] + 2)),
        ('userdata attributes', lambda a, ep: a.__setitem__(ep + 48, a[ep + 48] ^ 1)),
        ('userdata identity', lambda a, ep: a.__setitem__(ep + 16, a[ep + 16] ^ 1)),
        ('unrelated entry attributes', lambda a, ep: a.__setitem__(ep + 128 + 48, a[ep + 128 + 48] ^ 1)),
        ('unrelated entry label', lambda a, ep: a.__setitem__(ep + 128 + 56, ord('x'))),
    ]
    for name, mutate in mutations:
        raw = bytearray(planned)
        for role in ('pe', 'be'):
            mutate(raw, f[role])
        legacy.recalc_snap(raw)
        bad.write_bytes(raw)
        legacy.reject_unchanged(f'{sector}:range exact transition rejects changed {name}', sector, disk, before, bad)
    # Valid CRC and bounds still do not allow an arbitrary new partition.
    raw = bytearray(planned)
    for role in ('pe', 'be'):
        off = f[role] + 46 * 128
        raw[off:off + 16] = legacy.EFI_TYPE
        raw[off + 16:off + 32] = uuid.UUID('9ebcae8a-437e-40de-a041-3560d074c550').bytes_le
        struct.pack_into('<QQ', raw, off + 32, 1600, 1615)
        raw[off + 56:off + 66] = 'extra'.encode('utf-16le')
    legacy.recalc_snap(raw)
    bad.write_bytes(raw)
    legacy.reject_unchanged(f'{sector}:range exact transition rejects a second new partition', sector, disk, before, bad)
    check(f'{sector}:all rejected applies preserve the full fixture disk', legacy.hashfile(disk) == initial_hash)
    for path in (disk, before, after, bad):
        path.unlink(missing_ok=True)


def captured_snapshot_case(temp, captured):
    """Validate real captured metadata as four local snapshot-only layouts.

    The source snapshot describes a complete disk but is only a small ordinary
    file. This test calls the native snapshot/planner/transition functions and
    deliberately never calls open_disk/apply or creates a whole-disk fixture.
    Full apply transport is tested separately on the proportional fixtures.
    """
    harness = temp / 'captured-range-harness.c'
    include = str(SOURCE.resolve()).replace('\\', '/')
    code = r'''
#define main gpttool_cli_main
#include INCLUDE_SOURCE
#undef main
int main(int argc,char **argv) {
    struct snapshot base={0},before={0},after={0},canonical={0};
    uint64_t ud=0,efi=0,old_end,delta;unsigned char guid[16];bool ok=false;
    if(argc!=3||!load_snapshot(argv[1],&base))goto done;
    for(uint32_t i=0;i<base.count;i++){
        const unsigned char *e=base.pe+(size_t)i*base.entry_size;
        const unsigned char *b=base.be+(size_t)i*base.entry_size;
        if(!zero(e,16)&&named(e,"efisp")){fail("captured fixture must have no efisp");goto done;}
        if(!zero(e,16)&&named(e,"userdata")){if(ud){fail("ambiguous captured userdata");goto done;}ud=i+1;}
        if(!efi&&zero(e,base.entry_size)&&zero(b,base.entry_size))efi=i+1;
    }
    if(!ud||!efi||!parse_guid("d4052c9a-8972-4dda-982f-9ce47cb21217",guid))goto done;
    old_end=rd64(base.pe+(size_t)(ud-1)*base.entry_size+40);
    delta=UINT64_C(68)*1024*1024/base.sector;
    for(unsigned mode=0;mode<4;mode++){
        uint64_t offset=mode==0?0:mode==1?delta:mode==2?delta*2:UINT64_C(32)*1024*1024/base.sector;
        if(!clone_snapshot(&base,&before))goto done;
        unsigned char *tables[2]={before.pe,before.be},*headers[2]={before.ph,before.bh};
        for(unsigned role=0;role<2;role++){
            wr64(tables[role]+(size_t)(ud-1)*before.entry_size+40,old_end-offset);
            wr32(headers[role]+88,crc32(tables[role],(size_t)before.entry_bytes));
            wr32(headers[role]+16,header_crc(headers[role]));
        }
        if(!validate(&before,true)||!plan_range(&before,ud,old_end-delta,efi,old_end,guid,&after)||!transition(&before,&after))goto done;
        if(mode==0){if(!clone_snapshot(&after,&canonical))goto done;}
        else if(!same_snapshot(&canonical,&after)){fail("captured layout modes must converge on the same exact target");goto done;}
        char a[4096],b[4096];
        int an=snprintf(a,sizeof(a),"%s/captured-%u-before.bin",argv[2],mode);
        int bn=snprintf(b,sizeof(b),"%s/captured-%u-after.bin",argv[2],mode);
        if(an<0||(size_t)an>=sizeof(a)||bn<0||(size_t)bn>=sizeof(b)||!save_pair(a,b,&before,&after))goto done;
        free_snapshot(&before);free_snapshot(&after);
    }
    puts("CAPTURED_LAYOUTS=4\nEXACT_TRANSITIONS=4\nFULL_DISK_OPENED=0\nRESULT=success");ok=true;
done:
    if(!ok)fprintf(stderr,"ERROR=%s\n",error_text);
    free_snapshot(&base);free_snapshot(&before);free_snapshot(&after);free_snapshot(&canonical);
    return ok?0:1;
}
'''.replace('INCLUDE_SOURCE', json.dumps(include))
    harness.write_text(code, encoding='utf-8')
    executable = temp / ('captured-range.exe' if os.name == 'nt' else 'captured-range')
    subprocess.run(['clang', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', str(harness), '-o', str(executable)], check=True)
    output = subprocess.run([str(executable), str(captured.resolve()), str(temp.resolve())], capture_output=True, check=True)
    check('captured device metadata:four range modes reconstruct and transition exactly',
          b'EXACT_TRANSITIONS=4' in output.stdout and b'FULL_DISK_OPENED=0' in output.stdout)
    targets = []
    for mode, label in enumerate(('internal shrink', 'append-only', 'expand userdata', 'combined gaps')):
        before = (temp / f'captured-{mode}-before.bin').read_bytes()
        after = (temp / f'captured-{mode}-after.bin').read_bytes()
        f = legacy.snap_fields(before)
        # Discover indices from the captured entry labels; no model/index map.
        ph = f['ph']
        count, entry_size = struct.unpack_from('<II', before, ph + 80)
        ud_indices = []
        for i in range(count):
            off = f['pe'] + i * entry_size
            label_text = before[off + 56:off + 128].decode('utf-16le').split('\0')[0]
            if before[off:off + 16] != bytes(16) and label_text == 'userdata':
                ud_indices.append(i)
        check('captured:' + label + ':single userdata identity', len(ud_indices) == 1)
        expected = bytearray(before)
        for role in ('pe', 'be'):
            table = f[role]
            for i in range(count):
                a = table + i * entry_size
                if after[a:a + entry_size] == before[a:a + entry_size]:
                    continue
                if i == ud_indices[0]:
                    expected[a + 40:a + 48] = after[a + 40:a + 48]
                else:
                    check('captured:' + label + ':new EFI occupies previously all-zero slot',
                          before[a:a + entry_size] == bytes(entry_size) and after[a:a + 16] == legacy.EFI_TYPE)
                    expected[a:a + entry_size] = after[a:a + entry_size]
        legacy.recalc_snap(expected)
        check('captured:' + label + ':every other snapshot byte preserved', after == expected)
        targets.append(after)
    check('captured:all four original layouts converge to one exact target', len(set(targets)) == 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--captured-snapshot', type=Path,
                        help='optional captured HUBLGT22 snapshot with no efisp; local metadata-only audit')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='gpt-range-fixture-', dir=HERE) as directory:
        temp = Path(directory)
        executable = temp / ('gpttool-host.exe' if os.name == 'nt' else 'gpttool-host')
        subprocess.run(['clang', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', str(SOURCE), '-o', str(executable)], check=True)
        legacy.EXE = executable
        check('new native helper self-test', b'SELF_TEST_RESULT=PASS' in legacy.run(None, '--self-test').stdout)
        for sector in (512, 4096):
            legacy.suite(temp, sector)
            for mode in ('shrink', 'unchanged', 'expand'):
                accept_case(temp, sector, mode)
            accept_case(temp, sector, 'expand', successor=False)
            accept_case(temp, sector, 'unchanged', bounded_end=True)
            rejection_cases(temp, sector)
        if args.captured_snapshot:
            captured_snapshot_case(temp, args.captured_snapshot)
    report = {'result': 'pass', 'tests': len(results), 'passed': len(results), 'failed': 0,
              'scope': 'host temporary regular-file fixtures; no device commands or block device access',
              'sourceSha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(), 'results': results}
    if args.captured_snapshot:
        report['capturedSnapshot'] = {'path': str(args.captured_snapshot.resolve()),
                                     'sha256': hashlib.sha256(args.captured_snapshot.read_bytes()).hexdigest()}
    if args.report:
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(f'PASS: {len(results)} native GPT legacy and range fixture checks')


if __name__ == '__main__':
    main()
