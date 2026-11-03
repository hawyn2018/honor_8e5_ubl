package net.hawyn.hnubl.module;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** A confirmed critical installation failure, separate from optional discovery and transport diagnostics. */
final class CriticalMappingPolicy {
    static final class Failure extends IOException {
        final String code;
        Failure(List<String> missing) {
            super(reason(missing) + ": " + String.join(",", missing));
            code = reason(missing);
        }
        private static String reason(List<String> missing) {
            for (String value : missing) if (value.contains("ambiguous") || value.contains("asynchronous"))
                return "critical_mapping_ambiguous";
            return "critical_mapping_missing";
        }
    }

    /** Call only for the actual discoverer's result, after checking the installed APK did not change. */
    static Failure inspect(JSONObject result, String apkHash) {
        if (result == null || !Integer.valueOf(1).equals(result.opt("schema")) ||
                !"com.hihonor.ouc".equals(result.optString("package")) || !apkHash.equals(result.optString("apk_sha256")) ||
                !(result.opt("discovery_complete") instanceof Boolean)) return null;
        Set<String> missing = new LinkedHashSet<>();
        JSONArray explicit = result.optJSONArray("critical_missing");
        if (explicit != null) collect(explicit, missing, false);
        else if (Boolean.FALSE.equals(result.opt("discovery_complete"))) collect(result.optJSONArray("missing"), missing, true);
        missing.addAll(coverageMissing(result));
        return missing.isEmpty() ? null : new Failure(new ArrayList<>(missing));
    }

    static boolean coversCriticalRoles(JSONObject result) { return coverageMissing(result).isEmpty(); }

    private static List<String> coverageMissing(JSONObject result) {
        boolean manual = false, automatic = false, countdown = false, payload = false, terminal = false;
        JSONArray hooks = result == null ? null : result.optJSONArray("hooks");
        if (hooks != null) for (int i = 0; i < hooks.length(); i++) {
            JSONObject hook = hooks.optJSONObject(i); if (hook == null) continue;
            String role = hook.optString("role");
            if ("reboot_gate".equals(role)) {
                JSONArray values = hook.optJSONArray("manual_values"); manual |= values != null && values.length() > 0;
                values = hook.optJSONArray("automatic_values"); automatic |= values != null && values.length() > 0;
            }
            countdown |= "countdown".equals(role) || "countdown_timer".equals(role);
            payload |= "payload_start".equals(role);
            if ("reboot_terminal".equals(role)) {
                JSONArray args = hook.optJSONArray("parameter_types");
                terminal |= !hook.optString("class_name").isEmpty() && !hook.optString("method_name").isEmpty() &&
                    "void".equals(hook.optString("return_type")) && Boolean.FALSE.equals(hook.opt("is_static")) &&
                    args != null && args.length() == 1 && "java.lang.String".equals(args.optString(0));
            }
        }
        List<String> missing = new ArrayList<>();
        if (!manual) missing.add("manual_reboot_frontier");
        if (!automatic) missing.add("automatic_reboot_frontier");
        if (!countdown) missing.add("countdown");
        if (!payload) missing.add("payload_start");
        if (!terminal) missing.add("reboot_terminal");
        if (!continuations(result)) missing.add("reboot_continuation");
        return missing;
    }

    private static boolean continuations(JSONObject result) {
        JSONArray values = result == null ? null : result.optJSONArray("reboot_continuations");
        if (values == null || values.length() == 0) return false;
        for (int i = 0; i < values.length(); i++) {
            JSONObject entry = values.optJSONObject(i); if (entry == null || entry.optString("class_name").isEmpty()) return false;
            JSONObject callback = entry.optJSONObject("callback");
            if (callback == null || callback.optString("method_name").isEmpty() || !"void".equals(callback.optString("return_type")) ||
                    !Boolean.FALSE.equals(callback.opt("is_static")) || callback.optJSONArray("parameter_types") == null ||
                    callback.optJSONArray("parameter_types").length() != 0) return false;
            JSONArray constructors = entry.optJSONArray("constructors");
            if (constructors == null || constructors.length() == 0) return false;
            for (int n = 0; n < constructors.length(); n++) {
                JSONObject constructor = constructors.optJSONObject(n);
                JSONArray args = constructor == null ? null : constructor.optJSONArray("parameter_types");
                if (args == null) return false;
                for (int k = 0; k < args.length(); k++) if (!(args.opt(k) instanceof String) || args.optString(k).isEmpty()) return false;
            }
        }
        return true;
    }

    private static void collect(JSONArray array, Set<String> output, boolean legacy) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i); if (!(value instanceof String) || !((String)value).matches("[a-zA-Z0-9_/-]{1,80}")) continue;
            String name = (String)value;
            if (!legacy || name.equals("reboot_gate") || name.equals("manual_reboot_frontier") || name.equals("automatic_reboot_frontier") ||
                    name.equals("countdown") || name.equals("countdown_timer") || name.equals("payload_start") || name.equals("asynchronous_reboot_frontier") ||
                    name.equals("reboot_terminal") || name.equals("reboot_continuation"))
                output.add(name);
        }
    }

    /** Retrying a surviving PID is bounded; a newly opened/reused PID is never hidden by that throttle. */
    static final class StopThrottle {
        private Map<Integer, Long> attempted = new HashMap<>();
        private long retryAt;
        private boolean attemptedOnce;
        boolean due(Map<Integer, Long> alive, long now) {
            if (alive != null && alive.isEmpty()) { attempted.clear(); return false; }
            boolean fresh = alive != null && alive.entrySet().stream().anyMatch(e -> !e.getValue().equals(attempted.get(e.getKey())));
            if (attemptedOnce && !fresh && now < retryAt) return false;
            attempted = alive == null ? new HashMap<>() : new HashMap<>(alive);
            attemptedOnce = true;
            retryAt = now + TimeUnit.SECONDS.toNanos(5);
            return true;
        }
        void clear() { attempted.clear(); retryAt = 0; attemptedOnce = false; }
    }
}
