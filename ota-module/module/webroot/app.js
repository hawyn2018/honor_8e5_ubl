import { createModuleApi } from './bridge.js';
import { OtaSettings, activationState } from './state.js';

const byId = id => document.getElementById(id);
const modeName = (full, patch) => {
  const fullMode = full ? '始终获取全量包' : '按版本判断更新';
  return patch ? '补丁包优先 · 无补丁时' + fullMode : fullMode;
};
const clock = '<svg viewBox="0 0 24 24"><path d="M12 7v5l3 2"/><circle cx="12" cy="12" r="9"/></svg>';
const check = '<svg viewBox="0 0 24 24"><path d="m7 12 3 3 7-7"/><circle cx="12" cy="12" r="9"/></svg>';

export function render({ status, busy, feedback, selectedDesired, selectedPatchDesired }) {
  byId('forceFullPackage').disabled = !!busy || !status;
  byId('preferPatchPackage').disabled = !!busy || !status;
  byId('applyButton').disabled = !!busy || !status;
  byId('applyButton').setAttribute('aria-busy', busy === 'applying' ? 'true' : 'false');
  byId('forceFullPackage').setAttribute('aria-busy', busy === 'saving' ? 'true' : 'false');
  byId('preferPatchPackage').setAttribute('aria-busy', busy === 'saving' ? 'true' : 'false');
  byId('applyLabel').textContent = busy === 'applying' ? '正在应用…' : '立即应用';
  if (status) {
    const readiness = activationState(status);
    byId('statusHeading').textContent = readiness === 'running' ? '当前生效' : readiness === 'idle' ? '已应用' : '等待生效';
    const selected = typeof selectedDesired === 'boolean' ? selectedDesired : status.desired;
    const selectedPatch = typeof selectedPatchDesired === 'boolean' ? selectedPatchDesired : status.desiredPatch;
    byId('forceFullPackage').checked = selected;
    byId('preferPatchPackage').checked = selectedPatch;
    byId('activeMode').textContent = modeName(status.active, status.activePatch);
    byId('stateTag').textContent = busy === 'saving' ? '保存中' : busy === 'applying' ? '应用中' : status.pending ? '待应用' : readiness === 'running' ? '已生效' : readiness === 'idle' ? '等待打开' : '待就绪';
    byId('stateNote').textContent = status.pending ? '新设置已保存，当前仍使用上面的策略。' : readiness === 'running' ? '软件更新保护已就绪。' : readiness === 'idle' ? '下次手动打开软件更新时生效。' : '设置已加载，等待模块确认就绪。';
    byId('statusSymbol').innerHTML = !status.pending && readiness !== 'waiting' ? check : clock;
    byId('modeDescription').textContent = selected ? '开启后保留原模块行为，即使系统为最新版本，也提供全量包。' : '关闭时按版本判断更新，同版或较旧版本显示为最新。';
    byId('saveNote').textContent = busy === 'saving' ? '正在保存新设置：' + modeName(selected, selectedPatch) + '…' : status.pending ? '已保存的新设置：' + modeName(status.desired, status.desiredPatch) + '。' : readiness !== 'waiting' ? '设置已保存在模块中，手机重启后仍会保留。' : '设置会保存到模块中，下次手机启动时生效。';
  } else if (!busy) {
    byId('statusHeading').textContent = '模块状态';
    byId('activeMode').textContent = '尚未读取设置';
    byId('stateTag').textContent = '未连接';
    byId('stateNote').textContent = '请确认模块已安装，并从 KernelSU 打开。';
  }
  byId('feedback').hidden = !feedback;
  byId('refreshButton').hidden = !feedback || feedback.kind !== 'error' || !!busy;
  if (feedback) {
    byId('feedback').dataset.kind = feedback.kind;
    byId('feedbackMessage').textContent = feedback.message;
    byId('errorDetails').hidden = !feedback.detail;
    byId('errorText').textContent = feedback.detail || '';
  }
}

const settings = new OtaSettings(createModuleApi(), render);
byId('forceFullPackage').addEventListener('change', event => settings.save(event.target.checked));
byId('preferPatchPackage').addEventListener('change', event => settings.save(event.target.checked, 'desiredPatch'));
byId('applyButton').addEventListener('click', () => settings.apply());
byId('refreshButton').addEventListener('click', () => settings.load());
window.addEventListener('pagehide', event => { if (!event.persisted) settings.dispose(); });
document.addEventListener('visibilitychange', () => { if (!document.hidden && !settings.busy) settings.load(); });
settings.load();
