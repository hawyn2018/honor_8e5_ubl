package net.hawyn.hnubl.module;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Small root-only WebUI CLI: fixed settings operations, no arbitrary commands. */
public final class ModuleOptionsMain {
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0) throw new SecurityException("UID 0 required");
            if (args.length == 0) throw new IllegalArgumentException("Operation required");
            if ("updater-running".equals(args[0])) {
                if (args.length != 1) throw new IllegalArgumentException("Unexpected arguments");
                System.out.println(UpdaterProcessProbe.isRunning(Paths.get("/proc")) ? "true" : "false");
                return;
            }
            OtaOptions.Store store = OtaOptions.production();
            OtaOptions.Snapshot snapshot;
            switch (args[0]) {
                case "status":
                    if (args.length != 1) throw new IllegalArgumentException("Unexpected arguments");
                    snapshot = store.snapshot(); break;
                case "save":
                case "save-patch":
                    if (args.length != 2 || !("0".equals(args[1]) || "1".equals(args[1])))
                        throw new IllegalArgumentException("Setting must be 0 or 1");
                    snapshot = "save-patch".equals(args[0]) ?
                        store.savePatch("1".equals(args[1])) : store.save("1".equals(args[1]));
                    break;
                case "prepare":
                    if (args.length != 2) throw new IllegalArgumentException("Activation ID required");
                    snapshot = store.prepare(args[1]); break;
                default: throw new IllegalArgumentException("Unknown operation");
            }
            System.out.println(status(snapshot).toString());
        } catch (Throwable failure) {
            try { System.out.println(new JSONObject().put("ok", false).put("error", failure.toString())); }
            catch (Exception ignored) { System.out.println("{\"ok\":false,\"error\":\"Settings operation failed\"}"); }
            System.exit(1);
        }
    }

    private static JSONObject status(OtaOptions.Snapshot snapshot) throws Exception {
        JSONObject controller = new JSONObject();
        Path status = OtaOptions.STATE.resolve("status.json");
        String statusError = "";
        if (Files.exists(status, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (!Files.isRegularFile(status, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular status file");
                controller = new JSONObject(new String(Files.readAllBytes(status), StandardCharsets.UTF_8));
            } catch (Exception invalid) { statusError = "模块状态暂不可读"; }
        }
        return statusResponse(snapshot, controller, statusError);
    }

    static JSONObject statusResponse(OtaOptions.Snapshot snapshot, JSONObject controller, String statusError) throws Exception {
        boolean sameActivation = snapshot.activeKnown && snapshot.activationId.equals(controller.optString("activation_id"));
        boolean sameMode = Boolean.valueOf(snapshot.active).equals(controller.opt("force_full_package")) &&
            Boolean.valueOf(snapshot.activePatch).equals(controller.opt("prefer_patch_package"));
        String controllerError = sameActivation ? controller.optString("error", "") : "";
        String error = !snapshot.warning.isEmpty() ? snapshot.warning : !statusError.isEmpty() ? statusError :
            !controllerError.isEmpty() ? controllerError : sameActivation && sameMode ? "" :
            snapshot.activeKnown ? "设置已准备，等待模块重新激活" : "尚未应用设置";
        boolean matching = sameActivation && sameMode && error.isEmpty();
        boolean controllerReady = matching && Boolean.TRUE.equals(controller.opt("controller_ready"));
        boolean brokerReady = controllerReady && Boolean.TRUE.equals(controller.opt("broker_ready"));
        boolean criticalReady = controllerReady && Boolean.TRUE.equals(controller.opt("critical_ready"));
        boolean running = sameActivation && sameMode && Boolean.TRUE.equals(controller.opt("updater_running"));
        boolean waiting = brokerReady && Boolean.FALSE.equals(controller.opt("updater_running")) &&
            Boolean.TRUE.equals(controller.opt("waiting_for_updater"));
        boolean ready = brokerReady && criticalReady && running && Boolean.TRUE.equals(controller.opt("hook_ready"));
        return new JSONObject().put("ok", true).put("desired_force_full_package", snapshot.desired)
            .put("active_force_full_package", snapshot.active).put("pending", snapshot.pending())
            .put("desired_prefer_patch_package", snapshot.desiredPatch)
            .put("active_prefer_patch_package", snapshot.activePatch)
            .put("has_saved_value", snapshot.hasSavedValue).put("active_known", snapshot.activeKnown)
            .put("hook_ready", ready).put("controller_ready", controllerReady).put("updater_running", running)
            .put("broker_ready", brokerReady).put("critical_ready", criticalReady)
            .put("warnings", controller.optJSONArray("warnings") == null ? new org.json.JSONArray() : controller.optJSONArray("warnings"))
            .put("phase", controller.optString("phase", ""))
            .put("native_slot_ready",sameActivation && Boolean.TRUE.equals(controller.opt("native_slot_ready")))
            .put("native_slot_waiting",sameActivation && Boolean.TRUE.equals(controller.opt("native_slot_waiting")))
            .put("waiting_for_updater", waiting).put("activation_id", snapshot.activationId).put("error", error);
    }
}
