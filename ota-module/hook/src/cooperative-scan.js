// Work is bounded between iterator steps; native bridge calls remain indivisible.
// Slices run on the caller's UI scheduler, while state reads use their own worker.
export function startCooperativeScan(p) {
  const maxSteps = p.maxSteps || 2048, budgetMs = p.budgetMs || 4;
  let timer = null, ended = false;
  const close = () => { try { if (typeof p.iterator.return === 'function') p.iterator.return(); } catch (_) {} };
  const cancel = () => { if (ended) return; ended = true; if (timer != null) p.cancel(timer); timer = null; close(); };
  function run() {
    timer = null;
    if (ended) return;
    if (!p.current()) { cancel(); return; }
    try {
      if (p.beforeSlice && !p.beforeSlice()) { cancel(); p.deferred(); return; }
      const started = p.now(), values = [];
      let done = false, steps = 0;
      do {
        const value = p.iterator.next(); steps++;
        if (value.done) { done = true; break; }
        if (value.value != null) values.push(value.value);
      } while (steps < maxSteps && p.now() - started < budgetMs);
      p.chunk(values, steps);
      if (ended || !p.current()) { cancel(); return; }
      if (done) { ended = true; close(); p.done(); }
      else timer = p.schedule(run, 16);
    } catch (error) { cancel(); p.error(error); }
  }
  // Return cancellation before the first slice; chunk callbacks can invalidate
  // their own screen or synchronously trigger another discovery request.
  return {run, cancel};
}
