import assert from 'node:assert/strict';
import test from 'node:test';
import {createLatestVersionBadgeObserver} from '../src/latest-version-badge.js';

function fixture({initial = 1, desktop = 0, hasResources = true, firmware = true, enabled = true, readError = false,
  launcherReadError = false, launcherReplyClass = 'FixtureSettings', staleLauncherReads = 0} = {}) {
  const reads = [], writes = [], warnings = [], cleared = [];
  const launcherReads = [], launcherWrites = [], launcherCleared = [];
  let readsUntilRefresh = 0;
  const bundle = (data = {}) => {
    const values = {...data};
    return {values, putString: (key, value) => { values[key] = value; }, putInt: (key, value) => { values[key] = value; },
      getString: key => values[key] ?? null, getInt: (key, fallback) => Number.isInteger(values[key]) ? values[key] : fallback,
      getBoolean: (key, fallback) => typeof values[key] === 'boolean' ? values[key] : fallback};
  };
  const resolver = {call(uri, method, arg, extras) {
    assert.equal(uri, 'content://com.hihonor.android.launcher.settings/badge/');
    assert.equal(arg, '');
    assert.equal(extras.getString('package'), 'com.android.settings');
    assert.equal(extras.getString('class'), 'FixtureSettings');
    if (method === 'getbadgeNumber') {
      launcherReads.push({...extras.values});
      if (launcherReadError) throw new Error('launcher provider unavailable');
      let count = desktop;
      if (readsUntilRefresh > 0) {
        readsUntilRefresh--;
        count = 1;
      }
      return count === null ? null : bundle({package: 'com.android.settings', class: launcherReplyClass, badgenumber: count});
    }
    assert.equal(method, 'change_badge');
    launcherWrites.push({...extras.values});
    desktop = extras.getInt('badgenumber', -1);
    readsUntilRefresh = staleLauncherReads;
    return bundle({result: 'update badge table success'});
  }};
  let stored = initial;
  const ids = {tv_already_new_version: 78, check_update: 31, iv_magic_os: 44, latest_version: 309};
  const resources = {
    getIdentifier(name, type, pkg) {
      assert.equal(pkg, 'com.hihonor.ouc');
      assert.equal(type, name === 'latest_version' || name === 'res_current_all_latest_version' ? 'string' : 'id');
      return hasResources ? ids[name] || 0 : 0;
    },
    getString: id => { assert.equal(id, ids.latest_version); return 'Votre version est à jour.'; },
  };
  let resourceStatus = null;
  const root = {getResources: () => resources, hasWindowFocus: () => true, equals: other => root === other,
    findViewById(id) {
      if (id === ids.tv_already_new_version) return resourceStatus;
      if (id === ids.check_update) return {isShown: () => true, isEnabled: () => enabled};
      if (id === ids.iv_magic_os && firmware) return {isShown: () => true};
      return null;
    }};
  const Java = {cast: value => value, use(name) {
    if (name === 'android.view.View') return {};
    if (name === 'android.widget.TextView') return {class: {isInstance: value => typeof value.getText === 'function'}};
    if (name === 'android.graphics.Rect') return {$new: () => ({})};
    if (name === 'java.lang.ref.WeakReference') return {$new: value => ({get: () => value})};
    if (name === 'android.net.Uri') return {parse: value => value};
    if (name === 'android.os.Bundle') return {$new: () => bundle()};
    assert.equal(name, 'android.provider.Settings$Secure');
    return {
      getInt(...args) {
        reads.push(args);
        if (readError) throw new Error('settings unavailable');
        return stored;
      },
      putInt(...args) { writes.push(args); stored = args[2]; return true; },
    };
  }};
  const app = {getPackageName: () => 'com.hihonor.ouc', getContentResolver: () => resolver,
    getPackageManager: () => ({getLaunchIntentForPackage(name) {
      assert.equal(name, 'com.android.settings');
      return {getComponent: () => ({getPackageName: () => name, getClassName: () => 'FixtureSettings'})};
    }})};
  const observe = createLatestVersionBadgeObserver({Java, app, warn: error => warnings.push(error),
    onCleared: () => cleared.push(true), onLauncherCleared: () => launcherCleared.push(true)});
  const view = ({id = ids.tv_already_new_version, owner = root, shown = true, inBounds = true} = {}) =>
    ({getId: () => id, getRootView: () => owner, isShown: () => shown, getGlobalVisibleRect: () => inBounds});
  return {observe, root, view, reads, writes, warnings, cleared, resolver, launcherReads, launcherWrites, launcherCleared,
    setResourceStatus(text) { resourceStatus = {...view(), getText: () => text}; },
    setDesktop(value) { desktop = value; }};
}

test('focus return can read the known status view directly and reports only an actual write', () => {
  const f = fixture();
  f.setResourceStatus('正在检查更新');
  f.observe(f.root, true);
  assert.equal(f.reads.length, 0);
  f.setResourceStatus('已是最新版本');
  f.observe(f.root, true);
  f.observe(f.root, true);
  assert.equal(f.writes.length, 1);
  assert.deepEqual(f.cleared, [true]);
});

test('a visible latest-version resource clears 1 once and 0 is left alone on the next scan', () => {
  const f = fixture();
  const scan = f.observe(f.root);
  scan(f.view(), 'Votre version est à jour.');
  scan(f.view(), '已是最新版本');
  f.observe(f.root)(f.view(), '已是最新版本');
  assert.deepEqual(f.writes, [[f.resolver, 'hn_new_system_update', 0]]);
  assert.deepEqual(f.reads, Array(2).fill([f.resolver, 'hn_new_system_update', 0]));
});

test('other statuses and hidden, clipped, inactive, or background views leave the setting alone', () => {
  const f = fixture({desktop: 1});
  const scan = f.observe(f.root);
  for (const state of ['正在检查更新', '发现新版本', '立即重启', '检查失败', '更新后提示已是最新版本']) scan(f.view(), state);
  scan(f.view({shown: false}), '已是最新版本');
  scan(f.view({inBounds: false}), '已是最新版本');
  scan(f.view({owner: {}}), '已是最新版本');
  f.root.hasWindowFocus = () => false;
  scan(f.view(), '已是最新版本');
  assert.deepEqual(f.reads, []);
  assert.deepEqual(f.writes, []);
  assert.deepEqual(f.launcherReads, []);
  assert.deepEqual(f.launcherWrites, []);
  const checking = fixture({desktop: 1, enabled: false});
  checking.observe(checking.root)(checking.view(), '已是最新版本');
  assert.deepEqual(checking.writes, []);
  assert.deepEqual(checking.launcherWrites, []);
});

test('a changed view ID uses localized text in the firmware screen, never the accessory screen', () => {
  for (const firmware of [true, false]) {
    const f = fixture({firmware});
    f.observe(f.root)(f.view({id: 999}), 'Votre version est à jour.');
    assert.equal(f.writes.length, firmware ? 1 : 0);
  }
});

test('explicit device status can survive a missing resource mapping and unknown text has no effect', () => {
  const f = fixture({hasResources: false});
  const scan = f.observe(f.root);
  scan(f.view({id: 999}), '已是最新版本');
  scan(f.view({id: 999}), '设备版本状态未知');
  assert.deepEqual(f.reads, []);
  scan(f.view({id: 999}), '当前设备已是最新版本。');
  assert.equal(f.writes.length, 1);
});

test('values other than 1 and settings read failures do not write', () => {
  for (const options of [{initial: 0}, {initial: 2}, {readError: true}]) {
    const f = fixture(options);
    f.observe(f.root)(f.view(), '已是最新版本');
    assert.equal(f.reads.length, 1);
    assert.equal(f.writes.length, 0);
    assert.equal(f.warnings.length, options.readError ? 1 : 0);
  }
});

test('latest clears each confirmed badge independently and repeated scans leave zero alone', () => {
  for (const initial of [0, 1]) {
    const f = fixture({initial, desktop: 1});
    f.setResourceStatus('已是最新版本');
    f.observe(f.root, true);
    f.observe(f.root)(f.view(), '已是最新版本');
    assert.equal(f.writes.length, initial);
    assert.deepEqual(f.launcherWrites, [{package: 'com.android.settings', class: 'FixtureSettings', badgenumber: 0}]);
    assert.deepEqual(f.launcherCleared, [true]);
    assert.equal(f.launcherReads.length, 2);
  }
});

test('unavailable, zero, ambiguous and mismatched launcher replies never trigger a write', () => {
  for (const options of [{desktop: null}, {desktop: 0}, {desktop: 2}, {desktop: '1'},
    {desktop: 1, launcherReplyClass: 'OtherSettings'}, {launcherReadError: true}]) {
    const f = fixture(options);
    f.observe(f.root)(f.view(), '已是最新版本');
    assert.equal(f.writes.length, 1);
    assert.equal(f.launcherWrites.length, 0);
    assert.equal(f.warnings.length, options.launcherReadError ? 1 : 0);
  }
});

test('a secure read failure leaves the launcher badge cleanup available', () => {
  const f = fixture({desktop: 1, readError: true});
  f.observe(f.root)(f.view(), '已是最新版本');
  assert.equal(f.writes.length, 0);
  assert.equal(f.launcherWrites.length, 1);
  assert.equal(f.warnings.length, 1);
});

test('stale launcher model reads after a successful clear do not repeat the write', () => {
  const f = fixture({initial: 0, desktop: 1, staleLauncherReads: 2});
  f.setResourceStatus('已是最新版本');
  for (let scan = 0; scan < 4; scan++) f.observe(f.root, true);
  assert.equal(f.launcherReads.length, 4);
  assert.equal(f.launcherWrites.length, 1);
  f.setDesktop(1);
  f.observe(f.root, true);
  assert.equal(f.launcherWrites.length, 2);
});
