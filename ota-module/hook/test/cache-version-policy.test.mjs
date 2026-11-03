import assert from 'node:assert/strict';
import {cacheVersionDecision} from '../src/version-policy.js';

const current = 'APH-AN00 10.0.0.136(C00E130R201P5)';
const older = 'APH-AN00 10.0.0.112(C00E130R201P3)';
const newer = 'APH-AN00 10.0.0.137(C00E130R201P5)';
const metadata = target => ({renamed: {nested: {targetBlVersionNumber: target,
  currentBlVersionNumber: older}}, packageDetails: {versionNumber: 'APH-LGRP1-CHN 10.0.0.136'}});
const row = (id = '40', target = current) => ({id, state: 0, progress: 0, metadata: metadata(target)});
let assertions = 0;
function check(rows, decision, reason, options = {}) {
  const before = structuredClone(rows);
  const actual = cacheVersionDecision(rows, options.current ?? current, options.force ?? false, options.pendingStates);
  assert.equal(actual.decision, decision, reason);
  if (reason) assert.equal(actual.reason, reason);
  assert.deepEqual(actual.hiddenIds, decision === 'latest' ? rows.map(entry => entry.id) : []);
  assert.deepEqual(rows, before, 'input rows must stay unchanged');
  assertions++;
  return actual;
}

check([row()], 'latest', 'all_cached_targets_same_or_older');
check([row('40'), row('41'), row('42')], 'latest');
check([row('40', older)], 'latest');
check([row('40', newer)], 'preserve', 'cache_target_newer_changed_or_unknown');
check([row('40'), row('41', newer)], 'preserve');
check([row('40', 'APH-AN00 10.0.0.136(C00E131R201P5)')], 'preserve');
check([row('40', '10.0.0.136')], 'preserve');
check([row('40', 'unrecognized')], 'preserve', 'unproven_cache_target');
check([row('40', 'APH-AN00 10.0.0.135(C99E999R999P999)')], 'latest');
check([row()], 'forced', 'force_full_package', {force: true});
check([row('40', newer)], 'forced', 'force_full_package', {force: true});
check([row()], 'preserve', 'unparsed_current_version', {current: 'unknown'});
check([], 'preserve', 'no_cache_rows');
check(null, 'preserve', 'no_cache_rows');
for (const state of [-1, 1, 2, 3, 4, 5, 6, 10, 99, '0', null, undefined, NaN])
  check([{...row(), state}], 'preserve', 'cache_activity_or_unknown_state');
for (const progress of [1, 50, 100, -1, '0', null, undefined, NaN])
  check([{...row(), progress}], 'preserve', 'cache_activity_or_unknown_state');
check([{...row(), state: 2}], 'latest', null, {pendingStates: [0, 2]});
for (const pendingStates of [[], [null], ['0'], null, 0])
  check([row()], 'preserve', 'unproven_pending_states', {pendingStates});
for (const id of ['', '1 OR 1=1', '1);DELETE FROM firmwares;--', '1,2', '-1', '+1', '1.0', ' 1', '1 ', '1\n', '\n1', '１', 40, null])
  check([{...row(), id}], 'preserve', 'unreadable_cache_row');
check([row('9223372036854775807')], 'latest');
check([row('40'), row('40')], 'preserve', 'unreadable_cache_row');
for (const value of [null, 'null', '{}', '{bad', '[]', [], 1, true,
  {targetBlVersionNumber: null}, {targetBlVersionNumber: 'base-only'},
  {versionNumber: current}, {name: current}, {targetVersion: current}])
  check([{...row(), metadata: value}], 'preserve');
check([{...row(), metadata: JSON.stringify(metadata(current))}], 'latest');
check([{...row(), metadata: {arbitrary: [{deeper: {targetBlVersionNumber: current}}]}}], 'latest');
check([{...row(), metadata: {targetBlVersionNumber: current, other: {targetBlVersionNumber: newer}}}], 'preserve');
check([{...row(), metadata: {targetBlVersionNumber: current, other: {targetBlVersionNumber: 'unknown'}}}], 'preserve');
for (const key of ['components', 'additionComponents', 'lightUpgradeComponent', 'appList']) {
  check([{...row(), metadata: {...metadata(current), [key]: [{targetBlVersionNumber: current}]}}], 'latest');
  check([{...row(), metadata: {...metadata(current), [key]: []}}], 'latest');
  check([{...row(), metadata: {...metadata(current), [key]: {}}}], 'latest');
  for (const component of [{version: current}, {versionNumber: older}, {name: current}, {status: 0}, {status: 1}, 'unknown'])
    check([{...row(), metadata: {...metadata(current), [key]: [component]}}], 'preserve', 'unproven_cache_target');
  check([{...row(), metadata: {...metadata(current), [key]: 'malformed'}}], 'preserve');
}
// A separate COTA/app row without its own BL target keeps the whole group.
check([row(), {id: '43', state: 0, progress: 0, metadata: {versionNumber: 'COTA_20261103'}}], 'preserve');
check([row(), {...row('41'), progress: 5}, row('42', newer)], 'preserve');

// Source-shaped real cache metadata: the old current-version field must not
// block hiding an already-installed target; local subpackage versions are not
// compared to the whole-device display version, nor are obfuscated keys used.
const sourceShaped = [
  ['40', 'APH-LGRP1-CHN 10.0.0.136'], ['41', 'APH-AN00-CUST 10.0.0.130(C00)'],
  ['42', 'APH-AN00-PRELOAD 10.0.0.5(C00R201)']
].map(([id, versionNumber]) => ({id, state: 0, progress: 0, metadata: JSON.stringify({
  anyFutureName: {blVersionType: 1, currentBlVersionNumber: older, targetBlVersionNumber: current},
  independentProtocolDetails: {versionNumber}, unusedPayload: {versionNumber: '255467860'}
})}));
check(sourceShaped, 'latest');
const cycle = metadata(current); cycle.loop = cycle;
assert.equal(cacheVersionDecision([{...row(), metadata: cycle}], current, false).decision, 'preserve'); assertions++;
const throwing = Object.defineProperty(metadata(current), 'unreadable', {enumerable: true, get() {throw new Error('unreadable metadata');}});
assert.equal(cacheVersionDecision([{...row(), metadata: throwing}], current, false).reason, 'unreadable_cache_metadata'); assertions++;
let deep = {targetBlVersionNumber: current};
for (let depth = 0; depth < 3000; depth++) deep = {components: [deep]};
assert.equal(cacheVersionDecision([{...row(), metadata: deep}], current, false).decision, 'latest'); assertions++;
const nestedMissing = {targetBlVersionNumber: current, components: [{nested: {versionNumber: current}}]};
assert.equal(cacheVersionDecision([{...row(), metadata: nestedMissing}], current, false).decision, 'preserve'); assertions++;
console.log(`Cache version policy passed ${assertions} independent scenarios`);
