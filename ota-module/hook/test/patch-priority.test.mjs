import assert from 'node:assert/strict';
import {hasPureHotPatch, installPatchPriority} from '../src/patch-priority.js';

const list = values => ({size: () => values.length, get: i => values[i]});
const vendorMask = value => (value & 3) !== 0 && (value & 4) === 0;
const proof = values => hasPureHotPatch({components: list(values), count: x => x.size(),
  readType: x => x.type, readMask: x => x.mask, hotOnly: vendorMask, expectedType: 10});
const patch = {type: 10, mask: '3'};
assert.equal(proof([patch]), true);
assert.equal(proof([patch, {type: 10, mask: '1'}]), true);
for (const values of [[], [null], [{type: 9, mask: '3'}], [{type: 10, mask: '0'}],
  [{type: 10, mask: '7'}], [{type: 10, mask: '17'}], [{type: 10, mask: '1e0'}],
  [{type: 10, mask: '2147483648'}], [patch, {type: 10, mask: null}]]) assert.equal(proof(values), false);

function fixture({enabled = true, candidates = [patch], full = [{}], eligible = true} = {}) {
  const events = [];
  const shapes = {
    save: ['Normal', [], 'Base'], eligible: ['Base', ['boolean'], 'boolean'],
    components: ['Base', ['int'], 'java.util.List'], suppress: ['Base', ['int'], 'void'],
    package_type: ['Item', [], 'int'], extra_info: ['Item', [], 'Extra'],
    patch_mask: ['Extra', [], 'java.lang.String'], mask_test: ['Patch', ['int'], 'boolean'],
  };
  const methods = {};
  const specs = Object.fromEntries(Object.entries(shapes).map(([method_name, [class_name, parameter_types, return_type]]) =>
    [method_name, {method_name, class_name, parameter_types, return_type, is_static: method_name === 'mask_test'}]));
  const impl = {
    save: receiver => { events.push('native save'); methods.eligible.implementation.call(receiver, true); return receiver; },
    eligible: () => { events.push('native eligibility'); return eligible; },
    components: (receiver, group) => list(group === 2 ? candidates : full),
    suppress: (receiver, group) => { events.push('suppress ' + group); },
    package_type: receiver => receiver.type,
    extra_info: receiver => receiver,
    patch_mask: receiver => receiver.mask,
    mask_test: (receiver, value) => vendorMask(value),
  };
  for (const [name, body] of Object.entries(impl)) methods[name] = {call: body, implementation: null};
  const ready = installPatchPriority({enabled: () => enabled, mapping: {
    hotpatch_only: {patch_mask_test: specs.mask_test}, patch_priority: {...specs, patch_group: 2, full_group: 4, micro_patch_type: 10},
  }, methodCache: {resolve: spec => ({type: {}, method: methods[spec.method_name]})},
  Java: {cast: value => value, use: () => ({currentThread: () => ({getId: () => 1})})},
  warn: (message, error) => { throw new Error(message, {cause: error}); },
  telemetry: event => { events.push(event.event); }});
  const receiver = {$isSameObject: other => other === receiver};
  return {ready, events, inside: () => methods.save.implementation.call(receiver),
    outside: () => methods.eligible.implementation.call(receiver, true)};
}

const supported = fixture();
assert.equal(supported.ready, true);
assert.equal(supported.outside(), true);
assert.deepEqual(supported.events, ['native eligibility']);
supported.events.length = 0;
supported.inside();
assert.deepEqual(supported.events, ['native save', 'native eligibility', 'suppress 4', 'patch_package_preferred']);
for (const args of [{eligible: false}, {full: []}, {candidates: []}, {candidates: [{type: 10, mask: '7'}]},
  {candidates: [patch, {type: 10, mask: '17'}]}]) {
  const ordinary = fixture(args);
  ordinary.inside();
  assert.deepEqual(ordinary.events, ['native save', 'native eligibility']);
}
assert.equal(fixture({enabled: false}).ready, false);
console.log('Pure-hot priority preserves native eligibility, disabled mode and all full-package fallbacks');
