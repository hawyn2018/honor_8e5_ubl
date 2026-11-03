package net.hawyn.hnubl.module;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import android.system.StructStat;
import dalvik.system.PathClassLoader;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;

/** One ART VM hosts the private broker and a replaceable updater-only controller. */
public final class ModuleMain {
    private static final File STATE = new File("/data/adb/hnubl");
    private static volatile Runnable stopController;
    private static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) throw new SecurityException("Module service requires UID 0");
        try {
            Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
            Object runtime = runtimeClass.getDeclaredMethod("getRuntime").invoke(null);
            runtimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                .invoke(runtime, (Object) new String[] { "L" });
        } catch (ReflectiveOperationException failure) {
            // Only this root VM uses framework bootstrap APIs. Never alter a global policy.
            System.err.println("Framework bootstrap reflection policy: " + failure);
        }
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        STATE.mkdirs();
        RootBroker.start(context);
        atomic(new File(STATE, "service.pid"), Integer.toString(android.os.Process.myPid()));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            Runnable previous = stopController; stopController = null;
            if (previous != null) previous.run();
        }));
        String lastId = "";
        String lastError = "";
        RequestStamp lastRequest = null;
        while (running) {
            String attemptedId = lastId;
            try {
                File requestFile = new File(STATE, "activation.request");
                StructStat stat = requestStat(requestFile);
                if (stat == null) {
                    lastRequest = null;
                } else {
                    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != 0 || (stat.st_mode & 0022) != 0)
                        throw new SecurityException("Activation request must be a root-owned regular file");
                    RequestStamp stamp = new RequestStamp(stat);
                    if (!stamp.equals(lastRequest)) {
                        // Remember the attempted inode even if its JSON is malformed.
                        // A new atomic mv has a new inode and is always examined.
                        lastRequest = stamp;
                        JSONObject request = new JSONObject(new String(Files.readAllBytes(requestFile.toPath()), StandardCharsets.UTF_8));
                        StructStat after = requestStat(requestFile);
                        if (after == null || !OsConstants.S_ISREG(after.st_mode) || after.st_uid != 0 ||
                            (after.st_mode & 0022) != 0 || !stamp.equals(new RequestStamp(after)))
                            throw new IOException("Activation request changed while reading");
                        String id = request.getString("activation_id");
                        if (!id.equals(lastId)) {
                            if (!id.matches("[A-Za-z0-9-]+")) throw new SecurityException("Activation ID rejected");
                            attemptedId = id;
                            File module = new File(request.getString("module_dir")).getCanonicalFile();
                            File jar = new File(module, "broker.jar");
                            if (Os.lstat(module.getPath()).st_uid != 0 || (Os.lstat(module.getPath()).st_mode & 0022) != 0 ||
                                Os.lstat(jar.getPath()).st_uid != 0 || (Os.lstat(jar.getPath()).st_mode & 0022) != 0)
                                throw new SecurityException("Module payload must be root-owned and not writable by other users");
                            Runnable previous = stopController; stopController = null;
                            if (previous != null) previous.run();
                            String engine = sha256(jar);
                            File runtimeDir = new File(STATE, "runtime");
                            runtimeDir.mkdirs();
                            File snapshot = new File(runtimeDir, "controller-" + engine + ".jar");
                            if (!snapshot.isFile() || !sha256(snapshot).equals(engine)) {
                                File temp = File.createTempFile(".controller-", ".jar", runtimeDir);
                                try {
                                    try (InputStream input = new FileInputStream(jar); FileOutputStream output = new FileOutputStream(temp)) {
                                        byte[] bytes = new byte[65536]; int count;
                                        while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                                        output.getFD().sync();
                                    }
                                    if (!sha256(temp).equals(engine)) throw new IOException("Module changed during activation");
                                    Os.chmod(temp.getPath(), 0600);
                                    Files.move(temp.toPath(), snapshot.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                                } finally { temp.delete(); }
                            }
                            // Parent is the boot loader, not the old module's PathClassLoader.
                            // New controller/discovery code can replace an installed version
                            // without terminating the broker that carries this activation request.
                            ClassLoader loader = new PathClassLoader(snapshot.getPath(), ClassLoader.getSystemClassLoader().getParent());
                            Class<?> controller = Class.forName("net.hawyn.hnubl.module.HookController", true, loader);
                            stopController = (Runnable) controller.getMethod("launch", Context.class, File.class, boolean.class, String.class, String.class)
                                .invoke(null, context, module, request.optBoolean("activate"), id, engine);
                            lastId = id;
                            lastError = "";
                            // One collection after a version switch releases the obsolete DEX scan graph.
                            System.gc();
                        }
                    }
                }
            } catch (Throwable failure) {
                String error = failure.toString();
                String errorKey = attemptedId + "\n" + error;
                if (!errorKey.equals(lastError)) {
                    atomic(new File(STATE, "status.json"), new JSONObject().put("hook_ready", false)
                        .put("activation_id", attemptedId).put("error", error).toString());
                    lastError = errorKey;
                }
            }
            Thread.sleep(1000);
        }
    }

    private static StructStat requestStat(File file) throws ErrnoException {
        try { return Os.lstat(file.getPath()); }
        catch (ErrnoException missing) {
            if (missing.errno == OsConstants.ENOENT) return null;
            throw missing;
        }
    }

    private static final class RequestStamp {
        private final long device, inode, size, modifiedSeconds, modifiedNanos;
        RequestStamp(StructStat stat) {
            device = stat.st_dev; inode = stat.st_ino; size = stat.st_size;
            modifiedSeconds = stat.st_mtim.tv_sec; modifiedNanos = stat.st_mtim.tv_nsec;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof RequestStamp)) return false;
            RequestStamp stamp = (RequestStamp) other;
            return device == stamp.device && inode == stamp.inode && size == stamp.size &&
                modifiedSeconds == stamp.modifiedSeconds && modifiedNanos == stamp.modifiedNanos;
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(device, inode, size, modifiedSeconds, modifiedNanos);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536]; int count;
            while ((count = input.read(bytes)) != -1) hash.update(bytes, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : hash.digest()) result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    private static void atomic(File file, String text) throws IOException {
        File temp = File.createTempFile(".hnubl-main-", ".tmp", STATE);
        try {
            try (FileOutputStream stream = new FileOutputStream(temp)) { stream.write(text.getBytes(StandardCharsets.UTF_8)); stream.getFD().sync(); }
            temp.setReadable(false, false); temp.setWritable(false, false); temp.setReadable(true, true); temp.setWritable(true, true);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { temp.delete(); }
    }
}
