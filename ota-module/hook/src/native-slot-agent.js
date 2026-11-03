import {inside, locateNativeSlots} from './native-slot-locator.js';
import {createSlotDeferral} from './native-slot-policy.js';

// Separate native-only update_engine agent. It never alters headers, manifests,
// verification, FinishUpdate, result codes or the updater's original callbacks.
let initialized = false, ready = false, parameters = null, tracker = null;
let replacement = null, originalSetActive = null;
let replacedAddress = null;
const listeners = [], warnings = [];
let sequence = 0;

let nativeLog = null, logLookupAttempted = false;

// Diagnostic-only: never retries a transaction or writes completion evidence.
function emitNative(item) {
  const record = {engine_pid: Process.id, ...item};
  try { send(record); } catch (_) {}
  try {
    if (!logLookupAttempted) {
      logLookupAttempted = true;
      const library = Process.getModuleByName('liblog.so');
      nativeLog = new NativeFunction(library.getExportByName('__android_log_write'), 'int', ['int', 'pointer', 'pointer']);
    }
    if (nativeLog !== null)
      nativeLog(4, Memory.allocUtf8String('hnubl-ota'), Memory.allocUtf8String(JSON.stringify(record)));
  } catch (_) { /* Logging never changes the original native callback. */ }
}

function warning(message, failure) {
  const item = {message: String(message), details: failure == null ? '' : String(failure)};
  warnings.push(item);
  try { emitNative({event: 'native_warning', ...item}); } catch (_) {}
}

function libcWire(params) {
  const libc = Process.getModuleByName('libc.so');
  const native = (name, result, args) => new NativeFunction(libc.getExportByName(name), result, args);
  const socket = native('socket', 'int', ['int', 'int', 'int']);
  const connect = native('connect', 'int', ['int', 'pointer', 'uint']);
  const close = native('close', 'int', ['int']);
  const read = native('read', 'long', ['int', 'pointer', 'ulong']);
  // MSG_NOSIGNAL prevents an endpoint disappearing mid-frame from delivering
  // SIGPIPE to update_engine. Native failure must preserve the original flow.
  const write = native('send', 'long', ['int', 'pointer', 'ulong', 'int']);
  const options = native('setsockopt', 'int', ['int', 'int', 'int', 'pointer', 'uint']);
  const peer = native('getsockopt', 'int', ['int', 'int', 'int', 'pointer', 'pointer']);
  const byteLength = text => unescape(encodeURIComponent(text)).length;
  const pathBytes = byteLength(params.endpoint);
  if (!params.endpoint.startsWith('/') || pathBytes > 107 || params.endpoint.includes('\0'))
    throw new Error('Invalid native Unix endpoint');
  return (event, fields) => {
    const nonce = Process.id + '-' + Date.now() + '-' + (++sequence);
    const request = JSON.stringify({protocol: 1, activation_id: params.activation_id, boot_id: params.boot_id,
      pid: Process.id, start_time: params.engine_start, nonce, event, ...fields}) + '\n';
    const fd = socket(1, 1 | 0x80000, 0);
    if (fd < 0) throw new Error('Native broker socket creation failed');
    try {
      const timeout = Memory.alloc(16); timeout.writeS64(3); timeout.add(8).writeS64(0);
      if (options(fd, 1, 20, timeout, 16) !== 0 || options(fd, 1, 21, timeout, 16) !== 0)
        throw new Error('Native broker timeout setup failed');
      const address = Memory.alloc(110); address.writeU16(1); address.add(2).writeUtf8String(params.endpoint);
      if (connect(fd, address, 2 + pathBytes + 1) !== 0) throw new Error('Native broker connect failed');
      const credentials = Memory.alloc(12), length = Memory.alloc(4); length.writeU32(12);
      if (peer(fd, 1, 17, credentials, length) !== 0 || length.readU32() !== 12 || credentials.add(4).readU32() !== 0)
        throw new Error('Native broker peer is not root');
      const bytes = byteLength(request), outgoing = Memory.allocUtf8String(request);
      for (let sent = 0; sent < bytes;) {
        const count = Number(write(fd, outgoing.add(sent), bytes - sent, 0x4000).toString());
        if (count <= 0) throw new Error('Native broker write failed');
        sent += count;
      }
      const buffer = Memory.alloc(1024), all = [];
      for (;;) {
        const count = Number(read(fd, buffer, 1024).toString());
        if (count <= 0) throw new Error('Native broker response missing');
        const part = new Uint8Array(buffer.readByteArray(count));
        for (const value of part) {
          if (value === 10) {
            const encoded = Memory.alloc(all.length + 1); encoded.writeByteArray(all); encoded.add(all.length).writeU8(0);
            const reply = JSON.parse(encoded.readUtf8String());
            if (!reply || reply.protocol !== 1 || reply.nonce !== nonce || reply.activation_id !== params.activation_id ||
                reply.boot_id !== params.boot_id) throw new Error('Native broker response identity mismatch');
            return reply;
          }
          all.push(value);
          if (all.length > 65536) throw new Error('Native broker protocol frame is oversized');
        }
      }
    } finally { close(fd); }
  };
}

function inspect() {
  return {native_ready: ready, native_slot_ready: ready, engine_pid: Process.id,
    activation_id: parameters ? parameters.activation_id : '', warnings: warnings.slice(),
    deferred: tracker ? tracker.snapshot() : null};
}

rpc.exports = {
  init(_stage, params) {
    if (initialized) return inspect();
    initialized = true; parameters = params;
    try {
      if (Process.arch !== 'arm64' || Process.pointerSize !== 8 || !params ||
          Process.id !== params.engine_pid || ![0, 1].includes(params.source_slot) ||
          typeof params.activation_id !== 'string' || !params.activation_id ||
          typeof params.boot_id !== 'string' || !params.boot_id)
        throw new Error('Native engine ABI/process parameters do not match');
      const stat = File.readAllText('/proc/self/stat');
      const fields = stat.slice(stat.lastIndexOf(')') + 2).trim().split(/\s+/);
      if (String(fields[19]) !== String(params.engine_start) ||
          File.readAllText('/proc/sys/kernel/random/boot_id').trim() !== params.boot_id)
        throw new Error('Native engine start/boot identity changed');
      const module = Process.mainModule;
      if (!module || module.path !== params.engine_path) throw new Error('Native executable path does not match');
      const facts = locateNativeSlots(module, address => Instruction.parse(address), address => {
        const range = Process.findRangeByAddress(address); return range !== null && range.protection.includes('x');
      });
      const wire = libcWire(params);
      const hello = wire('HELLO', {});
      if (!hello || !((hello.ok === true && hello.decision === 'READY') ||
          (hello.ok === false && hello.decision === 'WAIT_BEGIN')))
        throw new Error('Native broker is not ready');
      tracker = createSlotDeferral({wire, warn: warning, sourceSlot: params.source_slot});
      listeners.push(Interceptor.attach(facts.complete.address, {
        onEnter(args) { this.hnublThread = this.threadId; tracker.enter(this.hnublThread, args[1].toInt32()); },
        onLeave() { tracker.leave(this.hnublThread); }
      }));
      listeners.push(Interceptor.attach(facts.finish.address, {
        onEnter() { this.hnublThread = this.threadId; this.hnublParent = inside(this.returnAddress, facts.complete); },
        onLeave(value) { tracker.finish(this.hnublThread, this.hnublParent, (value.toInt32() & 255) === 1); }
      }));
      listeners.push(Interceptor.attach(facts.terminate.address, {
        onEnter(args) { this.hnublCode = args[1].toInt32(); this.hnublPending = tracker.snapshot(this.threadId); },
        onLeave() { tracker.terminated(this.hnublPending, this.hnublCode); }
      }));
      replacement = new NativeCallback(function (self, slot) {
        try {
          if (tracker.defer(Process.getCurrentThreadId(), inside(this.returnAddress, facts.complete), slot)) return 1;
        } catch (failure) { warning('Native guard failed; original SetActiveBootSlot retained', failure); }
        return originalSetActive(self, slot);
      }, 'int', ['pointer', 'uint']);
      // Normal replace supplies the invocation context/returnAddress promised
      // by Gum. Calling the target's NativeFunction synchronously inside this
      // replacement uses Gum's documented original-implementation bypass.
      originalSetActive = new NativeFunction(facts.setActive.address, 'int', ['pointer', 'uint']);
      Interceptor.replace(facts.setActive.address, replacement);
      replacedAddress = facts.setActive.address;
      Interceptor.flush(); ready = true;
      try { emitNative({event: 'native_ready', ...inspect(), functions: Object.fromEntries(
        Object.entries(facts).map(([role, fact]) => [role, {name: fact.name, size: fact.size, address: String(fact.address)}]))}); } catch (_) {}
    } catch (failure) {
      if (replacedAddress !== null) { try { Interceptor.revert(replacedAddress); Interceptor.flush(); } catch (_) {} replacedAddress = null; }
      for (const listener of listeners.splice(0)) { try { listener.detach(); } catch (_) {} }
      ready = false; warning('Native slot enhancement unavailable; original update_engine preserved', failure);
    }
    return inspect();
  },
  inspect
};
