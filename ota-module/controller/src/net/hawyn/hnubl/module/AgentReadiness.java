package net.hawyn.hnubl.module;

import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Transport diagnostics are pending/warnings, not a reason to kill the updater. */
final class AgentReadiness {
    boolean criticalReady, brokerReady, declaredReady, verifiedSelectiveMapping;
    String error = "";
    final Set<String> warnings = new LinkedHashSet<>();

    void accept(JSONObject event) {
        String kind = event.optString("event");
        if ("warning".equals(kind)) {
            warnings.add(event.toString());
            return;
        }
        if ("error".equals(kind)) {
            error = event.toString();
            declaredReady = false;
            verifiedSelectiveMapping = false;
            return;
        }
        if (!"ready".equals(kind) && !"critical_ready".equals(kind)) return;
        verifiedSelectiveMapping = false;
        boolean criticalDeclared = Boolean.TRUE.equals(event.opt("critical_ready"));
        JSONObject counts = event.optJSONObject("counts");
        boolean actualTerminal = Boolean.TRUE.equals(event.opt("deoptimized")) && Boolean.TRUE.equals(event.opt("terminal_ready")) &&
            Boolean.TRUE.equals(event.opt("continuation_ready")) && counts != null && positive(counts.opt("reboot_terminal")) && positive(counts.opt("reboot_continuation"));
        criticalReady = criticalDeclared && actualTerminal;
        if (criticalDeclared && !actualTerminal) {
            error = "Critical terminal/continuation/deoptimization installation proof is incomplete";
            declaredReady = false;
            return;
        }
        brokerReady = Boolean.TRUE.equals(event.opt("broker_ready"));
        org.json.JSONArray values = event.optJSONArray("warnings");
        if (values != null) for (int i = 0; i < values.length(); i++) warnings.add(String.valueOf(values.opt(i)));
        declaredReady = "ready".equals(kind) && criticalReady && brokerReady &&
                Boolean.TRUE.equals(event.opt("hook_ready")) && error.isEmpty();
        JSONObject deoptimization = event.optJSONObject("deoptimization");
        verifiedSelectiveMapping = declaredReady && deoptimization != null &&
                "selective_native_barrier".equals(deoptimization.optString("strategy")) &&
                Boolean.TRUE.equals(deoptimization.opt("ready")) &&
                Boolean.TRUE.equals(deoptimization.opt("native_barrier")) &&
                positive(deoptimization.opt("method_count"));
    }

    boolean completed(int exitCode, boolean sameProcess) {
        return exitCode == 0 && sameProcess && declaredReady && error.isEmpty();
    }
    private static boolean positive(Object value) {
        return value instanceof Number && ((Number)value).longValue() > 0 && ((Number)value).doubleValue() == (double)((Number)value).longValue();
    }
}
