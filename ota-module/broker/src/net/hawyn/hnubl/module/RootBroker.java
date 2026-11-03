package net.hawyn.hnubl.module;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** app_process root service. No exported Android component or TCP listener. */
public final class RootBroker {
    private static Thread[] listeners;
    private static OtaMarker sharedMarker;
    private static final ExecutorService CLIENTS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "hnubl-broker-client");
        thread.setDaemon(false);
        return thread;
    });

    private RootBroker() { }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0 || args.length != 0) throw new SecurityException("Root service entry rejected");
        start(systemContext());
        Thread[] active;
        synchronized (RootBroker.class) { active = listeners; }
        for (Thread listener : active) listener.join();
    }

    /** Shared-VM entry: starts listeners once, without blocking the controller. */
    public static synchronized void start(Context context) throws IOException {
        if (Process.myUid() != 0) throw new SecurityException("Root service entry rejected");
        if (listeners != null) return;
        final PeerPolicy policy = new PeerPolicy(context);
        final OtaMarker marker = new OtaMarker(); // Holds an exclusive lifetime instance lock.
        sharedMarker = marker;
        Thread root = endpointLoop(policy, marker, false);
        root.start();
        // OTA transport belongs to the replaceable controller. ROOT remains in
        // this resident loader, including connections carrying module upgrades.
        listeners = new Thread[] { root };
        // No command or marker is retried at this level after it has been accepted.
    }

    /** Cross-loader marker ABI. No root command or arbitrary file operation. */
    public static synchronized String requestOtaMarker(String request) throws IOException {
        if (Process.myUid() != 0 || sharedMarker == null) throw new IOException("Resident marker unavailable");
        ResidentMarkerBridge.requireCommand(request);
        return sharedMarker.request(request);
    }

    private static Thread endpointLoop(final PeerPolicy policy, final OtaMarker marker, final boolean ota) {
        return new Thread(() -> {
            for (;;) {
                try {
                    int uid = ota ? policy.otaUid() : policy.rootUid();
                    String pkg = ota ? PeerPolicy.OTA_PACKAGE : PeerPolicy.ROOT_PACKAGE;
                    try (Endpoint endpoint = new Endpoint(pkg, uid, ota ? "ota.sock" : "root.sock")) {
                        System.out.println((ota ? "BROKER_OTA_READY=" : "BROKER_ROOT_READY=") + endpoint.path);
                        System.out.flush();
                        for (;;) {
                            LocalSocket connection = endpoint.server.accept();
                            try {
                                CLIENTS.execute(() -> serve(connection, policy, marker, ota));
                            } catch (RuntimeException failure) {
                                closeQuietly(connection);
                                throw failure;
                            }
                        }
                    }
                } catch (Exception failure) {
                    System.err.println("[hnubl-module] " + (ota ? "OTA" : "ROOT") + " endpoint unavailable: " +
                            failure.getClass().getSimpleName() + ": " + failure.getMessage());
                    try { Thread.sleep(2000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
        }, ota ? "hnubl-ota-endpoint" : "hnubl-root-endpoint");
    }

    private static void serve(LocalSocket connection, PeerPolicy policy, OtaMarker marker, boolean ota) {
        try (LocalSocket socket = connection) {
            // Covers stalled handshakes and a receiver that abandons its output.
            // It is not a deadline on a running command or partition transaction.
            socket.setSoTimeout(30000);
            if (ota) {
                policy.requireOta(socket.getPeerCredentials());
                String request = readOtaCommand(socket.getInputStream());
                byte[] result = marker.request(request).getBytes(StandardCharsets.US_ASCII);
                socket.getOutputStream().write(result);
                socket.getOutputStream().flush();
            } else {
                policy.requireRoot(socket.getPeerCredentials());
                OutputStream output = socket.getOutputStream();
                // Authentication is confirmed before the caller submits a command.
                output.write(RootWire.AUTH_MAGIC);
                output.flush();
                RootWire.serve(socket);
            }
        } catch (Exception failure) {
            // Errors do not authorize a fallback/replay. Do not log command text.
            System.err.println("[hnubl-module] " + (ota ? "OTA" : "ROOT") + " request closed: " +
                    failure.getClass().getSimpleName());
        }
    }

    static String readOtaCommand(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (;;) {
            int value = input.read();
            if (value == '\n') break;
            if (value == -1 || value < 32 || value > 126 || bytes.size() == 63) {
                throw new IOException("OTA command rejected");
            }
            bytes.write(value);
        }
        String command = bytes.toString("US-ASCII");
        if (command.startsWith("TRACE ")) { OtaClientTrace.parse(command); return command; }
        if (!command.equals("STATE") && !command.equals("RESET") && !command.equals("CONSUME") && !command.equals("BEGIN")) {
            throw new IOException("OTA command rejected");
        }
        return command;
    }

    private static Context systemContext() throws Exception {
        // Only this root broker's own runtime receives hidden-API exemptions.
        try {
            Class<?> runtimeType = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = runtimeType.getDeclaredMethod("getRuntime");
            Method exempt = runtimeType.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            exempt.invoke(getRuntime.invoke(null), (Object) new String[] { "L" });
        } catch (ReflectiveOperationException ignored) { }
        if (Looper.myLooper() == null) {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            else Looper.prepare();
        }
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method main = activityThread.getDeclaredMethod("systemMain");
        main.setAccessible(true);
        Object thread = main.invoke(null);
        Method system = activityThread.getDeclaredMethod("getSystemContext");
        system.setAccessible(true);
        return (Context) system.invoke(thread);
    }

    static final class Endpoint implements Closeable {
        final SecureFs.Directory directory;
        final LocalSocket owner;
        final LocalServerSocket server;
        final String path;
        private boolean closed;

        Endpoint(String pkg, int uid, String name) throws Exception {
            this("/data/user_de/0/" + pkg, uid, name, true);
        }

        Endpoint(String dataDirectory, int uid, String name, boolean actualDataDirectory) throws Exception {
            SecureFs.Directory pinned = SecureFs.privateAppSocketDirectoryAt(dataDirectory, uid);
            LocalSocket bound = null;
            LocalServerSocket listening = null;
            try {
                // Pin and temporarily own the parent throughout socket creation.
                // A caller can replace its own directory afterwards, but cannot
                // substitute a root server: the native client verifies peer UID 0.
                SecureFs.directoryOwner(pinned, 0);
                SecureFs.removeStaleSocket(pinned, name, uid);
                String address = pinned.child(name);
                bound = new LocalSocket(LocalSocket.SOCKET_STREAM);
                bound.bind(new LocalSocketAddress(address, LocalSocketAddress.Namespace.FILESYSTEM));
                listening = new LocalServerSocket(bound.getFileDescriptor());
                SecureFs.finishSocket(pinned, name, uid);
                SecureFs.directoryOwner(pinned, uid);
                directory = pinned;
                owner = bound;
                server = listening;
                path = dataDirectory + "/files/hnubl-module/" + name;
            } catch (Exception failure) {
                closeQuietly(listening);
                closeQuietly(bound);
                try { SecureFs.directoryOwner(pinned, uid); } catch (IOException ignored) { }
                pinned.close();
                throw failure;
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            closeQuietly(server);
            closeQuietly(owner);
            directory.close();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }
}
