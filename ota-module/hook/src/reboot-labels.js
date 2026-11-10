import {startCooperativeScan} from './cooperative-scan.js';

// Optional presentation only. Authorization belongs to the final reboot gate.
export const rebootLabels = Object.freeze({continue: '继续流程', reboot: '立即重启'});

export function labelForState(reply) {
  return reply && reply.ok === true && reply.state === 1 ? rebootLabels.reboot : rebootLabels.continue;
}

export function isManualRestartText(text, resourceTexts = []) {
  const value = String(text == null ? '' : text).trim();
  if (!value) return false;
  if (resourceTexts.some(candidate => String(candidate).trim() === value)) return true;
  return /^(立即重启|立即重新启动|立即重新啟動|立即重新開機|restart\s+now|reboot\s+now)$/iu.test(value);
}

// A long unrelated CharSequence need not cross the Java/JS bridge in full.
// This is a rejection-only optimization: uncertain/unsupported inputs take
// the original exact text path, including arbitrary whitespace and aliases.
export function readRestartCandidateText(sequence, resourceTexts = [], tracked = false) {
  if (!tracked && sequence != null && typeof sequence.length === 'function' && typeof sequence.charAt === 'function') try {
    const size = Number(sequence.length());
    if (size > 256) {
      const literalPrefix = (value, candidate) => candidate.startsWith(value) ||
        (value.startsWith(candidate) && value.slice(candidate.length).trim() === '');
      const englishPrefix = (value, word) => {
        if (value.length <= word.length) return new RegExp('^' + word.slice(0, value.length) + '$', 'iu').test(value);
        return new RegExp('^' + word + '\\s*$', 'iu').test(value);
      };
      const possible = raw => {
        const value = raw.trimStart(); if (!value) return true;
        if (resourceTexts.some(candidate => literalPrefix(value, String(candidate).trim()))) return true;
        if (['立即重启', '立即重新启动', '立即重新啟動', '立即重新開機'].some(candidate => literalPrefix(value, candidate))) return true;
        if (['restart', 'reboot'].some(word => englishPrefix(value, word))) return true;
        const fullVerb = /^(?:restart|reboot)\s+([\s\S]*)$/iu.exec(value);
        return fullVerb != null && englishPrefix(fullVerb[1], 'now');
      };
      let prefix = '';
      for (let i = 0; i < Math.min(size, 16); i++) {
        const cell = sequence.charAt(i);
        if (typeof cell === 'number' && Number.isInteger(cell) && cell >= 0 && cell <= 0xffff) prefix += String.fromCharCode(cell);
        else if (typeof cell === 'string' && cell.length === 1) prefix += cell;
        else return String(sequence); // Preserve unknown bridge representations.
        if (!possible(prefix)) return null;
      }
    }
  } catch (_) { /* CharSequence capabilities are optional. */ }
  return String(sequence == null ? '' : sequence);
}

/** Small state machine shared by the Android adapter and the host tests.
 * p keeps referents weak; scan/read/write/main are UI-thread operations.
 * state is invoked from p.background, never from a label setter or click.
 */
export function createRebootLabelController(p) {
  let activity = null, pendingActivity = null, records = [], generation = 0, stopped = false;
  let timer = null, requestTimer = null, requesting = null, suspended = 0, discovery = null;
  let stateRevision = 0, observedState = null, readFailed = false;
  let cached = rebootLabels.continue, cachedAt = -Infinity, renderPending = false;
  let resumeValidationPending = false;
  const counts = {discoveryCount: 0, candidateReadCount: 0, renderCount: 0, writeCount: 0, stateReadCount: 0, discoverySlices: 0,
    resumeCount: 0, sameRootResumeCount: 0, pendingResumeCount: 0};
  const interval = p.intervalMs || 2500, cache = p.cacheMs || 750;
  const warning = (message, error) => { try { p.warn(message, error); } catch (_) {} };
  const deref = ref => { try { return ref && ref.get(); } catch (_) { return null; } };
  const same = (a, b) => {
    try { return a != null && b != null && p.same(a, b); }
    catch (error) { warning('Comparing a restart label view failed', error); return false; }
  };
  const current = epoch => !stopped && epoch === generation && deref(activity) != null;
  const find = view => records.find(record => same(deref(record.view), view));
  const findAction = view => records.find(record => same(deref(record.view), view) || same(deref(record.action), view));
  const clearTimer = () => { if (timer != null) p.cancel(timer); timer = null; };
  const clearRequest = () => { if (requestTimer != null) p.cancel(requestTimer); requestTimer = null; };
  const write = (view, text) => { counts.writeCount++; return p.write(view, text); };
  const deferUi = (owner, force, discovery = true) => {
    if (typeof p.canDiscover !== 'function' || p.canDiscover(owner)) return false;
    const deferred = !discovery && typeof p.deferRender === 'function' ? p.deferRender : p.deferDiscovery;
    if (typeof deferred === 'function') deferred(owner, force);
    return true;
  };
  function restore(record) {
    const view = deref(record.view);
    if (!view || record.applied == null) return;
    try {
      // A native state transition owns its new text; never overwrite it with
      // a previously captured restart label.
      if (String(p.read(view).text) === record.applied) write(view, record.nativeText);
    } catch (error) { warning('Restoring the native restart label failed', error); }
    record.applied = null;
  }
  function restoreAll() { for (const record of records) restore(record); }
  function eligible(value) { return value && value.visible && value.clickable && value.listener && value.enabled !== false; }
  function collect(value) {
    if (!value || !value.view) return null;
    let record = find(value.view);
    if (!eligible(value)) {
      if (record) { restore(record); record.retired = true; }
      return null;
    }
    if (record) {
      const text = String(value.text);
      if (text !== record.nativeText && text !== record.applied) {
        if (!value.manual) { record.retired = true; record.applied = null; return null; }
        record.nativeText = text; record.applied = null;
      }
    } else if (value.manual) {
      record = {view: p.weak(value.view), nativeText: String(value.text), applied: null};
      records.push(record);
    }
    if (record) {
      record.action = p.weak(value.actionView || value.view);
      if (typeof p.watch === 'function') try { record.passive = p.watch(value.view, String(value.text)) === true; }
      catch (error) { record.passive = false; warning('Observing a restart text candidate failed', error); }
    }
    return record;
  }
  function render() {
    if (suspended) return null;
    counts.renderCount++;
    let eligibleCount = 0;
    for (const record of records) {
      const view = deref(record.view); if (!view) continue;
      try {
        const value = p.read(view);
        if (!eligible(value)) { restore(record); continue; }
        const text = String(value.text);
        if (text !== record.nativeText && text !== record.applied) {
          // e.g. the same native button has become Install / Download.
          record.retired = true; record.applied = null; continue;
        }
        eligibleCount++;
        if (cached === null) { restore(record); continue; }
        if (text !== cached) write(view, cached);
        record.applied = cached;
      } catch (error) { warning('Refreshing the restart label failed', error); }
    }
    records = records.filter(record => !record.retired && deref(record.view) != null);
    renderPending = false;
    return eligibleCount;
  }
  function poll(epoch) {
    clearTimer();
    if (current(epoch)) timer = p.later(() => {
      timer = null;
      if (!current(epoch)) return;
      if (!records.some(record => !record.retired && deref(record.view) != null)) {
        // Until a native restart control exists, retain an idle-only fallback.
        // Once matched and correct, polling goes back to STATE exclusively.
        if (typeof p.deferDiscovery === 'function') p.deferDiscovery(deref(activity), false);
        else refresh(false);
      } else {
        request(epoch, false);
        // A missing optional TextWatcher must not turn remembered text into
        // proof that an unobserved native control stayed unchanged.
        for (const record of records) if (!record.retired && record.passive === false) {
          const view = deref(record.view); if (view == null) continue;
          if (typeof p.deferCandidate === 'function') p.deferCandidate(view, deref(activity));
          else refreshView(view);
        }
      }
      poll(epoch);
    }, interval);
  }
  function request(epoch, force) {
    if (!current(epoch) || !records.length) return;
    // A focus/click return supersedes any read already in progress. A queued
    // worker has not read yet and can serve the newest request directly.
    if (force) stateRevision++;
    if (requesting) return;
    if (!force && p.now() - cachedAt < cache) return;
    const job = {}; requesting = job;
    try { requestTimer = p.background(() => {
      if (requesting !== job) return;
      requestTimer = null;
      if (!current(epoch)) { requesting = null; return; }
      const revision = stateRevision;
      let next = rebootLabels.continue, value = null, failure = null;
      try {
        counts.stateReadCount++;
        const reply = p.state();
        if (reply && reply.native === true) {
          next = null; // Proven hotpatch: restore the updater's native text.
        } else {
          if (!reply || reply.ok !== true || (reply.state !== 0 && reply.state !== 1))
            throw new Error('STATE did not prove an exact zero/one marker');
          value = reply.state; next = labelForState(reply);
        }
      } catch (error) { failure = error; }
      if (!current(epoch) || requesting !== job) return;
      requesting = null;
      if (revision !== stateRevision) { request(epoch, true); return; }
      if (failure) {
        warning('Reading restart label state failed; showing Continue', failure);
        if (!readFailed && typeof p.onStateError === 'function') try { p.onStateError(failure); }
        catch (error) { warning('Recording the optional label read failure failed', error); }
        readFailed = true;
      } else {
        readFailed = false;
        if (value !== observedState) {
          observedState = value;
          if (value !== null && typeof p.onState === 'function') try { p.onState(value); }
          catch (error) { warning('Recording the optional label state failed', error); }
        }
      }
      // Optional diagnostic transport may yield to a newer focus/click event.
      // It does not make the just-read value authoritative for that newer read.
      if (!current(epoch) || revision !== stateRevision) return;
      const changed = next !== cached;
      cached = next; cachedAt = p.now();
      // The stable path stays entirely off the UI thread. Native layout events
      // own discovery; a marker poll must not walk or reread the view tree.
      if (changed) renderPending = true;
      if (!renderPending) return;
      apply();
    }); } catch (error) { requesting = false; requestTimer = null; warning('Scheduling restart label state read failed', error); }
  }
  function apply() {
    const epoch = generation, revision = stateRevision;
    if (!current(epoch) || !renderPending) return;
    if (deferUi(deref(activity), false, false)) return;
    p.main(() => {
      if (!current(epoch) || revision !== stateRevision) return;
      if (deferUi(deref(activity), false, false)) return;
      const eligibleCount = render();
      if (resumeValidationPending && eligibleCount !== null) {
        resumeValidationPending = false;
        // Reuse this local read's eligibility proof. A hidden, disabled or
        // detached old control can remain alive while a new sibling replaces it.
        if (eligibleCount === 0) refresh(true);
      }
    });
  }
  function refreshState(force = true) { request(generation, force); }
  function cancelDiscovery() {
    const previous = discovery; discovery = null;
    if (previous && previous.pump) previous.pump.cancel();
  }
  function refresh(force = true) {
    const epoch = generation;
    if (!current(epoch)) return;
    if (deferUi(deref(activity), force)) { poll(epoch); return; }
    p.main(() => {
      const owner = deref(activity); if (!current(epoch) || !owner) return;
      if (deferUi(owner, force)) { poll(epoch); return; }
      if (discovery) { discovery.again = true; discovery.force ||= force; return; }
      const job = {seen: new Set(), force, again: false, pump: null};
      discovery = job;
      try {
        counts.discoveryCount++;
        const iterator = p.scan(owner)[Symbol.iterator]();
        job.pump = startCooperativeScan({iterator, now: p.now,
          maxSteps: p.scanMaxSteps || 2048, budgetMs: p.scanBudgetMs || 4,
          current: () => current(epoch) && discovery === job,
          beforeSlice: () => !deferUi(deref(activity), job.force),
          deferred: () => { if (discovery === job) { discovery = null; poll(epoch); } },
          schedule: (fn, delay) => p.later(() => p.main(fn), delay), cancel: p.cancel,
          chunk: values => {
            counts.discoverySlices++;
            for (const value of values) { const record = collect(value); if (record) job.seen.add(record); }
            // Publish native candidates as soon as their slice completes. A
            // long changelog elsewhere in the window must not delay a match.
            if (values.length) { render(); request(epoch, job.force); job.force = false; }
          },
          done: () => {
            if (discovery !== job) return;
            discovery = null;
            for (const record of records) if (!job.seen.has(record)) { restore(record); record.retired = true; }
            records = records.filter(record => !record.retired && deref(record.view) != null);
            render(); request(epoch, job.force); poll(epoch);
            if (job.again) refresh(job.force);
          },
          error: error => {
            if (discovery === job) discovery = null;
            warning('Scanning the active updater screen failed', error); poll(epoch);
          }});
        job.pump.run();
      } catch (error) {
        if (discovery === job) discovery = null;
        warning('Scanning the active updater screen failed', error);
      }
      poll(epoch);
    });
  }
  function refreshView(view) {
    if (typeof p.candidate !== 'function') { refresh(false); return; }
    const epoch = generation, reference = p.weak(view);
    const postpone = () => {
      if (typeof p.canDiscover !== 'function' || p.canDiscover(deref(activity))) return false;
      const candidate = deref(reference);
      if (candidate != null && typeof p.deferCandidate === 'function') p.deferCandidate(candidate, deref(activity));
      else if (typeof p.deferDiscovery === 'function') p.deferDiscovery(deref(activity), false);
      return true;
    };
    if (!current(epoch)) return;
    if (postpone()) return;
    p.main(() => {
      if (!current(epoch)) return;
      if (postpone()) return;
      const candidate = deref(reference); if (candidate == null) return;
      try {
        counts.candidateReadCount++;
        collect(p.candidate(deref(activity), candidate));
        records = records.filter(record => !record.retired && deref(record.view) != null);
        render(); request(epoch, false);
      } catch (error) { warning('Checking a changed restart text failed', error); }
      poll(epoch);
    });
  }
  function resume(owner) {
    if (stopped) return;
    counts.resumeCount++;
    if (same(deref(pendingActivity), owner)) { counts.pendingResumeCount++; return; }
    if (deref(pendingActivity) == null && same(deref(activity), owner)) {
      // onActivityResumed, onActivityPostResumed and foreground root discovery
      // can all report the same window. Its initial/layout discovery already
      // owns the tree; only the presentation's marker state needs refreshing.
      counts.sameRootResumeCount++;
      if (!records.some(record => !record.retired && deref(record.view) != null)) { refresh(true); return; }
      // Native code may rebind an already tracked label during PostResumed.
      // Reconcile those few remembered controls after STATE, never the body.
      renderPending = true;
      resumeValidationPending = true;
      request(generation, true);
      return;
    }
    cancelDiscovery(); clearTimer(); clearRequest(); requesting = null; const epoch = ++generation;
    // The queued work holds only WeakReference objects, never an Activity.
    const next = p.weak(owner);
    pendingActivity = next;
    try {
      p.main(() => { if (stopped || epoch !== generation) return; restoreAll(); records = []; activity = next; pendingActivity = null; cachedAt = -Infinity; cached = rebootLabels.continue; renderPending = false; resumeValidationPending = false; refresh(true); });
    } catch (error) {
      if (epoch === generation) pendingActivity = null;
      warning('Scheduling the resumed restart label root failed', error);
    }
  }
  function pause(owner) {
    const requested = deref(pendingActivity) || deref(activity);
    if (owner && !(p.sameOwner ? p.sameOwner(requested, owner) : same(requested, owner))) return;
    const epoch = ++generation; cancelDiscovery(); clearTimer(); clearRequest(); requesting = null; activity = null; pendingActivity = null; resumeValidationPending = false;
    p.main(() => { if (epoch !== generation) return; restoreAll(); records = []; });
  }
  function aroundOriginal(fn) {
    // Called on the original UI callback thread. No marker operation here.
    suspended++;
    try { restoreAll(); } catch (error) { warning('Preparing native restart labels failed', error); }
    try { return fn(); }
    finally {
      suspended--;
      if (!suspended) {
        // Restore presentation from the tracked controls without walking the
        // window tree. Read the file again even inside the normal cache window.
        renderPending = true;
        try { apply(); } catch (error) { warning('Scheduling restart label refresh failed', error); }
        try { refreshState(true); } catch (error) { warning('Scheduling restart label state refresh failed', error); }
      }
    }
  }
  function aroundClick(view, fn) {
    const record = findAction(view); if (!record) return fn();
    return aroundOriginal(() => {
      let manual = false;
      if (typeof p.manualAction === 'function' && !record.retired) try {
        const label = deref(record.view), value = label && p.read(label);
        manual = eligible(value) && String(value.text) === record.nativeText;
      } catch (error) { warning('Identifying the original manual restart action failed', error); }
      // The native callback exception belongs to its caller. Never retry it as
      // an optional classification failure after it may already have run.
      return manual ? p.manualAction(fn) : fn();
    });
  }
  return {
    resume, pause, refresh, refreshState, refreshView, apply, aroundOriginal, aroundClick,
    tracks: view => Boolean(findAction(view)),
    dispose() { if (stopped) return; try { pause(null); } finally { stopped = true; } },
    inspect() { return {active: deref(activity) != null && !stopped, tracked: records.length, cachedLabel: cached, stopped, ...counts}; },
  };
}

// Frida requires implementations even for Java interface default methods.
// Enumerate the actual boot-framework ABI, including inherited/default and
// overloaded callbacks, instead of assuming one Android release's method list.
export function lifecycleImplementations(contract, controller, warn) {
  const methods = Object.create(null), signatures = new Map();
  function defaultValue(type) {
    if (type === 'void') return undefined;
    if (type === 'boolean') return false;
    if (type === 'char') return '\u0000';
    if (['byte', 'short', 'int', 'long', 'float', 'double'].includes(type)) return 0;
    return null;
  }
  for (const source of [contract.getMethods(), contract.getDeclaredMethods()]) {
    for (let i = 0; i < source.length; i++) {
      const reflected = source[i], modifiers = Number(reflected.getModifiers());
      if ((modifiers & 1) === 0 || (modifiers & (2 | 8)) !== 0) continue;
      const name = String(reflected.getName()), types = reflected.getParameterTypes();
      const argumentTypes = [];
      for (let j = 0; j < types.length; j++) argumentTypes.push(String(types[j].getName()));
      const result = reflected.getReturnType(), returnType = String(result.getName());
      const signature = name + '(' + argumentTypes.join(',') + ')';
      const prior = signatures.get(signature);
      if (prior) {
        if (prior.returnType === returnType) continue;
        // Java may expose a bridge plus a covariant inherited declaration.
        if (prior.result.isAssignableFrom(result)) prior.retired = true;
        else if (result.isAssignableFrom(prior.result)) continue;
        else throw new Error('Conflicting lifecycle return types: ' + signature);
      }
      const specialization = returnType === 'void' && argumentTypes.length === 1 &&
        argumentTypes[0] === 'android.app.Activity' ?
          (name === 'onActivityResumed' || name === 'onActivityPostResumed' ? 'resume' :
            (name === 'onActivityPaused' || name === 'onActivityDestroyed' ? 'pause' : null)) : null;
      const entry = {returnType, argumentTypes, implementation: function (...args) {
        if (specialization) {
          try { controller[specialization](args[0]); }
          catch (error) { try { warn('Restart label lifecycle callback failed: ' + name, error); } catch (_) {} }
        }
        return defaultValue(returnType);
      }};
      const saved = {result, returnType, entry, retired: false};
      signatures.set(signature, saved);
      if (!methods[name]) methods[name] = [];
      methods[name].push(saved);
    }
  }
  for (const name of Object.keys(methods)) methods[name] = methods[name].filter(item => !item.retired).map(item => item.entry);
  return methods;
}

/** Install once after the critical hooks. All failures are optional warnings. */
export function installRebootLabels({Java, app, state, warn, manualAction, onState, onStateError, observeLatestVersion, intervalMs = 2500, cacheMs = 750}) {
  const warning = (message, error) => { try { warn(message, error); } catch (_) {} };
  const ActivityCallbacks = Java.use('android.app.Application$ActivityLifecycleCallbacks');
  const WeakReference = Java.use('java.lang.ref.WeakReference');
  const Activity = Java.use('android.app.Activity');
  const TextView = Java.use('android.widget.TextView');
  const View = Java.use('android.view.View');
  const ViewGroup = Java.use('android.view.ViewGroup');
  const ViewTreeObserver = Java.use('android.view.ViewTreeObserver');
  const JavaString = Java.use('java.lang.String');
  const nativeText = TextView.setText.overload('java.lang.CharSequence');
  const JavaSystem = Java.use('java.lang.System');
  const properties = JavaSystem.getProperties();
  const registry = 'net.hawyn.hnubl.ota.rebootLabels.callback';
  let callbacks = null, disposed = false;
  const hooks = [];
  let writing = 0, refreshQueued = false, refreshTimer = null, discoveryDeadline = 0, scrollingUntil = 0, refreshGeneration = 0;
  let discoveryDirty = false, renderDirty = false, focusDirty = false;
  let layoutClass = null, focusClass = null, scrollClass = null, textClass = null, labelRoot = null;
  const watches = [];
  const resourceCache = [];
  const textWatches = new Map();
  let textSeed = 0, textEventCount = 0;
  let pendingTextViews = [];
  const semanticResources = ['para_restart_now', 'restart_now', 'reboot_now', 'restart_immediately', 'reboot_immediately'];
  function normalizeRoot(owner) {
    if (owner == null) return null;
    if (View.class.isInstance(owner)) return Java.cast(owner, View).getRootView();
    if (Activity.class.isInstance(owner)) {
      const window = Java.cast(owner, Activity).getWindow();
      return window == null ? null : window.getDecorView();
    }
    return null;
  }
  function sameOwner(a, b) {
    if (a == null || b == null) return false;
    if (a.equals(b)) return true;
    try {
      const left = Java.cast(a, View).getApplicationWindowToken();
      const right = Java.cast(b, View).getApplicationWindowToken();
      return left != null && right != null && left.equals(right);
    } catch (_) { return false; }
  }
  function listValues(value) {
    if (value == null) return [];
    if (typeof value.size === 'function' && typeof value.get === 'function') {
      const out = []; for (let i = 0, n = Number(value.size()); i < n; i++) out.push(value.get(i)); return out;
    }
    if (typeof value.length === 'number') return Array.from(value);
    throw new Error('Window enumerator did not return a list or array');
  }
  // Standard framework capabilities are selected by their real zero-argument
  // signatures and returned View objects. No heap traversal or OEM type table.
  const windowSources = [];
  try {
    const inspector = Java.use('android.view.inspector.WindowInspector');
    const method = inspector.getGlobalWindowViews.overload();
    windowSources.push(() => listValues(method.call(inspector)));
  } catch (_) { /* Older frameworks may omit this optional public facade. */ }
  try {
    const global = Java.use('android.view.WindowManagerGlobal');
    const instance = global.getInstance.overload();
    try {
      const method = global.getWindowViews.overload();
      windowSources.push(() => listValues(method.call(instance.call(global))));
    } catch (_) {}
    try {
      const names = global.getViewRootNames.overload(), root = global.getRootView.overload('java.lang.String');
      windowSources.push(() => {
        const manager = instance.call(global);
        return listValues(names.call(manager)).map(name => root.call(manager, name));
      });
    } catch (_) {}
    windowSources.push(() => {
      const manager = instance.call(global), fields = global.class.getDeclaredFields(), found = [];
      const listType = Java.use('java.util.List');
      for (let i = 0; i < fields.length; i++) {
        try {
          const field = fields[i]; field.setAccessible(true);
          const value = field.get(manager); if (value == null) continue;
          if (!listType.class.isInstance(value)) continue;
          let values; try { values = listValues(Java.cast(value, listType)); } catch (_) { continue; }
          if (values.length && values.every(item => item != null && View.class.isInstance(item))) found.push(...values);
        } catch (_) {}
      }
      return found;
    });
  } catch (_) {}
  const failedSources = new Set();
  function windowRoots() {
    const found = [];
    for (let i = 0; i < windowSources.length; i++) {
      try {
        for (const candidate of windowSources[i]()) {
          if (candidate == null || !View.class.isInstance(candidate)) continue;
          const root = Java.cast(candidate, View).getRootView();
          if (!found.some(prior => prior.equals(root))) found.push(root);
        }
        if (found.length) return found;
      } catch (error) {
        if (!failedSources.has(i)) { failedSources.add(i); warning('Optional window root enumeration failed', error); }
      }
    }
    return found;
  }
  function updaterRoot(root) {
    try {
      return root != null && root.isShown() &&
        String(root.getContext().getPackageName()) === String(app.getPackageName());
    } catch (_) { return false; }
  }
  function actionFor(view) {
    let target = Java.cast(view, View); const seen = [];
    while (target != null && !seen.some(prior => prior.equals(target))) {
      seen.push(target);
      if (target.isClickable() && target.hasOnClickListeners()) return target;
      const parent = target.getParent();
      target = parent != null && View.class.isInstance(parent) ? Java.cast(parent, View) : null;
    }
    return null;
  }
  function read(view, knownText) {
    const target = Java.cast(view, View);
    const text = Java.cast(view, TextView);
    const action = actionFor(target);
    return {text: knownText === undefined ? String(text.getText()) : knownText, visible: target.isShown(), enabled: target.isEnabled() && (action == null || action.isEnabled()),
      clickable: action != null, listener: action != null, actionView: action || target};
  }
  function resourceTexts(root) {
    const resources = root.getResources(), pkg = String(root.getContext().getPackageName());
    let configuration = null;
    try { configuration = String(resources.getConfiguration()); } catch (_) {}
    for (let i = resourceCache.length - 1; i >= 0; i--) {
      const retained = resourceCache[i].resources.get();
      if (retained == null) { resourceCache.splice(i, 1); continue; }
      if (!retained.equals(resources)) continue;
      if (configuration != null && resourceCache[i].configuration === configuration && resourceCache[i].pkg === pkg) return resourceCache[i].texts;
      resourceCache.splice(i, 1);
    }
    const texts = [];
    for (const name of semanticResources) {
      const id = resources.getIdentifier(name, 'string', pkg);
      if (id) texts.push(String(resources.getString(id)));
    }
    if (configuration != null) resourceCache.push({resources: WeakReference.$new(resources), configuration, pkg, texts});
    return texts;
  }
  function scan(owner) {
    const primary = normalizeRoot(owner);
    const roots = [];
    if (primary != null) for (const root of [primary, ...windowRoots()])
      if (updaterRoot(root) && !roots.some(other => other.equals(root))) roots.push(root);
    for (const root of roots) watchRoot(root);
    const labels = [];
    if (roots.some(root => root.hasWindowFocus())) for (const root of roots) labels.push(...resourceTexts(root));
    else roots.length = 0;
    const observeBadge = observeLatestVersion?.(roots.find(root => root.hasWindowFocus()));
    // The cursor and identity buckets retain only weak views between frames.
    // Child enumeration itself is incremental, including wide ViewGroups.
    const pending = roots.map(root => ({view: WeakReference.$new(root), child: -1})), visited = new Map();
    roots.length = 0;
    return {
      [Symbol.iterator]() { return this; },
      return() { pending.length = 0; visited.clear(); return {done: true}; },
      next() {
        while (pending.length) {
          const frame = pending[pending.length - 1], view = frame.view.get();
          if (view == null) { pending.pop(); continue; }
          if (frame.child >= 0) {
            const group = Java.cast(view, ViewGroup);
            if (frame.child >= frame.count) { pending.pop(); continue; }
            const child = group.getChildAt(frame.child++);
            if (child != null) pending.push({view: WeakReference.$new(child), child: -1});
            return {done: false, value: null};
          }
          let identity = 'fallback';
          try { identity = String(JavaSystem.identityHashCode(view)); } catch (_) {}
          const bucket = visited.get(identity) || [];
          if (bucket.some(prior => { const value = prior.get(); return value != null && value.equals(view); })) { pending.pop(); continue; }
          // Reuse the cursor reference: each view needs one Java weak object,
          // including identity-hash collisions and multi-frame traversal.
          bucket.push(frame.view); visited.set(identity, bucket);
          if (ViewGroup.class.isInstance(view)) { frame.child = 0; frame.count = Java.cast(view, ViewGroup).getChildCount(); }
          else pending.pop();
          let value = null;
          if (TextView.class.isInstance(view)) {
            const tracked = controller.tracks(view);
            const text = readRestartCandidateText(Java.cast(view, TextView).getText(), labels, tracked);
            if (text != null) {
              if (observeBadge) observeBadge(view, text);
              const manual = isManualRestartText(text, labels);
              // Parent/listener walks remain exclusive to semantic candidates.
              if (manual || tracked) value = {...read(view, text), view, manual};
            }
          }
          return {done: false, value};
        }
        visited.clear(); return {done: true};
      },
    };
  }
  const controller = createRebootLabelController({state, warn: warning, read, scan, intervalMs, cacheMs, manualAction, onState, onStateError,
    weak: value => WeakReference.$new(value), same: (a, b) => Boolean(a.equals(b)),
    sameOwner,
    canDiscover: () => Date.now() >= scrollingUntil,
    deferDiscovery: owner => queueRefresh(owner, 'deferred'),
    deferRender: owner => queueRefresh(owner, 'render'),
    deferCandidate: (view, owner) => queueTextCandidate(view, owner),
    candidate: (owner, view) => {
      const root = normalizeRoot(view); if (root == null || !updaterRoot(root)) return null;
      const text = String(Java.cast(view, TextView).getText());
      return {...read(view, text), view, manual: isManualRestartText(text, resourceTexts(root))};
    },
    watch: (view, text) => watchText(view, text),
    write: (view, text) => {
      writing++;
      try {
        const result = nativeText.call(Java.cast(view, TextView), JavaString.$new(text));
        const entry = findTextWatch(view); if (entry) entry.lastText = text;
        return result;
      } finally { writing--; }
    },
    now: () => Date.now(), later: (fn, delay) => setTimeout(fn, delay), cancel: id => clearTimeout(id),
    main: fn => Java.scheduleOnMainThread(() => { try { fn(); } catch (error) { warning('Restart label UI callback failed', error); } }),
    background: fn => setTimeout(() => Java.perform(() => { try { fn(); } catch (error) { warning('Restart label worker failed', error); } }), 0),
  });
  const weakGet = reference => { try { return reference == null ? null : reference.get(); } catch (_) { return null; } };
  const weakCast = (reference, type) => {
    try { const value = weakGet(reference); return value == null || type == null ? null : Java.cast(value, type); }
    catch (error) { warning('Resolving the optional tree observer failed', error); return null; }
  };
  function removeTextWatch(entry) {
    const listener = weakCast(entry.listener, textClass), view = weakCast(entry.view, TextView);
    if (listener != null) try { listener.hnublActive.value = false; } catch (_) {}
    try { if (view != null && listener != null) view.removeTextChangedListener(listener); }
    catch (error) { warning('Removing the optional restart text observer failed', error); }
    textWatches.delete(entry.id);
  }
  function findTextWatch(view) {
    for (const entry of textWatches.values()) {
      const retained = weakGet(entry.view);
      if (retained == null) { removeTextWatch(entry); continue; }
      if (retained.equals(view)) return entry;
    }
    return null;
  }
  function watchText(view, text) {
    if (disposed || textClass == null) return false;
    const retained = findTextWatch(view);
    if (retained && weakGet(retained.listener) != null) { retained.lastText = text; return true; }
    if (retained) removeTextWatch(retained);
    try {
      const root = normalizeRoot(view), id = ++textSeed, listener = textClass.$new();
      listener.hnublTextId.value = id; listener.hnublActive.value = true;
      const entry = {id, view: WeakReference.$new(view), root: WeakReference.$new(root), listener: WeakReference.$new(listener), lastText: text};
      textWatches.set(id, entry);
      try { Java.cast(view, TextView).addTextChangedListener(listener); }
      catch (error) { removeTextWatch(entry); throw error; }
      return true;
    } catch (error) { warning('Adding the optional restart text observer failed', error); return false; }
  }
  function queueTextCandidate(view, owner) {
    const entry = findTextWatch(view);
    if (entry) queueRefresh(null, 'text', entry.root, entry.view);
    else queueRefresh(null, 'text', owner == null ? null : WeakReference.$new(owner), WeakReference.$new(view));
  }
  function changedText(listener, value) {
    textEventCount++;
    if (disposed || writing) return;
    try {
      if (listener.hnublActive.value !== true) return;
      const entry = textWatches.get(Number(listener.hnublTextId.value)); if (!entry) return;
      const text = String(value == null ? '' : value);
      // Native setters often repeat an already correct value. This passive
      // callback then does no View/parent reads and schedules no UI work.
      if (text === entry.lastText) return;
      entry.lastText = text;
      queueRefresh(null, 'text', entry.root, entry.view);
    } catch (error) { warning('Observing changed native restart text failed', error); }
  }
  function removeWatch(entry) {
    const observer = weakCast(entry.observer, ViewTreeObserver), layout = weakCast(entry.layout, layoutClass), focus = weakCast(entry.focus, focusClass), scroll = weakCast(entry.scroll, scrollClass);
    for (const listener of [layout, focus, scroll]) if (listener != null) try { listener.hnublActive.value = false; } catch (_) {}
    const targets = [];
    const retainLive = candidate => {
      if (candidate == null || !candidate.isAlive()) return;
      if (!targets.some(prior => prior.equals(candidate))) targets.push(candidate);
    };
    try { retainLive(observer); } catch (error) { warning('Checking the previous restart tree observer failed', error); }
    // Android may merge a detached observer's listener lists into the window's
    // live observer and then kill the old object. Remove our exact listeners
    // from both live identities; never call removal on a known dead observer.
    try {
      const root = weakCast(entry.root, View);
      if (root != null) retainLive(root.getViewTreeObserver());
    } catch (error) { warning('Resolving the current restart tree observer failed', error); }
    for (const target of targets) {
      try {
        if (layout != null) {
          if (typeof target.removeOnGlobalLayoutListener === 'function') target.removeOnGlobalLayoutListener(layout);
          else target.removeGlobalOnLayoutListener(layout);
        }
      } catch (error) { warning('Removing the restart layout observer failed', error); }
      try { if (focus != null) target.removeOnWindowFocusChangeListener(focus); }
      catch (error) { warning('Removing the restart focus observer failed', error); }
      try { if (scroll != null) target.removeOnScrollChangedListener(scroll); }
      catch (error) { warning('Removing the restart scroll observer failed', error); }
    }
    // A collected/detached root may have no recoverable observer. Its own
    // callbacks are already inactive; release our remaining weak bookkeeping.
    for (const listener of [layout, focus, scroll]) if (listener != null) try {
      listener.hnublRoot.value = null;
    } catch (_) {}
    for (const key of ['root', 'observer', 'layout', 'focus', 'scroll']) entry[key] = null;
  }
  function unwatchAll() {
    for (const entry of watches.splice(0)) try { removeWatch(entry); } catch (error) { warning('Cleaning the optional tree observer failed', error); }
    for (const entry of [...textWatches.values()]) try { removeTextWatch(entry); } catch (error) { warning('Cleaning the optional text observer failed', error); }
  }
  function watchRoot(root) {
    if (disposed || (layoutClass == null && focusClass == null && scrollClass == null) || root == null) return;
    try {
      const observer = Java.cast(root, View).getViewTreeObserver();
      if (observer == null || !observer.isAlive()) return;
      for (let i = watches.length - 1; i >= 0; i--) {
        const oldRoot = weakGet(watches[i].root), oldObserver = weakCast(watches[i].observer, ViewTreeObserver);
        if (oldRoot == null || oldObserver == null) { removeWatch(watches[i]); watches.splice(i, 1); continue; }
        if (!oldRoot.equals(root)) continue;
        if (oldObserver.equals(observer) && oldObserver.isAlive()) return;
        removeWatch(watches[i]); watches.splice(i, 1);
      }
      const rootRef = WeakReference.$new(root);
      const entry = {root: rootRef, observer: WeakReference.$new(observer), layout: null, focus: null, scroll: null};
      if (layoutClass != null) try {
        const layout = layoutClass.$new(); layout.hnublRoot.value = rootRef; layout.hnublActive.value = true;
        entry.layout = WeakReference.$new(layout); observer.addOnGlobalLayoutListener(layout);
      } catch (error) { warning('Adding the restart layout observer failed', error); }
      watches.push(entry);
      if (focusClass != null) try {
        const focus = focusClass.$new(); focus.hnublRoot.value = rootRef; focus.hnublActive.value = true;
        entry.focus = WeakReference.$new(focus); observer.addOnWindowFocusChangeListener(focus);
      } catch (error) { warning('Adding the optional restart focus observer failed', error); }
      if (scrollClass != null) try {
        const scroll = scrollClass.$new(); scroll.hnublRoot.value = rootRef; scroll.hnublActive.value = true;
        entry.scroll = WeakReference.$new(scroll); observer.addOnScrollChangedListener(scroll);
      } catch (error) { warning('Adding the optional restart scroll observer failed', error); }
    } catch (error) { warning('Preparing the restart window observer failed', error); }
  }
  function cancelDiscovery() {
    refreshGeneration++;
    if (refreshTimer != null) { try { clearTimeout(refreshTimer); } catch (_) {} refreshTimer = null; }
    refreshQueued = false; discoveryDeadline = 0; scrollingUntil = 0;
    discoveryDirty = false; renderDirty = false; focusDirty = false;
    pendingTextViews = [];
  }
  function resumeRoot(root) {
    if (root == null || disposed) return;
    observeLatestVersion?.(root, true);
    const prior = weakGet(labelRoot);
    if (prior == null || !prior.equals(root)) cancelDiscovery();
    watchRoot(root); labelRoot = WeakReference.$new(root); controller.resume(root);
  }
  function observerChanged(listener, reason = 'layout') {
    if (disposed || writing) return;
    try {
      if (listener.hnublActive.value !== true) return;
      // Motion callbacks retain the existing weak reference; no View/Context
      // reads, parent walks or WeakReference allocation on every scroll frame.
      queueRefresh(null, reason, listener.hnublRoot.value);
    } catch (error) { warning('Optional restart tree event failed', error); }
  }
  function queueRefresh(candidate = null, reason = 'layout', retainedRoot = null, textView = null) {
    if (disposed || writing) return;
    try {
      const weakCandidate = retainedRoot || (candidate == null ? null : WeakReference.$new(candidate));
      const now = Date.now();
      if (reason === 'scroll') scrollingUntil = now + 200;
      if (reason === 'layout' || reason === 'deferred') discoveryDirty = true;
      if (reason === 'render') renderDirty = true;
      if (reason === 'focus') focusDirty = true;
      if (reason === 'text' && textView != null && !pendingTextViews.some(reference => reference.equals(textView))) pendingTextViews.push(textView);
      // Scrolling is not evidence of changed content. It only delays an
      // already dirty discovery/render until the final motion event is quiet.
      if (!discoveryDirty && !renderDirty && !focusDirty && !pendingTextViews.length) return;
      if (reason === 'layout' || reason === 'text') discoveryDeadline = now + 200;
      else if (reason === 'focus') discoveryDeadline = now;
      discoveryDeadline = Math.max(discoveryDeadline, scrollingUntil);
      const ticket = ++refreshGeneration;
      if (refreshTimer != null) clearTimeout(refreshTimer);
      refreshQueued = true;
      // Never render inline in a ViewTreeObserver callback. A separate JS task,
      // then a main-thread message, runs only after the last motion/layout event.
      const delay = Math.max(0, discoveryDeadline - now);
      refreshTimer = setTimeout(() => {
        if (ticket !== refreshGeneration) return;
        refreshTimer = null;
        if (disposed) { refreshQueued = false; return; }
        try { Java.perform(() => {
          try { Java.scheduleOnMainThread(() => {
            if (ticket !== refreshGeneration) return;
            refreshQueued = false;
            if (disposed) return;
            try {
              if (Date.now() < Math.max(discoveryDeadline, scrollingUntil)) { queueRefresh(null, 'retry', weakCandidate); return; }
              const view = weakGet(weakCandidate), candidateRoot = normalizeRoot(view);
              const focused = candidateRoot != null && updaterRoot(candidateRoot) && candidateRoot.hasWindowFocus() ? candidateRoot :
                windowRoots().find(root => updaterRoot(root) && root.hasWindowFocus());
              if (!focused) { controller.pause(null); labelRoot = null; discoveryDirty = false; renderDirty = false; focusDirty = false; pendingTextViews = []; return; }
              const previous = weakGet(labelRoot);
              const discover = discoveryDirty, apply = renderDirty, forceState = focusDirty;
              const changedViews = pendingTextViews; pendingTextViews = [];
              discoveryDirty = false; renderDirty = false; focusDirty = false;
              if (!controller.inspect().active || previous == null || !previous.equals(focused)) resumeRoot(focused);
              else if (discover) {
                observeLatestVersion?.(focused, true);
                controller.refresh(false);
              }
              else {
                for (const reference of changedViews) { const view = weakGet(reference); if (view != null) controller.refreshView(view); }
                if (apply) controller.apply();
              }
              if (forceState) {
                observeLatestVersion?.(focused, true);
                controller.refreshState(true);
              }
            } catch (error) { warning('Optional restart label refresh failed', error); }
          }); } catch (error) { refreshQueued = false; warning('Scheduling optional restart label refresh failed', error); }
        }); } catch (error) { refreshQueued = false; warning('Entering optional restart label refresh failed', error); }
      }, delay);
    } catch (error) { refreshQueued = false; warning('Scheduling optional restart label refresh failed', error); }
  }
  function hook(method, implementation) {
    const prior = method.implementation;
    try { method.implementation = implementation; }
    finally {
      // The real bridge getter returns its NativeCallback, not the JS handler
      // supplied above. Retain that exact owned replacement for disposal.
      hooks.push([method, method.implementation, prior]);
    }
  }
  function dispose() {
    if (disposed) return; disposed = true;
    cancelDiscovery(); labelRoot = null; resourceCache.length = 0;
    unwatchAll();
    try { controller.dispose(); } catch (error) { warning('Stopping the optional restart label controller failed', error); }
    if (callbacks) { try { app.unregisterActivityLifecycleCallbacks(callbacks); } catch (error) { warning('Removing restart label lifecycle observer failed', error); } }
    for (const [method, implementation, prior] of hooks) {
      try { if (method.implementation === implementation) method.implementation = prior; } catch (error) { warning('Restoring native click dispatch failed', error); }
    }
    try { if (callbacks && callbacks.equals(properties.get(registry))) properties.remove(registry); } catch (_) {}
  }
  try {
    // A reinjected agent retires the previous optional observer/timer first.
    const previous = properties.get(registry);
    if (previous != null) previous.getClass().getMethod('hnublLabelsStop', Java.array('java.lang.Class', []))
      .invoke(previous, Java.array('java.lang.Object', []));
    const lifecycle = {
      resume(owner) { const root = normalizeRoot(owner); if (root != null) resumeRoot(root); },
      pause(owner) {
        try {
          const root = normalizeRoot(owner); if (root != null) controller.pause(root);
          if (controller.inspect().active && !windowRoots().some(view => updaterRoot(view) && view.hasWindowFocus())) controller.pause(null);
        } finally {
          if (!controller.inspect().active) { cancelDiscovery(); labelRoot = null; unwatchAll(); }
        }
      },
    };
    const methods = lifecycleImplementations(ActivityCallbacks.class, lifecycle, warning);
    methods.hnublLabelsStop = {returnType: 'void', argumentTypes: [], implementation: dispose};
    const Listener = Java.registerClass({name: 'net.hawyn.hnubl.RebootLabels' + Date.now(), implements: [ActivityCallbacks], methods});
    callbacks = Listener.$new();
    app.registerActivityLifecycleCallbacks(callbacks);
    properties.put(registry, callbacks);
    for (const name of ['performClick', 'callOnClick']) {
      const method = View[name].overload();
      const implementation = function () { return controller.aroundClick(this, () => method.call(this)); };
      hook(method, implementation);
    }
    // Standard tree observers are additive: never replace native text setters,
    // child insertion or window-focus methods merely to observe presentation.
    try {
      const contract = Java.use('android.view.ViewTreeObserver$OnGlobalLayoutListener');
      layoutClass = Java.registerClass({name: 'net.hawyn.hnubl.RebootTreeLayout' + Date.now(), implements: [contract],
        fields: {hnublRoot: 'java.lang.ref.WeakReference', hnublActive: 'boolean'},
        methods: {onGlobalLayout: {returnType: 'void', argumentTypes: [], implementation() { observerChanged(this); }}}});
    } catch (error) { warning('Optional global-layout observer unavailable; lifecycle refresh retained', error); }
    try {
      const contract = Java.use('android.view.ViewTreeObserver$OnWindowFocusChangeListener');
      focusClass = Java.registerClass({name: 'net.hawyn.hnubl.RebootTreeFocus' + Date.now(), implements: [contract],
        fields: {hnublRoot: 'java.lang.ref.WeakReference', hnublActive: 'boolean'},
        methods: {onWindowFocusChanged: {returnType: 'void', argumentTypes: ['boolean'], implementation() { observerChanged(this, 'focus'); }}}});
    } catch (error) { warning('Optional tree focus observer unavailable; lifecycle refresh retained', error); }
    try {
      const contract = Java.use('android.view.ViewTreeObserver$OnScrollChangedListener');
      scrollClass = Java.registerClass({name: 'net.hawyn.hnubl.RebootTreeScroll' + Date.now(), implements: [contract],
        fields: {hnublRoot: 'java.lang.ref.WeakReference', hnublActive: 'boolean'},
        methods: {onScrollChanged: {returnType: 'void', argumentTypes: [], implementation() { observerChanged(this, 'scroll'); }}}});
    } catch (error) { warning('Optional tree scroll observer unavailable; layout debounce retained', error); }
    try {
      const contract = Java.use('android.text.TextWatcher');
      textClass = Java.registerClass({name: 'net.hawyn.hnubl.RebootText' + Date.now(), implements: [contract],
        fields: {hnublTextId: 'int', hnublActive: 'boolean'},
        methods: {
          beforeTextChanged: {returnType: 'void', argumentTypes: ['java.lang.CharSequence', 'int', 'int', 'int'], implementation() {}},
          onTextChanged: {returnType: 'void', argumentTypes: ['java.lang.CharSequence', 'int', 'int', 'int'], implementation(value) { changedText(this, value); }},
          afterTextChanged: {returnType: 'void', argumentTypes: ['android.text.Editable'], implementation() {}},
        }});
    } catch (error) { warning('Optional restart TextWatcher unavailable; idle discovery fallback retained', error); }
    // An already visible Activity or Dialog is found through real framework
    // window roots. DecorContext need not retain an Activity reference.
    try {
      Java.scheduleOnMainThread(() => {
        if (disposed) return;
        try {
          const roots = windowRoots().filter(updaterRoot);
          for (const root of roots) watchRoot(root);
          const focused = roots.find(root => root.hasWindowFocus());
          if (focused) resumeRoot(focused);
          else if (!windowSources.length) warning('Optional foreground root capability unavailable; lifecycle hooks remain active');
        } catch (error) { warning('Optional foreground root scan failed; lifecycle hooks remain active', error); }
      });
    } catch (error) { warning('Scheduling optional foreground scan failed; lifecycle hooks remain active', error); }
  } catch (error) { dispose(); warning('Optional restart labels unavailable; native controls retained', error); }
  return {refresh: controller.refresh, refreshState: controller.refreshState, aroundOriginal: controller.aroundOriginal, dispose,
    inspect() { return {...controller.inspect(), scrollActive: Date.now() < scrollingUntil,
      discoveryDirty, renderDirty, focusDirty, textDirty: pendingTextViews.length > 0, textEventCount,
      textCandidates: textWatches.size, discoveryScheduled: refreshQueued}; }};
}
