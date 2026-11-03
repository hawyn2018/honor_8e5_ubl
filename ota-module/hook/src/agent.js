import Java from 'frida-java-bridge';
import {isAutomatic, isManual, consumeAllows, requiredReady, isCriticalRole, readiness} from './policy.js';
import {filterVersionResponse, parseFirmwareVersion} from './version-policy.js';
import {installRebootLabels} from './reboot-labels.js';
import {installCacheVersionQuery} from './cache-query.js';
import {createRebootContext} from './reboot-context.js';

const TARGET = 'com.hihonor.ouc';
const HNUBL = 'net.hawyn.honor8e5ubl';
let otaSocket = '';
let initialized = false;
let criticalReady = false;
let brokerReady = false;
let initialization = null;
let brokerRetry = null;
let app = null;
let mapping = null;
let forceFullPackage = false;
let cycleResetPending = false;
let payloadStarted = false;
let rebootLabels = null;
const installed = [];
const failures = [];
const warnings = [];
const warningKeys = new Set();
const counts = Object.create(null);
const gateDepths = new Map();
const settingMaps = new Map();
const timerClasses = new Set();
let timerStartPatched = false;
let diagnosticSequence = 0, attemptSequence = 0;
let engineLifecycle = null, rebootPending = null, rebootRunnable = null, rebootJobSequence = 0;
let rebootContext = null, deoptimized = false, continuationReady = false;
const rebootJobs = new Map();
const diagnosticSession = Date.now().toString(36);
const stats = {denied: 0, allowed: 0, automaticBlocked: 0, reset: 0, versionLatest: 0, versionPreserved: 0,
  cacheLatest: 0, cachePreserved: 0};

function telemetry(event) {
  let detail = event;
  try {
    detail = {...event, timestamp_ms: Date.now(), event_seq: ++diagnosticSequence,
      process_id: typeof Process !== 'undefined' && Number.isInteger(Process.id) ? Process.id : null,
      diagnostic_session: diagnosticSession};
  } catch (_) { /* Even an unavailable diagnostic clock/identity cannot alter the gate. */ }
  // The injector has exited after eternalizing. Keep gate decisions available
  // in Android logcat without relying on a live send receiver.
  if (['cycle_reset', 'cycle_reset_failed', 'automatic_reboot_blocked', 'unclassified_reboot_blocked',
    'reboot_authorization_requested', 'reboot_authorization_result',
    'reboot_authorization_transport_error', 'reboot_execution_failed', 'original_reboot_entry_failed',
    'payload_begin_blocked', 'redirect_to_hnubl', 'original_reboot_entry_allowed', 'terminal_reboot_blocked', 'reboot_frontier_passed', 'version_filter', 'cache_version_filter'].includes(event.event)) {
    try {
      const Log = Java.use('android.util.Log');
      Log.i.overload('java.lang.String', 'java.lang.String').call(Log, 'hnubl-ota', JSON.stringify(detail));
    } catch (_) { /* Diagnostic output never replaces the original callback. */ }
  }
  try { send(detail); } catch (_) { /* An absent host is not an updater failure. */ }
}

function reportError(message, details) {
  send({event: 'error', fatal: true, phase: 'critical_initialization',
    message: String(message), details: details || null, ...inspect()});
}

function reportWarning(phase, message, details) {
  const diagnostic = {phase, message: String(message), details: details == null ? null : String(details)};
  const key = JSON.stringify(diagnostic);
  if (!warningKeys.has(key)) {
    warningKeys.add(key);
    warnings.push(diagnostic);
    // The injector intentionally exits after eternalizing. logcat remains a
    // target-process diagnostic channel even when send has no live receiver.
    try {
      Java.use('android.util.Log').w.overload('java.lang.String', 'java.lang.String')
        .call(Java.use('android.util.Log'), 'hnubl-ota', JSON.stringify(diagnostic));
    } catch (_) { /* Logging never replaces a native updater callback. */ }
    // A diagnostic must not replace an original updater callback with a logging
    // exception. The controller treats this event as informational, not a kill.
    try { send({event: 'warning', ...diagnostic, ...readiness(criticalReady, brokerReady)}); }
    catch (_) { /* Best-effort diagnostics. Reboot decisions remain fail-closed. */ }
  }
}

function reportBrokerWarnings(response) {
  if (Array.isArray(response.warnings)) for (const warning of response.warnings) {
    if (typeof warning === 'string' && warning.length > 0) reportWarning('native_slot', warning, null);
  }
  return response;
}

function marker(command) {
  let socket;
  try {
    const LocalSocket = Java.use('android.net.LocalSocket');
    const LocalSocketAddress = Java.use('android.net.LocalSocketAddress');
    const Namespace = Java.use('android.net.LocalSocketAddress$Namespace');
    const Writer = Java.use('java.io.OutputStreamWriter');
    const Reader = Java.use('java.io.BufferedReader');
    const InputReader = Java.use('java.io.InputStreamReader');
    socket = LocalSocket.$new();
    socket.connect(LocalSocketAddress.$new(otaSocket, Namespace.FILESYSTEM.value));
    socket.setSoTimeout(3000);
    if (socket.getPeerCredentials().getUid() !== 0) throw new Error('OTA broker peer is not UID 0');
    const output = Writer.$new(socket.getOutputStream(), 'UTF-8');
    const request = command + '\n';
    Writer.write.overload('java.lang.String', 'int', 'int').call(output, request, 0, request.length);
    output.flush();
    const input = Reader.$new(InputReader.$new(socket.getInputStream(), 'UTF-8'));
    const line = input.readLine();
    if (line === null) throw new Error('OTA broker closed without a response');
    const response = JSON.parse(String(line));
    if (response === null || typeof response !== 'object') throw new Error('Invalid OTA broker response');
    return reportBrokerWarnings(response);
  } finally {
    if (socket !== undefined) { try { socket.close(); } catch (_) {} }
  }
}

function openHnubl() {
  try { Java.scheduleOnMainThread(() => {
    try {
      const Intent = Java.use('android.content.Intent');
      const intent = Intent.$new();
      intent.setClassName(HNUBL, 'dev.honor.bootforge.MainActivity');
      intent.putExtra.overload('java.lang.String', 'boolean').call(intent, 'open_ota', true);
      intent.addFlags(0x10000000 | 0x20000000 | 0x04000000);
      app.startActivity(intent);
    } catch (error) {
      reportWarning('handoff', 'Open hnubl OTA failed; original reboot remains blocked', error);
    }
  }); } catch (error) {
    reportWarning('handoff', 'Scheduling hnubl OTA failed; original reboot remains blocked', error);
  }
}

function resetCycle(source, command = 'RESET') {
  try {
    const response = marker(command);
    if (response.ok !== true || response.state !== 0) throw new Error('Broker did not prove marker reset');
    brokerReady = true;
    cycleResetPending = false;
    stats.reset++;
    telemetry({event: 'cycle_reset', source, command, diagnostic_id: response.diagnostic_id || null});
    return true;
  } catch (error) {
    cycleResetPending = true;
    brokerReady = false;
    telemetry({event: 'cycle_reset_failed', source, command, reason: String(error)});
    reportWarning('cycle_reset', 'New OTA marker reset failed; reboot gate remains closed', error);
    return false;
  }
}

function original(overload, receiver, args) {
  return overload.call(receiver, ...args);
}

function originalReboot(overload, receiver, args) {
  if (rebootLabels === null) return original(overload, receiver, args);
  let called = false, result, nativeFailed = false, nativeFailure;
  const invoke = () => {
    if (called) return result;
    called = true;
    try { result = original(overload, receiver, args); return result; }
    catch (error) { nativeFailed = true; nativeFailure = error; throw error; }
  };
  try {
    rebootLabels.aroundOriginal(invoke);
    if (nativeFailed) throw nativeFailure;
    return called ? result : invoke();
  }
  catch (error) {
    if (nativeFailed) throw nativeFailure;
    reportWarning('reboot_labels', 'Optional reboot label restoration failed; preserving original handler', error);
    return called ? result : invoke();
  }
}

function installRebootContext(continuations) {
  if (!Array.isArray(continuations) || continuations.length === 0) throw new Error('No proved original reboot continuation');
  const WeakReference = Java.use('java.lang.ref.WeakReference'), System = Java.use('java.lang.System'), buckets = new Map();
  const bucket = receiver => String(System.identityHashCode(receiver));
  function find(receiver, create, retire = false) {
    const key = bucket(receiver), entries = buckets.get(key) || []; let matched = null;
    for (let i = entries.length - 1; i >= 0; i--) {
      const actual = entries[i].reference.get();
      if (actual === null) { entries[i].reference.$dispose(); entries.splice(i, 1); }
      else if (actual.$isSameObject(receiver)) matched = entries[i];
    }
    if (matched === null && create) { matched = {reference: WeakReference.$new(receiver), kind: 'unknown'}; entries.push(matched); }
    if (matched !== null && retire) { entries.splice(entries.indexOf(matched),1); matched.reference.$dispose(); }
    if (entries.length) buckets.set(key, entries); else buckets.delete(key);
    return matched;
  }
  rebootContext = createRebootContext({
    threadId: () => String(Java.use('java.lang.Thread').currentThread().getId()),
    remember: (receiver, kind) => { find(receiver, true).kind = kind; },
    recall: receiver => { const entry = find(receiver, false, true); return entry === null ? 'unknown' : entry.kind; },
    warn: (message, error) => reportWarning('reboot_context', message, error)
  });
  for (const item of continuations) {
    if (!item || typeof item.class_name !== 'string' || !Array.isArray(item.constructors) || !item.constructors.length ||
        !item.callback || item.callback.method_name !== 'run' || item.callback.return_type !== 'void' ||
        item.callback.is_static !== false || !Array.isArray(item.callback.parameter_types) || item.callback.parameter_types.length)
      throw new Error('Original reboot continuation ABI is not proved');
    const type = Java.use(item.class_name);
    for (const ctor of item.constructors) {
      if (!Array.isArray(ctor.parameter_types)) throw new Error('Reboot constructor ABI missing');
      const overload = type.$init.overload(...ctor.parameter_types);
      overload.implementation = function (...args) { const result = original(overload, this, args); rebootContext.capture(this); return result; };
    }
    const run = type[item.callback.method_name].overload();
    run.implementation = function () { return rebootContext.resume(this, () => original(run, this, [])); };
    counts.reboot_continuation = (counts.reboot_continuation || 0) + 1;
    installed.push({role:'reboot_continuation',class_name:item.class_name,method_name:item.callback.method_name,parameter_types:[]});
    telemetry({event:'continuation_bound',class_name:item.class_name});
  }
  continuationReady = counts.reboot_continuation === continuations.length;
}

function bindEngineLifecycle(spec) {
  if (!spec || typeof spec.class_name !== 'string' || !spec.factory || !spec.start || !spec.stop) return null;
  const valid = (method, result, isStatic) => method && typeof method.method_name === 'string' &&
    Array.isArray(method.parameter_types) && method.parameter_types.length === 0 && method.return_type === result && method.is_static === isStatic;
  if (!valid(spec.factory, spec.class_name, true) || !valid(spec.start, 'boolean', false) || !valid(spec.stop, 'void', false))
    throw new Error('Vendor engine lifecycle descriptors are not proven');
  const type = Java.use(spec.class_name), factory = type[spec.factory.method_name].overload(),
    start = type[spec.start.method_name].overload(), stop = type[spec.stop.method_name].overload();
  return {acquire() {
    const instance = Java.retain(factory.call(type));
    if (instance === null) throw new Error('Vendor engine factory returned null');
    let acquired = false;
    try {
      acquired = start.call(instance) === true;
      if (!acquired) throw new Error('Vendor engine start did not return true');
      let released = false;
      return {release() {
        if (released) return; released = true;
        try { stop.call(instance); } finally { instance.$dispose(); }
      }};
    } finally { if (!acquired) instance.$dispose(); }
  }};
}

function makeRebootJob(body) {
  if (rebootRunnable === null) rebootRunnable = Java.registerClass({
    name: 'net.hawyn.hnubl.runtime.OtaJob' + diagnosticSession + (++rebootJobSequence),
    implements: [Java.use('java.lang.Runnable')], fields: {hnublJob: 'java.lang.String'},
    methods: {run() {
      const key = String(this.hnublJob.value), body = rebootJobs.get(key); rebootJobs.delete(key);
      if (body) try { body(); } catch (failure) { reportWarning('reboot_job', 'Updater continuation failed', failure); }
    }}
  });
  const key = diagnosticSession + '-' + (++rebootJobSequence), runnable = rebootRunnable.$new();
  runnable.hnublJob.value = key; rebootJobs.set(key, body);
  return {runnable, cancel() { rebootJobs.delete(key); }, dispose() { runnable.$dispose(); }};
}

function rebootBackground(body) {
  const job = makeRebootJob(body);
  let thread;
  try { thread = Java.use('java.lang.Thread').$new(job.runnable, 'hnubl-ota-prepare'); thread.start(); }
  catch (failure) { job.cancel(); throw failure; }
  finally { job.dispose(); if (thread) thread.$dispose(); }
}

function rebootOnLooper(looper, body) {
  if (looper === null) { body(); return; }
  const job = makeRebootJob(body); let handler;
  try {
    handler = Java.use('android.os.Handler').$new(looper);
    if (handler.post.overload('java.lang.Runnable').call(handler, job.runnable) !== true)
      throw new Error('Original updater Looper rejected the continuation');
  } catch (failure) { job.cancel(); throw failure; }
  finally { job.dispose(); if (handler) handler.$dispose(); }
}

function rebootTrace(stage, attemptId) {
  try { marker('TRACE ' + stage + ' ' + attemptId); }
  catch (failure) { reportWarning('reboot_trace', 'Optional updater preparation trace unavailable', failure); }
}

function prepareManualReboot(hook, overload, receiver, args, originalThreadId, terminal = true) {
  if (rebootPending) { telemetry({event: 'reboot_preparation_pending', mode: String(args[hook.mode_arg])}); return; }
  const pending = {}; rebootPending = pending;
  const attemptId = (diagnosticSession + '-' + (++attemptSequence).toString(36)).slice(-24), retained = [];
  let looper = null, capturedReceiver = receiver, capturedArgs, lease = null, cleaned = false;
  const retain = value => { const copy = Java.retain(value); retained.push(copy); return copy; };
  const cleanup = () => {
    if (cleaned) return; cleaned = true; if (rebootPending === pending) rebootPending = null;
    for (const value of retained) try { value.$dispose(); } catch (_) {}
  };
  const release = () => {
    if (lease !== null) {
      try { lease.release(); rebootTrace('lease_released', attemptId); }
      catch (failure) { reportWarning('engine_lifecycle', 'Vendor engine reference release failed', failure); }
      lease = null;
    }
  };
  try {
    const current = Java.use('android.os.Looper').myLooper(); if (current !== null) looper = retain(current);
    capturedReceiver = hook.is_static === true ? Java.use(hook.class_name) : retain(receiver);
    capturedArgs = args.map(value => value !== null && typeof value === 'object' ? retain(value) : value);
    rebootBackground(() => {
      let diagnosticId = null, authorized = false;
      try {
        const workerThreadId = String(Java.use('java.lang.Thread').currentThread().getId());
        if (terminal) rebootTrace('terminal_enter', attemptId);
        const state = marker('STATE'); rebootTrace('status', attemptId);
        if (state.state !== 1) throw new Error('OTA completion marker is not ready');
        if (terminal) {
          // The file is the only permission source. The original vendor open is
          // preparation for a deferred action, not a second authorization gate.
          if (engineLifecycle !== null) {
            rebootTrace('open_start', attemptId);
            try { lease = engineLifecycle.acquire(); rebootTrace('open_ok', attemptId); }
            catch (failure) {
              rebootTrace('open_failed', attemptId);
              reportWarning('engine_lifecycle', 'Optional original updater start failed; checking file permission and its pending action', failure);
            }
          }
          telemetry({event: 'reboot_authorization_requested', attempt_id: attemptId, thread_id: originalThreadId,
            mode: String(capturedArgs[hook.mode_arg]), cycle_reset_pending: cycleResetPending});
          const consumed = marker('CONSUME');
          diagnosticId = typeof consumed.diagnostic_id === 'string' ? consumed.diagnostic_id : null;
          brokerReady = [0, 1].includes(consumed.state) && (consumed.ok === true || consumed.authorized === true);
          authorized = consumed.authorized === true || consumeAllows(consumed);
          telemetry({event: 'reboot_authorization_result', ok: consumed.ok, state: consumed.state, decision: consumed.decision,
            authorized, action_completed: consumed.action_completed, activation_performed: consumed.activation_performed,
            reason: String(consumed.error || ''), attempt_id: attemptId, diagnostic_id: diagnosticId});
          if (authorized) stats.allowed++;
          if (!consumeAllows(consumed)) {
            if (!authorized) rebootTrace('consume_deny', attemptId);
            throw new Error(authorized ? 'OTA file permission granted, but the pending action failed: ' + String(consumed.error || consumed.decision) : 'OTA reboot file permission was not granted');
          }
          rebootTrace('consume_allow', attemptId);
          cycleResetPending = false;
        } else { rebootTrace('frontier_pass', attemptId); telemetry({event:'reboot_frontier_passed',attempt_id:attemptId}); }
        rebootOnLooper(looper, () => {
          const threadId = looper === null ? workerThreadId : originalThreadId;
          if (!terminal && rebootPending === pending) rebootPending = null;
          if (!terminal) gateDepths.set(threadId, (gateDepths.get(threadId) || 0) + 1);
          telemetry({event: 'original_reboot_entry_allowed', mode: String(capturedArgs[hook.mode_arg]),
            attempt_id: attemptId, thread_id: threadId, diagnostic_id: diagnosticId, terminal});
          let originalFailed = false;
          try {
            rebootContext.enter('manual', () => originalReboot(overload, capturedReceiver, capturedArgs));
          }
          catch (failure) { originalFailed = true; telemetry({event: 'original_reboot_entry_failed', attempt_id: attemptId, diagnostic_id: diagnosticId, reason: String(failure)}); }
          finally {
            if (!terminal) gateDepths.delete(threadId);
            try { rebootBackground(() => { rebootTrace(originalFailed ? 'original_failed' : 'original_started', attemptId); release(); }); }
            catch (failure) {
              reportWarning('engine_lifecycle', 'Background vendor lease cleanup could not be scheduled', failure);
              setTimeout(() => Java.perform(() => { rebootTrace(originalFailed ? 'original_failed' : 'original_started', attemptId); release(); }), 0);
            }
            cleanup();
          }
        });
      } catch (failure) {
        if (authorized) rebootTrace('original_failed', attemptId);
        if (!authorized) stats.denied++;
        telemetry({event: authorized ? 'reboot_execution_failed' : 'reboot_authorization_transport_error', attempt_id: attemptId, diagnostic_id: diagnosticId, reason: String(failure)});
        reportWarning('reboot_preparation', authorized ? 'OTA file permission was consumed; pending execution failed and the original reboot was not run' : 'Original reboot remains blocked until the file grants permission', failure);
        telemetry({event: 'redirect_to_hnubl', mode: String(capturedArgs[hook.mode_arg]), attempt_id: attemptId,
          thread_id: originalThreadId, diagnostic_id: diagnosticId, reason: String(failure)});
        release(); cleanup(); openHnubl();
      }
    });
  } catch (failure) { cleanup(); reportWarning('reboot_dispatch', 'Updater background preparation could not be scheduled', failure); openHnubl(); }
}

function bindSetting(hook) {
  const namespace = hook.setting_namespace;
  if (!['Global', 'Secure'].includes(namespace) || typeof hook.setting_key !== 'string' ||
      ![0, 1].includes(hook.setting_value)) throw new Error('Unproved setting descriptor');
  let values = settingMaps.get(namespace);
  if (values === undefined) {
    values = new Map();
    const settings = Java.use('android.provider.Settings$' + namespace);
    let overloadCount = 0;
    for (const methodName of ['getInt', 'getIntForUser']) {
      if (settings[methodName] === undefined) continue;
      for (const overload of settings[methodName].overloads) {
        if (overload.argumentTypes.length < 2 || overload.argumentTypes[1].className !== 'java.lang.String') continue;
        overload.implementation = function (...args) {
          try {
            const key = String(args[1]);
            if (values.has(key)) return values.get(key);
          } catch (error) { reportWarning('optional_setting', 'Settings classification failed; preserving native read', error); }
          return original(overload, this, args);
        };
        overloadCount++;
      }
    }
    if (overloadCount === 0) throw new Error('Settings read ABI not found');
    settingMaps.set(namespace, values);
  }
  values.set(hook.setting_key, hook.setting_value);
}

function bindTimer(hook) {
  timerClasses.add(hook.class_name);
  if (timerStartPatched) return;
  const start = Java.use('android.os.CountDownTimer').start.overload();
  start.implementation = function () {
    if (timerClasses.has(String(this.getClass().getName()))) {
      stats.automaticBlocked++;
      return this;
    }
    return original(start, this, []);
  };
  timerStartPatched = true;
}

function bindHook(hook) {
  if (!Array.isArray(hook.parameter_types) || typeof hook.class_name !== 'string' ||
      typeof hook.method_name !== 'string') throw new Error('Malformed method descriptor');
  const role = hook.role;
  const known = ['full_package', 'disable_auto', 'night_switch', 'auto_download',
    'countdown', 'countdown_timer', 'reboot_gate', 'reboot_context', 'reboot_terminal', 'payload_start', 'payload_complete', 'version_response', 'version_cache_query'];
  if (!known.includes(role)) throw new Error('Unknown hook role: ' + role);
  if (['disable_auto', 'night_switch', 'auto_download'].includes(role)) {
    bindSetting(hook);
    installed.push({role, setting_namespace: hook.setting_namespace,
      setting_key: hook.setting_key, setting_value: hook.setting_value});
    counts[role] = (counts[role] || 0) + 1;
    return;
  }
  if (role === 'countdown_timer') {
    bindTimer(hook);
    installed.push({role, class_name: hook.class_name});
    counts[role] = (counts[role] || 0) + 1;
    return;
  }
  if (role === 'version_cache_query') {
    installCacheVersionQuery({Java, hook, forceFullPackage: () => forceFullPackage, stats, telemetry,
      warn: (message, error) => reportWarning('version_cache', message, error)});
    if(role==='reboot_terminal')telemetry({event:'terminal_bound',class_name:hook.class_name,method_name:hook.method_name});
  installed.push({role, class_name: hook.class_name, method_name: hook.method_name,
      parameter_types: hook.parameter_types});
    counts[role] = (counts[role] || 0) + 1;
    return;
  }
  const type = Java.use(hook.class_name);
  const overload = type[hook.method_name].overload(...hook.parameter_types);

  if (role === 'full_package') {
    if (hook.return_type !== 'java.lang.String') throw new Error('Package type result must be String');
    overload.implementation = function () { return 'recovery'; };
  } else if (role === 'version_response') {
    const states = hook.version_states;
    const source = hook.current_version_source;
    if (hook.return_type !== 'void' || hook.parameter_types[hook.response_arg] !== 'java.lang.String' ||
        !states || !Number.isInteger(states.available) || !Number.isInteger(states.unavailable) ||
        states.available === states.unavailable || !source || typeof hook.native_no_update_callback !== 'string')
      throw new Error('Version response/native unavailable branch was not proven');
    const available = [states.available];
    if (Number.isInteger(states.request_again)) available.push(states.request_again);
    const protocol = {available, unavailable: states.unavailable};
    let field = null, infoType = null, supplier = null;
    if (typeof source.field_owner === 'string' && typeof source.field_name === 'string' &&
        typeof source.field_type === 'string' && typeof source.getter === 'string') {
      field = Java.use(source.field_owner).class.getDeclaredField(source.field_name);
      field.setAccessible(true);
      infoType = Java.use(source.field_type);
    }
    if (source.fallback_supplier) {
      const spec = source.fallback_supplier;
      const supplierType = Java.use(spec.class_name);
      const method = supplierType[spec.method_name].overload();
      supplier = () => String(method.call(supplierType));
    }
    if (field === null && supplier === null) throw new Error('Current firmware source is missing');
    overload.implementation = function (...args) {
      if (forceFullPackage) return original(overload, this, args);
      const nativeResponse = args[hook.response_arg];
      try {
        let deviceVersion = null;
        if (field !== null) {
          const info = field.get(this);
          if (info !== null) deviceVersion = String(Java.cast(info, infoType)[source.getter]());
        }
        if (parseFirmwareVersion(deviceVersion) === null && supplier !== null) deviceVersion = supplier();
        const filtered = filterVersionResponse(String(args[hook.response_arg]), deviceVersion, false, protocol);
        if (filtered.raw !== String(args[hook.response_arg])) {
          // Preserve the original control flow: its native status-unavailable
          // branch sends the existing CheckResult/Handler callback to the UI.
          args[hook.response_arg] = filtered.raw;
          stats.versionLatest++;
        } else stats.versionPreserved++;
        telemetry({event: 'version_filter', decision: filtered.decision, reason: filtered.reason,
          device_version: deviceVersion, server_version: filtered.serverVersion || null});
      } catch (error) {
        args[hook.response_arg] = nativeResponse;
        reportWarning('version_response', 'Version classification failed; preserving original updater response', error);
      }
      return original(overload, this, args);
    };
  } else if (role === 'countdown') {
    if (!['void', 'boolean'].includes(hook.return_type)) throw new Error('Countdown entry result unsupported');
    overload.implementation = function () {
      stats.automaticBlocked++;
      return hook.return_type === 'boolean' ? true : undefined;
    };
  } else if (role === 'reboot_gate') {
    if (hook.return_type !== 'void' || hook.parameter_types[hook.mode_arg] !== 'java.lang.String')
      throw new Error('Reboot gate reason descriptor is not proven');
    overload.implementation = function (...args) {
      const mode = args[hook.mode_arg];
      if (isAutomatic(mode, hook.automatic_values || []) || rebootContext.current() === 'automatic') {
        stats.automaticBlocked++;
        telemetry({event: 'automatic_reboot_blocked', mode: String(mode)});
        return;
      }
      if (!isManual(mode, hook.manual_values || [])) {
        // An unknown automatic source must never spend a pending manual permit.
        stats.denied++;
        telemetry({event: 'unclassified_reboot_blocked', mode: String(mode)});
        return;
      }
      let threadId;
      try { threadId = String(Java.use('java.lang.Thread').currentThread().getId()); }
      catch (error) {
        stats.denied++;
        reportWarning('reboot_thread', 'Reboot caller identity failed; original entry remains blocked', error);
        openHnubl();
        return;
      }
      const nested = gateDepths.get(threadId) || 0;
      // Nested source frontiers preserve the original synchronous preparation.
      // Only the final process-local reboot hook consumes file permission.
      if (nested > 0) return original(overload, this, args);
      return prepareManualReboot(hook, overload, this, args, threadId, false);
    };
  } else if (role === 'reboot_context') {
    if (hook.return_type !== 'void' || hook.parameter_types[hook.mode_arg] !== 'java.lang.String') throw new Error('Reboot reason bridge ABI missing');
    overload.implementation = function (...args) {
      const value=args[hook.mode_arg], kind=isAutomatic(value,hook.automatic_values||[])?'automatic':isManual(value,hook.manual_values||[])?'manual':'unknown';
      return rebootContext.enter(kind, () => original(overload,this,args));
    };
  } else if (role === 'reboot_terminal') {
    if (hook.return_type !== 'void' || hook.parameter_types.length !== 1 || hook.parameter_types[0] !== 'java.lang.String') throw new Error('Terminal reboot ABI missing');
    overload.implementation = function (...args) {
      const kind=isAutomatic(args[0],hook.automatic_values||[])?'automatic':rebootContext===null?'unknown':rebootContext.current();
      if (kind !== 'manual') {
        if (kind === 'automatic') stats.automaticBlocked++; else stats.denied++;
        telemetry({event:'terminal_reboot_blocked',origin:kind,mode:String(args[0])});
        try { rebootBackground(() => rebootTrace('terminal_blocked',(diagnosticSession+'-'+(++attemptSequence).toString(36)).slice(-24))); }
        catch(error){reportWarning('reboot_trace','Optional terminal trace could not be scheduled',error);}
        return;
      }
      return prepareManualReboot(hook,overload,this,args,String(Java.use('java.lang.Thread').currentThread().getId()),true);
    };
  } else if (role === 'payload_start') {
    overload.implementation = function (...args) {
      payloadStarted = false;
      // BEGIN arms a new durable native cycle and resets the old marker. A
      // completion RESET must only reset the marker, retaining a deferred plan.
      if (!resetCycle('payload_start', 'BEGIN')) {
        telemetry({event:'payload_begin_blocked',reason:'old file permission could not be cleared'});
        throw Java.use('java.lang.IllegalStateException').$new('hnubl: clearing the previous OTA permission failed; retry after the module is ready');
      }
      payloadStarted = true;
      return original(overload, this, args);
    };
  } else if (role === 'payload_complete') {
    overload.implementation = function (...args) {
      // A completion replay when binding/reopening the UI is not a new OTA.
      // Only a payload start observed by this process opens a completion cycle.
      if (payloadStarted && Number(args[0]) === 0) {
        payloadStarted = false;
        resetCycle('payload_application_complete');
      }
      return original(overload, this, args);
    };
  }
  if(role==='reboot_terminal')telemetry({event:'terminal_bound',class_name:hook.class_name,method_name:hook.method_name});
  installed.push({role, class_name: hook.class_name, method_name: hook.method_name,
    parameter_types: hook.parameter_types});
  counts[role] = (counts[role] || 0) + 1;
  if (role === 'reboot_gate') {
    if (Array.isArray(hook.manual_values) && hook.manual_values.length > 0)
      counts.manual_reboot_gate = (counts.manual_reboot_gate || 0) + 1;
    if (Array.isArray(hook.automatic_values) && hook.automatic_values.length > 0)
      counts.automatic_reboot_gate = (counts.automatic_reboot_gate || 0) + 1;
  }
}

function inspect() {
  return {package: TARGET, installed, failures, counts, stats, cycle_reset_pending: cycleResetPending,
    ...readiness(criticalReady, brokerReady), initialized, warnings,
    reboot_preparing: rebootPending !== null, engine_lifecycle_ready: engineLifecycle !== null,
    deoptimized, terminal_ready:(counts.reboot_terminal||0)>0, continuation_ready:continuationReady,
    ota_socket: otaSocket, apk_sha256: mapping && mapping.apk_sha256, force_full_package: forceFullPackage};
}

function init(stage, parameters) {
  if (initialized) return Promise.resolve(inspect());
  if (initialization !== null) return initialization;
  initialization = new Promise((resolve, reject) => {
    Java.perform(() => {
      try {
        app = Java.use('android.app.ActivityThread').currentApplication();
        if (app === null || String(app.getPackageName()) !== TARGET) throw new Error('Target application identity mismatch');
        const cmdline = Java.use('java.io.FileInputStream').$new('/proc/self/cmdline');
        const reader = Java.use('java.io.InputStreamReader').$new(cmdline, 'UTF-8');
        const chars = Java.array('char', Array(512).fill('\0'));
        const count = reader.read.overload('[C', 'int', 'int').call(reader, chars, 0, chars.length);
        reader.close();
        const processName = Array.from(chars).slice(0, count > 0 ? count : 0).join('').split('\0')[0];
        if (processName !== TARGET && !processName.startsWith(TARGET + ':')) throw new Error('Target process identity mismatch');
        Java.classFactory.loader = app.getClassLoader();
        const suppliedSocket = parameters && parameters.ota_socket;
        const actualSocket = String(app.getFilesDir().getAbsolutePath()) + '/hnubl-module/ota.sock';
        if (suppliedSocket !== undefined && suppliedSocket !== null &&
            (typeof suppliedSocket !== 'string' || suppliedSocket !== actualSocket))
          throw new Error('OTA endpoint does not match the target application files directory');
        otaSocket = suppliedSocket || actualSocket;
        mapping = parameters && parameters.mapping;
        forceFullPackage = parameters && parameters.forceFullPackage === true;
        if (!mapping || mapping.schema !== 1 || mapping.package !== TARGET ||
            mapping.discovery_complete !== true || !Array.isArray(mapping.hooks)) throw new Error('Incomplete semantic discovery mapping');
        // Install the final boundary before optional hooks or continuation setup.
        // Until origin tracking is available, its unknown-origin path stays shut.
        const terminalHooks = mapping.hooks.filter(hook => hook.role === 'reboot_terminal');
        if (!terminalHooks.length) throw new Error('Required terminal reboot entry is missing');
        for (const hook of terminalHooks) try { bindHook(hook); }
        catch (error) { failures.push({hook,error:String(error)}); throw new Error('A required discovered entry was not hooked: '+String(error)); }
        if (typeof Java.deoptimizeEverything !== 'function') throw new Error('Target-process deoptimization unavailable');
        Java.deoptimizeEverything(); deoptimized = true; telemetry({event:'runtime_deoptimized'});
        installRebootContext(mapping.reboot_continuations);
        try { engineLifecycle = bindEngineLifecycle(mapping.engine_lifecycle); }
        catch (failure) { reportWarning('engine_lifecycle', 'Optional vendor engine lifecycle binding unavailable', failure); }
        for (const role of mapping.optional_missing || [])
          reportWarning('optional_discovery', 'Optional updater feature was not discovered: ' + role, null);
        for (const hook of mapping.hooks) {
          if (hook.role === 'reboot_terminal') continue;
          try { bindHook(hook); }
          catch (error) {
            failures.push({hook, error: String(error)});
            if (!isCriticalRole(hook.role)) reportWarning('optional_hook', 'Optional updater feature was not hooked: ' + hook.role, error);
          }
        }
        const requiredFailures = failures.filter(failure => isCriticalRole(failure.hook.role));
        if (requiredFailures.length > 0) throw new Error('A required discovered entry was not hooked: ' + JSON.stringify(requiredFailures));
        if (!deoptimized || !continuationReady || !requiredReady(counts)) throw new Error('Required hook coverage is incomplete: ' + JSON.stringify(counts));
        criticalReady = true;
        try {
          rebootLabels = installRebootLabels({Java, app, state: () => marker('STATE'),
            warn: (message, error) => reportWarning('reboot_labels', message, error),
            onState: value => rebootTrace(value === 1 ? 'label_state_one' : 'label_state_zero', (diagnosticSession + '-label').slice(-24)),
            onStateError: () => rebootTrace('label_read_error', (diagnosticSession + '-label').slice(-24)),
            manualAction: fn => rebootContext.enter('manual', fn)});
        } catch (error) {
          reportWarning('reboot_labels', 'Optional reboot labels unavailable; original updater UI retained', error);
        }
        send({event: 'critical_ready', ...inspect()});
        // Keep the injector connected while the endpoint is being prepared, so
        // the controller receives the actual later proof before eternalizing.
        // Critical gates already run: UI/download/application stay native, but
        // no manual reboot can proceed without a successful atomic CONSUME.
        const proveBroker = () => Java.perform(() => {
          try {
            const proof = marker('STATE');
            if (proof.ok !== true || ![0, 1].includes(proof.state)) throw new Error('OTA broker STATE proof failed');
            brokerReady = true;
            initialized = true;
            brokerRetry = null;
            const snapshot = inspect();
            send({event: 'ready', ...snapshot});
            resolve(snapshot);
          } catch (error) {
            brokerReady = false;
            reportWarning('broker_state', 'OTA endpoint is pending; reboot gate remains closed', error);
            brokerRetry = setTimeout(proveBroker, 2000);
          }
        });
        proveBroker();
      } catch (error) {
        reportError('OUC hooks are not ready', String(error));
        reject(error);
      }
    });
  });
  return initialization;
}

rpc.exports = {init, inspect};
