package net.hawyn.hnubl.module;

import java.io.IOException;
import org.json.JSONObject;

final class MarkerStateProof {
    private MarkerStateProof() { }

    static void require(String response) throws IOException {
        try {
            JSONObject state = new JSONObject(response);
            Object value = state.opt("state");
            if (!Boolean.TRUE.equals(state.opt("ok")) || !(value instanceof Number) ||
                    (((Number) value).doubleValue() != 0 && ((Number) value).doubleValue() != 1)) {
                throw new IOException("Resident STATE proof rejected");
            }
            String decision = state.optString("decision", "");
            if (!(((Number) value).intValue() == 0 ? "DENY" : "ALLOW").equals(decision)) {
                throw new IOException("Inconsistent resident STATE decision");
            }
        } catch (org.json.JSONException failure) { throw new IOException("Invalid resident STATE proof", failure); }
    }
}
