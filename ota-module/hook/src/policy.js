/** Shared, side-effect-free decisions; permission is consumed only at the final process-local reboot gate. */
export function isAutomatic(mode, discovered = []) {
  const text = String(mode == null ? '' : mode).trim().toLowerCase();
  return discovered.some(value => String(value).toLowerCase() === text) ||
    /^(auto|automatic|night|scheduled?|countdown)/.test(text);
}

export function isManual(mode, discovered = []) {
  const text = String(mode == null ? '' : mode).trim().toLowerCase();
  return discovered.some(value => String(value).toLowerCase() === text) ||
    ['manual', 'manul', 'manually'].includes(text);
}

export function consumeAllows(reply) {
  return reply !== null && typeof reply === 'object' && reply.ok === true &&
    reply.state === 0 && reply.decision === 'ALLOW';
}

export function requiredReady(counts) {
  return (counts.reboot_gate || 0) > 0 && (counts.reboot_terminal || 0) > 0 && (counts.reboot_continuation || 0) > 0 && (counts.manual_reboot_gate || 0) > 0 &&
    (counts.automatic_reboot_gate || 0) > 0 &&
    ((counts.countdown || 0) + (counts.countdown_timer || 0) > 0) &&
    // Completion notifications can be replayed after reopening the UI. At least
    // one real payload-start boundary is needed to discard an old permission.
    (counts.payload_start || 0) > 0;
}

export function isCriticalRole(role) {
  return ['reboot_gate', 'reboot_terminal', 'countdown', 'countdown_timer', 'payload_start'].includes(role);
}

export function readiness(critical, broker) {
  return {critical_ready: critical === true, broker_ready: broker === true,
    hook_ready: critical === true && broker === true};
}
