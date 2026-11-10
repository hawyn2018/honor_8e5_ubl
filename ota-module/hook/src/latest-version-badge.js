const badgeKey = 'hn_new_system_update';
const settingsPackage = 'com.android.settings';
const launcherBadgeUri = 'content://com.hihonor.android.launcher.settings/badge/';
const deviceLatestText = new Set([
  '当前设备已是最新版本', '目前裝置已是最新版本',
  'Your device is up to date', 'Your device is up to date.',
]);
const shortLatestText = ['已是最新版本', '当前已是最新版本', '目前已是最新版本', "You're up to date"];

function createLauncherBadgeClearer(Java, app) {
  let awaitingZero = null;
  return resolver => {
    const launchIntent = app.getPackageManager().getLaunchIntentForPackage(settingsPackage);
    const component = launchIntent?.getComponent();
    if (component == null || String(component.getPackageName()) !== settingsPackage) return false;
    const className = String(component.getClassName());
    const uri = Java.use('android.net.Uri').parse(launcherBadgeUri);
    const extras = Java.use('android.os.Bundle').$new();
    extras.putString('package', settingsPackage);
    extras.putString('class', className);
    const badge = resolver.call(uri, 'getbadgeNumber', '', extras);
    if (badge === null || String(badge.getString('package')) !== settingsPackage ||
        String(badge.getString('class')) !== className) return false;
    const count = badge.getInt('badgenumber', -1);
    // The provider commits before the launcher's shortcut model refreshes.
    // Ignore stale reads of 1 until that model acknowledges the cleared value.
    if (count === 0 && awaitingZero === className) awaitingZero = null;
    if (count !== 1 || awaitingZero === className) return false;
    extras.putInt('badgenumber', 0);
    const result = resolver.call(uri, 'change_badge', '', extras);
    if (result !== null && (String(result.getString('result')) === 'update badge table success' ||
        result.getBoolean('callbadge', false))) {
      awaitingZero = className;
      return true;
    }
    throw new Error('HONOR Launcher did not confirm the badge change');
  };
}

export function createLatestVersionBadgeObserver({Java, app, warn, onCleared, onLauncherCleared}) {
  const report = (stage, error) => { try { warn(`${stage}: ${error}`); } catch (_) {} };
  const clearLauncherBadge = createLauncherBadgeClearer(Java, app);
  // Share the existing focused-window scan. The observer keeps no view or UI
  // state after a scan and reads the badges only after a visible status match.
  return (root, resourceOnly = false) => {
    if (root == null) return null;
    let stage = 'resource lookup';
    try {
      if (!root.hasWindowFocus()) return null;
      const resources = root.getResources();
      const pkg = String(app.getPackageName());
      const statusId = resources.getIdentifier('tv_already_new_version', 'id', pkg);
      const checkId = resources.getIdentifier('check_update', 'id', pkg);
      const firmwareId = resources.getIdentifier('iv_magic_os', 'id', pkg);
      const latestTexts = new Set(shortLatestText);
      for (const name of ['latest_version', 'res_current_all_latest_version']) {
        const id = resources.getIdentifier(name, 'string', pkg);
        if (id) latestTexts.add(String(resources.getString(id)).trim());
      }
      const View = Java.use('android.view.View');
      const rootRef = Java.use('java.lang.ref.WeakReference').$new(root);
      let checked = false;
      const observe = (candidate, text) => {
        if (checked) return;
        const value = text.trim();
        const deviceLatest = deviceLatestText.has(value.replace(/。$/u, ''));
        if (!value || (!deviceLatest && !latestTexts.has(value))) return;
        let checkStage = 'window focus';
        try {
          const window = rootRef.get();
          if (window === null) return;
          const focusedRoot = Java.cast(window, View);
          const view = Java.cast(candidate, View);
          if (!focusedRoot.hasWindowFocus() || !focusedRoot.equals(view.getRootView()) || !view.isShown()) return;
          checkStage = 'status and check controls';
          const statusView = statusId !== 0 && view.getId() === statusId;
          const findView = id => {
            const match = id === 0 ? null : focusedRoot.findViewById(id);
            return match === null ? null : Java.cast(match, View);
          };
          const check = findView(checkId);
          if (check !== null && (!check.isShown() || !check.isEnabled())) return;
          // Short status strings can also appear on peripheral update screens.
          if (!statusView && !deviceLatest) {
            const firmware = findView(firmwareId);
            if (check === null || firmware === null || !firmware.isShown()) return;
          }
          checkStage = 'status visibility';
          const bounds = Java.use('android.graphics.Rect').$new();
          if (!view.getGlobalVisibleRect(bounds)) return;
          checked = true;
          checkStage = 'badge resolver';
          const resolver = app.getContentResolver();
          try {
            const secure = Java.use('android.provider.Settings$Secure');
            if (secure.getInt(resolver, badgeKey, 0) === 1) {
              if (secure.putInt(resolver, badgeKey, 0)) onCleared?.();
              else report('badge setting', 'Settings.Secure did not accept the badge value');
            }
          } catch (error) { report('badge setting', error); }
          try {
            if (clearLauncherBadge(resolver)) onLauncherCleared?.();
          } catch (error) { report('launcher badge', error); }
        } catch (error) { report(checkStage, error); }
      };
      if (resourceOnly) {
        stage = 'status view lookup';
        const status = statusId === 0 ? null : Java.cast(root, View).findViewById(statusId);
        if (status !== null) {
          const TextView = Java.use('android.widget.TextView');
          if (TextView.class.isInstance(status)) observe(status, String(Java.cast(status, TextView).getText()));
        }
        return null;
      }
      return observe;
    } catch (error) { report(stage, error); return null; }
  };
}
