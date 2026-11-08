// Preserve OUC's request/signature verification and offer selection. Only the
// native normal-save path may prefer a separately proven hot patch to HOTA.
export function hasPureHotPatch({components, count, readType, readMask, hotOnly, expectedType}) {
  if (components == null) return false;
  const size = count(components);
  if (!Number.isInteger(size) || size <= 0) return false;
  for (let index = 0; index < size; index++) {
    const component = components.get(index);
    if (component == null || readType(component) !== expectedType) return false;
    const text = readMask(component);
    if (typeof text !== 'string' || !/^[0-9]+$/.test(text)) return false;
    const mask = Number(text);
    if (!Number.isSafeInteger(mask) || mask <= 0 || mask > 0x7fffffff || hotOnly(mask) !== true) return false;
    // The native predicate accepts a known hot bit in an otherwise unknown
    // mask. Every announced subtype must independently be a proved hot bit.
    for (let bits = mask; bits !== 0; bits &= bits - 1) {
      if (hotOnly(bits & -bits) !== true) return false;
    }
  }
  return true;
}

export function installPatchPriority({Java, mapping, methodCache, enabled, warn, telemetry}) {
  const proof = mapping.patch_priority, maskTest = mapping.hotpatch_only?.patch_mask_test;
  if (!enabled() || !proof?.save || !maskTest) return false;
  const bind = (name, params, result, isStatic = false) => {
    const spec = name === 'mask_test' ? maskTest : proof[name];
    if (!spec || spec.is_static !== isStatic || !Array.isArray(spec.parameter_types) ||
        spec.parameter_types.join(',') !== params.join(',') ||
        (result != null && spec.return_type !== result))
      throw new Error('Patch priority method descriptor is not proved: ' + name);
    return methodCache.resolve(spec, {validateBody: true});
  };
  let restoreEligibility = null;
  try {
    if (proof.patch_group !== 2 || proof.full_group !== 4 || proof.micro_patch_type !== 10 ||
        proof.save.return_type !== proof.eligible.class_name)
      throw new Error('Patch priority package groups do not match the native normal-mode proof');
    const save = bind('save', [], null).method, eligible = bind('eligible', ['boolean'], 'boolean').method;
    const components = bind('components', ['int'], 'java.util.List').method;
    const suppress = bind('suppress', ['int'], 'void').method;
    const readType = bind('package_type', [], 'int').method;
    const extraInfo = bind('extra_info', [], proof.patch_mask.class_name).method;
    const readMask = bind('patch_mask', [], 'java.lang.String').method;
    const mask = bind('mask_test', ['int'], 'boolean', true);
    const Thread = Java.use('java.lang.Thread');
    const List = Java.use('java.util.List');
    const active = new Map();
    const thread = () => String(Thread.currentThread().getId());
    const previousEligibility = eligible.implementation;
    if (previousEligibility != null || save.implementation != null) throw new Error('Patch priority entry is already hooked');
    if (typeof Java.deoptimizeMethods === 'function') {
      const handles = [proof.save, proof.eligible].map(spec => methodCache.resolve(spec, {requireHandle: true}).handle);
      Java.deoptimizeMethods(handles);
    } else if (typeof Java.deoptimizeMethod === 'function') {
      for (const spec of [proof.save, proof.eligible]) Java.deoptimizeMethod(methodCache.resolve(spec, {requireHandle: true}).handle);
    }
    eligible.implementation = function (...args) {
      const result = eligible.call(this, ...args);
      if (result !== true || !enabled()) return result;
      try {
        const scope = active.get(thread());
        if (!scope || scope.checked || !scope.receiver.$isSameObject(this)) return result;
        scope.checked = true;
        const patch = Java.cast(components.call(this, proof.patch_group), List);
        if (!hasPureHotPatch({components: patch, count: value => Number(value.size()),
          readType: item => Number(readType.call(item)),
          readMask: item => {
            const extra = extraInfo.call(item);
            if (extra == null) return null;
            const text = readMask.call(extra);
            return text == null ? null : String(text);
          },
          hotOnly: value => mask.method.call(mask.type, value), expectedType: proof.micro_patch_type})) return result;
        const full = Java.cast(components.call(this, proof.full_group), List);
        if (full == null || Number(full.size()) <= 0) return result;
        // The native setter marks only this HOTA result unavailable in this
        // check. Other groups, installed firmware and later checks stay native.
        suppress.call(this, proof.full_group);
        telemetry({event: 'patch_package_preferred', patch_count: Number(patch.size())});
      } catch (failure) {
        warn('Optional hot-patch priority could not be applied', failure);
      }
      return result;
    };
    restoreEligibility = () => { eligible.implementation = previousEligibility; };
    save.implementation = function (...args) {
      if (!enabled()) return save.call(this, ...args);
      let id;
      try { id = thread(); }
      catch (failure) {
        warn('Optional hot-patch priority scope could not be established', failure);
        return save.call(this, ...args);
      }
      const prior = active.get(id);
      active.set(id, {receiver: this, checked: false});
      try { return save.call(this, ...args); }
      finally { if (prior === undefined) active.delete(id); else active.set(id, prior); }
    };
    return true;
  } catch (failure) {
    if (restoreEligibility !== null) try { restoreEligibility(); } catch (_) {}
    warn('Optional hot-patch priority is unavailable; keeping native update selection', failure);
    return false;
  }
}
