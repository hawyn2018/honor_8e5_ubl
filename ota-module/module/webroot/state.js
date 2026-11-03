/** All state is read from the module; page initialization never writes defaults. */
export function activationState(status) {
  if (!status || status.error) return 'waiting';
  if (status.ready) return 'running';
  return status.controllerReady && !status.updaterRunning && status.waitingForUpdater ? 'idle' : 'waiting';
}

export class OtaSettings {
  constructor(api, render, { wait = ms => new Promise(resolve => setTimeout(resolve, ms)), pollAttempts = 30 } = {}) {
    this.api = api; this.render = render; this.wait = wait; this.pollAttempts = pollAttempts;
    this.status = null; this.busy = null; this.feedback = null; this.disposed = false;
    this.selectedDesired = null; this.operation = 0;
  }
  emit() { if (!this.disposed) this.render({ status: this.status, busy: this.busy, feedback: this.feedback,
    selectedDesired: this.selectedDesired === null ? this.status?.desired : this.selectedDesired }); }
  current(operation) { return !this.disposed && operation === this.operation; }
  async load() {
    if (this.busy || this.disposed) return;
    const operation = ++this.operation;
    this.busy = 'loading'; this.feedback = null; this.emit();
    try { const status = await this.api.status(); if (this.current(operation)) this.status = status; }
    catch (error) { if (this.current(operation)) this.feedback = { kind: 'error', message: '读取模块设置失败。', detail: String(error.message || error) }; }
    finally { if (this.current(operation)) { this.busy = null; this.emit(); } }
  }
  async save(value) {
    if (this.busy || !this.status || this.disposed || typeof value !== 'boolean') return;
    const previous = this.status;
    const operation = ++this.operation;
    // Keep the thumb under the user's new selection while persistence is pending.
    // status remains the last backend-confirmed value, not an optimistic success.
    this.selectedDesired = value;
    this.busy = 'saving'; this.feedback = { kind: 'progress', message: '正在保存设置…' }; this.emit();
    try {
      const saved = await this.api.save(value);
      if (!this.current(operation)) return;
      if (saved.desired !== value) throw new Error('模块未确认保存所选设置。');
      this.status = saved;
      this.feedback = { kind: 'neutral', message: saved.pending ? '设置已保存。点击“立即应用”，或在下次手机启动时生效。' : '设置已保存，与当前加载的策略一致。' };
    } catch (error) {
      if (!this.current(operation)) return;
      this.status = previous;
      this.feedback = { kind: 'error', message: '设置保存失败，请重试。', detail: String(error.message || error) };
    } finally { if (this.current(operation)) { this.selectedDesired = null; this.busy = null; this.emit(); } }
  }
  async apply() {
    if (this.busy || !this.status || this.disposed) return;
    const wanted = this.status.desired;
    const oldId = this.status.activationId;
    const operation = ++this.operation;
    this.busy = 'applying';
    this.feedback = { kind: 'progress', message: '正在应用设置，等待模块确认…' }; this.emit();
    try {
      const requested = await this.api.apply();
      if (!this.current(operation)) return;
      if (!requested.activationId || requested.activationId === oldId) throw new Error('模块未返回本次应用设置的新激活标识。');
      this.status = requested; this.emit();
      const confirmed = current => {
        if (current.activationId !== requested.activationId) throw new Error('模块已开始另一次激活，请刷新页面确认当前状态。');
        if (current.desired !== wanted) throw new Error('保存的设置已被其他操作修改，请重新应用。');
        const readiness = activationState(current);
        if (readiness === 'waiting' || current.active !== wanted || current.pending) return false;
        this.feedback = { kind: 'success', message: readiness === 'idle' ?
          '设置已应用，下次打开软件更新时生效。' : '设置已生效，软件更新保护已就绪。' };
        return true;
      };
      // The apply response already carries the same activation-bound proof as status.
      // Avoid an extra VM launch and an artificial 2-second delay when it is ready.
      if (confirmed(requested)) return;
      for (let attempt = 0; attempt < this.pollAttempts && this.current(operation); attempt++) {
        await this.wait([0, 250, 500, 1000, 1500][attempt] ?? 2000);
        if (!this.current(operation)) return;
        const current = await this.api.status();
        if (!this.current(operation)) return;
        this.status = current; this.emit();
        if (confirmed(current)) return;
      }
      if (this.current(operation)) throw new Error(this.status && this.status.error ? this.status.error : '本次激活尚未确认就绪。可返回页面查看状态，或稍后再次点击“立即应用”。');
    } catch (error) {
      if (!this.current(operation)) return;
      this.feedback = { kind: 'error', message: '本次应用尚未确认完成。', detail: String(error.message || error) };
    } finally { if (this.current(operation)) { this.busy = null; this.emit(); } }
  }
  dispose() { this.disposed = true; this.operation++; }
}
