import {createProcessMethodCache} from './method-resolution-cache.js';
export const deoptimizationPolicy = 'critical-dispatch-native-barrier-v1';

const primitive = {void:'V',boolean:'Z',byte:'B',char:'C',short:'S',int:'I',long:'J',float:'F',double:'D'};
const dexType = value => primitive[value] || (value.startsWith('[') ? value.replace(/\./g, '/') : 'L' + value.replace(/\./g, '/') + ';');
const descriptor = item => dexType(item.class_name) + '->' + item.method_name + '(' + item.parameter_types.map(dexType).join('') + ')' + dexType(item.return_type);
function requiredMethods(mapping) {
  const roles = new Set(['reboot_gate','reboot_terminal','reboot_context','countdown','countdown_timer','payload_start',
    'full_package','version_response','version_cache_query','payload_complete']);
  const required = [];
  for (const item of mapping.hooks || []) if (roles.has(item.role)) {
    required.push(descriptor(item));
    if (item.role === 'countdown_timer') required.push('Landroid/os/CountDownTimer;->start()Landroid/os/CountDownTimer;');
  }
  for (const item of mapping.reboot_continuations || []) {
    required.push(descriptor({class_name:item.class_name, ...item.callback}));
    for (const constructor of item.constructors) required.push(descriptor({class_name:item.class_name,
      method_name:'<init>',parameter_types:constructor.parameter_types,return_type:'void'}));
  }
  return required;
}

export function decodeDeoptimizationMethods(Java, plan) {
  if (Array.isArray(plan.methods)) return plan.methods;
  if (plan.encoding !== 'gzip-base64-json-v1' || typeof plan.methods_gzip_base64 !== 'string')
    throw new Error('Selective method encoding unavailable');
  const Base64 = Java.use('android.util.Base64');
  const bytes = Base64.decode.overload('java.lang.String', 'int').call(Base64, plan.methods_gzip_base64, 0);
  let input = null, reader = null;
  try {
    input = Java.use('java.util.zip.GZIPInputStream').$new(Java.use('java.io.ByteArrayInputStream').$new(bytes));
    reader = Java.use('java.io.BufferedReader').$new(Java.use('java.io.InputStreamReader').$new(input, 'UTF-8'));
    const lines = []; let line;
    while ((line = reader.readLine()) !== null) lines.push(String(line));
    const methods = JSON.parse(lines.join('\n'));
    if (!Array.isArray(methods)) throw new Error('Selective method payload is not an array');
    return methods;
  } finally {
    if (reader !== null) reader.close(); else if (input !== null) input.close();
  }
}

/** One synchronous native invocation belonging to an already consumed grant. */
export function createNativeRebootScope(threadId) {
  const current = new Map();
  return {
    run(body) {
      const thread = String(threadId()), prior = current.get(thread), scope = {used: false};
      current.set(thread, scope);
      try { return body(); }
      finally { if (prior === undefined) current.delete(thread); else current.set(thread, prior); }
    },
    take() {
      const scope = current.get(String(threadId()));
      if (!scope || scope.used) return false;
      scope.used = true; return true;
    }
  };
}

/** Selective dispatch improves normal continuation. Native IPC is the final
 * non-inline barrier; this deliberately does not claim full CHA inline coverage. */
export function applyCriticalDeoptimization({Java, mapping, nativeReady, warn, resolver = createProcessMethodCache(Java), onMappingInvalid, now = Date.now}) {
  const started = now(), plan = mapping && mapping.deoptimization_plan;
  let reason = '', count = 0, resolving = false;
  try {
    if (!nativeReady) throw new Error('Native reboot barrier unavailable');
    if (typeof Java.deoptimizeMethod !== 'function' && typeof Java.deoptimizeMethods !== 'function')
      throw new Error('Selective ART deoptimization API unavailable');
    resolving = true;
    const specifications = plan && decodeDeoptimizationMethods(Java, plan);
    if (!plan || plan.policy !== deoptimizationPolicy || plan.requires_native_barrier !== true ||
        plan.full_inline_caller_proof !== false || !Array.isArray(specifications) || !specifications.length ||
        plan.method_count !== specifications.length || !Array.isArray(plan.roots) || !plan.roots.length)
      throw new Error('Selective dispatch plan is missing or incompatible');
    const methods = [], descriptors = new Set(), handles = new Set();
    for (const item of specifications) {
      if (!item || typeof item.class_name !== 'string' || typeof item.method_name !== 'string' ||
          typeof item.dex_method !== 'string' || !Array.isArray(item.parameter_types) || typeof item.return_type !== 'string' ||
          typeof item.is_static !== 'boolean' || descriptors.has(item.dex_method) || descriptor(item) !== item.dex_method)
        throw new Error('Selective method descriptor invalid or duplicated');
      const {handle} = resolver.resolve(item, {validateBody:true,requireHandle:true});
      descriptors.add(item.dex_method);
      const identity = handle.toString();
      if (!handles.has(identity)) { handles.add(identity); methods.push(handle); }
    }
    if (!plan.roots.every(name => typeof name === 'string' && descriptors.has(name)) ||
        !requiredMethods(mapping).every(name => descriptors.has(name)))
      throw new Error('Selective plan omits a critical root');
    resolving = false;
    const resolvedMs = now() - started;
    // The bridge prepares all opaque IDs and native APIs before mutation. Its
    // bounded batches each resume ART threads before the next suspension.
    let batches = 0, mutations = 0, skipped = 0;
    if (typeof Java.deoptimizeMethods === 'function') {
      const result = Java.deoptimizeMethods(methods);
      count = methods.length; batches = result && Number.isInteger(result.batches) ? result.batches : 0;
      mutations = result && Number.isInteger(result.count) ? result.count : count;
      skipped = result && Number.isInteger(result.skipped) ? result.skipped : 0;
    } else {
      for (const handle of methods) { Java.deoptimizeMethod(handle); count++; batches++; mutations++; }
    }
    return {ready: true, strategy: 'selective_native_barrier', method_count: count, elapsed_ms: now() - started,
      resolution_ms:resolvedMs,batch_count:batches,native_mutations:mutations,already_deoptimized:skipped,resolution_cache:resolver.inspect(),
      full_inline_caller_proof: false, native_barrier: true};
  } catch (failure) { reason = String(failure); }
  if (resolving && typeof onMappingInvalid === 'function') onMappingInvalid(reason);
  try { warn('Selective deoptimization unavailable; using existing full-process coverage', reason); } catch (_) {}
  if (typeof Java.deoptimizeEverything !== 'function') throw new Error('ART fallback deoptimization unavailable: ' + reason);
  Java.deoptimizeEverything();
  return {ready: true, strategy: 'global_fallback', selective_method_count: count, fallback_reason: reason,
    elapsed_ms: now() - started, native_barrier: nativeReady === true};
}
