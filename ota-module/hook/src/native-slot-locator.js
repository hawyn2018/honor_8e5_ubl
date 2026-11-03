// Function signatures, not specimen RVAs, identify the native ABI. Gum 17.25
// resolves .gnu_debugdata via its offline/XZ fallback when the main ELF is stripped.
export const SLOT_SYMBOLS = Object.freeze({
  complete: '_ZN22chromeos_update_engine23PostinstallRunnerAction19CompletePostinstallENS_9ErrorCodeE',
  finish: '_ZN22chromeos_update_engine30DynamicPartitionControlAndroid12FinishUpdateEb',
  setActive: '_ZN22chromeos_update_engine18BootControlAndroid17SetActiveBootSlotEj',
  terminate: '_ZN22chromeos_update_engine22UpdateAttempterAndroid24TerminateUpdateAndNotifyENS_9ErrorCodeE'
});

const numeric = value => BigInt(String(value));
const semantic = name => String(name).replace(/\.(?:cfi|cfi_jt)$/, '');
export function inside(address, fact) {
  const value = numeric(address), start = numeric(fact.address);
  return value >= start && value < start + BigInt(fact.size);
}

export function locateNativeSlots(module, parseInstruction, executable) {
  const symbols = module.enumerateSymbols(), result = {};
  for (const [role, exact] of Object.entries(SLOT_SYMBOLS)) {
    const matches = symbols.filter(s => semantic(s.name) === exact && s.type === 'function');
    if (!matches.length) throw new Error('Native symbol unavailable: ' + exact);
    const candidates = new Map();
    for (const symbol of matches) {
      if (!Number.isSafeInteger(symbol.size) || symbol.size <= 0 || symbol.size % 4 !== 0 ||
          numeric(symbol.address) % 4n !== 0n || !inside(symbol.address, {address: module.base, size: module.size}) ||
          !executable(symbol.address) || !executable(symbol.address.add(symbol.size - 1)))
        continue; // zero-sized aliases do not establish a function body/range
      const key = String(symbol.address), previous = candidates.get(key);
      if (previous && previous.size !== symbol.size) throw new Error('Native symbol has conflicting body sizes: ' + exact);
      candidates.set(key, {name: exact, address: symbol.address, size: symbol.size});
    }
    if (!candidates.size) throw new Error('Native symbol has no proven executable body: ' + exact);
    const resolved = new Map();
    for (const candidate of candidates.values()) {
      let body = candidate;
      const seen = new Set();
      while (true) {
        const key = String(body.address);
        if (seen.has(key)) throw new Error('Native CFI thunk cycle: ' + exact);
        seen.add(key);
        // Only an entire symbol consisting of entry hints and a final direct B
        // is a thunk. A branch anywhere inside a real body is not a thunk.
        let cursor = body.address, branch = null, thunk = true;
        while (inside(cursor, body)) {
          const instruction = parseInstruction(cursor);
          if (!instruction || instruction.size !== 4) throw new Error('Native ARM64 decode failed: ' + exact);
          const last = numeric(cursor) + 4n === numeric(body.address) + BigInt(body.size);
          if (instruction.mnemonic === 'b' && last) {
            const target = String(instruction.opStr).match(/^#?(0x[0-9a-f]+)$/i);
            if (target) branch = target[1]; else thunk = false;
          } else if (!['bti', 'nop'].includes(instruction.mnemonic)) { thunk = false; break; }
          cursor = cursor.add(4);
        }
        if (!thunk || branch === null) break;
        const next = [...candidates.values()].find(c => numeric(c.address) === numeric(branch));
        if (!next) throw new Error('Native thunk target lacks the same proven ABI/size: ' + exact);
        body = next;
      }
      resolved.set(String(body.address), body);
    }
    if (resolved.size !== 1) throw new Error('Ambiguous native function bodies: ' + exact);
    result[role] = [...resolved.values()][0];
  }
  if (new Set(Object.values(result).map(f => String(f.address))).size !== Object.keys(result).length)
    throw new Error('Distinct native functions overlap at one entry');
  return result;
}
