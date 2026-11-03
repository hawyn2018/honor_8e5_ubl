package net.hawyn.hnubl.module;

import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Transport diagnostics are pending/warnings, not a reason to kill the updater. */
final class AgentReadiness {
    boolean criticalReady, brokerReady, declaredReady;
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
            return;
        }
        if (!"ready".equals(kind) && !"critical_ready".equals(kind)) return;
        criticalReady = Boolean.TRUE.equals(event.opt("critical_ready"));
        brokerReady = Boolean.TRUE.equals(event.opt("broker_ready"));
        org.json.JSONArray values = event.optJSONArray("warnings");
        if (values != null) for (int i = 0; i < values.length(); i++) warnings.add(String.valueOf(values.opt(i)));
        declaredReady = "ready".equals(kind) && criticalReady && brokerReady &&
                Boolean.TRUE.equals(event.opt("hook_ready")) && error.isEmpty();
    }

    boolean completed(int exitCode, boolean sameProcess) {
        return exitCode == 0 && sameProcess && declaredReady && error.isEmpty();
    }
}
