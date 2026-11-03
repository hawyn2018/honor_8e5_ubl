"""One synthetic loopback acceptance attempt with the stock Fastboot client.

Actual kbl_fetch.c is built into a host DLL. Python provides fake in-memory
readers and the TCP transport callbacks. No phone, partition or UEFI is used.
This checks client/server framing only, not production USB/firmware integration.
"""
from pathlib import Path
import argparse
import ctypes as C
import hashlib
import json
import shutil
import socket
import struct
import subprocess
import threading

HERE = Path(__file__).resolve().parent
RUNTIME = HERE.parent
WORKSPACE = next((p for p in RUNTIME.parents if (p/'platform-tools/fastboot.exe').is_file()), None)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=(WORKSPACE/'analysis/kbl-runtime-20261029/fastboot-tcp-fetch'
                        if WORKSPACE else RUNTIME.parent/'build/fastboot-tcp-fetch'))
    parser.add_argument('--fastboot', type=Path, default=(WORKSPACE/'platform-tools/fastboot.exe' if WORKSPACE else None))
    parser.add_argument('--clang', default=shutil.which('clang.exe') or shutil.which('clang'))
    args = parser.parse_args()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    report = {'scope': 'SYNTHETIC localhost TCP; actual C fetch module + stock host client. No device/UEFI/USB access.',
              'attempts': 0, 'commands': [], 'events': [], 'errors': [], 'passed': False}
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    report['sources'] = {p.name: sha(p) for p in [RUNTIME/'kbl_control.c', RUNTIME/'kbl_fetch.c']}
    report_file = output/'validation.json'
    def save():
        report_file.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    try:
        if not args.clang or not args.fastboot or not args.fastboot.is_file():
            raise RuntimeError('Host clang or stock Fastboot not found')
        dll_path = output/'fetch-fixture.dll'
        build_cmd = [args.clang, '-std=c11', '-O1', '-Wall', '-Wextra', '-Werror', '-shared',
                     '-Wl,--export-all-symbols', '-I', str(RUNTIME), str(RUNTIME/'kbl_control.c'),
                     str(RUNTIME/'kbl_fetch.c'), '-o', str(dll_path)]
        build = subprocess.run(build_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        report['build'] = {'command': build_cmd, 'exit': build.returncode,
                           'stdout': build.stdout.decode(errors='replace'), 'stderr': build.stderr.decode(errors='replace')}
        if build.returncode:
            raise RuntimeError('Fixture DLL build failed')
        report['dll_sha256'] = sha(dll_path); report['fastboot_sha256'] = sha(args.fastboot)
        lib = C.CDLL(str(dll_path))
        READ = C.CFUNCTYPE(C.c_int, C.c_void_p, C.c_uint64, C.c_void_p, C.c_size_t, C.POINTER(C.c_size_t))
        CLOSE = C.CFUNCTYPE(None, C.c_void_p)
        class Reader(C.Structure):
            _fields_ = [('opaque', C.c_void_p), ('size', C.c_uint64), ('read_at', READ), ('close', CLOSE)]
        OPEN = C.CFUNCTYPE(C.c_int, C.c_void_p, C.c_char_p, C.POINTER(Reader))
        class Source(C.Structure):
            _fields_ = [('opaque', C.c_void_p), ('open', OPEN)]
        DATA = C.CFUNCTYPE(C.c_int, C.c_void_p, C.c_uint32)
        WRITE = C.CFUNCTYPE(C.c_int, C.c_void_p, C.c_void_p, C.c_size_t, C.POINTER(C.c_size_t))
        FINAL = C.CFUNCTYPE(None, C.c_void_p, C.c_int, C.c_char_p)
        ABORT = C.CFUNCTYPE(None, C.c_void_p)
        class Wire(C.Structure):
            _fields_ = [('opaque', C.c_void_p), ('data', DATA), ('write', WRITE), ('final', FINAL), ('abort', ABORT)]
        lib.kbl_fetch.argtypes = [C.c_void_p, C.c_size_t, C.POINTER(Source), C.POINTER(Wire), C.c_void_p, C.c_size_t]
        lib.kbl_fetch.restype = C.c_int
        payload = bytearray((i * 17) & 255 for i in range(1025)); payload[300:312] = b'FAILOKAYDATA'
        payload = bytes(payload); report['expected_bytes'] = len(payload)
        report['expected_sha256'] = hashlib.sha256(payload).hexdigest()
        state = {'socket': None, 'opens': 0, 'closes': 0, 'read_calls': 0,
                 'writes': 0, 'announced': [], 'finals': 0, 'aborts': 0}
        def exact(sock, count):
            data = bytearray()
            while len(data) < count:
                part = sock.recv(count - len(data))
                if not part:
                    raise EOFError('TCP EOF')
                data.extend(part)
            return bytes(data)
        def packet(data):
            state['socket'].sendall(struct.pack('>Q', len(data)) + data)
        @READ
        def read_at(_, offset, dest, count, got):
            if offset > len(payload): return -1
            n = min(int(count), len(payload)-offset, 19)
            C.memmove(dest, payload[offset:offset+n], n); got[0] = n
            state['read_calls'] += 1; return 0
        @CLOSE
        def close(_): state['closes'] += 1
        @OPEN
        def open_source(_, name, reader):
            if name != b'kbl.fixture': return -1
            state['opens'] += 1
            value = Reader(None, len(payload), read_at, close)
            C.memmove(reader, C.byref(value), C.sizeof(value)); return 0
        @DATA
        def data_header(_, count):
            try:
                if state['closes'] != state['opens']: raise RuntimeError('Header before close')
                state['announced'].append(count)
                packet(b'DATA' + f'{count:08x}'.encode()); return 0
            except Exception as ex:
                report['errors'].append('data: '+repr(ex)); return -1
        @WRITE
        def write(_, data, count, written):
            try:
                n = min(int(count), 37); packet(C.string_at(data, n)); written[0] = n
                state['writes'] += 1; return 0
            except Exception as ex:
                report['errors'].append('write: '+repr(ex)); written[0] = 0; return -1
        @FINAL
        def final(_, okay, reason):
            try:
                state['finals'] += 1; packet((b'OKAY' if okay else b'FAIL') + (reason or b''))
            except Exception as ex: report['errors'].append('final: '+repr(ex))
        @ABORT
        def abort(_):
            state['aborts'] += 1
            try: state['socket'].shutdown(socket.SHUT_RDWR)
            except OSError: pass
        source = Source(None, open_source); wire = Wire(None, data_header, write, final, abort)
        workspace = C.create_string_buffer(256)
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.bind(('127.0.0.1', 0)); listener.listen(1); listener.settimeout(15)
        port = listener.getsockname()[1]
        def server():
            try:
                conn, peer = listener.accept(); report['peer_loopback'] = peer[0] == '127.0.0.1'
                conn.settimeout(15); state['socket'] = conn
                with conn:
                    greeting = exact(conn, 4); report['greeting'] = greeting.decode(errors='replace')
                    if greeting != b'FB01': raise RuntimeError('Unexpected TCP greeting')
                    conn.sendall(b'FB01')
                    while True:
                        try: size = struct.unpack('>Q', exact(conn, 8))[0]
                        except EOFError: break
                        if size > 4096: raise RuntimeError('Oversized client frame')
                        command = exact(conn, size); report['commands'].append(command.decode(errors='replace'))
                        if command.startswith(b'getvar:'):
                            key = command[7:]
                            values = {b'max-fetch-size': b'0x100', b'partition-size:kbl.fixture': b'0x401',
                                b'has-slot:kbl.fixture': b'no', b'is-userspace': b'yes', b'version': b'0.4',
                                b'product': b'synthetic-kbl-fixture', b'current-slot': b'a'}
                            packet(b'OKAY' + values[key] if key in values else b'FAILUnknown fixture variable')
                        elif command.startswith(b'fetch:'):
                            raw = C.create_string_buffer(command)
                            handled = lib.kbl_fetch(raw, len(command), C.byref(source), C.byref(wire), workspace, len(workspace))
                            if handled != 1: raise RuntimeError('C module did not handle fetch')
                        else: packet(b'FAILUnsupported fixture command')
            except Exception as ex:
                report['errors'].append('server: '+repr(ex))
            finally: listener.close()
        thread = threading.Thread(target=server, daemon=True); thread.start()
        outfile = output/'fetched.bin'
        command = [str(args.fastboot), '-s', f'tcp:127.0.0.1:{port}', 'fetch', 'kbl.fixture', str(outfile)]
        report['attempts'] = 1; report['client_command'] = command
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25)
            report['client_exit'] = result.returncode
            report['client_stdout'] = result.stdout.decode(errors='replace')
            report['client_stderr'] = result.stderr.decode(errors='replace')
        except subprocess.TimeoutExpired as ex:
            report['errors'].append('client timeout')
            report['client_stdout'] = (ex.stdout or b'').decode(errors='replace')
            report['client_stderr'] = (ex.stderr or b'').decode(errors='replace')
        thread.join(17)
        report['server_thread_ended'] = not thread.is_alive()
        report['callbacks'] = {k: v for k, v in state.items() if k != 'socket'}
        report['fetched_bytes'] = outfile.stat().st_size if outfile.is_file() else None
        report['fetched_sha256'] = sha(outfile) if outfile.is_file() else None
        report['passed'] = (report.get('client_exit') == 0 and report['fetched_sha256'] == report['expected_sha256']
            and state['opens'] == state['closes'] == len(state['announced']) == state['finals']
            and state['opens'] > 1 and not state['aborts'] and not report['errors'] and not thread.is_alive())
    except Exception as ex:
        report['errors'].append(repr(ex))
    save()
    print(json.dumps({k: report.get(k) for k in ['passed', 'attempts', 'client_exit', 'commands', 'fetched_bytes',
            'expected_sha256', 'fetched_sha256', 'errors']}, indent=2))
    print(str(report_file))
    return 0 if report['passed'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
