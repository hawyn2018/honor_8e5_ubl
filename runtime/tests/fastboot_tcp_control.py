"""Actual portable C controller + stock Fastboot + actual PC Console interop.

Only the backend and local TCP transport are synthetic. The fake backend does
not implement a microkernel. No USB, real partition or device is accessed.
"""
from pathlib import Path
import argparse
import ctypes as C
import hashlib
import io
import json
import shutil
import socket
import struct
import subprocess
import sys
import threading

HERE = Path(__file__).resolve().parent
RUNTIME = HERE.parent
SOURCE_ROOT = RUNTIME.parent
WORKSPACE = next((p for p in RUNTIME.parents if (p/'platform-tools/fastboot.exe').is_file()), None)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=(WORKSPACE/'analysis/kbl-runtime-20261029/fastboot-tcp-control'
                        if WORKSPACE else SOURCE_ROOT/'build/fastboot-tcp-control'))
    parser.add_argument('--fastboot', type=Path, default=(WORKSPACE/'platform-tools/fastboot.exe' if WORKSPACE else None))
    parser.add_argument('--clang', default=shutil.which('clang.exe') or shutil.which('clang'))
    args = parser.parse_args()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    host_file = SOURCE_ROOT/'tools/kbl_console/kbl_console.py'
    report = {'scope': 'SYNTHETIC localhost TCP and fake backend; real C controller, stock Fastboot, actual PC Console.',
              'passed': False, 'commands': [], 'errors': [], 'checks': {},
              'sources': {p.name: sha(p) for p in [RUNTIME/'kbl_api.h', RUNTIME/'kbl_control.c', host_file]}}
    listener = None; thread = None; stop_event = threading.Event()
    def require(name, condition):
        report['checks'][name] = bool(condition)
        if not condition: raise AssertionError(name)
    try:
        if not args.clang or not args.fastboot or not args.fastboot.is_file():
            raise RuntimeError('Host compiler or stock Fastboot missing')
        sys.path.insert(0, str(host_file.parent))
        from kbl_console import NativeFastboot, Console
        abi_source = output/'fixture-abi.c'
        abi_source.write_text('#include "kbl_api.h"\nsize_t fixture_session_size(void) { return sizeof(struct kbl_session); }\n', encoding='utf-8')
        dll_path = output/'control-fixture.dll'
        compile_cmd = [args.clang, '-std=c11', '-O1', '-Wall', '-Wextra', '-Werror', '-shared',
            '-Wl,--export-all-symbols', '-I', str(RUNTIME), str(RUNTIME/'kbl_control.c'), str(abi_source), '-o', str(dll_path)]
        build = subprocess.run(compile_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        report['build'] = {'command': compile_cmd, 'exit': build.returncode,
                          'stdout': build.stdout.decode(errors='replace'), 'stderr': build.stderr.decode(errors='replace')}
        require('host_dll_build', build.returncode == 0)
        report['dll_sha256'] = sha(dll_path); report['fastboot_sha256'] = sha(args.fastboot)
        lib = C.CDLL(str(dll_path))
        class Session(C.Structure):
            _fields_ = [('phase', C.c_int), ('context', C.c_void_p), ('generation', C.c_uint64),
                ('job', C.c_uint64), ('upload', C.c_uint64), ('el', C.c_uint), ('has_job', C.c_uint),
                ('has_upload', C.c_uint), ('line_bytes', C.c_size_t), ('line', C.c_ubyte * 4096), ('dynamic_line', C.c_void_p), ('line_capacity', C.c_size_t)]
        lib.fixture_session_size.restype = C.c_size_t
        require('ctypes_session_layout_size', C.sizeof(Session) == lib.fixture_session_size())
        EL = C.CFUNCTYPE(C.c_uint, C.c_void_p)
        START = C.CFUNCTYPE(C.c_int, C.c_void_p, C.POINTER(C.c_void_p))
        SUBMIT = C.CFUNCTYPE(C.c_int, C.c_void_p, C.c_void_p, C.c_size_t, C.POINTER(C.c_uint64))
        POLL = C.CFUNCTYPE(C.c_int, C.c_void_p, C.c_uint64, C.c_uint, C.c_void_p,
                         C.c_size_t, C.POINTER(C.c_size_t), C.POINTER(C.c_int), C.POINTER(C.c_int))
        STOP = C.CFUNCTYPE(C.c_int, C.c_void_p)
        class Backend(C.Structure):
            _fields_ = [('opaque', C.c_void_p), ('current_el', EL), ('start', START), ('submit', SUBMIT), ('poll', POLL), ('stop', STOP), ('allocate', C.c_void_p), ('release', C.c_void_p)]
        INFO = C.CFUNCTYPE(None, C.c_void_p, C.c_char_p)
        FINAL = C.CFUNCTYPE(None, C.c_void_p, C.c_int, C.c_char_p)
        class Output(C.Structure):
            _fields_ = [('opaque', C.c_void_p), ('info', INFO), ('final', FINAL)]
        lib.kbl_handle.argtypes = [C.POINTER(Session), C.POINTER(Backend), C.c_void_p, C.c_size_t, C.POINTER(Output)]
        lib.kbl_handle.restype = C.c_int
        state = {'socket': None, 'starts': 0, 'submits': 0, 'polls': 0, 'stops': 0, 'el_calls': 0,
                 'job_output': b'', 'output_at': 0, 'submitted': [], 'budgets': [], 'synthetic_flashes': 0,
                 'native_backend_violations': [], 'connections': 0}
        session = Session(); context = C.create_string_buffer(b'EXPLICIT FAKE BACKEND')
        def exact(sock, count):
            data = bytearray()
            while len(data) < count:
                part = sock.recv(count - len(data))
                if not part: raise EOFError('TCP EOF')
                data.extend(part)
            return bytes(data)
        def packet(data): state['socket'].sendall(struct.pack('>Q', len(data)) + data)
        def callbacks(): return [state[k] for k in ['starts', 'submits', 'polls', 'stops', 'el_calls']]
        @EL
        def el(_): state['el_calls'] += 1; return 1
        @START
        def start(_, handle):
            state['starts'] += 1; handle[0] = C.addressof(context); return 0
        @SUBMIT
        def submit(_, line, count, job):
            state['submits'] += 1; data = C.string_at(line, count); state['submitted'].append(data)
            state['job_output'] = b'fixture output: ' + data + b'\n'; state['output_at'] = 0
            job[0] = state['submits']; return 0
        @POLL
        def poll(_, job, budget, dest, capacity, written, done, rc):
            state['polls'] += 1; state['budgets'].append(budget)
            if job != state['submits']:
                report['errors'].append('wrong backend job'); return -1
            data = state['job_output'][state['output_at']:state['output_at']+capacity]
            C.memmove(dest, data, len(data)); written[0] = len(data); state['output_at'] += len(data)
            done[0] = int(state['output_at'] == len(state['job_output'])); rc[0] = 0; return 0
        @STOP
        def stop(_): state['stops'] += 1; return 0
        @INFO
        def info(_, text):
            try:
                if len(text or b'') > 56: raise RuntimeError('oversized INFO')
                packet(b'INFO' + (text or b''))
            except Exception as ex: report['errors'].append('INFO: '+repr(ex))
        @FINAL
        def final(_, okay, text):
            try: packet((b'OKAY' if okay else b'FAIL') + (text or b''))
            except Exception as ex: report['errors'].append('final: '+repr(ex))
        backend = Backend(None, el, start, submit, poll, stop); wire = Output(None, info, final)
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.bind(('127.0.0.1', 0)); listener.listen(8); listener.settimeout(0.25)
        port = listener.getsockname()[1]
        def server():
            try:
                while not stop_event.is_set():
                    try: conn, peer = listener.accept()
                    except socket.timeout: continue
                    state['connections'] += 1; state['socket'] = conn; conn.settimeout(10)
                    with conn:
                        if peer[0] != '127.0.0.1': raise RuntimeError('Non-loopback peer')
                        if exact(conn, 4) != b'FB01': raise RuntimeError('Bad TCP greeting')
                        conn.sendall(b'FB01')
                        while True:
                            try: size = struct.unpack('>Q', exact(conn, 8))[0]
                            except EOFError: break
                            if size > 4096: raise RuntimeError('Oversized command')
                            command = exact(conn, size); report['commands'].append(command.decode(errors='replace'))
                            raw = C.create_string_buffer(command); before = callbacks()
                            handled = lib.kbl_handle(C.byref(session), C.byref(backend), raw, len(command), C.byref(wire))
                            if handled: continue
                            if callbacks() != before: state['native_backend_violations'].append(command.decode(errors='replace'))
                            if command.startswith(b'getvar:'):
                                key = command[7:]
                                values = {b'fixture': b'synthetic', b'max-download-size': b'0x100000', b'is-userspace': b'yes',
                                    b'current-slot': b'a', b'version': b'0.4', b'product': b'synthetic-kbl-fixture',
                                    b'partition-size:synthetic': b'0x1000', b'partition-type:synthetic': b'raw'}
                                packet(b'OKAY' + values.get(key, b'no'))
                            elif command.startswith(b'download:'):
                                length = int(command[9:], 16); packet(b'DATA' + f'{length:08x}'.encode())
                                total = 0
                                while total < length:
                                    n = struct.unpack('>Q', exact(conn, 8))[0]
                                    if n > length-total: raise RuntimeError('Synthetic download overrun')
                                    exact(conn, n); total += n
                                packet(b'OKAY')
                            elif command == b'flash:synthetic':
                                state['synthetic_flashes'] += 1; packet(b'OKAY')
                            else: packet(b'FAILUnsupported synthetic command')
            except Exception as ex:
                if not stop_event.is_set(): report['errors'].append('server: '+repr(ex))
        thread = threading.Thread(target=server, daemon=True); thread.start()
        transport = NativeFastboot(args.fastboot, ['-s', f'tcp:127.0.0.1:{port}'], timeout=10)
        def console(*items):
            lines = iter(items); stdout = io.StringIO(); stderr = io.StringIO(); prompts = []
            def reader(prompt):
                prompts.append(prompt)
                try: value = next(lines)
                except StopIteration: raise EOFError
                if isinstance(value, BaseException): raise value
                return value
            obj = Console(transport, output=stdout, error=stderr, reader=reader, poll_interval=0, sleeper=lambda _: None)
            return obj, stdout, stderr, prompts
        first, _, _, prompts = console('exit')
        require('start_then_exit_only_detaches', first.attach('kblstart') == 0 and state['starts'] == 1 and state['stops'] == 0)
        require('hash_prompt', prompts == ['# ']); first_id = first.session_id
        ready = transport.request('kblstart')
        require('second_start_idempotent', ready.identifier('id') == first_id and state['starts'] == 1)
        attached, _, _, prompts = console(KeyboardInterrupt())
        require('ctrl_c_only_detaches', attached.attach('kblin') == 130 and state['stops'] == 0)
        require('attach_same_session_no_start', attached.session_id == first_id and state['starts'] == 1 and prompts == ['# '])
        line = 'printf "%s" ' + '中文变量' * 8
        reply = transport.submit(line); job = reply.identifier('job')
        require('utf8_multipart_exact', len(line.encode()) > 100 and state['submitted'] == [line.encode()])
        require('job_busy_after_submit', session.phase == 3 and session.has_job == 1)
        require('multipart_wire_limit', all(len(c.encode('ascii')) <= transport.wire_limit for c in report['commands']))
        head = transport.request('kblpoll', job)
        require('one_poll_preserves_busy_job', head.integer('done') == 0 and session.has_job == 1)
        before = callbacks()
        gv = transport._capture(['getvar', 'fixture'])
        dummy = output/'synthetic-flash-input.bin'; dummy.write_bytes(b'FAKE')
        flash = transport._capture(['flash', 'synthetic', str(dummy)])
        report['native_getvar'] = {'exit': gv.returncode, 'output': (gv.stdout+gv.stderr).decode(errors='replace')}
        report['native_synthetic_flash'] = {'exit': flash.returncode, 'output': (flash.stdout+flash.stderr).decode(errors='replace')}
        require('native_getvar_flash_succeeded_only_in_fixture', gv.returncode == flash.returncode == 0 and state['synthetic_flashes'] == 1)
        require('native_traffic_zero_backend_calls', callbacks() == before and not state['native_backend_violations'])
        resumed, tail, _, prompts = console('exit')
        require('busy_attach_resumes_then_detaches', resumed.attach('kblin') == 0 and prompts == ['# '])
        require('busy_attach_no_new_start_or_submit', resumed.session_id == first_id and state['starts'] == state['submits'] == 1)
        require('output_roundtrip_after_reconnect', head.output + tail.getvalue().encode() == state['job_output'])
        require('poll_budget_and_retention', state['budgets'] and set(state['budgets']) == {2000} and state['stops'] == 0
                and session.phase == 2 and not session.has_job)
        stopped = transport.request('kbloff')
        require('only_kbloff_stops_once', stopped.fields.get('status') == 'stopped' and state['stops'] == 1 and not session.context)
        report['command_utf8_bytes'] = len(line.encode())
        report['session_id'] = first_id
        report['counts'] = {k: v for k, v in state.items() if k not in ['socket', 'job_output', 'submitted']}
        report['submitted_hex'] = [v.hex() for v in state['submitted']]
        report['pc_output_sha256'] = hashlib.sha256(head.output + tail.getvalue().encode()).hexdigest()
        report['passed'] = all(report['checks'].values()) and not report['errors']
    except Exception as ex:
        report['errors'].append(repr(ex))
    finally:
        stop_event.set()
        if thread: thread.join(2)
        if listener: listener.close()
        report_file = output/'validation.json'; report_file.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: report.get(k) for k in ['passed', 'checks', 'command_utf8_bytes', 'counts', 'errors']}, indent=2))
    print(str(report_file))
    return 0 if report['passed'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
