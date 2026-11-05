package net.hawyn.hnubl.module;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent semantic identity validation and clean recovery sequencing, without Android/device entry points. */
public final class DiscoveryMappingCacheTest {
    private static final String APK = "a".repeat(64), ENGINE = "b".repeat(64), AGENT = "c".repeat(64), RUNTIME = "sdk:abi:firmware:vm-fixture";
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static JSONObject mapping() throws Exception {
        return new JSONObject().put("schema", 1).put("package", "com.hihonor.ouc").put("apk_sha256", APK)
            .put("discovery_complete", true).put("deoptimization_plan", new JSONObject().put("policy", "critical-dispatch-native-barrier-v1"))
            .put("hooks", new JSONArray().put(new JSONObject().put("role", "reboot_gate")
                .put("manual_values", new JSONArray().put("manual")).put("automatic_values", new JSONArray().put("auto")))
                .put(new JSONObject().put("role", "countdown")).put(new JSONObject().put("role", "payload_start"))
                .put(new JSONObject().put("role", "reboot_terminal").put("class_name", "framework.RebootFixture").put("method_name", "reboot")
                    .put("parameter_types", new JSONArray().put("java.lang.String")).put("return_type", "void").put("is_static", false)))
            .put("reboot_continuations", new JSONArray().put(new JSONObject().put("class_name", "fixture.NativeContinuation")
                .put("constructors", new JSONArray().put(new JSONObject().put("parameter_types", new JSONArray())))
                .put("callback", new JSONObject().put("method_name", "run").put("parameter_types", new JSONArray()).put("return_type", "void").put("is_static", false))));
    }
    private static JSONObject encoded() throws Exception { return DiscoveryMappingCache.encode(mapping(), ENGINE, APK, AGENT, RUNTIME); }
    private static DiscoveryMappingCache.Result inspect(JSONObject value) { return DiscoveryMappingCache.inspect(value, ENGINE, APK, AGENT, RUNTIME); }
    private static JSONObject invalidEvent() throws Exception { return new JSONObject().put("event", "cached_mapping_invalid")
        .put("stage", "critical_preflight").put("code", "method_contract_mismatch").put("reason", "fixture ABI changed"); }

    public static void main(String[] args) throws Exception {
        DiscoveryMappingCache.Result good = inspect(encoded());
        check(good.hit() && good.reason.equals("verified_descriptor_cache"), "exact engine/APK/agent/runtime cache is reusable");
        check(good.mapping.getString("apk_sha256").equals(APK), "mapping is decoded as semantic identity, not runtime pointers");
        check(!inspect(null).hit(), "missing cache triggers discovery");
        for (String[] mutation : new String[][] {{"engine_hash", "e".repeat(64)}, {"apk_hash", "d".repeat(64)},
                {"agent_hash", "f".repeat(64)}, {"runtime_identity", "other-runtime"}, {"cache_policy", "old-policy"},
                {"identity_encoding", "raw-art-pointer-v1"}, {"mapping_sha256", "0".repeat(64)}}) {
            check(!inspect(encoded().put(mutation[0], mutation[1])).hit(), "identity change invalidates " + mutation[0]);
        }
        check(inspect(encoded().put("agent_hash", "f".repeat(64))).reason.equals("agent_changed"), "agent-only update invalidates even if broker is byte-identical");
        check(inspect(encoded().put("runtime_identity", "other-runtime")).reason.equals("runtime_changed"), "runtime drift has an explicit diagnostic");
        check(!inspect(encoded().put("schema", 1)).hit(), "old shallow cache migrates through a fresh scan");
        check(!inspect(encoded().put("runtime_addresses", true)).hit(), "absolute-address caches are never treated as reusable");
        check(!inspect(encoded().put("mapping_json", encoded().getString("mapping_json") + " ")).hit(), "truncated or altered payload is rejected before using entries");
        JSONObject missingRole = mapping(); missingRole.getJSONArray("hooks").remove(3);
        check(!inspect(DiscoveryMappingCache.encode(missingRole, ENGINE, APK, AGENT, RUNTIME)).hit(), "cached coverage must still prove the critical terminal");
        JSONObject wrongPlan = mapping(); wrongPlan.getJSONObject("deoptimization_plan").put("policy", "old-global-policy");
        check(!inspect(DiscoveryMappingCache.encode(wrongPlan, ENGINE, APK, AGENT, RUNTIME)).hit(), "incompatible dispatch identity triggers discovery");
        for (String key : new String[] {"art_method", "jni_method_id", "absolute_address", "runtime_address", "method_handle", "native_handle", "pointer"}) {
            try { DiscoveryMappingCache.encode(mapping().put("nested", new JSONArray().put(new JSONObject().put(key, "0x12345678"))), ENGINE, APK, AGENT, RUNTIME);
                throw new AssertionError("persisted process pointer " + key); }
            catch (IllegalArgumentException expected) { checks++; }
        }

        CachedMappingRecovery policy = new CachedMappingRecovery();
        check(policy.request(false, APK, ENGINE, invalidEvent()).equals("fresh_mapping_not_cache"), "a fresh semantic failure never starts cache recovery");
        check(policy.request(true, APK, ENGINE, new JSONObject().put("event", "warning")).equals("unrelated_event"), "broker/warning events do not trigger rediscovery");
        JSONObject noStage = invalidEvent(); noStage.remove("stage");
        check(policy.request(true, APK, ENGINE, noStage).equals("incomplete_cache_failure"), "missing failure stage does not consume budget");
        JSONObject incomplete = invalidEvent(); incomplete.remove("code");
        check(policy.request(true, APK, ENGINE, incomplete).equals("incomplete_cache_failure") && policy.count() == 0, "missing typed proof preserves the retry budget");
        check(policy.request(true, APK, ENGINE, invalidEvent()).equals("rediscovery_scheduled") && policy.count() == 1, "first real cache mismatch schedules one rebuild");
        check(policy.request(true, APK, ENGINE, invalidEvent()).equals("rediscovery_already_attempted") && policy.count() == 1, "same APK/engine cannot loop on a newly reused PID");
        check(policy.request(true, "d".repeat(64), ENGINE, invalidEvent()).equals("rediscovery_scheduled"), "a different exact APK has its own recovery");

        MappingGeneration generations = new MappingGeneration();
        try { generations.assign(); throw new AssertionError("session accepted without a mapping"); }
        catch (IllegalStateException expected) { checks++; }
        generations.publish(false);
        MappingGeneration.Selection firstFresh = generations.assign();
        check(!firstFresh.cached && firstFresh.source.equals("fresh_discovery") && firstFresh.assignment == 1, "first PID after full discovery remains a fresh source");
        MappingGeneration.Selection laterPid = generations.assign();
        check(laterPid.cached && laterPid.source.equals("memory_cache") && laterPid.generation == firstFresh.generation, "a later PID reusing a fresh mapping is a memory-cache source");
        CachedMappingRecovery memoryRecovery = new CachedMappingRecovery();
        check(memoryRecovery.request(firstFresh.cached, APK, ENGINE, invalidEvent()).equals("fresh_mapping_not_cache"), "first fresh failure does not consume cache retry");
        check(memoryRecovery.request(laterPid.cached, APK, ENGINE, invalidEvent()).equals("rediscovery_scheduled"), "later new PID's memory-cache mismatch receives its one full rediscovery");
        check(generations.assignments() == 2, "keeping a selected session for Activity re-entry requires no new assignment");
        generations.publish(false);
        MappingGeneration.Selection rediscovered = generations.assign();
        check(rediscovered.generation > laterPid.generation && !rediscovered.cached && rediscovered.assignment == 1, "a real rediscovery resets generation use counts");
        generations.publish(true);
        MappingGeneration.Selection disk = generations.assign();
        check(disk.cached && disk.source.equals("disk_cache") && disk.assignment == 1, "first PID after a disk-cache hit is explicitly cache sourced");
        MappingGeneration.Selection diskLater = generations.assign();
        check(diskLater.cached && diskLater.source.equals("memory_cache") && diskLater.generation == disk.generation, "subsequent PIDs use in-memory mapping rather than claiming another disk read");

        for (String failure : new String[] {"", "stop", "publish", "start"}) {
            List<String> calls = new ArrayList<>();
            try {
                CachedMappingRecovery.rebuild(new CachedMappingRecovery.Rebuild() {
                    private void action(String name) throws Exception { calls.add(name); if (failure.equals(name)) throw new java.io.IOException("fixture-" + name); }
                    public void stopTarget() throws Exception { action("stop"); }
                    public void discoverAndPublish() throws Exception { action("publish"); }
                    public void startFreshTarget() throws Exception { action("start"); }
                });
                check(failure.isEmpty(), "only an entirely successful rebuild returns");
            } catch (java.io.IOException expected) { check(!failure.isEmpty(), "failure propagates rather than restarting with bad data"); }
            int expected = failure.equals("stop") ? 1 : failure.equals("publish") ? 2 : 3;
            check(calls.equals(List.of("stop", "publish", "start").subList(0, expected)), "fresh target starts only after clean stop and successful cache publication: " + failure);
        }

        List<JSONObject> events = new ArrayList<>();
        SessionEventJournal journal = new SessionEventJournal(123, 9001, "fixture-activation", ENGINE, (name, fields) -> {
            try { events.add(new JSONObject(fields.toString()).put("event", name)); } catch (Exception bad) { throw new AssertionError(bad); }
        });
        journal.mappingSource(true, disk.source, "verified_descriptor_cache", disk.generation, disk.assignment, 1);
        journal.residentReuse(1);
        journal.cacheValidation("critical_preflight", "method_contract_mismatch", "fixture", "rediscovery_scheduled");
        check(events.get(0).getBoolean("cache_hit") && events.get(0).getInt("session_start_count") == 1, "diagnostic records actual cache source and install count");
        check(events.get(0).getString("source").equals("disk_cache") && events.get(0).getLong("mapping_generation") == disk.generation, "source diagnostic distinguishes disk origin and exact generation");
        check(!events.get(1).getBoolean("hooks_reinstalled") && events.get(1).getLong("target_start") == 9001, "resident reuse is bound to exact PID/start and never claims reinjection");
        check(events.get(2).getString("decision").equals("rediscovery_scheduled") && !events.get(2).has("mapping"), "failure report has a clear reason without copying the mapping");
        System.out.println("DISCOVERY_MAPPING_CACHE_CHECKS=" + checks);
    }
}
