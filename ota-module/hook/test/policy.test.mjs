import assert from 'node:assert/strict';
import {isAutomatic, isManual, consumeAllows, requiredReady, isCriticalRole, readiness} from '../src/policy.js';

assert.equal(isAutomatic('auto'), true);
assert.equal(isAutomatic('night'), true);
assert.equal(isAutomatic('manual'), false);
assert.equal(isAutomatic('manul'), false);
assert.equal(isAutomatic('future_autoreason', ['future_autoreason']), true);
assert.equal(isAutomatic('autoNoWait'), true);
assert.equal(isManual('manul'), true);
assert.equal(isManual('manual'), true);
assert.equal(isManual('night'), false);
assert.equal(isManual('unclassified'), false);
for (const response of [null, {}, {ok: false, state:0, decision: 'ALLOW'}, {ok: true, state:0, decision: 'DENY'}, {ok: 1, state:0, decision: 'ALLOW'},
  {ok:true,decision:'ALLOW'},{ok:true,state:1,decision:'ALLOW'},{ok:true,state:'0',decision:'ALLOW'}]) {
  assert.equal(consumeAllows(response), false);
}
assert.equal(consumeAllows({ok: true, state:0, decision: 'ALLOW'}), true);
assert.equal(requiredReady({full_package: 1, disable_auto: 1, countdown: 1, reboot_gate: 1}), false);
assert.equal(requiredReady({countdown: 1, reboot_gate: 1, payload_start: 1}), false);
const reasons = {manual_reboot_gate:1, automatic_reboot_gate:1,reboot_terminal:1,reboot_continuation:1};
assert.equal(requiredReady({...reasons,countdown: 1, reboot_gate: 1, payload_start: 1}), true);
assert.equal(requiredReady({...reasons,full_package: 1, disable_auto: 1, countdown: 1, reboot_gate: 1, payload_start: 1, version_response: 1}), true);
assert.equal(requiredReady({full_package: 1, disable_auto: 1, countdown_timer: 1, reboot_gate: 1, payload_complete: 1, version_response: 1}), false);
assert.equal(requiredReady({...reasons,countdown_timer: 1, reboot_gate: 1, payload_start: 1}), true);
assert.equal(requiredReady({manual_reboot_gate:1,countdown_timer:1,reboot_gate:1,payload_start:1}),false);
assert.equal(requiredReady({automatic_reboot_gate:1,countdown_timer:1,reboot_gate:1,payload_start:1}),false);
for (const role of ['reboot_gate', 'reboot_terminal', 'countdown', 'countdown_timer', 'payload_start']) assert.equal(isCriticalRole(role), true);
for (const role of ['version_response', 'disable_auto', 'full_package', 'payload_complete', 'auto_download']) assert.equal(isCriticalRole(role), false);
assert.deepEqual(readiness(true, false), {critical_ready:true,broker_ready:false,hook_ready:false});
assert.deepEqual(readiness(false, true), {critical_ready:false,broker_ready:true,hook_ready:false});
assert.deepEqual(readiness(true, true), {critical_ready:true,broker_ready:true,hook_ready:true});
console.log('Policy checks passed');
