package net.hawyn.hnubl.module;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Set;
import net.hawyn.hnubl.discovery.OucDiscovery;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persist DEX identities, never process-local ART/JNI pointers. No DEX graph is loaded on a hit. */
final class DiscoveryMappingCache {
    static final int SCHEMA = 2;
    private static final Set<String> PROCESS_ADDRESSES = Set.of("art_method", "jni_method_id", "absolute_address",
        "runtime_address", "method_handle", "native_handle", "pointer");

    static final class Result {
        final JSONObject mapping;
        final String reason;
        Result(JSONObject mapping, String reason) { this.mapping = mapping; this.reason = reason; }
        boolean hit() { return mapping != null; }
    }

    static JSONObject encode(JSONObject mapping, String engineHash, String apkHash, String agentHash, String runtimeIdentity) throws Exception {
        if (containsProcessAddress(mapping)) throw new IllegalArgumentException("Process-local address in discovery mapping");
        String payload = mapping.toString();
        return new JSONObject().put("schema", SCHEMA).put("engine_hash", engineHash).put("apk_hash", apkHash)
            .put("agent_hash", agentHash).put("runtime_identity", runtimeIdentity)
            .put("cache_policy", OucDiscovery.CACHE_POLICY).put("identity_encoding", "dex-descriptor-v1")
            .put("runtime_addresses", false).put("mapping_sha256", digest(payload)).put("mapping_json", payload);
    }

    static Result inspect(JSONObject cache, String engineHash, String apkHash, String agentHash, String runtimeIdentity) {
        try {
            if (cache == null) return new Result(null, "cache_absent");
            if (!Integer.valueOf(SCHEMA).equals(cache.opt("schema"))) return new Result(null, "cache_schema_changed");
            if (!engineHash.equals(cache.optString("engine_hash"))) return new Result(null, "engine_changed");
            if (!apkHash.equals(cache.optString("apk_hash"))) return new Result(null, "apk_changed");
            if (!agentHash.equals(cache.optString("agent_hash"))) return new Result(null, "agent_changed");
            if (!runtimeIdentity.equals(cache.optString("runtime_identity"))) return new Result(null, "runtime_changed");
            if (!OucDiscovery.CACHE_POLICY.equals(cache.optString("cache_policy"))) return new Result(null, "locator_policy_changed");
            if (!"dex-descriptor-v1".equals(cache.optString("identity_encoding")) ||
                    !Boolean.FALSE.equals(cache.opt("runtime_addresses"))) return new Result(null, "non_relocatable_identity");
            Object raw = cache.opt("mapping_json");
            if (!(raw instanceof String)) return new Result(null, "mapping_payload_missing");
            String payload = (String) raw;
            if (!digest(payload).equals(cache.optString("mapping_sha256"))) return new Result(null, "mapping_digest_mismatch");
            JSONObject mapping = new JSONObject(payload);
            if (containsProcessAddress(mapping)) return new Result(null, "process_address_rejected");
            if (!Integer.valueOf(1).equals(mapping.opt("schema")) || !"com.hihonor.ouc".equals(mapping.optString("package")) ||
                    !apkHash.equals(mapping.optString("apk_sha256")) || !Boolean.TRUE.equals(mapping.opt("discovery_complete")) ||
                    !CriticalMappingPolicy.coversCriticalRoles(mapping)) return new Result(null, "mapping_coverage_mismatch");
            JSONObject plan = mapping.optJSONObject("deoptimization_plan");
            if (plan == null || !"critical-dispatch-native-barrier-v1".equals(plan.optString("policy")))
                return new Result(null, "dispatch_policy_changed");
            return new Result(mapping, "verified_descriptor_cache");
        } catch (Exception invalid) { return new Result(null, "cache_invalid_" + invalid.getClass().getSimpleName()); }
    }

    private static boolean containsProcessAddress(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (PROCESS_ADDRESSES.contains(key) || containsProcessAddress(object.opt(key))) return true;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (containsProcessAddress(array.opt(i))) return true;
        }
        return false;
    }

    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
}
