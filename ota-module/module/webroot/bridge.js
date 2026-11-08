/** Small offline adapter for KernelSU's documented three-argument exec bridge.
 * Reference: https://github.com/tiann/KernelSU/blob/main/js/index.js
 * The UI exposes only the fixed module-control commands below.
 */
const CONTROL = 'sh /data/adb/modules/hnubl_ota/webui-control.sh';
let sequence = 0;

export function controlCommand(action, value) {
  if (action === 'status' || action === 'apply') return CONTROL + ' ' + action;
  if ((action === 'save' || action === 'save-patch') && typeof value === 'boolean')
    return CONTROL + ' ' + action + ' ' + (value ? '1' : '0');
  throw new Error('无效的设置操作');
}

export function execKernelSu(command, { timeout = 15000, scope = globalThis } = {}) {
  return new Promise((resolve, reject) => {
    const bridge = scope.ksu;
    if (!bridge || typeof bridge.exec !== 'function') {
      reject(new Error('请从 KernelSU 管理器的模块页面打开此 WebUI。'));
      return;
    }
    const name = 'hnubl_ota_exec_' + Date.now() + '_' + sequence++;
    let completed = false;
    let launchTimer, paintFrame;
    const finish = (result, failure) => {
      if (completed) return;
      completed = true;
      scope.clearTimeout(timer);
      if (launchTimer !== undefined) scope.clearTimeout(launchTimer);
      if (paintFrame !== undefined && typeof scope.cancelAnimationFrame === 'function') scope.cancelAnimationFrame(paintFrame);
      delete scope[name];
      if (failure) reject(failure); else resolve(result);
    };
    const timer = scope.setTimeout(() => finish(null, new Error('模块响应超时，请稍后重试。')), timeout);
    scope[name] = (errno, stdout, stderr) => finish({ errno: Number(errno), stdout: String(stdout || ''), stderr: String(stderr || '') });
    const launch = () => {
      if (completed) return;
      try { bridge.exec(command, '{}', name); }
      catch (failure) { finish(null, failure instanceof Error ? failure : new Error(String(failure))); }
    };
    // Some managers do native work while entering exec(). Let the new selection
    // and progress label paint first instead of keeping the click visually stale.
    if (typeof scope.requestAnimationFrame === 'function') {
      paintFrame = scope.requestAnimationFrame(() => { if (!completed) launchTimer = scope.setTimeout(launch, 0); });
    } else launchTimer = scope.setTimeout(launch, 0);
  });
}

export function parseStatus(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('模块状态格式无效。');
  for (const name of ['desired_force_full_package', 'active_force_full_package', 'desired_prefer_patch_package',
    'active_prefer_patch_package', 'pending', 'hook_ready', 'controller_ready', 'updater_running', 'waiting_for_updater']) {
    if (typeof data[name] !== 'boolean') throw new Error('模块状态缺少有效字段：' + name + '。请确认已安装支持补丁包开关的版本。');
  }
  if (typeof data.activation_id !== 'string' || typeof data.error !== 'string') throw new Error('模块激活状态格式无效。');
  return {
    desired: data.desired_force_full_package,
    active: data.active_force_full_package,
    desiredPatch: data.desired_prefer_patch_package,
    activePatch: data.active_prefer_patch_package,
    pending: data.pending,
    ready: data.hook_ready,
    controllerReady: data.controller_ready,
    updaterRunning: data.updater_running,
    waitingForUpdater: data.waiting_for_updater,
    activationId: data.activation_id,
    error: data.error
  };
}

export function createModuleApi(exec = execKernelSu) {
  async function invoke(action, value) {
    const result = await exec(controlCommand(action, value), { timeout: action === 'apply' ? 30000 : 15000 });
    let data;
    try { data = JSON.parse(result.stdout.trim()); }
    catch (_) { throw new Error(result.stderr.trim() || '模块返回了无效的状态，请确认已安装支持补丁包开关的版本。'); }
    if (result.errno !== 0) throw new Error(typeof data.error === 'string' && data.error ? data.error : result.stderr.trim() || '模块操作失败，请稍后重试。');
    return parseStatus(data);
  }
  return {
    status: () => invoke('status'),
    save: value => invoke('save', value),
    savePatch: value => invoke('save-patch', value),
    apply: () => invoke('apply')
  };
}
