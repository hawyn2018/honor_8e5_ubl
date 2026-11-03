package net.hawyn.hnubl.module;

import android.content.Context;
import android.net.LocalSocket;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    OtaTransport(Context context,DeferredSlotStore deferred,DeferredSlotStore.Committer committer,boolean fallbackCanConsume,String nativeWarning) throws Exception {
        this.deferred=deferred;this.committer=committer;
        this.fallback=new LegacyOtaFallback(fallbackCanConsume,nativeWarning);
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
        try (LocalSocket socket = connection) {
            socket.setSoTimeout(30000);
            policy.requireOta(socket.getPeerCredentials());
            String command = RootBroker.readOtaCommand(socket.getInputStream());
            byte[] response = (deferred==null?fallback.request(command,marker::request):deferred.ota(command,marker::request,committer)).getBytes(StandardCharsets.US_ASCII);
            socket.getOutputStream().write(response); socket.getOutputStream().flush();
        } catch (Exception | LinkageError failure) {
            System.err.println("[hnubl-module] OTA request closed: " + failure.getClass().getSimpleName());
        }
    }

    @Override public void close() {
        active = false;
        endpoint.close(); listener.interrupt();
        // Already accepted marker transactions finish under the resident lock.
        clients.shutdown();
    }
}
