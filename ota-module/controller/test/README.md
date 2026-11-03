# OTA options lifecycle tests

`OtaOptionsTest` is a host JVM executable that exercises first installation,
upgrading an old module with no settings, preserving a valid explicit choice,
invalid settings recovery, saving without activation, matching activation IDs,
and snapshot isolation. It creates files only in its supplied test directory.
Symlink cases run when the host permits symlink creation.

Compile `controller/src/net/hawyn/hnubl/module/OtaOptions.java` with
`controller/test/net/hawyn/hnubl/module/OtaOptionsTest.java`, then execute
`net.hawyn.hnubl.module.OtaOptionsTest TEST_DIRECTORY` using Java 17 or later.

`OptionsStatusTest` also needs the Android SDK compile jar and a host `org.json`
implementation on its class path. It compiles with `ModuleOptionsMain.java` and
checks that old activation IDs, different modes, missing mode fields and
incomplete injections do not falsely report Apply success. No Android/root
entry point is invoked by these host tests.

The production CLI accepts only fixed commands:

```text
sh /data/adb/modules/hnubl_ota/webui-control.sh status
sh /data/adb/modules/hnubl_ota/webui-control.sh save 0
sh /data/adb/modules/hnubl_ota/webui-control.sh save 1
sh /data/adb/modules/hnubl_ota/webui-control.sh apply
```

`save` changes `/data/adb/hnubl/ota-options.conf` only. Boot/activation prepares
`ota-options.active.conf` with a fresh activation ID before the existing
controller replacement request. The controller reads that snapshot once;
updater process restarts alone do not activate another saved value.

The JSON response contains `desired_force_full_package`,
`active_force_full_package`, `pending`, `has_saved_value`, `active_known`,
`hook_ready`, `activation_id`, and `error`. Apply success requires a matching
activation ID and runtime mode. A running updater requires `hook_ready=true`;
an idle updater requires `controller_ready=true`, `updater_running=false`, and
`waiting_for_updater=true`. Both paths require no error and no pending setting.
A changed active file alone does not establish readiness.

`apply` follows the module's established `service.sh --activate` path, which
replaces the updater controller. The service captures OUC's exact process name
and `com.hihonor.ouc:*` children before the old controller is disposed. If any
are running, it restarts OUC and waits for actual hooks. If none are running,
it only initializes the new mapped watcher; opening OUC manually later injects
the active setting. An unreadable process inventory or mapping is not idle
readiness. It does not reboot the phone or change the OTA completion marker.

`IdleApplyTest` compiles with `UpdaterProcessProbe.java` and
`ControllerReadiness.java` on a host JVM. It tests fake process directories,
exact and child names, the pre-disposal snapshot, initial idle readiness,
later manual launch, restart failure, and invalidation. Its argument is a new
temporary fixture directory. No device/Android entry point is invoked.
