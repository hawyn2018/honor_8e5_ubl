/**
 * A synchronous bridge call, never a stored OTA permission. Outside an active
 * ART replacement frame, a saved overload.call can dispatch to its hook again.
 * Match that one immediate self-entry so its in-hook call reaches the original.
 */
export function createOriginalReplay({threadId, sameObject = (a, b) => a === b}) {
  const scopes = new Map();
  const same = (a, b) => a === b || (a !== null && b !== null &&
    typeof a === 'object' && typeof b === 'object' && sameObject(a, b));
  function run(method, receiver, args, body) {
    const thread = String(threadId()), previous = scopes.get(thread);
    const scope = {method, receiver, args: args.slice(), used: false};
    scopes.set(thread, scope);
    try { return body(); }
    finally {
      if (previous === undefined) scopes.delete(thread);
      else scopes.set(thread, previous);
    }
  }
  function take(method, receiver, args) {
    const scope = scopes.get(String(threadId()));
    if (!scope || scope.used || scope.method !== method || !same(scope.receiver, receiver) ||
        scope.args.length !== args.length || !scope.args.every((value, i) => same(value, args[i]))) return false;
    scope.used = true;
    return true;
  }
  return {run, take};
}
