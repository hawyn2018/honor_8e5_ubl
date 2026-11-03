package net.hawyn.hnubl.module;

import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/** Acknowledges deferred activation only after durable storage; commits at the original reboot gate. */
final class DeferredSlotStore {
    interface Marker { String request(String command) throws IOException; }
    interface Committer {
        boolean commit(int source, int target) throws IOException;
        default JSONObject evidence() { return new JSONObject(); }
    }
    interface Access { JSONObject read() throws IOException; void write(JSONObject value) throws IOException; }
    interface Transaction<T> { T run(Access access) throws IOException; }
    interface Persistence {
        <T> T locked(Transaction<T> operation) throws IOException;
        default <T> T diagnostic(Transaction<T> operation) throws IOException { return locked(operation); }
    }
    interface Observer { void record(String event, JSONObject fields); }
    private final Persistence persistence;
    private final String boot;
    private final int source;
    private final Observer observer;
    private volatile String warning = "";

    DeferredSlotStore(Persistence persistence, String boot, int source) {
        this(persistence, boot, source, (event, fields) -> { });
    }

    DeferredSlotStore(Persistence persistence, String boot, int source, Observer observer) {
        this(persistence, boot, source, observer, false);
    }
    private DeferredSlotStore(Persistence persistence, String boot, int source, Observer observer, boolean unregistered) {
        if (!unregistered && (persistence == null || boot == null || !boot.matches("[0-9a-fA-F-]{36}") || source < 0 || source > 1))
            throw new IllegalArgumentException("Current boot and A/B source slot required");
        this.persistence = persistence; this.boot = boot; this.source = source;
        this.observer = observer == null ? (event, fields) -> { } : observer;
    }
    static DeferredSlotStore unregistered(Observer observer) {
        return new DeferredSlotStore(null, "", -1, observer, true);
    }

    String bootId() { return boot; }
    int sourceSlot() { return source; }
    String warning() { return warning; }

    String ota(String command, Marker marker, Committer committer) throws IOException {
        return ota(command, marker, committer, null);
    }

    String ota(String command, Marker marker, Committer committer, String diagnosticId) throws IOException {
        if ("STATE".equals(command)) return marker.request("STATE");
        if ("CONSUME".equals(command)) return consume(marker, committer, diagnosticId);
        if (!"BEGIN".equals(command) && !"RESET".equals(command)) throw new IOException("OTA command rejected");
        // A new payload invalidates its old file grant before touching any local registry lock or metadata.
        String reset = marker.request("RESET"); MarkerStateProof.require(reset);
        if ("RESET".equals(command)) return observed(command, diagnosticId, null, null, null, reset);
        try {
            if (persistence == null) throw new IOException("Local native-cycle registration unavailable");
            return persistence.locked(access -> json(() -> {
                JSONObject armed = object().put("cycle_id", UUID.randomUUID().toString()).put("phase", "ARMED")
                    .put("target_slot", -1).put("engine_pid", 0).put("engine_start", 0)
                    .put("pending_activation", false).put("completed", false);
                access.write(armed); warning = "";
                return observed(command, diagnosticId, null, null, armed, reset);
            }));
        } catch (IOException | RuntimeException | LinkageError diagnostic) {
            String note = "File permission was reset; local native-cycle registration failed: " + diagnostic;
            warning = note;
            observe("ota_registry_warning", command, diagnosticId, reset, null, null, null, diagnostic);
            return json(() -> observed(command, diagnosticId, null, null, null, new JSONObject(reset)
                .put("registry_warning", note).put("warnings", new org.json.JSONArray().put(note)).toString()));
        }
    }

    /** Permission is consumed once, then the official API identifies and executes its pending task. */
    private String consume(Marker marker, Committer committer, String id) throws IOException {
        String raw = marker.request("CONSUME");
        JSONObject grant;
        try { grant = new JSONObject(raw); }
        catch (org.json.JSONException invalid) { throw new IOException("Invalid marker permission response", invalid); }
        if (!Boolean.TRUE.equals(grant.opt("ok")) || !number(grant.opt("state"), 0) || !"ALLOW".equals(grant.optString("decision"))) {
            return json(() -> observed("CONSUME", id, raw, null, null, new JSONObject().put("ok", false)
                .put("authorized", false).put("state", number(grant.opt("state"), 1) ? 1 : 0).put("decision", "DENY")
                .put("action_completed", false).put("error", grant.optString("error", "marker_not_ready")).toString()));
        }
        observe("ota_permission_granted", "CONSUME", id, raw, null, null, null, null);
        warning = "";
        try {
            observe("slot_commit_start", "CONSUME", id, raw, null, null, null, null);
            if (committer == null) throw new IOException("Official OTA execution capability unavailable");
            // The arguments are diagnostic context only. The typed official API chooses the task/slot itself.
            if (!committer.commit(source, source >= 0 ? 1 - source : -1)) throw new IOException("Official slot activation was not confirmed");
        } catch (IOException | RuntimeException | LinkageError failure) {
            JSONObject evidence = evidence(committer);
            observeEvidence("slot_execution_failed", id, evidence, failure);
            return json(() -> observed("CONSUME", id, raw, null, null, merge(new JSONObject().put("ok", false)
                .put("authorized", true).put("state", 0).put("decision", "EXECUTION_FAILED").put("action_completed", false)
                .put("error", failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage())), evidence).toString()));
        }
        JSONObject actual = evidence(committer);
        observeEvidence("official_activation_confirmed", id, actual, null);
        JSONObject registration = recordSuccess(id, actual);
        return json(() -> {
            JSONObject response = merge(new JSONObject().put("ok", true).put("authorized", true).put("state", 0)
                .put("decision", "ALLOW").put("action_completed", true).put("activation_performed", true), actual);
            if (registration.has("recovered")) response.put("recovered", registration.get("recovered"));
            if (registration.has("registry_warning")) {
                String note = registration.getString("registry_warning");
                response.put("registry_warning", note).put("warnings", new org.json.JSONArray().put(note));
            }
            return observed("CONSUME", id, raw, null, registration.optJSONObject("plan_after"), response.toString());
        });
    }

    private JSONObject recordSuccess(String id, JSONObject actual) {
        try {
            if (persistence == null) throw new IOException("Local diagnostic registry unavailable");
            return persistence.diagnostic(access -> json(() -> {
                JSONObject previous = access.read();
                boolean reuse = previous != null && current(previous) && previous.optInt("schema", -1) == 1 &&
                    previous.optString("cycle_id").matches("[0-9a-fA-F-]{36}") && !Boolean.TRUE.equals(previous.opt("completed"));
                JSONObject saved = reuse ? snapshot(previous) : object().put("cycle_id", UUID.randomUUID().toString())
                    .put("engine_pid", 0).put("engine_start", 0);
                saved.put("target_slot", 1 - source).put("phase", "COMMITTED").put("pending_activation", false).put("completed", true)
                    .put("recovered", !reuse).put("activation_source", "official_update_engine");
                merge(saved, actual);
                access.write(saved);
                observe("activation_registry_updated", "CONSUME", id, null, previous, saved, null, null);
                return new JSONObject().put("recovered", !reuse).put("plan_after", saved);
            }));
        } catch (IOException | RuntimeException | LinkageError diagnostic) {
            String note = "Official activation confirmed; local diagnostic registry was not updated: " + diagnostic;
            warning = note;
            observeEvidence("activation_registry_warning", id, actual, diagnostic);
            try { return new JSONObject().put("registry_warning", note); }
            catch (org.json.JSONException ignored) { return new JSONObject(); }
        }
    }

    static final String[] OFFICIAL_FIELDS = {"setter_invoked", "setter_confirmed"};
    private static JSONObject evidence(Committer committer) {
        try { return committer == null ? new JSONObject() : merge(new JSONObject(), committer.evidence()); }
        catch (Exception | LinkageError ignored) { return new JSONObject(); }
    }
    private static JSONObject merge(JSONObject into, JSONObject evidence) throws org.json.JSONException {
        if (evidence != null) for (String key : OFFICIAL_FIELDS) if (evidence.has(key)) into.put(key, evidence.get(key));
        return into;
    }
    private void observeEvidence(String event, String id, JSONObject actual, Throwable error) {
        try {
            JSONObject fields = merge(new JSONObject().put("command", "CONSUME").put("authorized", true), actual);
            if (id != null) fields.put("request_id", id);
            if (error != null) fields.put("exception_class", error.getClass().getSimpleName()).put("error", String.valueOf(error.getMessage()));
            observer.record(event, fields);
        } catch (Exception | LinkageError ignored) { }
    }

    JSONObject nativeRequest(JSONObject request, int pid, long startTime) throws IOException {
        try { return nativeRequestChecked(request, pid, startTime); }
        catch (IOException | RuntimeException | LinkageError failure) {
            try {
                JSONObject fields = new JSONObject().put("native_event", request.optString("event"))
                    .put("engine_pid", pid).put("engine_start", startTime).put("boot_id", boot).put("source_slot", source)
                    .put("exception_class", failure.getClass().getSimpleName()).put("error", String.valueOf(failure.getMessage()));
                observer.record("native_store_error", fields);
            } catch (Exception | LinkageError ignored) { }
            throw failure;
        }
    }

    private JSONObject nativeRequestChecked(JSONObject request, int pid, long startTime) throws IOException {
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
            JSONObject before = snapshot(plan);
            JSONObject response = new JSONObject().put("protocol", 1).put("nonce", nonce).put("ok", false)
                .put("decision", "WAIT_BEGIN").put("source_slot", source);
            if (plan == null) return observedNative(request, response, null, null, pid, startTime);
            validate(plan);
            if (!current(plan)) return observedNative(request, response.put("decision", "STALE_BOOT_OR_SOURCE"), before, plan, pid, startTime);
            String cycle = plan.getString("cycle_id");
            response.put("cycle_id", cycle);
            if ("HELLO".equals(event)) return response.put("ok", true).put("decision", "READY").put("phase", plan.optString("phase", "UNKNOWN"));
            if (!cycle.equals(request.optString("cycle_id"))) return observedNative(request, response.put("decision", "STALE_CYCLE"), before, plan, pid, startTime);
            int target = request.optInt("target_slot", -1);
            if (!number(request.opt("target_slot"),1-source) || target != 1 - source) return observedNative(request, response.put("decision", "INVALID_TARGET"), before, plan, pid, startTime);
            if ("DEFER".equals(event)) {
                if (plan.getInt("target_slot") >= 0 && (plan.getInt("engine_pid") != pid || plan.getLong("engine_start") != startTime)) return observedNative(request, response.put("decision", "ENGINE_CHANGED"), before, plan, pid, startTime);
                if(plan.getInt("target_slot") < 0){
                    plan.put("target_slot", target).put("engine_pid", pid).put("engine_start", startTime).put("phase", "DEFERRED").put("pending_activation", true).put("completed", false);
                    access.write(plan);
                } else if(plan.getInt("target_slot")!=target)return observedNative(request, response.put("decision","INVALID_TARGET"), before, plan, pid, startTime);
                else if (!(plan.has("completed") ? plan.getBoolean("completed") : "COMMITTED".equals(plan.optString("phase"))) &&
                        !Boolean.TRUE.equals(plan.opt("pending_activation"))) {
                    plan.put("pending_activation", true).put("completed", false); access.write(plan);
                }
                // Duplicate/concurrent CompletePostinstall calls must never fall
                // through to the original HAL after this cycle was deferred.
                // Reuse its durable ACK without erasing an actually completed activation.
                return observedNative(request, response.put("ok", true).put("decision", "DEFER").put("target_slot", target).put("durable", true), before, plan, pid, startTime);
            }
            if (plan.getInt("target_slot") < 0 || plan.getInt("engine_pid") != pid ||
                    plan.getLong("engine_start") != startTime || plan.getInt("target_slot") != target) return observedNative(request, response.put("decision", "NO_MATCHING_DEFER"), before, plan, pid, startTime);
            if ("DONE".equals(event) && !number(request.opt("code"),0)) return observedNative(request, response.put("decision", "INVALID_COMPLETION_CODE"), before, plan, pid, startTime);
            String diagnosticPhase = "DONE".equals(event) ? "DONE" : "FAILED";
            if (diagnosticPhase.equals(plan.optString("phase")) && number(plan.opt("completion_code"), request.optInt("code", -1)))
                return observedNative(request, response.put("ok", true).put("decision", "DONE".equals(event) ? "DONE" : "FAIL").put("target_slot", target).put("durable", true), before, plan, pid, startTime);
            plan.put("phase", "DONE".equals(event) ? "DONE" : "FAILED").put("completion_code", request.optInt("code", -1));
            access.write(plan);
            return observedNative(request, response.put("ok", true).put("decision", "DONE".equals(event) ? "DONE" : "FAIL").put("target_slot",target).put("durable",true), before, plan, pid, startTime);
        }));
    }

    private static JSONObject snapshot(JSONObject plan) {
        if (plan == null) return null;
        try { return new JSONObject(plan.toString()); } catch (Exception ignored) { return null; }
    }

    private String observed(String command, String id, String marker, JSONObject before, JSONObject after, String response) {
        observe("ota_plan_result", command, id, marker, before, after, response, null);
        return response;
    }

    private void observe(String event, String command, String id, String marker, JSONObject before, JSONObject after, String response, Throwable failure) {
        try {
            JSONObject fields = new JSONObject().put("command", command).put("boot_id", boot).put("source_slot", source);
            if (id != null) fields.put("request_id", id);
            if (marker != null) fields.put("CONSUME".equals(command) ? "marker_after" : "marker_before", new JSONObject(marker));
            if ("ota_permission_granted".equals(event)) fields.put("authorized", true);
            if (before != null) fields.put("plan_before", before);
            if (after != null) fields.put("plan_after", after);
            if (response != null) {
                JSONObject result = new JSONObject(response);
                fields.put("response_ok", result.opt("ok")).put("response_state", result.opt("state"))
                    .put("decision", result.optString("decision")).put("reason", result.optString("error"));
                merge(fields, result);
                for (String key : new String[] {"authorized", "action_completed", "activation_performed", "recovered", "registry_warning"})
                    if (result.has(key)) fields.put(key, result.get(key));
            }
            if (failure != null) fields.put("exception_class", failure.getClass().getSimpleName()).put("error", String.valueOf(failure.getMessage()));
            observer.record(event, fields);
        } catch (Exception | LinkageError ignored) { /* Only the original transaction controls its result. */ }
    }

    private JSONObject observedNative(JSONObject request, JSONObject response, JSONObject before, JSONObject after, int pid, long start) {
        try {
            JSONObject fields = new JSONObject().put("native_event", request.optString("event"))
                .put("engine_pid", pid).put("engine_start", start).put("boot_id", boot).put("source_slot", source)
                .put("response_ok", response.opt("ok")).put("decision", response.optString("decision"));
            for (String key : new String[] {"cycle_id", "target_slot", "durable"}) if (response.has(key)) fields.put(key, response.get(key));
            if (request.has("code")) fields.put("code", request.get("code"));
            if (before != null) fields.put("plan_before", before);
            if (after != null) fields.put("plan_after", after);
            observer.record("native_plan_result", fields);
        } catch (Exception | LinkageError ignored) { }
        return response;
    }

    private JSONObject object() throws org.json.JSONException { return new JSONObject().put("schema", 1).put("boot_id", boot).put("source_slot", source); }
    private boolean current(JSONObject plan) { return boot.equals(plan.optString("boot_id")) && source == plan.optInt("source_slot", -1); }
    private static void validate(JSONObject plan) throws IOException {
        try {
            int source = plan.getInt("source_slot"), target = plan.getInt("target_slot");
            if (!number(plan.opt("schema"), 1) || !plan.getString("boot_id").matches("[0-9a-fA-F-]{36}") ||
                    !plan.getString("cycle_id").matches("[0-9a-fA-F-]{36}") || !number(plan.opt("source_slot"), source) ||
                    source < 0 || source > 1 || !number(plan.opt("target_slot"), target) || target < -1 || target > 1)
                throw new IOException("Invalid deferred activation metadata");
            if (target >= 0 && (target != 1 - source ||
                    (!Boolean.TRUE.equals(plan.opt("completed")) && plan.getInt("engine_pid") <= 0) || plan.getLong("engine_start") < 0))
                throw new IOException("Invalid deferred engine identity");
            for (String key : new String[] {"completed", "pending_activation"})
                if (plan.has(key) && !(plan.opt(key) instanceof Boolean)) throw new IOException("Invalid activation metadata flag");
        } catch (org.json.JSONException invalid) { throw new IOException("Invalid deferred activation metadata", invalid); }
    }
    private interface JsonAction<T> { T run() throws IOException, org.json.JSONException; }
    private static <T> T json(JsonAction<T> action) throws IOException {
        try { return action.run(); } catch (org.json.JSONException invalid) { throw new IOException("Deferred activation JSON rejected", invalid); }
    }
    private static boolean number(Object value,long expected){return value instanceof Number && ((Number)value).longValue()==expected && ((Number)value).doubleValue()==(double)expected;}
}
