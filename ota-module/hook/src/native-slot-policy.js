// No Java/ART dependency. All exceptional paths preserve the original native call.
export function createSlotDeferral({wire, warn, sourceSlot}) {
  const stacks = new Map();
  const pending = new Map(), threadPlans = new Map();
  let nextProof = 0;
  const top = thread => (stacks.get(thread) || []).at(-1);
  function warning(message, failure) { try { warn(message, failure); } catch (_) {} }
  return {
    enter(thread, code) {
      const stack = stacks.get(thread) || [];
      stack.push({eligible: code === 0, finished: false}); stacks.set(thread, stack);
    },
    leave(thread) { const stack = stacks.get(thread); if (stack) { stack.pop(); if (!stack.length) stacks.delete(thread); } },
    finish(thread, directParent, success) {
      const context = top(thread);
      if (context && directParent) context.finished = success === true;
    },
    defer(thread, directParent, slot) {
      const context = top(thread);
      if (!context || !context.eligible || !context.finished || !directParent ||
          ![0, 1].includes(slot) || slot === sourceSlot) return false;
      try {
        // NativeFunction can release the JS lock while the broker runs. Every
        // eligible call rebinds to the current cycle, then uses the root store's
        // idempotent DEFER transaction. An in-flight call is not a reason to
        // execute the original HAL method on another native thread.
        const hello = wire('HELLO', {});
        if (!hello || hello.ok !== true || hello.decision !== 'READY' ||
            typeof hello.cycle_id !== 'string' || !hello.cycle_id || hello.source_slot !== sourceSlot)
          throw new Error('Native OTA cycle/source is not armed');
        const plan = {cycle_id: hello.cycle_id, target_slot: slot, code: 0};
        const reply = wire('DEFER', plan);
        if (!reply || reply.ok !== true || reply.decision !== 'DEFER' || reply.durable !== true ||
            reply.cycle_id !== plan.cycle_id || reply.target_slot !== slot)
          throw new Error('Native deferred slot was not durably acknowledged');
        const old = pending.get(plan.cycle_id);
        const sameProof = context.deferred && old && old.local_id === context.deferred.local_id && old.target_slot === slot;
        const proof = sameProof ? old : {...plan, local_id: ++nextProof};
        context.deferred = proof; pending.set(plan.cycle_id, proof); threadPlans.set(thread, proof);
        return true;
      } catch (failure) { warning('Native slot deferral skipped; original SetActiveBootSlot retained', failure); return false; }
    },
    snapshot(thread) {
      let proof = thread === undefined ? [...pending.values()].at(-1) : (top(thread)?.deferred || threadPlans.get(thread));
      // Completion can move to another native thread. A single pending cycle
      // is unambiguous; multiple cycles require a thread-bound proof.
      if (!proof && thread !== undefined && pending.size === 1) proof = [...pending.values()][0];
      return proof ? {...proof} : null;
    },
    terminated(captured, code) {
      const current = captured && pending.get(captured.cycle_id);
      if (!captured || !Number.isSafeInteger(captured.local_id) || !current || current.target_slot !== captured.target_slot) return;
      // The actual Terminate method has returned; code 52 remains a failure for
      // this flow. A failed DONE notification leaves root's plan uncompleted.
      const event = code === 0 ? 'DONE' : 'FAIL';
      try {
        const reply = wire(event, {cycle_id: captured.cycle_id, target_slot: captured.target_slot, code});
        if (!reply || reply.ok !== true || reply.cycle_id !== captured.cycle_id)
          throw new Error('Native completion acknowledgment missing');
      } catch (failure) { warning('Native slot completion was not acknowledged', failure); }
      finally {
        // A cooperative RPC can observe a new DEFER before this returns. Retire
        // only this exact local proof, never a newer proof or another cycle.
        if (pending.get(captured.cycle_id)?.local_id === captured.local_id) pending.delete(captured.cycle_id);
        for (const [thread, proof] of threadPlans)
          if (proof.local_id === captured.local_id) threadPlans.delete(thread);
      }
    }
  };
}
