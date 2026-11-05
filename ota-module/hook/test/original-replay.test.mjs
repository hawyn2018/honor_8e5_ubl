import assert from 'node:assert/strict';
import {createOriginalReplay} from '../src/original-replay.js';

let thread = 1, checks = 0;
const sameObject = (a, b) => a.javaId !== undefined && a.javaId === b.javaId;
const replay = createOriginalReplay({threadId: () => thread, sameObject});
const method = {}, receiver = {javaId: 7}, reason = {javaId: 8};
const expect = (value, message) => { checks++; assert.equal(value, true, message); };

expect(!replay.take(method, receiver, ['ota']), 'an ordinary invocation has no replay scope');
assert.equal(replay.run(method, receiver, ['ota', reason], () => {
  expect(!replay.take({}, receiver, ['ota', reason]), 'another method stays gated');
  expect(!replay.take(method, {javaId: 9}, ['ota', reason]), 'another receiver stays gated');
  expect(!replay.take(method, receiver, ['other', reason]), 'another reason stays gated');
  expect(!replay.take(method, receiver, ['ota']), 'another overload shape stays gated');
  thread = 2;
  expect(!replay.take(method, receiver, ['ota', reason]), 'another thread stays gated');
  thread = 1;
  expect(replay.take(method, {javaId: 7}, ['ota', {javaId: 8}]), 'retained Java wrappers match the same actual objects');
  expect(!replay.take(method, receiver, ['ota', reason]), 'the exact synchronous self-entry is one shot');
  return 42;
}), 42);
expect(!replay.take(method, receiver, ['ota', reason]), 'the returning scope leaves no permission for a later click');

const failure = new Error('native failure');
assert.throws(() => replay.run(method, receiver, ['ota'], () => { throw failure; }), error => error === failure);
expect(!replay.take(method, receiver, ['ota']), 'an exceptional original call also clears its scope');
replay.run(method, receiver, ['outer'], () => {
  replay.run(method, receiver, ['inner'], () => {
    expect(!replay.take(method, receiver, ['outer']), 'nested calls see only the current exact bridge operation');
    expect(replay.take(method, receiver, ['inner']), 'nested replay remains independent');
  });
  expect(replay.take(method, receiver, ['outer']), 'nested cleanup restores the enclosing synchronous call');
});
expect(!replay.take(method, receiver, ['outer']), 'nested cleanup leaves no process permission');

// Java object comparison failure follows the native call's normal error path;
// it does not leave a live scope or produce a second broker transaction.
const broken = createOriginalReplay({threadId: () => 1, sameObject() { throw failure; }});
assert.throws(() => broken.run(method, receiver, [], () => broken.take(method, {javaId: 7}, [])), error => error === failure);
expect(!broken.take(method, receiver, []), 'identity comparison failure also closes the scope');
console.log(`Original synchronous replay: ${checks} boundary checks passed; no marker or slot API`);
