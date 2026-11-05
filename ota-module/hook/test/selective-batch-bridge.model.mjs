// Execute the exact prepared bridge source with Native-state/lock-order ports.
// Usage: node test/selective-batch-bridge.model.mjs PREPARED_BRIDGE_DIRECTORY
// This is an atomicity model, not a claim to execute ART on the host.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';

const root = process.argv[2];
assert(root, 'Supply the prepared frida-java-bridge directory');
const recipe = JSON.parse(fs.readFileSync(new URL('../patches/selective-deoptimization-batch-policy.json', import.meta.url), 'utf8'));
const files = new Map();
for (const item of recipe.files) {
  let raw = fs.readFileSync(path.join(root, item.path), 'utf8');
  const quickLayer=JSON.parse(fs.readFileSync(new URL('../patches/art-quick-code-publication-policy.json',import.meta.url),'utf8')).files.find(entry=>entry.path===item.path);
  if(quickLayer&&createHash('sha256').update(raw).digest('hex')===quickLayer.afterSha256){
    for(const operation of [...quickLayer.operations].reverse()){assert.equal(raw.split(operation.new).length-1,1);raw=raw.replace(operation.new,operation.old);}
    assert.equal(createHash('sha256').update(raw).digest('hex'),quickLayer.beforeSha256);
  }
  if(createHash('sha256').update(raw).digest('hex')!==item.afterSha256){
    const nativeRecipe=JSON.parse(fs.readFileSync(new URL('../patches/native-transaction-filter-policy.json',import.meta.url),'utf8'));
    const layer=nativeRecipe.files.find(entry=>entry.path===item.path);
    assert(layer,'only a recorded independent native-filter layer may follow the batch layer');
    assert.equal(createHash('sha256').update(raw).digest('hex'),layer.afterSha256);
    assert.equal(layer.beforeSha256,item.afterSha256);
    for(const operation of [...layer.operations].reverse()){
      assert.equal(raw.split(operation.new).length-1,1);
      raw=raw.replace(operation.new,operation.old);
    }
  }
  assert.equal(createHash('sha256').update(raw).digest('hex'), item.afterSha256, 'bind exact prepared ' + item.path);
  files.set(item.path, raw);
}
const raw = files.get('lib/android.js');
const part = (start, end) => {
  const a = raw.indexOf(start), b = raw.indexOf(end, a);
  assert(a >= 0 && b > a, start);
  return raw.slice(a, b).replace(/^export /gm, '');
};
const source = part('// Runtime-local only: native ART addresses', 'export function deoptimizeBootImage (') + '\n' +
  part('function requestDeoptimization (', '\nclass JdwpSession') + '\n' +
  part('export function withAllArtThreadsSuspended (', '\nclass ArtClassVisitor');
let checks = 0;
function test(name, body) { body(); checks++; console.log('PASS ' + name); }
function pointer(value) { return {value, isNull: () => value === 0, toString: () => String(value)}; }
function fixture({level = 36, enable = false, enabled = false, failAt = -1, query = false,
                  failSuspend = false, failEnable = false, failGlobal = false, missing = '', instrumentationNull = false} = {}) {
  let state = 'native', stopped = false, mutations = 0, resumeCount = 0;
  const events = [];
  const nativeApplied = new Set();
  const enabledAddress = {readU8() { assert(stopped); events.push('enabled-read'); return enabled ? 1 : 0; }};
  const instrumentation = {add(offset) {
    assert(!stopped, 'resolve metadata before suspension'); assert.equal(offset, 17);
    events.push('address'); return enabledAddress;
  }};
  const api = {
    artInstrumentation: instrumentationNull ? null : instrumentation,
    artThreadList: {name: 'thread-list'},
    'art::ThreadList::SuspendAll'(list, cause, long) {
      assert.equal(state, 'native'); assert(!stopped); assert.equal(long, 0); events.push('suspend');
      if (failSuspend) throw Error('suspend failure'); stopped = true;
    },
    'art::ThreadList::ResumeAll'() {
      assert(stopped); events.push('resume'); stopped = false; resumeCount++;
    },
    'art::Instrumentation::Deoptimize'(target, method) {
      assert(stopped, 'ART mutators must be stopped'); assert.equal(state, 'native');
      assert.strictEqual(target, instrumentation); assert(method.value > 1000);
      assert(!nativeApplied.has(method.value), 'ART rejects repeated native selective deoptimization');
      events.push('deopt:' + method.value); const index = mutations++;
      if (index === failAt) throw Error('native selective failure');
      nativeApplied.add(method.value);
    },
    'art::Instrumentation::DeoptimizeEverything'() {
      assert.equal(state, 'runnable'); assert(!stopped); events.push('global');
      if (failGlobal) throw Error('global failure');
    },
    isJdwpStarted: () => true, isDebuggerActive: () => true,
    'art::Dbg::RequestDeoptimization'() { assert.equal(state, 'runnable'); assert(!stopped); events.push('request'); },
    'art::Dbg::ManageDeoptimization'() { assert.equal(state, 'runnable'); events.push('manage'); }
  };
  if (query) api['art::Instrumentation::IsDeoptimized'] = (_target, method) => {
    assert(stopped, 'query and mutation share the exclusive lock'); assert.equal(state, 'native');
    events.push('query:' + method.value); return nativeApplied.has(method.value);
  };
  if (enable) api['art::Instrumentation::EnableDeoptimization'] = () => {
    assert(stopped); events.push('enable'); if (failEnable) throw Error('enable failure'); enabled = true;
  };
  if (missing) delete api[missing];
  const memory = {writeU32() { return memory; }, add() { return memory; }, writePointer() { return memory; }};
  const context = {
    Set: class extends Set {
      add(value) { assert(!stopped, 'registry/Set mutation must wait until ART threads resume'); return super.add(value); }
    },
    kSelectiveDeoptimization: 2, kFullDeoptimization: 3, pointerSize: 8, jdwpSessions: [],
    getAndroidApiLevel() { assert(!stopped); events.push('api-level'); return level; },
    getApi() { assert(!stopped, 'no lazy ART lookup inside STW'); events.push('api'); return api; },
    getArtInstrumentationSpec() { assert(!stopped); events.push('spec'); return {offset: {deoptimizationEnabled: 17}}; },
    unwrapMethodId(method) {
      assert.equal(state, 'native'); assert(!stopped, 'decode opaque IDs before mutation');
      events.push('decode:' + method.value); if (method.value === -1) throw Error('decode failure');
      return pointer(method.value + 1000);
    },
    withRunnableArtThread(_vm, _env, body) {
      assert(!stopped); events.push('runnable-enter'); state = 'runnable';
      try { return body(); } finally { state = 'native'; events.push('runnable-exit'); }
    },
    Memory: {alloc() { assert(!stopped); return memory; }, allocUtf8String(value) { assert(!stopped); return value; }},
    startJdwp() { throw Error('fixture never starts JDWP'); }
  };
  vm.createContext(context); vm.runInContext(source, context);
  return {
    events, context, pointers: count => Array.from({length: count}, (_, i) => pointer(i + 1)),
    run: (methods, options) => JSON.parse(JSON.stringify(context.deoptimizeMethods({}, {}, methods, options))),
    single: handle => context.deoptimizeMethod({}, {}, handle),
    global: () => context.deoptimizeEverything({}, {}), nativeApplied,
    state: () => ({state, stopped, mutations, resumeCount})
  };
}

test('all opaque JNI IDs decode before the first exclusive suspension', () => {
  const f = fixture(); const result = f.run(f.pointers(70));
  assert.deepEqual(result, {count: 70, batches: 3, batch_size: 32, strategy: 'selective_batch', skipped: 0, requested_count: 70, already_global: false});
  assert(f.events.indexOf('decode:70') < f.events.indexOf('suspend'));
  assert.equal(f.events.filter(e => e === 'suspend').length, 3);
  assert.equal(f.state().resumeCount, 3); assert.equal(f.state().stopped, false);
});
test('2756 methods use 87 short batches instead of 2756 suspension cycles', () => {
  const f = fixture(); const result = f.run(f.pointers(2756));
  assert.equal(result.count, 2756); assert.equal(result.batches, 87);
  let inBatch = 0, maxBatch = 0;
  for (const event of f.events) {
    if (event === 'suspend') inBatch = 0;
    if (event.startsWith('deopt:')) maxBatch = Math.max(maxBatch, ++inBatch);
  }
  assert.equal(maxBatch, 32); assert.equal(f.state().resumeCount, 87);
});
test('configured short batch size is honored without crossing its bound', () => {
  const f = fixture(); assert.equal(f.run(f.pointers(65), {batchSize: 16}).batches, 5);
});
test('maximum batch size is bounded and zero/negative/noninteger/oversized inputs reject before decode', () => {
  for (const batchSize of [0, -1, 1.5, 65, Infinity, '32']) {
    const f = fixture(); assert.throws(() => f.run(f.pointers(1), {batchSize}), /batchSize/);
    assert(!f.events.some(e => e.startsWith('decode:'))); assert(!f.events.includes('suspend'));
  }
  const f = fixture(); assert.equal(f.run(f.pointers(65), {batchSize: 64}).batches, 2);
});
test('decoded duplicate handles mutate the underlying ArtMethod once', () => {
  const f = fixture(); const result = f.run([pointer(1), pointer(2), pointer(1)]);
  assert.equal(result.count, 2); assert.equal(f.state().mutations, 2);
});
test('empty method list does not acquire a global lock or initialize ART APIs', () => {
  const f = fixture(); assert.deepEqual(f.run([]), {count: 0, batches: 0, batch_size: 32, strategy: 'selective_batch', skipped: 0, requested_count: 0, already_global: false});
  assert(!f.events.includes('api')); assert(!f.events.includes('suspend'));
});
test('invalid collection and null or zero handles reject before any native mutation', () => {
  for (const methods of [null, {}, [null], [pointer(0)], [pointer(1), undefined]]) {
    const f = fixture(); assert.throws(() => f.run(methods), /array|handle/);
    assert(!f.events.includes('suspend')); assert.equal(f.state().mutations, 0);
  }
});
test('late decode failure is preflight-only and does not partially mutate ART', () => {
  const f = fixture(); assert.throws(() => f.run([pointer(1), pointer(-1)]), /decode failure/);
  assert(!f.events.includes('suspend')); assert.equal(f.state().mutations, 0);
});
test('EnableDeoptimization metadata resolves before STW and enable runs only once', () => {
  const f = fixture({enable: true}); f.run(f.pointers(40));
  assert(f.events.indexOf('spec') < f.events.indexOf('suspend'));
  assert(f.events.indexOf('address') < f.events.indexOf('suspend'));
  assert.equal(f.events.filter(e => e === 'enable').length, 1);
});
test('already enabled ART never repeats EnableDeoptimization', () => {
  const f = fixture({enable: true, enabled: true}); f.run(f.pointers(40)); assert(!f.events.includes('enable'));
});
test('failure in second batch resumes threads and does not enter a later batch', () => {
  const f = fixture({failAt: 35}); assert.throws(() => f.run(f.pointers(80)), /native selective failure/);
  assert.equal(f.state().mutations, 36); assert.equal(f.state().resumeCount, 2);
  assert.equal(f.events.at(-1), 'resume'); assert.equal(f.state().stopped, false);
});
test('enable failure resumes threads before propagating and never changes a method', () => {
  const f = fixture({enable: true, failEnable: true}); assert.throws(() => f.run(f.pointers(2)), /enable failure/);
  assert.equal(f.state().mutations, 0); assert.equal(f.state().resumeCount, 1); assert.equal(f.state().stopped, false);
});
test('SuspendAll failure neither pretends lock ownership nor invokes ResumeAll', () => {
  const f = fixture({failSuspend: true}); assert.throws(() => f.run(f.pointers(2)), /suspend failure/);
  assert.equal(f.state().mutations, 0); assert.equal(f.state().resumeCount, 0); assert.equal(f.state().stopped, false);
});
test('missing ART capability rejects during preflight, never after a suspension', () => {
  for (const missing of ['art::ThreadList::SuspendAll', 'art::ThreadList::ResumeAll', 'art::Instrumentation::Deoptimize']) {
    const f = fixture({missing}); assert.throws(() => f.run(f.pointers(2)), /API unavailable/); assert(!f.events.includes('suspend'));
  }
  const f = fixture({instrumentationNull: true}); assert.throws(() => f.run(f.pointers(2)), /Instrumentation unavailable/); assert(!f.events.includes('suspend'));
});
test('Android 24-29 keeps the existing Runnable Dbg request/manage route', () => {
  const f = fixture({level: 29}); const result = f.run(f.pointers(3));
  assert.deepEqual(result, {count: 3, batches: 3, batch_size: 1, strategy: 'selective_legacy', skipped: 0, requested_count: 3, already_global: false});
  assert.equal(f.events.filter(e => e === 'request').length, 3); assert(!f.events.includes('suspend'));
  assert.equal(f.state().state, 'native');
});
test('unsupported pre-24 runtimes reject before decoding JNI IDs', () => {
  const f = fixture({level: 23}); assert.throws(() => f.run(f.pointers(2)), /only available on Android/);
  assert(!f.events.some(e => e.startsWith('decode:'))); assert(!f.events.includes('suspend'));
});
test('existing single-method route retains exactly one exclusive suspension', () => {
  const f = fixture(); f.single(pointer(4)); assert.equal(f.state().mutations, 1); assert.equal(f.state().resumeCount, 1);
});
test('single then batch and repeated batch/single share one success registry', () => {
  const f = fixture(); f.single(pointer(1)); f.single(pointer(2));
  const batch = f.run([pointer(1), pointer(2), pointer(3)]);
  assert.equal(batch.count, 1); assert.equal(batch.skipped, 2); assert.equal(batch.batches, 1);
  const again = f.run([pointer(1), pointer(2), pointer(3)]);
  assert.equal(again.count, 0); assert.equal(again.skipped, 3); assert.equal(again.batches, 0);
  f.single(pointer(3)); assert.equal(f.state().mutations, 3);
});
test('successful prefix of a failing later batch is retained before retry', () => {
  const f = fixture({failAt: 35}); const methods = f.pointers(80);
  assert.throws(() => f.run(methods), /native selective failure/);
  const retry = f.run(methods);
  assert.equal(retry.skipped, 35); assert.equal(retry.count, 45);
  assert.equal(f.nativeApplied.size, 80); assert.equal(f.state().stopped, false);
  assert.equal(f.state().mutations, 81, 'one failed native attempt, eighty successful unique methods');
});
test('optional official query detects externally deoptimized methods atomically', () => {
  const f = fixture({query: true}); f.nativeApplied.add(1002);
  const result = f.run(f.pointers(3));
  assert.equal(result.count, 2); assert.equal(result.skipped, 1);
  assert(f.events.indexOf('query:1001') > f.events.indexOf('suspend'));
  assert.equal(f.state().mutations, 2);
  const repeat = f.run(f.pointers(3)); assert.equal(repeat.count, 0); assert.equal(repeat.skipped, 3);
});
test('official query overrides a stale local registry after external undeoptimization', () => {
  const f = fixture({query: true}); f.single(pointer(1)); f.nativeApplied.delete(1001);
  const result = f.run([pointer(1)]); assert.equal(result.count, 1); assert.equal(result.skipped, 0);
});
test('successful global operation is idempotent and skips later selective native calls', () => {
  const f = fixture(); f.single(pointer(1)); f.global(); f.global();
  const result = f.run(f.pointers(3));
  assert.equal(result.count, 0); assert.equal(result.skipped, 3); assert.equal(result.batches, 0);
  assert.equal(result.already_global, true); assert.equal(f.events.filter(e => e === 'global').length, 1);
  assert.equal(f.state().mutations, 1);
});
test('failed global operation never grants all-method coverage', () => {
  const f = fixture({failGlobal: true}); assert.throws(f.global, /global failure/);
  const result = f.run(f.pointers(3)); assert.equal(result.count, 3); assert.equal(result.already_global, false);
});
test('legacy single/batch repeats also avoid another Dbg request', () => {
  const f = fixture({level: 29}); f.single(pointer(1)); const result = f.run(f.pointers(3));
  assert.equal(result.skipped, 1); assert.equal(result.count, 2);
  assert.equal(f.events.filter(e => e === 'request').length, 3);
});
test('public runtime API forwards its VM/environment and bounded options once', () => {
  const index = files.get('index.js');
  assert(index.includes('  deoptimizeMethod,\n  deoptimizeMethods\n'));
  const match = /  deoptimizeMethods \(methods, options = \{\}\) \{\n([\s\S]*?)\n  \}/.exec(index);
  assert(match); const calls = [], handles = [pointer(1)], options = {batchSize: 8}, env = {}, runtimeVm = {getEnv: () => env};
  const fn = new Function('deoptimizeMethods', 'return function(methods, options={}) {' + match[1] + '\n};')((...args) => { calls.push(args); return 27; });
  assert.equal(fn.call({vm: runtimeVm}, handles, options), 27); assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], [runtimeVm, env, handles, options]);
});
console.log(JSON.stringify({passed: true, tests: checks, preparedAndroidSha256: recipe.files[0].afterSha256,
  nativeCallsModeled: true, ARTExecuted: false, deviceOperations: false}));
