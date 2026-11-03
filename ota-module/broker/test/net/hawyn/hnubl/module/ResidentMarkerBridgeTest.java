package net.hawyn.hnubl.module;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;

/** A separate legacy loader models only the resident ownership/transaction ABI. */
public final class ResidentMarkerBridgeTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Invalid bridge accepted");
    }

    private static URLClassLoader fixture(Path root, boolean explicit, boolean ambiguous) throws Exception {
        Files.createDirectories(root);
        Path source = root.resolve("RootBroker.java");
        String text = "package net.hawyn.hnubl.module;\n" +
            "class OtaMarker { static int constructed; int state=1; OtaMarker(){constructed++;}" +
            "synchronized String request(String c){if(c.equals(\"STATE\"))return \"state=\"+state;" +
            "if(c.equals(\"RESET\")){state=0;return \"DENY\";}if(c.equals(\"CONSUME\")&&state==1){state=0;return \"ALLOW\";}return \"DENY\";}}\n" +
            "public final class RootBroker { private static Thread[] listeners; private static OtaMarker marker;" +
            "public static volatile boolean rootInterrupted,acceptedClosed; public static Thread accepted;" +
            "public static void start(){marker=new OtaMarker(); final OtaMarker a=marker,b=" + (ambiguous ? "new OtaMarker()" : "a") + ";" +
            "Thread root=new Thread(()->{a.request(\"STATE\");try{Thread.sleep(Long.MAX_VALUE);}catch(InterruptedException e){rootInterrupted=true;}},\"hnubl-root-endpoint\");" +
            "Thread ota=new Thread(()->{b.request(\"STATE\");try{Thread.sleep(Long.MAX_VALUE);}catch(InterruptedException e){}},\"hnubl-ota-endpoint\");" +
            "accepted=new Thread(()->{try{Thread.sleep(Long.MAX_VALUE);}catch(InterruptedException e){acceptedClosed=true;}},\"accepted-root-install\");" +
            "root.setDaemon(true);ota.setDaemon(true);accepted.setDaemon(true);listeners=new Thread[]{root,ota};root.start();ota.start();accepted.start();}" +
            "public static int constructions(){return OtaMarker.constructed;}public static void one(){marker.state=1;}" +
            "public static void close(){for(Thread t:listeners)t.interrupt();accepted.interrupt();}" +
            (explicit ? "public static String requestOtaMarker(String command){return marker.request(command);}" : "") + "}";
        Files.writeString(source, text, StandardCharsets.UTF_8);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-d", root.toString(), source.toString());
        if (result != 0) throw new AssertionError("Fixture compile failed");
        URLClassLoader loader = new URLClassLoader(new java.net.URL[] { root.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
        loader.loadClass("net.hawyn.hnubl.module.RootBroker").getMethod("start").invoke(null);
        return loader;
    }

    private static void run(Path root, boolean explicit) throws Exception {
        try (URLClassLoader loader = fixture(root, explicit, false)) {
            Class<?> resident = loader.loadClass("net.hawyn.hnubl.module.RootBroker");
            try {
                ResidentMarkerBridge first = ResidentMarkerBridge.connect(loader);
                check(first.legacy != explicit, "Correct legacy/new ABI selected");
                check("state=1".equals(first.request("STATE")), "Bridge reaches resident marker");
                ResidentMarkerBridge second = ResidentMarkerBridge.connect(loader);
                check((Integer) resident.getMethod("constructions").invoke(null) == 1, "Hot upgrade never constructs another marker/lock");
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger allows = new AtomicInteger();
                AtomicInteger failures = new AtomicInteger();
                Thread[] callers = new Thread[16];
                for (int i = 0; i < callers.length; i++) {
                    final ResidentMarkerBridge bridge = (i & 1) == 0 ? first : second;
                    callers[i] = new Thread(() -> {
                        try { start.await(); if ("ALLOW".equals(bridge.request("CONSUME"))) allows.incrementAndGet(); }
                        catch (Exception error) { failures.incrementAndGet(); }
                    }); callers[i].start();
                }
                start.countDown(); for (Thread caller : callers) caller.join();
                check(allows.get() == 1 && failures.get() == 0, "Two controller bridges consume through the same synchronized marker exactly once");
                check("state=0".equals(second.request("STATE")), "Consumed state is shared");
                rejects(() -> first.request("STATE\n")); rejects(() -> first.request("EXEC")); rejects(() -> first.request(null));
                check(!(Boolean) resident.getField("rootInterrupted").get(null), "ROOT listener is untouched");
                check(!((Boolean) resident.getField("acceptedClosed").get(null)) && ((Thread) resident.getField("accepted").get(null)).isAlive(), "Accepted ROOT installation remains alive");
                check(resident.getClassLoader() != ResidentMarkerBridge.class.getClassLoader(), "Actual independent resident/controller class loaders");
            } finally { resident.getMethod("close").invoke(null); }
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        run(root.resolve("legacy"), false); run(root.resolve("explicit"), true);
        try (URLClassLoader loader = fixture(root.resolve("ambiguous"), false, true)) {
            Class<?> resident = loader.loadClass("net.hawyn.hnubl.module.RootBroker");
            try { rejects(() -> ResidentMarkerBridge.connect(loader));
                check(!(Boolean) resident.getField("rootInterrupted").get(null), "Ambiguous marker does not touch ROOT");
            } finally { resident.getMethod("close").invoke(null); }
        }
        System.out.println("RESIDENT_MARKER_BRIDGE_CHECKS=" + checks);
    }
}
