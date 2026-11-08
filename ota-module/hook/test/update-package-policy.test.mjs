import assert from 'node:assert/strict';
import {createUpdatePackagePolicy} from '../src/update-package-policy.js';

let enabled = false, proof = true, probes = 0, warnings = 0;
const policy = createUpdatePackagePolicy({
  enabled: () => enabled,
  readHotpatchOnly: () => {
    probes++;
    if (proof instanceof Error) throw proof;
    return proof;
  },
  warn: () => { warnings++; },
});
assert.equal(policy.isHotpatchOnly(), false);
assert.equal(probes, 0, 'off retains legacy flow without probing');
enabled = true;
assert.equal(policy.isHotpatchOnly(), true);
for (const other of [false, null, 1, 'true']) {
  proof = other;
  assert.equal(policy.isHotpatchOnly(), false);
}
proof = new Error('unknown updater');
assert.equal(policy.isHotpatchOnly(), false);
assert.equal(warnings, 1);
proof = true;
policy.beginProtectedPayload();
assert.equal(policy.isHotpatchOnly(), false, 'a firmware payload always keeps the legacy gate');

const inFlight = createUpdatePackagePolicy({
  enabled: () => true,
  readHotpatchOnly: () => {
    assert.equal(inFlight.isHotpatchOnly(), false, 'nested vendor reads cannot reenter the proof');
    inFlight.beginProtectedPayload();
    return true;
  },
  warn: () => assert.fail('unexpected proof error'),
});
assert.equal(inFlight.isHotpatchOnly(), false, 'a payload starting mid-probe invalidates a positive answer');
console.log('Update package policy OK');
