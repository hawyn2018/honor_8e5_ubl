/** Offline Edge/CDP integration/visual check. No npm or device is involved. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile, mkdtemp } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../module/webroot/', import.meta.url));
const out = resolve(process.argv[2] || 'analysis/hnubl-2.8.6-patch-retry-webui-20261102/webui-previews');
await mkdir(out, { recursive: true });
const profile = await mkdtemp(join(out, 'edge-profile-'));
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const errors = []; const requests = [];
const fixture = `
(() => {
  const initial = new URL(location.href).searchParams.get('saved') === 'on';
  const running = new URL(location.href).searchParams.get('idle') !== '1';
  const slow = new URL(location.href).searchParams.get('slow') === '1';
  let state = { desired_force_full_package: initial, active_force_full_package: initial, pending: false, hook_ready: running, controller_ready: true, updater_running: running, waiting_for_updater: !running, activation_id: 'before-apply', error: '' };
  let settling = false, polls = 0;
  window.__commands = [];
  window.__updaterRestarts = 0;
  window.__saveReplies = 0;
  window.__applyReplies = 0;
  window.ksu = { exec(command, options, callback) {
    window.__commands.push(command);
    const prefix = 'sh /data/adb/modules/hnubl_ota/webui-control.sh ';
    if (!command.startsWith(prefix) || !['status', 'apply', 'save 0', 'save 1'].includes(command.slice(prefix.length))) throw new Error('Unexpected command: ' + command);
    if (command.endsWith('save 1') || command.endsWith('save 0')) {
      state.desired_force_full_package = command.endsWith('1');
      state.pending = state.desired_force_full_package !== state.active_force_full_package;
    } else if (command.endsWith(' apply')) {
      state.active_force_full_package = state.desired_force_full_package;
      state.pending = false; state.hook_ready = false; state.controller_ready = false; state.waiting_for_updater = false; state.activation_id = 'current-apply'; settling = true; polls = 0;
      if (running) window.__updaterRestarts++;
    } else if (settling && ++polls >= 2) { state.controller_ready = true; state.hook_ready = running; state.waiting_for_updater = !running; settling = false; }
    const reply = JSON.stringify(state);
    const delayed = slow && / (?:apply|save [01])$/.test(command);
    setTimeout(() => {
      if (/ save [01]$/.test(command)) window.__saveReplies++;
      if (command.endsWith(' apply')) window.__applyReplies++;
      window[callback](0, reply, '');
    }, delayed ? 1000 : 10);
  } };
})();`;
const allowed = new Set(['index.html', 'style.css', 'app.js', 'state.js', 'bridge.js']);
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://localhost');
    if (url.pathname === '/fixture.js') { response.setHeader('Content-Type', 'text/javascript'); response.end(fixture); return; }
    const name = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
    if (!allowed.has(name)) { response.writeHead(404); response.end(); return; }
    let data = await readFile(join(root, name));
    if (name === 'index.html') data = Buffer.from(data.toString().replace('</head>', '<script src="fixture.js"></script></head>'));
    response.setHeader('Content-Type', name.endsWith('.html') ? 'text/html; charset=utf-8' : name.endsWith('.css') ? 'text/css' : 'text/javascript');
    response.end(data);
  } catch (error) { response.writeHead(500); response.end(String(error)); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = 'http://127.0.0.1:' + server.address().port;
const edge = process.env.HNUBL_TEST_BROWSER || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const child = spawn(edge, ['--headless=new', '--disable-gpu', '--no-first-run', '--disable-background-networking', '--disable-extensions', '--disable-component-update', '--disable-sync', '--disable-default-apps', '--no-proxy-server', '--remote-debugging-port=0', '--remote-debugging-address=127.0.0.1', '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: 'ignore' });
let ws;
try {
  let port;
  for (let tries = 0; tries < 100 && !port; tries++) {
    try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); } catch (_) {}
    if (!port) await pause(100);
  }
  assert.ok(port, 'Headless browser did not expose a local DevTools port');
  const target = await (await fetch('http://127.0.0.1:' + port + '/json/new?about:blank', { method: 'PUT' })).json();
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); });
  const pending = new Map(); let next = 1;
  ws.addEventListener('message', event => {
    const data = JSON.parse(event.data);
    if (data.id) { const item = pending.get(data.id); if (item) { pending.delete(data.id); data.error ? item.reject(new Error(JSON.stringify(data.error))) : item.resolve(data.result); } }
    if (data.method === 'Runtime.exceptionThrown') errors.push(data.params.exceptionDetails);
    if (data.method === 'Network.requestWillBeSent') requests.push(data.params.request.url);
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => { const id = next++; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })); });
  const evaluate = async expression => {
    const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  async function until(expression) { for (let n = 0; n < 100; n++) { if (await evaluate('Boolean(document.getElementById("forceFullPackage")) && (' + expression + ')')) return; await pause(100); } throw new Error('Timed out: ' + expression); }
  async function viewport(width, dark) {
    await send('Emulation.setDeviceMetricsOverride', { width, height: 880, deviceScaleFactor: 1, mobile: true });
    await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: dark ? 'dark' : 'light' }] });
  }
  async function shot(name) {
    assert.ok(await evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Horizontal content overflow');
    const size = await send('Page.getLayoutMetrics');
    const capture = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip: { x: 0, y: 0, width: size.cssContentSize.width, height: size.cssContentSize.height, scale: 1 } });
    await writeFile(join(out, name + '.png'), Buffer.from(capture.data, 'base64'));
  }
  await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable');
  await viewport(390, false);
  await send('Page.navigate', { url: origin + '/' });
  await until('!document.getElementById("forceFullPackage").disabled');
  assert.deepEqual(await evaluate('window.__commands'), ['sh /data/adb/modules/hnubl_ota/webui-control.sh status']);
  assert.equal(await evaluate('document.getElementById("forceFullPackage").checked'), false);
  await shot('light-off');
  await evaluate('document.getElementById("forceFullPackage").click()');
  await until('document.getElementById("stateTag").textContent === "待应用" && !document.getElementById("applyButton").disabled');
  assert.equal(await evaluate('document.getElementById("activeMode").textContent'), '按版本判断更新');
  await shot('light-pending');
  await evaluate('document.getElementById("applyButton").click()');
  await until('document.getElementById("statusHeading").textContent === "等待生效"');
  assert.notEqual(await evaluate('document.getElementById("feedback").dataset.kind'), 'success');
  assert.equal(await evaluate('document.getElementById("applyButton").disabled'), true);
  await until('document.getElementById("feedback").dataset.kind === "success"');
  assert.equal(await evaluate('document.getElementById("activeMode").textContent'), '始终获取全量包');
  assert.equal(await evaluate('window.__updaterRestarts'), 1);
  await viewport(320, true); await shot('dark-on-320');
  const commands = await evaluate('window.__commands');
  assert.equal(commands.filter(command => command.endsWith(' apply')).length, 1);
  assert.equal(commands.filter(command => command.includes('save ')).length, 1);
  assert.ok(commands.every(command => !command.includes('reboot')));
  await send('Page.navigate', { url: origin + '/?saved=on' });
  await until('!document.getElementById("forceFullPackage").disabled');
  assert.equal(await evaluate('document.getElementById("forceFullPackage").checked'), true);
  assert.deepEqual(await evaluate('window.__commands'), ['sh /data/adb/modules/hnubl_ota/webui-control.sh status']);
  await viewport(390, false);
  await send('Page.navigate', { url: origin + '/?idle=1' });
  await until('!document.getElementById("forceFullPackage").disabled');
  assert.equal(await evaluate('document.getElementById("statusHeading").textContent'), '已应用');
  assert.equal(await evaluate('document.getElementById("stateTag").textContent'), '等待打开');
  await evaluate('document.getElementById("forceFullPackage").click()');
  await until('document.getElementById("stateTag").textContent === "待应用" && !document.getElementById("applyButton").disabled');
  await evaluate('document.getElementById("applyButton").click()');
  await until('document.getElementById("statusHeading").textContent === "等待生效"');
  assert.notEqual(await evaluate('document.getElementById("feedback").dataset.kind'), 'success');
  await until('document.getElementById("feedback").dataset.kind === "success"');
  assert.equal(await evaluate('document.getElementById("feedbackMessage").textContent'), '设置已应用，下次打开软件更新时生效。');
  assert.equal(await evaluate('document.getElementById("statusHeading").textContent'), '已应用');
  assert.equal(await evaluate('document.getElementById("stateTag").textContent'), '等待打开');
  assert.equal(await evaluate('window.__updaterRestarts'), 0);
  assert.ok((await evaluate('window.__commands')).every(command => !/am |monkey|reboot/.test(command)));
  await shot('light-idle-applied');
  // Exercise the actual DOM and bridge with persistence deliberately pending.
  await send('Page.navigate', { url: origin + '/?slow=1' });
  await until('!document.getElementById("forceFullPackage").disabled');
  const saveFeedback = await evaluate(`(() => {
    document.getElementById('forceFullPackage').click();
    return { checked: document.getElementById('forceFullPackage').checked,
      busy: document.getElementById('forceFullPackage').getAttribute('aria-busy'),
      tag: document.getElementById('stateTag').textContent,
      note: document.getElementById('saveNote').textContent,
      replies: window.__saveReplies };
  })()`);
  assert.equal(saveFeedback.checked, true); assert.equal(saveFeedback.busy, 'true');
  assert.equal(saveFeedback.tag, '保存中'); assert.match(saveFeedback.note, /^正在保存/);
  assert.equal(saveFeedback.replies, 0);
  await until('window.__commands.some(command => command.includes(" save "))');
  assert.equal(await evaluate('window.__saveReplies'), 0);
  assert.equal(await evaluate('document.getElementById("forceFullPackage").checked'), true);
  assert.equal(await evaluate('getComputedStyle(document.querySelector(".switch-thumb")).transform'), 'matrix(1, 0, 0, 1, 20, 0)');
  await shot('light-saving-pending');
  await until('!document.getElementById("applyButton").disabled');
  const applyFeedback = await evaluate(`(() => {
    document.getElementById('applyButton').click();
    return { label: document.getElementById('applyLabel').textContent,
      tag: document.getElementById('stateTag').textContent,
      feedback: document.getElementById('feedback').dataset.kind,
      replies: window.__applyReplies };
  })()`);
  assert.equal(applyFeedback.label, '正在应用…'); assert.equal(applyFeedback.tag, '应用中');
  assert.equal(applyFeedback.feedback, 'progress'); assert.equal(applyFeedback.replies, 0);
  await until('document.getElementById("feedback").dataset.kind === "success"');
  assert.ok(requests.every(url => url.startsWith(origin)), 'Unexpected external request');
  assert.deepEqual(errors, []);
  await writeFile(join(out, 'browser-result.json'), JSON.stringify({ passed: true, productionUiWithMockKernelSuBridge: true, deviceOperations: false, browser: edge, screenshots: ['light-off.png', 'light-pending.png', 'dark-on-320.png', 'light-idle-applied.png', 'light-saving-pending.png'], commands, externalRequests: [], runtimeExceptions: errors, delayedCallbackMilliseconds: 1000, immediateSaveFeedback: saveFeedback, immediateApplyFeedback: applyFeedback, verified: ['read-only initialization', 'existing true preserved', 'save pending without apply', 'new activation and hook readiness required when updater running', 'only one apply command', '320px no overflow', 'light and dark render', 'no device reboot command', 'idle controller readiness accepted without updater launch', 'active snapshot alone not accepted', 'idle applied status clearly waits for manual updater opening', 'thumb and saving feedback update before a slow persistence response', 'Apply feedback appears before a slow activation response'] }, null, 2) + '\n');
  console.log(JSON.stringify({ passed: true, screenshots: out, cases: 13 }));
  await send('Browser.close').catch(() => {});
} finally {
  if (ws) ws.close();
  child.kill();
  server.close();
}
