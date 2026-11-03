import {cacheVersionDecision, parseFirmwareVersion} from './version-policy.js';

/** Read-only cache visibility at the discovered native list/count query boundary. */
export function installCacheVersionQuery({Java, hook, forceFullPackage, warn, telemetry, stats}) {
  const types = ['android.net.Uri', '[Ljava.lang.String;', 'java.lang.String', '[Ljava.lang.String;', 'java.lang.String'];
  if (hook.return_type !== 'android.database.Cursor' || hook.is_static === true ||
      !Array.isArray(hook.parameter_types) || !types.every((type, i) => hook.parameter_types[i] === type) ||
      hook.parameter_types.length !== types.length)
    throw new Error('Cache query descriptor was not proven');
  const columns = hook.columns;
  for (const name of ['id', 'state', 'progress', 'metadata']) {
    if (!columns || typeof columns[name] !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(columns[name]))
      throw new Error('Cache column relationship is missing: ' + name);
  }
  if (!Array.isArray(hook.pending_states) || hook.pending_states.length === 0 ||
      !hook.pending_states.every(value => Number.isInteger(value)))
    throw new Error('Native pending cache states were not proven');
  const source = hook.uri_source;
  if (!source || source.field_type !== 'android.net.Uri' ||
      typeof source.class_name !== 'string' || typeof source.field_name !== 'string')
    throw new Error('Firmware cache URI source was not proven');
  const owner = Java.use(hook.class_name);
  const overload = owner[hook.method_name].overload(...hook.parameter_types);
  const receiverType = Java.use(hook.receiver_class);
  const uriOwner = Java.use(source.class_name);
  const uriField = uriOwner.class.getDeclaredField(source.field_name);
  uriField.setAccessible(true);
  const expectedUri = uriField.get(null);
  if (expectedUri === null) throw new Error('Firmware cache URI is unavailable');
  const uri = String(expectedUri.toString());
  const current = hook.current_version_source && hook.current_version_source.fallback_supplier;
  if (!current || typeof current.class_name !== 'string' || typeof current.method_name !== 'string')
    throw new Error('Complete current firmware supplier was not proven');
  const supplierType = Java.use(current.class_name);
  const supplier = supplierType[current.method_name].overload();
  const thread = Java.use('java.lang.Thread');
  const inside = new Set();
  let lastDiagnostic = null;
  const invoke = (self, args) => overload.call(self, ...args);
  const warning = error => {
    try { warn('Optional cache version filtering failed; original query retained', error); }
    catch (_) { /* Diagnostics never replace the native query. */ }
  };
  function report(decision, rows, deviceVersion) {
    const diagnostic = {event: 'cache_version_filter', decision: decision.decision, reason: decision.reason,
      device_version: deviceVersion, row_count: rows.length, hidden_count: decision.hiddenIds.length};
    const key = JSON.stringify(diagnostic);
    if (lastDiagnostic === key) return;
    lastDiagnostic = key;
    try { telemetry(diagnostic); } catch (_) { /* Read-only classification remains usable. */ }
  }
  function readRows(self, target) {
    let cursor = null;
    try {
      cursor = invoke(self, [target, null, null, null, null]);
      if (cursor === null) throw new Error('Native cache query returned no cursor');
      const index = {};
      for (const name of ['id', 'state', 'progress', 'metadata']) {
        index[name] = cursor.getColumnIndex(columns[name]);
        if (index[name] < 0) throw new Error('Native cache projection lacks ' + columns[name]);
      }
      const rows = [];
      if (cursor.moveToFirst()) do {
        rows.push({id: cursor.isNull(index.id) ? null : String(cursor.getString(index.id)),
          state: cursor.isNull(index.state) ? null : Number(cursor.getInt(index.state)),
          // The native row mapper also calls Cursor.getInt here: SQLite NULL
          // represents its initial zero progress, not an unknown activity stage.
          progress: Number(cursor.getInt(index.progress)),
          metadata: cursor.isNull(index.metadata) ? null : String(cursor.getString(index.metadata))});
      } while (cursor.moveToNext());
      return rows;
    } finally {
      if (cursor !== null) cursor.close();
    }
  }
  overload.implementation = function (...args) {
    let forced;
    try { forced = forceFullPackage() === true; }
    catch (error) { warning(error); return invoke(this, args); }
    if (forced) return invoke(this, args);
    let identity, matches;
    try {
      matches = args[0] !== null && receiverType.class.isInstance(this) && String(args[0].toString()) === uri;
      if (matches) identity = String(thread.currentThread().getId());
    } catch (error) {
      warning(error);
      return invoke(this, args);
    }
    if (!matches || inside.has(identity)) return invoke(this, args);
    inside.add(identity);
    let selected = args;
    try {
      const version = String(supplier.call(supplierType));
      if (parseFirmwareVersion(version) === null) throw new Error('Current full firmware version is unclassified');
      const rows = readRows(this, args[0]);
      const decision = cacheVersionDecision(rows, version, false, hook.pending_states);
      report(decision, rows, version);
      if (decision.decision !== 'latest' || decision.hiddenIds.length === 0) {
        stats.cachePreserved = (stats.cachePreserved || 0) + 1;
      } else {
        // Numeric IDs are validated again at this native SQL-selection boundary.
        // Original projection, bind arguments and sort order remain untouched.
        if (!decision.hiddenIds.every(value => typeof value === 'string' && /^\d+$/.test(value)))
          throw new Error('Cache exclusion contains an invalid row identity');
        const exclusion = '"' + columns.id + '" NOT IN (' + decision.hiddenIds.join(',') + ')';
        selected = [...args];
        selected[2] = args[2] === null || String(args[2]).trim() === '' ? exclusion :
          '(' + String(args[2]) + ') AND (' + exclusion + ')';
      }
    } catch (error) {
      warning(error);
      selected = args;
    }
    try {
      if (selected !== args) {
        try {
          const result = invoke(this, selected);
          stats.cacheLatest = (stats.cacheLatest || 0) + 1;
          return result;
        } catch (error) {
          warning(error);
          // Only a failed optional read filter falls back. An exception from
          // the original caller's unmodified query is never caught/replayed.
        }
      }
      return invoke(this, args);
    } finally { inside.delete(identity); }
  };
  return {uri, descriptor: hook.descriptor || null,
    dispose() { overload.implementation = null; }};
}
