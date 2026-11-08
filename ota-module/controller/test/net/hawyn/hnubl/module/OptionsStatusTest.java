package net.hawyn.hnubl.module;

import org.json.JSONObject;

/** A prepared snapshot alone must never be reported as a ready injected mode. */
public final class OptionsStatusTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static JSONObject controller(String id, Object mode, boolean ready) throws Exception {
        JSONObject result = new JSONObject().put("activation_id", id).put("hook_ready", ready).put("error", "");
        result.put("controller_ready", true).put("updater_running", true).put("waiting_for_updater", false);
        result.put("broker_ready", true).put("critical_ready", true).put("prefer_patch_package", false);
        if (mode != null) result.put("force_full_package", mode);
        return result;
    }
    public static void main(String[] ignored) throws Exception {
        OtaOptions.Snapshot active = new OtaOptions.Snapshot(true, true, false, false, true, true, "new-id", "");
        JSONObject matching = ModuleOptionsMain.statusResponse(active, controller("new-id", true, true), "");
        check(matching.getBoolean("hook_ready"), "Matching ready activation should be accepted");
        check(!matching.getBoolean("pending"), "Matching saved and active settings should agree");
        check("new-id".equals(matching.getString("activation_id")), "UI should receive the target activation identity");
        check(!ModuleOptionsMain.statusResponse(active, controller("old-id", true, true), "").getBoolean("hook_ready"), "Old controller readiness must not complete Apply");
        check(!ModuleOptionsMain.statusResponse(active, controller("new-id", false, true), "").getBoolean("hook_ready"), "Wrong runtime mode must not complete Apply");
        check(!ModuleOptionsMain.statusResponse(active, controller("new-id", "true", true), "").getBoolean("hook_ready"), "Truthy text must not be treated as a boolean status");
        check(!ModuleOptionsMain.statusResponse(active, controller("new-id", null, true), "").getBoolean("hook_ready"), "Older statuses without a mode must not complete Apply");
        check(!ModuleOptionsMain.statusResponse(active, controller("new-id", true, false), "").getBoolean("hook_ready"), "A pending injection must remain pending");

        OtaOptions.Snapshot savedOnly = new OtaOptions.Snapshot(false, true, false, false, true, true, "new-id", "");
        JSONObject pending = ModuleOptionsMain.statusResponse(savedOnly, controller("new-id", true, true), "");
        check(pending.getBoolean("pending") && pending.getBoolean("active_force_full_package") && !pending.getBoolean("desired_force_full_package"), "Saving must preserve the active mode and report a pending choice");
        OtaOptions.Snapshot missing = new OtaOptions.Snapshot(false, false, false, false, false, false, "", "");
        check(!ModuleOptionsMain.statusResponse(missing, controller("", false, true), "").getBoolean("hook_ready"), "Missing active file cannot claim readiness");
        String escaped = "设置错误：\"quoted\"\nnext\\line";
        OtaOptions.Snapshot warning = new OtaOptions.Snapshot(false, false, false, false, false, false, "", escaped);
        JSONObject serialized = new JSONObject(ModuleOptionsMain.statusResponse(warning, new JSONObject(), "").toString());
        check(escaped.equals(serialized.getString("error")), "Status errors must round-trip through valid escaped JSON");
        check("state unreadable".equals(ModuleOptionsMain.statusResponse(active, controller("new-id", true, false), "state unreadable").getString("error")), "Unreadable controller state must surface an error");
        JSONObject failedBeforeConstruction = controller("new-id", null, false).put("error", "controller initialization failed");
        check("controller initialization failed".equals(ModuleOptionsMain.statusResponse(active, failedBeforeConstruction, "").getString("error")), "A same-activation bootstrap failure must remain visible even before a mode field exists");

        JSONObject idle = controller("new-id", true, false).put("updater_running", false).put("waiting_for_updater", true);
        JSONObject idleStatus = ModuleOptionsMain.statusResponse(active, idle, "");
        check(idleStatus.getBoolean("controller_ready") && idleStatus.getBoolean("waiting_for_updater") && !idleStatus.getBoolean("updater_running"), "A mapped watcher that observed idle should report idle readiness");
        check(!idleStatus.getBoolean("hook_ready") && idleStatus.getString("error").isEmpty(), "Idle readiness is not a fabricated injection");
        check(!ModuleOptionsMain.statusResponse(active, new JSONObject(idle.toString()).put("activation_id", "old-id"), "").getBoolean("waiting_for_updater"), "Old idle status must not complete Apply");
        check(!ModuleOptionsMain.statusResponse(active, new JSONObject(idle.toString()).put("force_full_package", false), "").getBoolean("waiting_for_updater"), "Wrong-mode idle status must not complete Apply");
        check(!ModuleOptionsMain.statusResponse(active, new JSONObject(idle.toString()).put("controller_ready", false), "").getBoolean("waiting_for_updater"), "Prepared settings without initialized watcher must not complete Apply");
        check(!ModuleOptionsMain.statusResponse(active, new JSONObject(idle.toString()).put("controller_ready", "true"), "").getBoolean("controller_ready"), "Readiness fields require typed booleans");
        check(!ModuleOptionsMain.statusResponse(active, new JSONObject(idle.toString()).put("updater_running", true), "").getBoolean("waiting_for_updater"), "A live updater must never count as idle");
        JSONObject noObservation = new JSONObject(idle.toString()); noObservation.remove("updater_running");
        check(!ModuleOptionsMain.statusResponse(active, noObservation, "").getBoolean("waiting_for_updater"), "Missing process observation must not count as idle");
        JSONObject failedIdle = ModuleOptionsMain.statusResponse(active, new JSONObject(idle.toString()).put("error", "mapping failed"), "");
        check(!failedIdle.getBoolean("controller_ready") && !failedIdle.getBoolean("waiting_for_updater"), "Mapping failure must not complete idle Apply");
        JSONObject startupFailure = ModuleOptionsMain.statusResponse(active, controller("new-id", true, false).put("error", "Waiting for restarted updater"), "");
        check(!startupFailure.getBoolean("hook_ready") && !startupFailure.getBoolean("waiting_for_updater"), "A failed or incomplete updater restart must not be accepted as idle success");
        check(!ModuleOptionsMain.statusResponse(active, new JSONObject(idle.toString()).put("broker_ready", false), "").getBoolean("waiting_for_updater"), "Idle watcher requires actual marker STATE proof");
        check(!ModuleOptionsMain.statusResponse(active, controller("new-id", true, true).put("broker_ready", "true"), "").getBoolean("hook_ready"), "Broker truthy text is not proof");
        check(!ModuleOptionsMain.statusResponse(active, controller("new-id", true, true).put("critical_ready", false), "").getBoolean("hook_ready"), "Running hooks require critical protection");
        JSONObject optionalWarning = controller("new-id", true, true).put("warnings", new org.json.JSONArray().put("optional version response absent"));
        check(ModuleOptionsMain.statusResponse(active, optionalWarning, "").getBoolean("hook_ready"), "Optional warnings preserve genuine readiness");
        OtaOptions.Snapshot patchActive = new OtaOptions.Snapshot(true, true, true, true, true, true, "new-id", "");
        JSONObject patchReady = controller("new-id", true, true).put("prefer_patch_package", true);
        JSONObject patchStatus = ModuleOptionsMain.statusResponse(patchActive, patchReady, "");
        check(patchStatus.getBoolean("hook_ready") && patchStatus.getBoolean("desired_prefer_patch_package") &&
            patchStatus.getBoolean("active_prefer_patch_package"), "Patch-on readiness requires the applied choice");
        check(!ModuleOptionsMain.statusResponse(patchActive, controller("new-id", true, true), "").getBoolean("hook_ready"),
            "Matching full with a different patch setting must not complete Apply");
        patchReady.remove("prefer_patch_package");
        check(!ModuleOptionsMain.statusResponse(patchActive, patchReady, "").getBoolean("hook_ready"),
            "Status without a patch setting must not complete Apply");
        System.out.println("OptionsStatus checks passed: " + checks);
    }
}
