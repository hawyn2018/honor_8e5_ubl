/** Version comparison and response decisions, independent of ART and the UI. */
export function parseFirmwareVersion(value) {
  if (typeof value !== 'string') return null;
  const text = value.trim().replace(/（/g, '(').replace(/）/g, ')');
  const match = /(?:^|[^\d.])(\d+(?:\.\d+)+)\s*(?:\(([^()]*)\))?$/.exec(text);
  if (match === null) return null;
  // Multiple dotted versions are ambiguous (for example a from/to range).
  const prefix = text.slice(0, match.index);
  if (/\d+\.\d+/.test(prefix)) return null;
  return {parts: match[1].split('.').map(part => part.replace(/^0+(?=\d)/, '')),
    qualifier: match[2] === undefined ? null : match[2]};
}

function integerCompare(a, b) {
  return a.length === b.length ? (a === b ? 0 : a < b ? -1 : 1) : a.length < b.length ? -1 : 1;
}

export function compareFirmwareVersions(server, device) {
  const a = parseFirmwareVersion(server), b = parseFirmwareVersion(device);
  if (a === null || b === null) return {decision: 'unknown', reason: 'unparsed_version'};
  for (let i = 0; i < Math.max(a.parts.length, b.parts.length); i++) {
    const order = integerCompare(a.parts[i] || '0', b.parts[i] || '0');
    if (order !== 0) return {decision: order > 0 ? 'update' : 'latest',
      reason: order > 0 ? 'numeric_newer' : 'numeric_older'};
  }
  return {decision: a.qualifier === b.qualifier ? 'latest' : 'update',
    reason: a.qualifier === b.qualifier ? 'same_version' : 'qualifier_changed'};
}

function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function status(value) {
  return (typeof value === 'number' || typeof value === 'string') && String(value).trim() !== '' ? Number(value) : NaN;
}

function hasUnclassifiedAdditionalUpdate(value, states) {
  const entries = Array.isArray(value) ? value : object(value) && Object.keys(value).length > 0 ? [value] : [];
  // A populated protocol placeholder is not an update when the native status
  // explicitly says unavailable. Unknown or available entries remain native.
  return entries.some(entry => !object(entry) || status(entry.status) !== states.unavailable);
}

/**
 * Select only protocol version fields, never unrelated URLs, descriptions or IDs.
 * An unclassified update keeps the original response. This also preserves COTA,
 * light updates and future response extensions instead of hiding a real update.
 */
export function responseVersionDecision(response, deviceVersion, states) {
  if (!object(response) || !object(response.result)) return {decision: 'unknown', reason: 'unrecognized_response'};
  if (!states.available.includes(status(response.status))) return {decision: 'unchanged', reason: 'native_not_available'};
  const result = response.result;
  for (const name of ['additionComponents', 'lightUpgradeComponent', 'appList']) {
    if (hasUnclassifiedAdditionalUpdate(result[name], states))
      return {decision: 'unknown', reason: 'additional_update_present'};
  }
  let groups, readVersions;
  if (Array.isArray(result.blVersionCheckResults) && result.blVersionCheckResults.length > 0) {
    groups = result.blVersionCheckResults;
    readVersions = group => object(group.blVersionInfo) ? [group.blVersionInfo.targetBlVersionNumber] : [];
  } else if (Array.isArray(result.versionPackageCheckResults)) {
    groups = result.versionPackageCheckResults;
    readVersions = group => Array.isArray(group.components) ? group.components.map(component => {
      if (!object(component)) return null;
      // display name is the original updater's complete visible firmware string;
      // the protocol version is a fallback for older clients that omit the name.
      return parseFirmwareVersion(component.name) !== null ? component.name : component.version;
    }) : [];
  } else return {decision: 'unknown', reason: 'unrecognized_version_schema'};
  let compared = 0;
  for (const group of groups) {
    if (!object(group) || !Number.isFinite(status(group.status))) return {decision: 'unknown', reason: 'unclassified_group'};
    if (!states.available.includes(status(group.status))) continue;
    const versions = readVersions(group);
    if (versions.length === 0) return {decision: 'unknown', reason: 'missing_target_version'};
    for (const version of versions) {
      const result = compareFirmwareVersions(version, deviceVersion);
      if (result.decision !== 'latest') return {...result, serverVersion: version};
      compared++;
    }
  }
  return compared > 0 ? {decision: 'latest', reason: 'all_available_versions_same_or_older', compared} :
    {decision: 'unknown', reason: 'no_available_version_to_compare'};
}

/**
 * Classify a read-only cache snapshot. A row's base/cust/preload display fields
 * are not whole-device targets: only the explicit BL target field proves that
 * relationship. Never partially hide a group or modify its stored records.
 */
export function cacheVersionDecision(rows, currentVersion, forceFullPackage, pendingStates = [0]) {
  const keep = reason => ({decision: 'preserve', reason, hiddenIds: []});
  if (forceFullPackage === true) return {decision: 'forced', reason: 'force_full_package', hiddenIds: []};
  if (!Array.isArray(rows) || rows.length === 0) return keep('no_cache_rows');
  if (parseFirmwareVersion(currentVersion) === null) return keep('unparsed_current_version');
  if (!Array.isArray(pendingStates) || pendingStates.length === 0 || !pendingStates.every(Number.isInteger))
    return keep('unproven_pending_states');
  const ids = new Set();
  try {
    for (const row of rows) {
      if (!object(row) || typeof row.id !== 'string' || row.id.length === 0 || /[^0-9]/.test(row.id) || ids.has(row.id))
        return keep('unreadable_cache_row');
      if (!Number.isInteger(row.state) || !pendingStates.includes(row.state) || row.progress !== 0)
        return keep('cache_activity_or_unknown_state');
      let metadata = row.metadata;
      if (typeof metadata === 'string') {
        try { metadata = JSON.parse(metadata); } catch (_) { return keep('invalid_cache_metadata'); }
      }
      if (!object(metadata)) return keep('invalid_cache_metadata');
      const targets = cacheTargets(metadata);
      if (targets === null || targets.length === 0) return keep('unproven_cache_target');
      for (const target of targets) {
        if (compareFirmwareVersions(target, currentVersion).decision !== 'latest')
          return keep('cache_target_newer_changed_or_unknown');
      }
      ids.add(row.id);
    }
  } catch (_) {
    // A malformed object/getter is not evidence that a cache row is obsolete.
    return keep('unreadable_cache_metadata');
  }
  return {decision: 'latest', reason: 'all_cached_targets_same_or_older', hiddenIds: [...ids]};
}

function cacheTargets(metadata) {
  const targets = [], seen = new Set(), work = [{value: metadata, parent: null, independent: false}];
  const componentKeys = new Set(['components', 'additionComponents', 'lightUpgradeComponent', 'appList']);
  while (work.length > 0) {
    const frame = work.pop(), value = frame.value;
    if (frame.exit) {
      if (frame.independent && frame.targetCount === 0) return null;
      if (frame.parent !== null) frame.parent.targetCount += frame.targetCount;
      continue;
    }
    if (frame.independent && !object(value)) return null;
    if (value === null || typeof value !== 'object') continue;
    if (seen.has(value)) return null;
    seen.add(value);
    frame.targetCount = 0; frame.exit = true; work.push(frame);
    if (Array.isArray(value)) {
      for (const child of value) work.push({value: child, parent: frame, independent: frame.collection === true});
      continue;
    }
    if (Object.prototype.hasOwnProperty.call(value, 'targetBlVersionNumber')) {
      if (parseFirmwareVersion(value.targetBlVersionNumber) === null) return null;
      targets.push(value.targetBlVersionNumber); frame.targetCount++;
    }
    for (const [key, child] of Object.entries(value)) {
      if (componentKeys.has(key) && child !== null) {
        if (typeof child !== 'object') return null;
        // Each independent component must have a complete target in its own
        // subtree. Post-order counts prove this in one non-recursive walk.
        if (Array.isArray(child)) work.push({value: child, parent: frame, independent: false, collection: true});
        else if (Object.keys(child).length > 0) work.push({value: child, parent: frame, independent: true});
      } else if (child !== null && typeof child === 'object') {
        work.push({value: child, parent: frame, independent: false});
      }
    }
  }
  return targets;
}

/** Replace one top-level scalar without reserializing large integer version IDs. */
export function replaceRootStatus(raw, nextStatus) {
  let depth = 0;
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c === '{' || c === '[') { depth++; continue; }
    if (c === '}' || c === ']') { depth--; continue; }
    if (c !== '"') continue;
    const start = i++;
    while (i < raw.length) {
      if (raw[i] === '\\') { i += 2; continue; }
      if (raw[i] === '"') break;
      i++;
    }
    if (depth !== 1 || JSON.parse(raw.slice(start, i + 1)) !== 'status') continue;
    let at = i + 1;
    while (/\s/.test(raw[at] || '') && at < raw.length) at++;
    if (raw[at++] !== ':') continue;
    while (/\s/.test(raw[at] || '') && at < raw.length) at++;
    const scalar = /^(?:"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(raw.slice(at));
    if (scalar === null) return null;
    const replacement = scalar[0][0] === '"' ? JSON.stringify(String(nextStatus)) : String(nextStatus);
    return raw.slice(0, at) + replacement + raw.slice(at + scalar[0].length);
  }
  return null;
}

export function filterVersionResponse(raw, deviceVersion, forceFullPackage, states) {
  if (forceFullPackage === true) return {raw, decision: 'forced', reason: 'force_full_package'};
  let parsed;
  try { parsed = JSON.parse(raw); }
  catch (_) { return {raw, decision: 'unknown', reason: 'invalid_response_json'}; }
  const decision = responseVersionDecision(parsed, deviceVersion, states);
  if (decision.decision !== 'latest') return {raw, ...decision};
  const changed = replaceRootStatus(raw, states.unavailable);
  return changed === null ? {raw, decision: 'unknown', reason: 'status_replacement_not_proven'} :
    {raw: changed, ...decision};
}
