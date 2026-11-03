package net.hawyn.hnubl.module;

import java.io.IOException;
import org.json.JSONObject;
import org.json.JSONArray;

/** No local registry is not proof that an official unfinished OTA has no activation work. */
final class LegacyOtaFallback {
    private final String warning;
    private final DeferredSlotStore.Committer committer;
    private final DeferredSlotStore execution = DeferredSlotStore.unregistered(OtaEventJournal::record);
    LegacyOtaFallback(String warning) { this(warning, null); }
    LegacyOtaFallback(String warning, DeferredSlotStore.Committer committer) { this.warning = warning; this.committer = committer; }
    String request(String command, DeferredSlotStore.Marker marker) throws IOException { return request(command, marker, null); }
    String request(String command, DeferredSlotStore.Marker marker, String id) throws IOException {
        if ("STATE".equals(command)) return marker.request(command);
        if (!"BEGIN".equals(command) && !"RESET".equals(command) && !"CONSUME".equals(command)) throw new IOException("OTA command rejected");
        try {
            JSONObject response = new JSONObject("CONSUME".equals(command) ? execution.ota(command, marker, committer, id)
                : marker.request("RESET"));
            if (!warning.isEmpty()) {
                JSONArray warnings = response.optJSONArray("warnings"); if (warnings == null) warnings = new JSONArray();
                warnings.put(warning); response.put("warnings", warnings);
            }
            return response.toString();
        } catch (org.json.JSONException invalid) { throw new IOException("Invalid OTA fallback response", invalid); }
    }
}
