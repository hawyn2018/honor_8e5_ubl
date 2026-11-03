/** Process-local origin only. This object never reads or stores a Root grant. */
export function createRebootContext({threadId, remember, recall, warn = () => {}}) {
  const scopes = new Map();
  const valid = value => value === 'manual' || value === 'automatic';
  function current() { return scopes.get(String(threadId())) || 'unknown'; }
  function enter(kind, body) {
    const id = String(threadId()), prior = scopes.get(id), inherited = prior || 'unknown';
    const next = inherited === 'automatic' || kind === 'automatic' ? 'automatic' : kind === 'manual' ? 'manual' : inherited;
    if (valid(next)) scopes.set(id, next); else scopes.delete(id);
    try { return body(); } finally { if (prior === undefined) scopes.delete(id); else scopes.set(id, prior); }
  }
  function capture(receiver) {
    const kind = current(); if (!valid(kind)) return;
    try { remember(receiver, kind); } catch (error) { warn('Recording the original reboot continuation failed', error); }
  }
  function resume(receiver, body) {
    let kind = 'unknown';
    try { const saved = recall(receiver); if (valid(saved)) kind = saved; }
    catch (error) { warn('Reading the original reboot continuation failed', error); }
    return enter(kind, body);
  }
  return {current, enter, capture, resume};
}
