import assert from 'node:assert/strict';
import {parseFirmwareVersion, compareFirmwareVersions, filterVersionResponse, replaceRootStatus} from '../src/version-policy.js';

const device = 'BKQ-AN20 11.0.0.108(SP21C00E108R101P4)';
const states = {available: [0, 4], unavailable: 1};
const compare = (server, expected, current = device) => assert.equal(compareFirmwareVersions(server, current).decision, expected, server);
compare('BRT-AN00 11.0.0.109(SP1C01)', 'update');
compare('BRT-AN00 11.0.0.99(SP999C999E999R999P999)', 'latest');
compare('11.0.0.108(SP21C00E108R101P4)', 'latest');
compare('11.0.0.108（SP21C00E108R101P4）', 'latest');
for (const qualifier of ['SP22C00E108R101P4', 'SP21C01E108R101P4', 'SP21C00E109R101P4',
  'SP21C00E108R102P4', 'SP21C00E108R101P5', 'SP21C00E108R101P4 ']) compare('11.0.0.108(' + qualifier + ')', 'update');
compare('11.0.0.108', 'update');
compare('12.0.0.1', 'update');
compare('9.9.999.99999', 'latest');
compare('11.0.0.90071992547409931234567891', 'update', '11.0.0.90071992547409931234567890');
compare('011.00.0.0108.0(SP21C00E108R101P4)', 'latest');
compare('11.0.0.108', 'latest', '11.0.0.108');
for (const value of [null, '', 'unknown', 'latest', '11.0.0.108(beta', '11.0.0.108D(SP21)',
  '11.0.0.107 to 11.0.0.108']) assert.equal(parseFirmwareVersion(value), null, String(value));

const bl = (versions, status = 0) => JSON.stringify({status, result: {versionPackageTypes: [2, 3, 4],
  blVersionCheckResults: versions.map(version => ({status: 0, blVersionInfo: {targetBlVersionNumber: version}}))}});
const legacy = version => JSON.stringify({status: '0', result: {versionPackageTypes: [2],
  versionPackageCheckResults: [{versionPackageType: 2, status: '0', components: [{name: version, version: 'base-only'}]}]}});
for (const raw of [bl([device]), bl(['11.0.0.99(C999)']), legacy(device), legacy('11.0.0.100(C99)')]) {
  const filtered = filterVersionResponse(raw, device, false, states);
  assert.equal(filtered.decision, 'latest');
  assert.equal(Number(JSON.parse(filtered.raw).status), states.unavailable);
  assert.deepEqual(JSON.parse(filtered.raw).result, JSON.parse(raw).result);
  assert.equal(filterVersionResponse(raw, device, true, states).raw, raw);
}
for (const raw of [bl(['11.0.0.109(C0)']), bl(['11.0.0.108(SP22C00E108R101P4)']),
  bl([device, '11.0.0.109(C0)']), bl(['cota-description']), '{bad json', JSON.stringify({status: 0, result: {newSchema: 1}})]) {
  assert.equal(filterVersionResponse(raw, device, false, states).raw, raw);
}
const withCota = JSON.parse(bl([device]));
withCota.result.blVersionCheckResults.push({status: 0, blVersionInfo: {targetBlVersionNumber: 'COTA_20261102'}});
assert.equal(filterVersionResponse(JSON.stringify(withCota), device, false, states).decision, 'unknown');
const withLightUpdate = JSON.parse(bl([device]));
withLightUpdate.result.lightUpgradeComponent = {status: 0};
assert.equal(filterVersionResponse(JSON.stringify(withLightUpdate), device, false, states).reason, 'additional_update_present');
assert.equal(filterVersionResponse(bl([device]), 'unknown', false, states).decision, 'unknown');
assert.equal(filterVersionResponse(bl([device], 1), device, false, states).decision, 'unchanged');

// Keep every byte except root status: long IDs in real BL JSON are 64-bit and
// would be corrupted by a JSON.parse/JSON.stringify transform in JavaScript.
const exact = '{"result":{"status":0,"id":9007199254740993123,"text":"a \\\"status\\\":0"}, "st\\u0061tus" : "0"}';
assert.equal(replaceRootStatus(exact, 1), exact.replace(': "0"}', ': "1"}'));
assert.equal(replaceRootStatus('{"result":{"status":0}}', 1), null);
const huge = bl([device]).replace('"blVersionInfo":', '"id":9007199254740993123,"blVersionInfo":');
assert.ok(filterVersionResponse(huge, device, false, states).raw.includes('9007199254740993123'));

// An empty BL placeholder does not hide a populated legacy response. Preserve
// the original payload byte-for-byte except for the native unavailable status.
const legacyWithEmptyBl = JSON.parse(legacy(device));
legacyWithEmptyBl.result.blVersionCheckResults = [];
assert.equal(filterVersionResponse(JSON.stringify(legacyWithEmptyBl), device, false, states).decision, 'latest');
legacyWithEmptyBl.result.versionPackageCheckResults[0].components[0].name = '11.0.0.109(C0)';
assert.equal(filterVersionResponse(JSON.stringify(legacyWithEmptyBl), device, false, states).decision, 'update');
assert.equal(filterVersionResponse(bl([]), device, false, states).decision, 'unknown');
for (const name of ['additionComponents', 'lightUpgradeComponent', 'appList']) {
  for (const value of [{status: 1, description: 'native unavailable'}, [{status: '1'}], {}, []]) {
    const raw = JSON.parse(bl([device])); raw.result[name] = value;
    const filtered = filterVersionResponse(JSON.stringify(raw), device, false, states);
    assert.equal(filtered.decision, 'latest', name + JSON.stringify(value));
    assert.deepEqual(JSON.parse(filtered.raw).result, raw.result);
  }
  for (const value of [{status: 0}, {status: 99}, {status: ''}, {description: 'unknown'},
    [{status: 1}, {status: 0}], [{status: 1}, {}], ['unknown']]) {
    const raw = JSON.parse(bl([device])); raw.result[name] = value;
    const serialized = JSON.stringify(raw), filtered = filterVersionResponse(serialized, device, false, states);
    assert.equal(filtered.reason, 'additional_update_present', name + JSON.stringify(value));
    assert.equal(filtered.raw, serialized);
  }
}
// A component missing the full qualifier must not be treated as the complete
// equal whole-device version merely because its numeric part is equal.
const missingQualifier = JSON.parse(legacy(device));
missingQualifier.result.versionPackageCheckResults[0].components[0].name = '11.0.0.108';
assert.equal(filterVersionResponse(JSON.stringify(missingQualifier), device, false, states).decision, 'update');

// The discovered original entry branches on top-level protocol status BEFORE
// parsing package/component lists. Its no-update callback is consumed by the
// real CheckNewVersion classifier, which selects showNoNewVersion/status 4.
// These fixtures ensure we select that branch, rather than manufacture an empty
// component list that the original client would classify as an error.
function nativeCompletion(raw, sink) {
  const response = JSON.parse(raw);
  if (![0, 4].includes(Number(response.status))) return sink.originalNoUpdateCallback({hasNewVersion: false});
  return sink.originalAvailableCallback(response.result);
}
for (const raw of [bl([device]), legacy(device)]) {
  let called = null;
  nativeCompletion(filterVersionResponse(raw, device, false, states).raw, {
    originalNoUpdateCallback(result) { called = result.hasNewVersion ? 'new' : 'native_latest'; },
    originalAvailableCallback() { called = 'available'; }
  });
  assert.equal(called, 'native_latest');
}
console.log('Version comparison, BL/legacy response, exact JSON and native no-update branch checks passed');
await import('./cache-version-policy.test.mjs');
