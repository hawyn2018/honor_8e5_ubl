package net.hawyn.hnubl.module;

import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/** Acknowledges deferred activation only after durable storage; commits at the original reboot gate. */
final class DeferredSlotStore {
    interface Marker { String request(String command) throws IOException; }
    interface Committer { boolean commit(int source, int target) throws IOException; }
    interface Access { JSONObject read() throws IOException; void write(JSONObject value) throws IOException; }
    interface Transaction<T> { T run(Access access) throws IOException; }
    interface Persistence { <T> T locked(Transaction<T> operation) throws IOException; }
    private final Persistence persistence;
    private final String boot;
    private final int source;
    private volatile String warning = "";

    DeferredSlotStore(Persistence persistence, String boot, int source) {
        if (persistence == null || boot == null || !boot.matches("[0-9a-fA-F-]{36}") || source < 0 || source > 1) {
            throw new IllegalArgumentException("Current boot and A/B source slot required");
        }
        this.persistence = persistence; this.boot = boot; this.source = source;
    }

    String bootId() { return boot; }
    int sourceSlot() { return source; }
    String warning() { return warning; }

    String ota(String command, Marker marker, Committer committer) throws IOException {
        if ("STATE".equals(command)) return marker.request("STATE");
        if (!"BEGIN".equals(command) && !"RESET".equals(command) && !"CONSUME".equals(command)) throw new IOException("OTA command rejected");
        return persistence.locked(access -> json(() -> {
            if ("RESET".equals(command)) return marker.request("RESET");
            if ("BEGIN".equals(command)) {
                String reset = marker.request("RESET");
                MarkerStateProof.require(reset);
                JSONObject armed = object().put("cycle_id", UUID.randomUUID().toString()).put("phase", "ARMED")
                    .put("target_slot", -1).put("engine_pid", 0).put("engine_start", 0);
                access.write(armed);
                warning = "";
                return reset;
            }
            String state = marker.request("STATE");
            MarkerStateProof.require(state);
            if (new JSONObject(state).getInt("state") != 1) return deny("marker_not_ready");
            JSONObject plan = access.read();
            if (plan == null) {
                warning = "No native deferred activation was recorded; using the updater's original slot flow";
                return withWarning(marker.request("CONSUME"), warning);
            }
            validate(plan);
            if (!current(plan)) return deny("stale_boot_or_source");
            String phase = plan.getString("phase");
            if ("ARMED".equals(phase)) {
                warning = "Native slot hook did not defer this cycle; using the updater's original slot flow";
                return withWarning(marker.request("CONSUME"), warning);
            }
            if ("COMMITTED".equals(phase)) return marker.request("CONSUME");
            if (!"DONE".equals(phase)) return deny("deferred_activation_not_complete:" + phase);
            int target = plan.getInt("target_slot");
            if (target != 1 - source) return deny("invalid_deferred_target");
            // Never replay an ambiguous HAL call after timeout/crash/false. The
            // durable COMMITTING record remains until a new explicit BEGIN.
            plan.put("phase", "COMMITTING"); access.write(plan);
            if (!committer.commit(source, target)) return deny("slot_commit_not_confirmed");
            plan.put("phase", "COMMITTED"); access.write(plan);
            warning = "";
            return marker.request("CONSUME");
        }));
    }

    JSONObject nativeRequest(JSONObject request, int pid, long startTime) throws IOException {
        if (!number(request.opt("protocol"),1) || !boot.equals(request.optString("boot_id")) ||
                !number(request.opt("pid"),pid) || !number(request.opt("start_time"),startTime)) {
            throw new IOException("Native identity envelope rejected");
        }
        String event = request.optString("event");
        if (!"HELLO".equals(event) && !"DEFER".equals(event) && !"DONE".equals(event) && !"FAIL".equals(event)) throw new IOException("Native event rejected");
        String nonce = request.optString("nonce");
        if (!nonce.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Native nonce rejected");
        return persistence.locked(access -> json(() -> {
            JSONObject plan = access.read();
            JSONObject response = new JSONObject().put("protocol", 1).put("nonce", nonce).put("ok", false)
                .put("decision", "WAIT_BEGIN").put("source_slot", source);
            if (plan == null) return response;
            validate(plan);
            if (!current(plan)) return response.put("decision", "STALE_BOOT_OR_SOURCE");
            String cycle = plan.getString("cycle_id");
            response.put("cycle_id", cycle);
            if ("HELLO".equals(event)) return response.put("ok", true).put("decision", "READY").put("phase", plan.getString("phase"));
            if (!cycle.equals(request.optString("cycle_id"))) return response.put("decision", "STALE_CYCLE");
            int target = request.optInt("target_slot", -1);
            if (!number(request.opt("target_slot"),1-source) || target != 1 - source) return response.put("decision", "INVALID_TARGET");
            String phase = plan.getString("phase");
            if ("DEFER".equals(event)) {
                if (!"ARMED".equals(phase) && (plan.getInt("engine_pid") != pid || plan.getLong("engine_start") != startTime)) return response.put("decision", "ENGINE_CHANGED");
                if("ARMED".equals(phase)){
                    plan.put("target_slot", target).put("engine_pid", pid).put("engine_start", startTime).put("phase", "DEFERRED");
                    access.write(plan);
                } else if(plan.getInt("target_slot")!=target)return response.put("decision","INVALID_TARGET");
                // Duplicate/concurrent CompletePostinstall calls must never fall
                // through to the original HAL after this cycle was deferred.
                // Reuse its durable ACK without erasing DONE/FAILED/COMMITTED.
                return response.put("ok", true).put("decision", "DEFER").put("target_slot", target).put("durable", true);
            }
            if ("ARMED".equals(phase) || plan.getInt("engine_pid") != pid ||
                    plan.getLong("engine_start") != startTime || plan.getInt("target_slot") != target) return response.put("decision", "NO_MATCHING_DEFER");
            if ("DONE".equals(event) && !number(request.opt("code"),0)) return response.put("decision", "INVALID_COMPLETION_CODE");
            if("DONE".equals(event)&&("COMMITTING".equals(phase)||"COMMITTED".equals(phase)||"DONE".equals(phase)))return response.put("ok",true).put("decision","DONE");
            if("DONE".equals(event)&&"FAILED".equals(phase))return response.put("decision","CYCLE_FAILED");
            if("FAIL".equals(event)&&("COMMITTING".equals(phase)||"COMMITTED".equals(phase)))return response.put("decision","COMMIT_ALREADY_STARTED");
            plan.put("phase", "DONE".equals(event) ? "DONE" : "FAILED").put("completion_code", request.optInt("code", -1));
            access.write(plan);
            return response.put("ok", true).put("decision", "DONE".equals(event) ? "DONE" : "FAIL");
        }));
    }

    private JSONObject object() throws org.json.JSONException { return new JSONObject().put("schema", 1).put("boot_id", boot).put("source_slot", source); }
    private boolean current(JSONObject plan) { return boot.equals(plan.optString("boot_id")) && source == plan.optInt("source_slot", -1); }
    private static void validate(JSONObject plan) throws IOException {
        try {
            if (plan.getInt("schema") != 1 || !plan.getString("boot_id").matches("[0-9a-fA-F-]{36}") ||
                    !plan.getString("cycle_id").matches("[0-9a-fA-F-]{36}") || plan.getInt("source_slot") < 0 || plan.getInt("source_slot") > 1 ||
                    !plan.getString("phase").matches("ARMED|DEFERRED|DONE|FAILED|COMMITTING|COMMITTED")) throw new IOException("Invalid deferred activation record");
            if (!"ARMED".equals(plan.getString("phase")) && (plan.getInt("target_slot") != 1 - plan.getInt("source_slot") ||
                    plan.getInt("engine_pid") <= 0 || plan.getLong("engine_start") < 0)) throw new IOException("Invalid deferred engine identity");
        } catch (org.json.JSONException invalid) { throw new IOException("Invalid deferred activation record", invalid); }
    }
    private interface JsonAction<T> { T run() throws IOException, org.json.JSONException; }
    private static <T> T json(JsonAction<T> action) throws IOException {
        try { return action.run(); } catch (org.json.JSONException invalid) { throw new IOException("Deferred activation JSON rejected", invalid); }
    }
    private static boolean number(Object value,long expected){return value instanceof Number && ((Number)value).longValue()==expected && ((Number)value).doubleValue()==(double)expected;}
    private static String withWarning(String response,String message)throws org.json.JSONException{return new JSONObject(response).put("warnings",new org.json.JSONArray().put(message)).toString();}
    private static String deny(String reason) throws org.json.JSONException { return new JSONObject().put("ok", false).put("state", 0).put("decision", "DENY").put("error", reason).toString(); }
}
