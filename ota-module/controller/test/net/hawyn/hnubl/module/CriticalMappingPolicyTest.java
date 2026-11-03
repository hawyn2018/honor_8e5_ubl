package net.hawyn.hnubl.module;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure installation policy and PID throttle; no Android process or am command is executed. */
public final class CriticalMappingPolicyTest {
    private static final String HASH = "a".repeat(64);
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static JSONObject mapping() throws Exception {
        return new JSONObject().put("schema", 1).put("package", "com.hihonor.ouc").put("apk_sha256", HASH)
            .put("discovery_complete", true).put("critical_missing", new JSONArray()).put("optional_missing", new JSONArray())
            .put("hooks", new JSONArray().put(new JSONObject().put("role", "reboot_gate")
                .put("manual_values", new JSONArray().put("manual")).put("automatic_values", new JSONArray().put("auto")))
                .put(new JSONObject().put("role", "countdown")).put(new JSONObject().put("role", "payload_start"))
                .put(new JSONObject().put("role", "reboot_terminal").put("class_name", "FrameworkRebootFixture").put("method_name", "reboot")
                    .put("parameter_types", new JSONArray().put("java.lang.String")).put("return_type", "void").put("is_static", false)))
            .put("reboot_continuations", new JSONArray().put(new JSONObject().put("class_name", "NativeRebootContinuationFixture")
                .put("constructors", new JSONArray().put(new JSONObject().put("parameter_types", new JSONArray())))
                .put("callback", new JSONObject().put("method_name", "run").put("parameter_types", new JSONArray()).put("return_type", "void").put("is_static", false))));
    }
    public static void main(String[] args) throws Exception {
        JSONObject supported = mapping();
        check(CriticalMappingPolicy.inspect(supported, HASH) == null, "complete critical protection has no stop reason");
        for (String role : new String[] {"engine_lifecycle", "version_response", "version_cache_query", "full_package", "auto_download", "payload_complete"}) {
            JSONObject optional = mapping().put("optional_missing", new JSONArray().put(role));
            check(CriticalMappingPolicy.inspect(optional, HASH) == null, "optional feature missing is not a stop reason " + role);
        }
        for (String missing : new String[] {"manual_reboot_frontier", "automatic_reboot_frontier", "payload_start", "countdown", "reboot_terminal", "reboot_continuation"}) {
            JSONObject bad = mapping().put("discovery_complete", false).put("critical_missing", new JSONArray().put(missing));
            CriticalMappingPolicy.Failure failure = CriticalMappingPolicy.inspect(bad, HASH);
            check(failure != null && failure.code.equals("critical_mapping_missing") && failure.getMessage().contains(missing), "explicit critical reason is typed " + missing);
        }
        JSONObject ambiguous = mapping().put("discovery_complete", false).put("critical_missing", new JSONArray().put("asynchronous_reboot_frontier"));
        check(CriticalMappingPolicy.inspect(ambiguous, HASH).code.equals("critical_mapping_ambiguous"), "unproved async frontier is a typed ambiguity");
        for (int removed : new int[] {0, 1, 2, 3}) {
            JSONObject missing = mapping(); missing.getJSONArray("hooks").remove(removed);
            check(!CriticalMappingPolicy.coversCriticalRoles(missing) && CriticalMappingPolicy.inspect(missing, HASH) != null, "actual result must prove every critical frontier " + removed);
        }
        JSONObject noManual = mapping(); noManual.getJSONArray("hooks").getJSONObject(0).put("manual_values", new JSONArray());
        check(CriticalMappingPolicy.inspect(noManual, HASH).getMessage().contains("manual_reboot_frontier"), "manual coverage must be real");
        JSONObject noAuto = mapping(); noAuto.getJSONArray("hooks").getJSONObject(0).put("automatic_values", new JSONArray());
        check(CriticalMappingPolicy.inspect(noAuto, HASH).getMessage().contains("automatic_reboot_frontier"), "automatic coverage must be real");
        JSONObject timer = mapping(); timer.getJSONArray("hooks").getJSONObject(1).put("role", "countdown_timer");
        check(CriticalMappingPolicy.inspect(timer, HASH) == null, "structural CountDownTimer coverage is accepted");
        JSONObject oldCache = mapping(); oldCache.remove("reboot_continuations"); oldCache.getJSONArray("hooks").remove(3);
        check(!CriticalMappingPolicy.coversCriticalRoles(oldCache), "old cache without terminal and continuation is not protected");
        JSONObject badTerminal = mapping(); badTerminal.getJSONArray("hooks").getJSONObject(3).put("return_type", "boolean");
        check(CriticalMappingPolicy.inspect(badTerminal, HASH).getMessage().contains("reboot_terminal"), "terminal ABI must actually match");
        JSONObject contextOnly = mapping(); contextOnly.getJSONArray("hooks").getJSONObject(3).put("role", "reboot_context");
        check(CriticalMappingPolicy.inspect(contextOnly, HASH).getMessage().contains("reboot_terminal"), "optional context does not substitute for terminal protection");
        JSONObject badContinuation = mapping(); badContinuation.getJSONArray("reboot_continuations").getJSONObject(0).getJSONObject("callback").put("is_static", true);
        check(CriticalMappingPolicy.inspect(badContinuation, HASH).getMessage().contains("reboot_continuation"), "continuation ABI is required");
        for (JSONObject untrusted : new JSONObject[] {mapping().put("schema", 2), mapping().put("package", "other.app"),
                mapping().put("apk_sha256", "b".repeat(64)), mapping().put("discovery_complete", "false")}) {
            untrusted.put("critical_missing", new JSONArray().put("countdown"));
            check(CriticalMappingPolicy.inspect(untrusted, HASH) == null, "identity/type/cache errors are not confirmed critical mapping evidence");
        }
        check(CriticalMappingPolicy.inspect(null, HASH) == null, "missing cache/data is transient");
        JSONObject legacyOptional = mapping().put("discovery_complete", false).put("missing", new JSONArray().put("version_response"));
        legacyOptional.remove("critical_missing");
        check(CriticalMappingPolicy.inspect(legacyOptional, HASH) == null, "legacy optional diagnostic is not a stop reason");
        legacyOptional.put("missing", new JSONArray().put("countdown"));
        check(CriticalMappingPolicy.inspect(legacyOptional, HASH) != null, "legacy explicit critical list is recognized");
        check(!(new IOException("OTA endpoint pending") instanceof CriticalMappingPolicy.Failure), "ordinary transport error never selects typed stop branch");

        Method valid = HookController.class.getDeclaredMethod("validMapping", JSONObject.class, String.class); valid.setAccessible(true);
        check(Boolean.TRUE.equals(valid.invoke(null, mapping(), HASH)), "controller cache accepts complete critical mapping");
        check(Boolean.FALSE.equals(valid.invoke(null, noManual, HASH)), "controller ignores incomplete cache and must actually discover before classifying");

        CriticalMappingPolicy.StopThrottle throttle = new CriticalMappingPolicy.StopThrottle();
        long second = TimeUnit.SECONDS.toNanos(1);
        check(throttle.due(Map.of(101, 11L), 0), "first unsupported PID is stopped");
        check(!throttle.due(Map.of(101, 11L), second), "same surviving PID is throttled");
        check(throttle.due(Map.of(102, 12L), 2 * second), "reopened new PID bypasses old throttle");
        check(throttle.due(Map.of(102, 13L), 3 * second), "reused PID with a new start time is a new process");
        check(!throttle.due(Map.of(102, 13L), 4 * second), "new repeated PID still has a bounded retry");
        check(throttle.due(Map.of(102, 13L), 8 * second), "failed force-stop can be retried after five seconds");
        check(!throttle.due(Map.of(), 9 * second), "no running target means no force-stop");
        check(throttle.due(Map.of(102, 13L), 9 * second), "a reopened updater is checked again even after an empty sample");
        throttle.clear();
        check(throttle.due(null, -second), "unavailable process sampling does not erase an already confirmed critical failure");
        check(!throttle.due(null, 0), "unknown sampling retry is throttled too");
        throttle.clear();
        Map<Integer, Long> mutable = new HashMap<>(); mutable.put(201, 21L);
        check(throttle.due(mutable, 0), "process snapshot accepted"); mutable.put(202, 22L);
        check(throttle.due(mutable, second), "throttle snapshots maps instead of retaining mutable observations");
        System.out.println("CRITICAL_MAPPING_POLICY_CHECKS=" + checks);
    }
}
