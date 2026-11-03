import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const base = new URL('../module/webroot/', import.meta.url);
async function load(name) { return import('data:text/javascript;base64,' + Buffer.from(await readFile(new URL(name, base))).toString('base64')); }
const { controlCommand, execKernelSu, parseStatus, createModuleApi } = await load('bridge.js');
const { OtaSettings, activationState } = await load('state.js');
const status = (values = {}) => ({ desired: false, active: false, pending: false, ready: true, controllerReady: true, updaterRunning: true, waitingForUpdater: false, activationId: 'old-id', error: '', ...values });
const wire = (values = {}) => ({ desired_force_full_package: false, active_force_full_package: false, pending: false, hook_ready: true, controller_ready: true, updater_running: true, waiting_for_updater: false, activation_id: 'old-id', error: '', ...values });

test('commands are a closed set and do not accept shell text', () => {
  assert.equal(controlCommand('status'), 'sh /data/adb/modules/hnubl_ota/webui-control.sh status');
  assert.match(controlCommand('save', true), / save 1$/);
  assert.match(controlCommand('save', false), / save 0$/);
  for (const input of ['1; reboot', 1, '0', null]) assert.throws(() => controlCommand('save', input));
  assert.throws(() => controlCommand('reboot'));
});
test('bridge follows the documented callback ABI and cleans callbacks', async () => {
  const scope = { setTimeout, clearTimeout };
  scope.ksu = { exec(command, options, name) { assert.equal(command, 'fixture'); assert.equal(options, '{}'); assert.equal(typeof scope[name], 'function'); scope[name](0, 'stdout', 'stderr'); } };
  assert.deepEqual(await execKernelSu('fixture', { scope }), { errno: 0, stdout: 'stdout', stderr: 'stderr' });
  assert.deepEqual(Object.keys(scope).sort(), ['clearTimeout', 'ksu', 'setTimeout']);
});
test('bridge errors and timeouts leave no callback behind', async () => {
  await assert.rejects(execKernelSu('fixture', { scope: { setTimeout, clearTimeout } }), /KernelSU/);
  for (const exec of [() => { throw new Error('bridge failed'); }, () => {}]) {
    const scope = { setTimeout, clearTimeout, ksu: { exec } };
    await assert.rejects(execKernelSu('fixture', { scope, timeout: 5 }));
    assert.deepEqual(Object.keys(scope).sort(), ['clearTimeout', 'ksu', 'setTimeout']);
  }
});
test('status requires actual booleans and activation fields', () => {
  assert.deepEqual(parseStatus(wire({ desired_force_full_package: true, pending: true })), status({ desired: true, pending: true }));
  for (const value of [null, [], {}, wire({ hook_ready: 'true' }), wire({ pending: 1 }), wire({ activation_id: null }), wire({ controller_ready: undefined }), wire({ updater_running: 0 }), wire({ waiting_for_updater: 'true' })]) assert.throws(() => parseStatus(value));
  assert.throws(() => parseStatus(wire({ controller_ready: undefined })), /2\.7\.6/);
});
test('backend failures and malformed JSON do not become success', async () => {
  for (const result of [{ errno: 1, stdout: '{"error":"fixture failure"}', stderr: '' }, { errno: 0, stdout: 'bad', stderr: '' }]) {
    const api = createModuleApi(async () => result);
    await assert.rejects(api.status());
  }
});
test('initialization only reads backend; never writes or replaces saved true', async () => {
  const calls = [];
  const model = new OtaSettings({ status: async () => { calls.push('status'); return status({ desired: true, active: true }); }, save: async () => calls.push('save') }, () => {});
  await model.load();
  assert.deepEqual(calls, ['status']); assert.equal(model.status.desired, true);
});
test('save leaves active policy unchanged and never applies implicitly', async () => {
  const events = [];
  const model = new OtaSettings({ status: async () => status(), save: async value => { events.push(['save', value]); return status({ desired: value, pending: true }); }, apply: async () => events.push('apply') }, () => {});
  await model.load(); await model.save(true);
  assert.deepEqual(events, [['save', true]]); assert.equal(model.status.active, false); assert.equal(model.status.pending, true); assert.notEqual(model.feedback.kind, 'success');
});
test('save failure restores the backend-confirmed selection', async () => {
  const model = new OtaSettings({ status: async () => status(), save: async () => { throw new Error('fixture'); } }, () => {});
  await model.load(); await model.save(true);
  assert.equal(model.status.desired, false); assert.equal(model.feedback.kind, 'error');
});
test('apply waits for new matching activation, ready=true and the desired mode', async () => {
  const states = []; let requests = 0;
  const model = new OtaSettings({
    status: async () => {
      if (requests++ === 0) return status({ desired: true, pending: true });
      return status({ desired: true, active: true, pending: false, activationId: 'new-id', ready: requests > 2 });
    },
    apply: async () => status({ desired: true, active: true, pending: false, activationId: 'new-id', ready: false })
  }, snapshot => states.push({ busy: snapshot.busy, feedback: snapshot.feedback && snapshot.feedback.kind }), { wait: async () => {} });
  await model.load(); await model.apply();
  assert.equal(requests, 3); assert.equal(model.feedback.kind, 'success');
  assert.equal(states.filter(s => s.feedback === 'success').length, 1);
});
test('active snapshot alone does not mean successful application', async () => {
  let first = true;
  const model = new OtaSettings({ status: async () => { if (first) { first = false; return status(); } return status({ activationId: 'new-id', ready: false }); }, apply: async () => status({ activationId: 'new-id', ready: false }) }, () => {}, { wait: async () => {}, pollAttempts: 2 });
  await model.load(); await model.apply();
  assert.equal(model.feedback.kind, 'error'); assert.equal(model.busy, null);
});

test('idle Apply completes on the new controller proof without opening the updater', async () => {
  const calls = [];
  const idle = status({ desired: true, active: true, activationId: 'new-id', ready: false, updaterRunning: false, waitingForUpdater: true });
  const api = createModuleApi(async command => {
    calls.push(command);
    const value = command.endsWith(' status') && calls.length === 1 ? wire({ desired_force_full_package: true, pending: true }) : wire({ desired_force_full_package: true, active_force_full_package: true, activation_id: 'new-id', hook_ready: false, updater_running: false, waiting_for_updater: true });
    return { errno: 0, stdout: JSON.stringify(value), stderr: '' };
  });
  const model = new OtaSettings(api, () => {}, { wait: async () => {}, pollAttempts: 1 });
  await model.load(); await model.apply();
  assert.equal(activationState(idle), 'idle');
  assert.equal(model.feedback.kind, 'success');
  assert.equal(model.feedback.message, '设置已应用，下次打开软件更新时生效。');
  assert.equal(model.status.ready, false);
  assert.deepEqual(calls.map(command => command.split(' ').at(-1)), ['status', 'apply']);
  assert.ok(calls.every(command => !/am |monkey|reboot/.test(command)));
});

test('idle proof requires a controller observation and no running updater', async () => {
  for (const changes of [{ controllerReady: false }, { updaterRunning: true }, { waitingForUpdater: false }, { error: 'fixture error' }]) {
    let reads = 0;
    const candidate = status({ activationId: 'new-id', ready: false, updaterRunning: false, waitingForUpdater: true, ...changes });
    assert.equal(activationState(candidate), 'waiting');
    const model = new OtaSettings({ status: async () => reads++ === 0 ? status() : candidate, apply: async () => candidate }, () => {}, { wait: async () => {}, pollAttempts: 1 });
    await model.load(); await model.apply();
    assert.equal(model.feedback.kind, 'error');
  }
});

test('an idle controller with a stale activation or wrong policy is not applied', async () => {
  for (const scenario of ['stale', 'wrong-policy', 'pending']) {
    let reads = 0;
    const candidate = status({ desired: true, active: scenario !== 'wrong-policy', pending: scenario === 'pending', activationId: scenario === 'stale' ? 'old-id' : 'new-id', ready: false, updaterRunning: false, waitingForUpdater: true });
    const model = new OtaSettings({ status: async () => reads++ === 0 ? status({ desired: true, pending: true }) : candidate, apply: async () => candidate }, () => {}, { wait: async () => {}, pollAttempts: 1 });
    await model.load(); await model.apply();
    assert.equal(model.feedback.kind, 'error', scenario);
  }
});
test('stale activation, superseded activation, or wrong policy cannot report applied', async () => {
  for (const scenario of ['stale', 'superseded', 'wrong-policy', 'pending', 'changed-desired', 'error']) {
    let first = true;
    const model = new OtaSettings({
      status: async () => {
        if (first) { first = false; return status({ desired: true, pending: true }); }
        return status({ desired: scenario !== 'changed-desired', active: scenario !== 'wrong-policy', pending: scenario === 'pending', activationId: scenario === 'superseded' ? 'other-id' : 'new-id', error: scenario === 'error' ? 'fixture reported error' : '' });
      },
      apply: async () => status({ desired: true, active: true, activationId: scenario === 'stale' ? 'old-id' : 'new-id', ready: false })
    }, () => {}, { wait: async () => {}, pollAttempts: 1 });
    await model.load(); await model.apply(); assert.equal(model.feedback.kind, 'error', scenario);
  }
});
test('saving or applying is serialized; a second click does not send another command', async () => {
  let complete; let count = 0;
  const promise = new Promise(resolve => { complete = resolve; });
  const model = new OtaSettings({ status: async () => status(), save: async () => { count++; await promise; return status({ desired: true, pending: true }); } }, () => {});
  await model.load(); const work = model.save(true); await model.save(false); await model.apply(); complete(); await work;
  assert.equal(count, 1);
});
test('closing the page prevents late success feedback and further polling', async () => {
  let model; let statusReads = 0;
  model = new OtaSettings({ status: async () => { statusReads++; return status(); }, apply: async () => status({ activationId: 'new-id', ready: false }) }, () => {}, { wait: async () => model.dispose() });
  await model.load(); await model.apply(); assert.equal(statusReads, 1); assert.notEqual(model.feedback.kind, 'success');
});
test('assets are offline and production app has no preview/mock flag', async () => {
  const html = await readFile(new URL('index.html', base), 'utf8');
  assert.match(html, /connect-src 'none'/); assert.doesNotMatch(html, /(?:src|href)=["']https?:/);
  assert.match(html, /role="switch"/); assert.match(html, /aria-live="polite"/);
  const js = await readFile(new URL('app.js', base), 'utf8');
  assert.doesNotMatch(js, /localStorage|sessionStorage|preview|mock/i);
  assert.match(js, /activationState\(status\)/);
  assert.match(js, /'已应用'/); assert.match(js, /'等待打开'/);
  assert.match(html, /未运行时保持关闭/);
  const props = await readFile(new URL('../module/module.prop', import.meta.url), 'utf8');
  const version = props.match(/^version=(.+)$/m)?.[1];
  assert.ok(version); assert.ok(html.includes('class="version">' + version + '</span>'));
});

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

test('a slow save immediately shows the new selection without claiming persistence', async () => {
  const saving = deferred(); const frames = []; const commands = [];
  const model = new OtaSettings({ status: async () => status(), save: value => {
    commands.push(['save', value]); return saving.promise;
  }, apply: async () => { commands.push('apply'); throw new Error('Unexpected Apply'); } }, value => frames.push(value));
  await model.load();
  const work = model.save(true);
  const frame = frames.at(-1);
  assert.equal(frame.selectedDesired, true);
  assert.equal(frame.status.desired, false);
  assert.equal(frame.status.active, false);
  assert.equal(frame.busy, 'saving');
  assert.equal(frame.feedback.kind, 'progress');
  await model.save(false); await model.apply(); await model.load();
  assert.deepEqual(commands, [['save', true]]);
  saving.resolve(status({ desired: true, pending: true })); await work;
  assert.equal(frames.at(-1).selectedDesired, true);
  assert.equal(model.status.desired, true); assert.equal(model.busy, null);
  assert.notEqual(model.feedback.kind, 'success');
});

test('a rejected or unconfirmed slow save restores the last confirmed selection', async () => {
  for (const rejected of [true, false]) {
    const saving = deferred(); const frames = [];
    const model = new OtaSettings({ status: async () => status(), save: () => saving.promise }, value => frames.push(value));
    await model.load(); const work = model.save(true);
    assert.equal(frames.at(-1).selectedDesired, true);
    if (rejected) saving.reject(new Error('write failed')); else saving.resolve(status());
    await work;
    assert.equal(frames.at(-1).selectedDesired, false);
    assert.equal(model.status.desired, false); assert.equal(model.feedback.kind, 'error');
  }
});

test('Apply displays progress before its result and accepts fresh actual readiness without polling', async () => {
  const applying = deferred(); const frames = []; const delays = []; let reads = 0;
  const model = new OtaSettings({ status: async () => { reads++; return status({ desired: true, pending: true }); },
    apply: () => applying.promise }, value => frames.push(value), { wait: async ms => delays.push(ms) });
  await model.load(); const work = model.apply();
  assert.equal(frames.at(-1).busy, 'applying'); assert.equal(frames.at(-1).feedback.kind, 'progress');
  applying.resolve(status({ desired: true, active: true, activationId: 'fresh-id' })); await work;
  assert.equal(model.feedback.kind, 'success'); assert.equal(reads, 1); assert.deepEqual(delays, []);
});

test('an unready Apply polls immediately then backs off while keeping real activation checks', async () => {
  const delays = []; let reads = 0;
  const model = new OtaSettings({ status: async () => {
    if (reads++ === 0) return status();
    return status({ activationId: 'fresh-id', ready: reads >= 3 });
  }, apply: async () => status({ activationId: 'fresh-id', ready: false }) }, () => {},
  { wait: async ms => delays.push(ms) });
  await model.load(); await model.apply();
  assert.equal(model.feedback.kind, 'success'); assert.deepEqual(delays, [0, 250]);
});

test('disposed pages ignore delayed status/save/apply callbacks', async () => {
  for (const action of ['load', 'save', 'apply']) {
    const result = deferred(); const frames = [];
    const model = new OtaSettings({ status: () => action === 'load' ? result.promise : Promise.resolve(status()),
      save: () => result.promise, apply: () => result.promise }, value => frames.push(value));
    if (action !== 'load') await model.load();
    const work = action === 'save' ? model.save(true) : model[action]();
    const count = frames.length; const confirmed = model.status;
    model.dispose(); result.resolve(status({ desired: true, active: true, activationId: 'fresh-id' }));
    await work;
    assert.equal(frames.length, count); assert.equal(model.status, confirmed);
    assert.notEqual(model.feedback?.kind, 'success');
  }
});

test('native bridge is deferred across a render frame before potentially blocking exec', async () => {
  let frame, calls = 0;
  const scope = { setTimeout, clearTimeout, requestAnimationFrame: callback => { frame = callback; return 1; },
    cancelAnimationFrame: () => {} };
  scope.ksu = { exec(command, options, name) { calls++; scope[name](0, '{}', ''); } };
  const result = execKernelSu('fixture', { scope });
  assert.equal(calls, 0); assert.equal(typeof frame, 'function');
  frame(); assert.equal(calls, 0);
  await result; assert.equal(calls, 1);
});

test('a bridge request that expires before a frame never starts its command later', async () => {
  let frame, calls = 0;
  const scope = { setTimeout, clearTimeout, requestAnimationFrame: callback => { frame = callback; return 1; },
    cancelAnimationFrame: () => {}, ksu: { exec() { calls++; } } };
  await assert.rejects(execKernelSu('fixture', { scope, timeout: 5 }), /超时/);
  frame(); await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(calls, 0);
  assert.equal(Object.keys(scope).filter(name => name.startsWith('hnubl_ota_exec_')).length, 0);
});
