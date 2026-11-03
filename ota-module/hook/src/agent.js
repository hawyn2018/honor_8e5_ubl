import Java from 'frida-java-bridge';
import {isAutomatic, isManual, consumeAllows, requiredReady, isCriticalRole, readiness} from './policy.js';
import {filterVersionResponse, parseFirmwareVersion} from './version-policy.js';
import {installRebootLabels} from './reboot-labels.js';
import {installCacheVersionQuery} from './cache-query.js';

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
const stats = {denied: 0, allowed: 0, automaticBlocked: 0, reset: 0, versionLatest: 0, versionPreserved: 0,
  cacheLatest: 0, cachePreserved: 0};

function telemetry(event) {
  // The injector has exited after eternalizing. Keep gate decisions available
  // in Android logcat without relying on a live send receiver.
  if (['cycle_reset', 'automatic_reboot_blocked', 'unclassified_reboot_blocked',
    'reboot_authorization_requested', 'reboot_authorization_result',
    'redirect_to_hnubl', 'original_reboot_entry_allowed', 'version_filter', 'cache_version_filter'].includes(event.event)) {
    try {
      const Log = Java.use('android.util.Log');
      Log.i.overload('java.lang.String', 'java.lang.String').call(Log, 'hnubl-ota', JSON.stringify(event));
    } catch (_) { /* Diagnostic output never replaces the original callback. */ }
  }
  try { send(event); } catch (_) { /* An absent host is not an updater failure. */ }
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
    telemetry({event: 'cycle_reset', source, command});
  } catch (error) {
    cycleResetPending = true;
    brokerReady = false;
    reportWarning('cycle_reset', 'New OTA marker reset failed; reboot gate remains closed', error);
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
    'countdown', 'countdown_timer', 'reboot_gate', 'payload_start', 'payload_complete', 'version_response', 'version_cache_query'];
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
      if (isAutomatic(mode, hook.automatic_values || [])) {
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
      // A proven outer frontier is the only token consumer. Preserve synchronous
      // calls within its original path without a second read after token=0.
      if (nested > 0) return original(overload, this, args);
      let allow = false;
      try {
        telemetry({event: 'reboot_authorization_requested', mode: String(mode), cycle_reset_pending: cycleResetPending});
        // A newly written completion token supersedes an earlier reset failure.
        // The broker's actual CONSUME result owns the decision; never RESET a
        // just-completed hnubl transaction merely because this process saw an
        // older transport failure.
        const consumed = marker('CONSUME');
        brokerReady = consumed.ok === true && [0, 1].includes(consumed.state);
        allow = consumeAllows(consumed);
        telemetry({event: 'reboot_authorization_result', ok: consumed.ok, state: consumed.state,
          decision: consumed.decision, reason: String(consumed.error || '')});
        if (allow) cycleResetPending = false;
      }
      catch (error) {
        brokerReady = false;
        reportWarning('consume', 'Reboot authorization read failed; original entry blocked', error);
      }
      if (!allow) {
        stats.denied++;
        telemetry({event: 'redirect_to_hnubl', mode: String(mode)});
        openHnubl();
        return;
      }
      stats.allowed++;
      gateDepths.set(threadId, nested + 1);
      telemetry({event: 'original_reboot_entry_allowed', mode: String(mode)});
      try { return originalReboot(overload, this, args); }
      finally { gateDepths.delete(threadId); }
    };
  } else if (role === 'payload_start') {
    overload.implementation = function (...args) {
      payloadStarted = true;
      // BEGIN arms a new durable native cycle and resets the old marker. A
      // completion RESET must only reset the marker, retaining a deferred plan.
      resetCycle('payload_start', 'BEGIN');
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
        for (const role of mapping.optional_missing || [])
          reportWarning('optional_discovery', 'Optional updater feature was not discovered: ' + role, null);
        for (const hook of mapping.hooks) {
          try { bindHook(hook); }
          catch (error) {
            failures.push({hook, error: String(error)});
            if (!isCriticalRole(hook.role)) reportWarning('optional_hook', 'Optional updater feature was not hooked: ' + hook.role, error);
          }
        }
        const requiredFailures = failures.filter(failure => isCriticalRole(failure.hook.role));
        if (requiredFailures.length > 0) throw new Error('A required discovered entry was not hooked: ' + JSON.stringify(requiredFailures));
        if (!requiredReady(counts)) throw new Error('Required hook coverage is incomplete: ' + JSON.stringify(counts));
        criticalReady = true;
        try {
          rebootLabels = installRebootLabels({Java, app, state: () => marker('STATE'),
            warn: (message, error) => reportWarning('reboot_labels', message, error)});
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
