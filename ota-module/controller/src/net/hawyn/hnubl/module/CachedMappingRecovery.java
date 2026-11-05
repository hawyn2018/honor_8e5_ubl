package net.hawyn.hnubl.module;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** One semantic rediscovery per exact APK/engine per controller, never a re-injection loop. */
final class CachedMappingRecovery {
    private final Set<String> attempted = new HashSet<>();
    private int count;

    synchronized String request(boolean cached, String apkHash, String engineHash, JSONObject event) {
        if (event == null || !"cached_mapping_invalid".equals(event.optString("event"))) return "unrelated_event";
        if (!cached) return "fresh_mapping_not_cache";
        if (event.optString("stage").isEmpty() || event.optString("code").isEmpty()) return "incomplete_cache_failure";
        String key = apkHash + ":" + engineHash;
        if (!attempted.add(key)) return "rediscovery_already_attempted";
        count++;
        return "rediscovery_scheduled";
    }

    synchronized int count() { return count; }

    interface Rebuild {
        void stopTarget() throws Exception;
        void discoverAndPublish() throws Exception;
        void startFreshTarget() throws Exception;
    }

    /** No replacement attachment before clean-up and fresh semantic publication.
     * Persistent caching is best effort; a validated fresh in-memory mapping also suffices. */
    static void rebuild(Rebuild actions) throws Exception {
        actions.stopTarget();
        actions.discoverAndPublish();
        actions.startFreshTarget();
    }
}
