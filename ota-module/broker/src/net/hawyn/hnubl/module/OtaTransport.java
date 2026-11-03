package net.hawyn.hnubl.module;

import android.content.Context;
import android.net.LocalSocket;
import android.net.Credentials;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import org.json.JSONObject;

/** Replaceable OTA-only listener; never owns or closes a ROOT connection. */
final class OtaTransport implements Closeable {
    private final RootBroker.Endpoint endpoint;
    private final PeerPolicy policy;
    private final ResidentMarkerBridge marker;
    private final DeferredSlotStore deferred;
    private final DeferredSlotStore.Committer committer;
    private final LegacyOtaFallback fallback;
    private final ExecutorService clients = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "hnubl-ota-client"); thread.setDaemon(true); return thread;
    });
    private final Thread listener;
    private volatile boolean active;
    private volatile String error = "";
    private final int uid;
    private final String dataDirectory;
    final String path;

    OtaTransport(Context context,DeferredSlotStore deferred,DeferredSlotStore.Committer committer,String nativeWarning) throws Exception {
        this.deferred=deferred;this.committer=committer;
        this.fallback=new LegacyOtaFallback(nativeWarning,committer);
        policy = new PeerPolicy(context);
        marker = ResidentMarkerBridge.connect(ClassLoader.getSystemClassLoader());
        // A successful bind alone is not readiness: prove the same live marker
        // backend can perform STATE before publishing a ready idle watcher.
        MarkerStateProof.require(marker.request("STATE"));
        uid = policy.otaUid();
        dataDirectory = policy.otaDataDirectory();
        endpoint = new RootBroker.Endpoint(dataDirectory, uid, "ota.sock", true);
        path = ApplicationStorage.otaSocket(dataDirectory);
        active = true;
        listener = new Thread(this::acceptLoop, "hnubl-ota-controller-endpoint");
        listener.setDaemon(true);
        listener.start();
        System.out.println("BROKER_OTA_READY=" + path + " marker=" + (marker.legacy ? "resident-legacy" : "resident-v1"));
        System.out.flush();
        record("ota_transport_ready", null, null, null, null, null, null, null, 0);
    }

    boolean ready() { return active; }
    String error() { return error; }
    boolean matches(android.content.pm.ApplicationInfo application) {
        return uid == application.uid && dataDirectory.equals(application.dataDir);
    }

    private void acceptLoop() {
        try {
            while (active) {
                LocalSocket connection = endpoint.server.accept();
                try { clients.execute(() -> serve(connection)); }
                catch (RuntimeException rejected) { connection.close(); throw rejected; }
            }
        } catch (Exception | LinkageError failure) {
            if (active) error = failure.toString();
        } finally { active = false; endpoint.close(); clients.shutdown(); }
    }

    private void serve(LocalSocket connection) {
        String command = null, id = null;
        Credentials peer = null;
        JSONObject before = null;
        long started = System.nanoTime();
        try (LocalSocket socket = connection) {
            socket.setSoTimeout(30000);
            peer = socket.getPeerCredentials();
            policy.requireOta(peer);
            command = RootBroker.readOtaCommand(socket.getInputStream());
            if (command.startsWith("TRACE ")) {
                OtaClientTrace trace = OtaClientTrace.parse(command);
                recordTrace(trace, peer);
                socket.getOutputStream().write("{\"ok\":true}\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                return;
            }
            if (!"STATE".equals(command)) {
                id = UUID.randomUUID().toString();
                before = sampleMarker();
                record("ota_request_start", command, id, peer, before, null, null, null, 0);
            }
            String response = deferred==null?fallback.request(command,marker::request,id):deferred.ota(command,marker::request,committer,id);
            if (id != null) {
                record("ota_request_result", command, id, peer, before, sampleMarker(), response, null, System.nanoTime() - started);
                response = correlated(response, id);
            }
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
        } catch (Exception | LinkageError failure) {
            boolean tracing = command != null && command.startsWith("TRACE ");
            if (!"STATE".equals(command)) record("ota_transport_error", tracing ? "TRACE" : command, id, peer, before,
                tracing ? null : sampleMarker(), null, failure, System.nanoTime() - started);
            System.err.println("[hnubl-module] OTA request closed: " + failure.getClass().getSimpleName());
        }
    }

    private JSONObject sampleMarker() {
        try { return new JSONObject(marker.request("STATE")); }
        catch (Exception | LinkageError ignored) { return null; }
    }

    private static void recordTrace(OtaClientTrace trace, Credentials peer) {
        try { OtaEventJournal.record("ota_client_trace", OtaEventJournal.fields("command", "TRACE",
            "attempt_id", trace.attempt, "client_stage", trace.stage, "peer_pid", peer.getPid(), "peer_uid", peer.getUid())); }
        catch (RuntimeException | LinkageError ignored) { /* Diagnostics never replace the original updater flow. */ }
    }

    private static String correlated(String response, String id) {
        try { return new JSONObject(response).put("diagnostic_id", id).toString() + "\n"; }
        catch (Exception | LinkageError ignored) { return response; }
    }

    private void record(String event, String command, String id, Credentials peer, JSONObject before,
            JSONObject after, String response, Throwable error, long duration) {
        try {
            JSONObject fields = OtaEventJournal.fields("command", command, "request_id", id,
                "legacy_marker", marker.legacy, "native_available", deferred != null,
                "marker_before", before, "marker_after", after, "duration_ms", duration / 1_000_000L);
            if (peer != null) fields.put("peer_pid", peer.getPid()).put("peer_uid", peer.getUid());
            if (response != null) {
                JSONObject result = new JSONObject(response);
                fields.put("response_ok", result.opt("ok")).put("response_state", result.opt("state"))
                    .put("decision", result.optString("decision")).put("reason", result.optString("error"));
                if (result.has("reason")) fields.put("reason", result.optString("reason"));
                for (String key : new String[] {"authorized", "action_completed", "activation_performed", "phase", "cycle_id", "recovered", "registry_warning"})
                    if (result.has(key)) fields.put(key, result.get(key));
                for (String key : DeferredSlotStore.OFFICIAL_FIELDS) if (result.has(key)) fields.put(key, result.get(key));
            }
            if (error != null) fields.put("exception_class", error.getClass().getSimpleName()).put("error", String.valueOf(error.getMessage()));
            OtaEventJournal.record(event, fields);
        } catch (Exception | LinkageError ignored) { /* Diagnostic samples never authorize or replay an operation. */ }
    }

    @Override public void close() {
        active = false;
        endpoint.close(); listener.interrupt();
        // Already accepted marker transactions finish under the resident lock.
        clients.shutdown();
    }
}
