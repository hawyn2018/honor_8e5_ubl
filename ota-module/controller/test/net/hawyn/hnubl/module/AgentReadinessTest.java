package net.hawyn.hnubl.module;

import org.json.JSONObject;

public final class AgentReadinessTest {
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static JSONObject ready(Object critical, Object broker, Object hook) throws Exception {
        return new JSONObject().put("event", "ready").put("critical_ready", critical).put("broker_ready", broker).put("hook_ready", hook);
    }
    public static void main(String[] args) throws Exception {
        AgentReadiness state = new AgentReadiness();
        state.accept(new JSONObject().put("event", "warning").put("message", "optional hook absent"));
        check(state.error.isEmpty() && state.warnings.size() == 1 && !state.completed(0, true), "warning is nonfatal and does not fabricate readiness");
        state.accept(ready(true, false, false).put("event", "critical_ready"));
        check(state.criticalReady && !state.brokerReady && state.error.isEmpty() && !state.completed(0, true), "critical gate while STATE pending is pending, not error");
        state.accept(ready(true, true, true));
        check(state.completed(0, true), "actual critical and broker ready may complete");
        check(!state.completed(1, true) && !state.completed(0, false), "injector failure or PID reuse never completes");
        check(state.warnings.size() == 1 && state.error.isEmpty(), "optional diagnostic remains visible after ready");
        for (JSONObject malformed : new JSONObject[] { ready("true", true, true), ready(true, "true", true), ready(true, true, "true"), new JSONObject().put("event", "ready") }) {
            AgentReadiness invalid = new AgentReadiness(); invalid.accept(malformed);
            check(!invalid.completed(0, true), "typed readiness required");
        }
        state.accept(new JSONObject().put("event", "error").put("message", "critical reboot gate bind failed"));
        check(!state.completed(0, true) && !state.error.isEmpty(), "real critical failure invalidates ready");
        state.accept(ready(true, true, true));
        check(!state.completed(0, true), "later ready cannot erase fatal critical integrity failure");
        System.out.println("AGENT_READINESS_CHECKS=" + checks);
    }
}
