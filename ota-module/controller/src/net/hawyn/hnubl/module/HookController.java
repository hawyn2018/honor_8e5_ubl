package net.hawyn.hnubl.module;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.provider.Settings;
import net.hawyn.hnubl.discovery.OucDiscovery;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Root-side supervisor. Only the verified updater's own processes receive an agent. */
public final class HookController {
    private static final String TARGET = "com.hihonor.ouc";
    private static final File STATE = new File("/data/adb/hnubl");
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final Context context;
    private final File module;
    private final String activationId;
    private final String engineSha;
    private final OtaOptions.Active options;
    private String apkHash = "", appVersion = "";
    private JSONObject mapping;
    private ApkMetadata mappingMetadata;
    private String mappingError = "";
    private CriticalMappingPolicy.Failure criticalMappingFailure;
    private final CriticalMappingPolicy.StopThrottle criticalStopThrottle = new CriticalMappingPolicy.StopThrottle();
    private String metadataError = "";
    private boolean metadataAttempted;
    private long nextMetadataCheck;
    private int targetUid;
    private volatile boolean stopping;
    private boolean everAttached;
    private String lastStatus = "";
    private String lastHookError = "";
    private final Map<String, String> warnings = new LinkedHashMap<>();
    private OtaTransport otaTransport;
    private String brokerError = "";
    private long nextBrokerAttempt;
    private String phase = "initializing";
    private final DeferredSlotFiles deferredFiles;
    private final DeferredSlotStore deferredSlots;
    private final TypedSlotCommitter slotCommitter;
    private final NativeSlotRuntime nativeSlots;
    private final String nativeInitWarning;
    private ControllerReadiness readiness = new ControllerReadiness(false);
    private volatile String terminalError = "";
    private static final long METADATA_INTERVAL = TimeUnit.SECONDS.toNanos(10);
    private static final int CACHE_SCHEMA = 1;
    private static final int MAPPING_SCHEMA = 1;
    private boolean discoveryCached;
    private int scanCount;
    private ActivityManager activityManager;
    private volatile String processDiscoverySource = "initializing";
    private long nextFullProcScan;
    private static final long FULL_PROC_INTERVAL = TimeUnit.SECONDS.toNanos(10);

    private HookController(Context context, File module, String activationId) throws Exception {
        // Compatibility callers have no immutable snapshot digest to supply.
        // Capture their engine once here; never reread a replaced module jar later.
        this(context, module, activationId, sha256(new File(module.getCanonicalFile(), "broker.jar")));
    }

    private HookController(Context context, File module, String activationId, String engineSha) throws IOException {
        if (android.os.Process.myUid() != 0) throw new SecurityException("Root supervisor required");
        if (activationId == null || activationId.isEmpty()) throw new IllegalArgumentException("Activation ID required");
        if (engineSha == null || !engineSha.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Engine SHA-256 required");
        this.context = Objects.requireNonNull(context);
        this.module = module.getCanonicalFile();
        this.activationId = activationId;
        this.engineSha = engineSha.toLowerCase(Locale.ROOT);
        try { OtaEventJournal.context(activationId, this.engineSha, null, null); }
        catch (RuntimeException | LinkageError ignored) { }
        // Capture once per activation. Saving a WebUI choice cannot silently
        // change a running updater; boot/Apply prepares a new matching snapshot.
        this.options = OtaOptions.production().requireActive(activationId);
        DeferredSlotFiles files=null;DeferredSlotStore slots=null;TypedSlotCommitter committer=new TypedSlotCommitter();NativeSlotRuntime runtime=null;
        String nativeWarning="";
        try{
            files=new DeferredSlotFiles();
            BootIdentity boot=BootIdentity.current();
            try { OtaEventJournal.context(activationId,this.engineSha,boot.bootId,boot.sourceSlot); }
            catch (RuntimeException | LinkageError ignored) { }
            slots=new DeferredSlotStore(files,boot.bootId,boot.sourceSlot,OtaEventJournal::record);
            runtime=new NativeSlotRuntime(this.module,activationId,slots);
        }catch(IOException | LinkageError optional){nativeWarning="Optional native slot enhancement unavailable; file permission and original updater flow retained: "+optional;}
        if(slots==null&&files!=null){files.close();files=null;}
        this.deferredFiles=files;this.deferredSlots=slots;this.slotCommitter=committer;this.nativeSlots=runtime;
        this.nativeInitWarning=nativeWarning;
        try { OtaEventJournal.record("controller_slot_initialization", OtaEventJournal.fields(
            "native_available", slots != null, "reason", nativeWarning)); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) throw new SecurityException("Root supervisor required");
        if (args.length < 1) throw new IllegalArgumentException("Module directory required");
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        run(context, new File(args[0]), args.length > 1 && "--activate".equals(args[1]),
            args.length > 2 ? args[2] : "standalone");
    }

    /** Compatibility entry point; a resident ModuleMain should use launch(). */
    public static void run(Context context, File module, boolean activate) throws Exception {
        run(context, module, activate, "standalone");
    }

    public static void run(Context context, File module, boolean activate, String activationId) throws Exception {
        HookController controller = new HookController(context, module, activationId);
        Thread hook = new Thread(controller::shutdown, "hnubl-ouc-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try { controller.runLoop(activate); }
        catch (Exception failure) {
            controller.terminalError = "钩子服务已停止：" + failure;
            controller.stopTargetsAfterFailure();
            throw failure;
        }
        finally {
            controller.shutdown();
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) {}
        }
    }

    /** One worker in the existing VM; the returned disposer never joins while holding our lock. */
    public static Runnable launch(Context context, File module, boolean activate, String activationId) throws Exception {
        return launchController(new HookController(context, module, activationId), activate);
    }

    /** ModuleMain supplies the digest of the immutable jar actually backing this class loader. */
    public static Runnable launch(Context context, File module, boolean activate, String activationId, String engineSha) throws Exception {
        return launchController(new HookController(context, module, activationId, engineSha), activate);
    }

    private static Runnable launchController(HookController controller, boolean activate) {
        Thread worker = new Thread(() -> {
            try { controller.runLoop(activate); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Throwable failure) {
                controller.terminalError = "钩子服务已停止：" + failure;
                try { controller.writeStatus(false, controller.terminalError); } catch (Exception ignored) {}
                controller.stopTargetsAfterFailure();
            } finally { controller.shutdown(); }
        }, "hnubl-ouc-controller");
        worker.setDaemon(true);
        worker.start();
        return () -> {
            controller.shutdown(true);
            worker.interrupt();
            if (Thread.currentThread() == worker) return;
            boolean interrupted = false;
            while (worker.isAlive()) {
                try { worker.join(); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        };
    }

    private void runLoop(boolean activate) throws Exception {
        synchronized (this) {
            if (stopping) return;
            readiness = new ControllerReadiness(activate);
        }
        if (!STATE.isDirectory() && !STATE.mkdirs()) throw new IOException("Module state directory unavailable");
        phase = "正在定位软件更新功能";
        writeStatus(false, "");
        if(nativeSlots!=null)nativeSlots.start();
        // Apply the policy once without opening OUC. An idle Apply only replaces
        // this watcher; the user can open OUC later to receive the new agent.
        Thread settings = new Thread(() -> {
            try { disableAutomaticSettings(); }
            catch (Exception | LinkageError optional) {
                synchronized (HookController.this) {
                    if (!stopping) warnings.put("automatic_settings", optional.toString());
                }
            }
        }, "hnubl-ouc-optional-settings");
        settings.setDaemon(true); settings.start();
        if (activate) {
            command("am", "force-stop", "--user", "0", TARGET);
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(TARGET);
            if (launch == null || launch.getComponent() == null) throw new IOException("软件更新启动入口未找到");
            command("am", "start", "-W", "--user", "0", "-n", launch.getComponent().flattenToString());
        }
        while (!stopping) {
            boolean updaterRunning = false;
            try {
                refreshBroker();
                refreshMapping(false);
                Map<Integer, Long> alive = updaterProcesses();
                if ("proc_unavailable".equals(processDiscoverySource)) throw new IOException("Updater process observation unavailable");
                updaterRunning = !alive.isEmpty();
                boolean newProcess;
                synchronized (this) {
                    newProcess = alive.entrySet().stream().anyMatch(entry -> {
                        Session session = sessions.get(entry.getKey());
                        return session == null || session.startTime != entry.getValue();
                    });
                }
                // A newly observed PID gets a current PM identity before any injection.
                // The identity cache avoids hashing unchanged APK bytes on this path.
                if (newProcess && refreshMapping(true)) alive = updaterProcesses();
                if ("proc_unavailable".equals(processDiscoverySource)) throw new IOException("Updater process observation unavailable");
                updaterRunning = !alive.isEmpty();
                synchronized (this) {
                    if (stopping) break;
                    Iterator<Map.Entry<Integer, Session>> iterator = sessions.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<Integer, Session> entry = iterator.next();
                        if (!Objects.equals(alive.get(entry.getKey()), entry.getValue().startTime)) {
                            if (!entry.getValue().ready && entry.getValue().error.isEmpty())
                                lastHookError = "软件更新进程在钩子初始化完成前退出，请查看原生崩溃与模块日志";
                            entry.getValue().stop(); iterator.remove();
                        }
                    }
                    for (Map.Entry<Integer, Long> entry : alive.entrySet()) {
                        if (!sessions.containsKey(entry.getKey())) {
                            Session session = new Session(entry.getKey(), entry.getValue());
                            sessions.put(entry.getKey(), session);
                            startCriticalSession(session::start, failure ->
                                failSession(session, "Critical injector launch failed: " + failure));
                        }
                    }
                    boolean ready = !sessions.isEmpty();
                    String error = sessions.isEmpty() ? lastHookError : "";
                    for (Session session : sessions.values()) {
                        ready &= session.ready;
                        if (!session.error.isEmpty()) error = session.error;
                    }
                    if (ready) lastHookError = "";
                    phase = ready ? "ready" : updaterRunning ? "正在安装进程内钩子" :
                        readiness.awaitingRestart() ? "等待软件更新重新启动并完成钩子初始化" : "waiting_for_updater";
                    readiness.observe(updaterRunning, ready, !error.isEmpty());
                    writeStatus(ready, ready ? "" : error);
                }
            } catch (Throwable failure) {
                if (stopping) break;
                synchronized (this) { readiness.invalidate(); }
                if (failure instanceof CriticalMappingPolicy.Failure) {
                    updaterRunning = stopUnprotectedUpdater((CriticalMappingPolicy.Failure) failure);
                } else synchronized (this) { phase = "pending"; }
                writeStatus(false, "钩子适配未就绪：" + failure.toString());
                // Optional hooks, cache/PM I/O and transport failures never select the stop policy.
            }
            Thread.sleep(updaterRunning ? 1_000 : 2_000);
        }
    }

    interface SessionStarter { void start() throws Exception; }
    static void startCriticalSession(SessionStarter starter, java.util.function.Consumer<Throwable> failed) {
        try { starter.start(); }
        catch (Exception | LinkageError failure) { failed.accept(failure); }
    }

    private synchronized void refreshBroker() {
        if (otaTransport != null && otaTransport.ready()) return;
        long now = System.nanoTime();
        if (now < nextBrokerAttempt) return;
        nextBrokerAttempt = now + TimeUnit.SECONDS.toNanos(2);
        if (otaTransport != null) { otaTransport.close(); otaTransport = null; }
        try {
            otaTransport = new OtaTransport(context,deferredSlots,slotCommitter,nativeInitWarning);
            brokerError = "";
            warnings.remove("ota_transport");
        } catch (Exception pending) {
            String previous = brokerError;
            brokerError = pending.toString();
            warnings.put("ota_transport", brokerError);
            if (!previous.equals(brokerError)) {
                System.err.println("[OUC transport pending] " + brokerError);
                try { OtaEventJournal.record("ota_transport_initialization_error", OtaEventJournal.fields(
                    "exception_class", pending.getClass().getSimpleName(), "error", String.valueOf(pending.getMessage()))); }
                catch (RuntimeException | LinkageError ignored) { }
            }
        }
    }

    private boolean refreshMapping(boolean forceMetadata) throws Exception {
        long now = System.nanoTime();
        if (!forceMetadata && metadataAttempted && now < nextMetadataCheck) {
            if (mapping == null && criticalMappingFailure != null) throw criticalMappingFailure;
            if (!metadataError.isEmpty()) throw new IOException(metadataError);
            if (mapping == null) throwMappingUnavailable();
            return false;
        }
        metadataAttempted = true;
        nextMetadataCheck = now + METADATA_INTERVAL;
        ApkMetadata current;
        try { current = readMetadata(); metadataError = ""; }
        catch (Exception failure) {
            metadataError = failure.toString();
            // A transient PM read must not erase an already confirmed failed installation.
            if (mapping == null && criticalMappingFailure != null) throw criticalMappingFailure;
            throw failure;
        }
        if (current.equals(mappingMetadata)) {
            if (mapping == null) throwMappingUnavailable();
            return false;
        }
        criticalMappingFailure = null;
        criticalStopThrottle.clear();
        synchronized (this) {
            if (stopping) return false;
            readiness.invalidate();
            if (!sessions.isEmpty()) {
                sessions.values().forEach(Session::stop); sessions.clear();
                // An eternalized agent survives its injector. End the old process
                // before applying a different mapping, rather than stacking hooks.
                command("am", "force-stop", "--user", "0", TARGET);
            }
            writeStatus(false, "软件更新版本变化，重新定位功能");
        }
        boolean scanned = false;
        String analyzedHash = "";
        try {
            String hash = sha256(current.apk);
            analyzedHash = hash;
            JSONObject discovered = hash.equals(apkHash) && validMapping(mapping, hash) ? mapping :
                readDiscoveryCache(hash);
            boolean cached = discovered != null;
            if (!cached) {
                scanned = true;
                scanCount++;
                discovered = new JSONObject(OucDiscovery.discover(current.apk));
            }
            // Do not publish a mapping made from an APK replaced during analysis.
            if (!current.equals(readMetadata())) throw new IOException("Updater APK changed during semantic analysis");
            if (scanned) {
                CriticalMappingPolicy.Failure critical = CriticalMappingPolicy.inspect(discovered, hash);
                if (critical != null) {
                    try { atomic(new File(STATE, "discovery.json"), discovered.toString(2)); }
                    catch (Exception diagnostic) { System.err.println("[OUC critical discovery] export snapshot unavailable: " + diagnostic); }
                    OtaEventJournal.record("critical_mapping_failure", OtaEventJournal.fields("code", critical.code, "reason", critical.getMessage()));
                    throw critical;
                }
            }
            if (!validMapping(discovered, hash)) throw new IOException("Incomplete or mismatched semantic discovery: " + discovered);
            if (stopping) throw new InterruptedException("Controller disposed during semantic analysis");
            mapping = discovered;
            mappingMetadata = current;
            mappingError = "";
            criticalMappingFailure = null;
            criticalStopThrottle.clear();
            apkHash = hash; appVersion = current.version; targetUid = current.uid;
            discoveryCached = cached;
            atomic(new File(STATE, "discovery.json"), mapping.toString(2));
            if (scanned) writeDiscoveryCache(hash, discovered);
            return true;
        } catch (Exception failure) {
            // Failed/ambiguous mappings are cached too. Reopening the same version
            // must not trigger another full SHA/DEX pass every polling interval.
            mapping = null;
            mappingMetadata = current;
            mappingError = failure.toString();
            criticalMappingFailure = failure instanceof CriticalMappingPolicy.Failure ? (CriticalMappingPolicy.Failure)failure : null;
            if (!analyzedHash.isEmpty()) apkHash = analyzedHash;
            discoveryCached = false;
            appVersion = current.version; targetUid = current.uid;
            throw failure;
        } finally {
            // Release a newly built DEX graph once. Cache hits and idle polling
            // neither create a graph nor force an unrelated root request to pause.
            if (scanned) System.gc();
        }
    }

    private void throwMappingUnavailable() throws IOException {
        if (criticalMappingFailure != null) throw criticalMappingFailure;
        throw new IOException(mappingError);
    }

    /** An explicit failed critical installation is not a file-permission decision. */
    private synchronized boolean stopUnprotectedUpdater(CriticalMappingPolicy.Failure failure) {
        if (stopping) return false;
        phase = failure.code;
        Map<Integer, Long> alive = null;
        try { alive = updaterProcesses(); }
        catch (IOException | RuntimeException | LinkageError observation) {
            OtaEventJournal.record("critical_mapping_observation_error", OtaEventJournal.fields("code", failure.code, "error", observation.toString()));
        }
        boolean running = alive == null || !alive.isEmpty();
        if (!criticalStopThrottle.due(alive, System.nanoTime())) return running;
        for (Session session : sessions.values()) {
            try { session.stop(); }
            catch (RuntimeException | LinkageError cleanup) {
                OtaEventJournal.record("critical_mapping_cleanup_error", OtaEventJournal.fields("code", failure.code, "error", cleanup.toString()));
            }
        }
        sessions.clear();
        try {
            command("am", "force-stop", "--user", "0", TARGET);
            OtaEventJournal.record("critical_mapping_stop", OtaEventJournal.fields("code", failure.code,
                "reason", failure.getMessage(), "response_ok", true));
        } catch (Exception | LinkageError stopFailure) {
            OtaEventJournal.record("critical_mapping_stop", OtaEventJournal.fields("code", failure.code,
                "reason", failure.getMessage(), "response_ok", false, "error", stopFailure.toString()));
        }
        return running;
    }

    private static boolean validMapping(JSONObject candidate, String apkHash) {
        return candidate != null && Integer.valueOf(MAPPING_SCHEMA).equals(candidate.opt("schema")) &&
            TARGET.equals(candidate.optString("package")) && apkHash.equals(candidate.optString("apk_sha256")) &&
            Boolean.TRUE.equals(candidate.opt("discovery_complete")) &&
            (!candidate.has("ready") || Boolean.TRUE.equals(candidate.opt("ready"))) &&
            candidate.optJSONArray("hooks") != null && candidate.optJSONArray("hooks").length() > 0 &&
            CriticalMappingPolicy.coversCriticalRoles(candidate);
    }

    private JSONObject readDiscoveryCache(String apkHash) {
        File file = new File(STATE, "discovery-cache.json");
        try {
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
            JSONObject cache = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (!Integer.valueOf(CACHE_SCHEMA).equals(cache.opt("schema")) ||
                !engineSha.equals(cache.optString("engine_hash")) || !apkHash.equals(cache.optString("apk_hash"))) return null;
            JSONObject candidate = cache.optJSONObject("mapping");
            return validMapping(candidate, apkHash) ? candidate : null;
        } catch (Exception invalid) {
            // A stale/truncated cache is optional evidence, never a replacement
            // for semantic analysis of the installed updater.
            System.err.println("[OUC discovery cache] ignored: " + invalid);
            return null;
        }
    }

    private void writeDiscoveryCache(String apkHash, JSONObject discovered) {
        try {
            JSONObject cache = new JSONObject().put("schema", CACHE_SCHEMA).put("engine_hash", engineSha)
                .put("apk_hash", apkHash).put("mapping", discovered);
            atomic(new File(STATE, "discovery-cache.json"), cache.toString());
        } catch (Exception failure) {
            // This performance cache is best effort. Validated hooks still work
            // when the optional cache cannot be persisted.
            System.err.println("[OUC discovery cache] write failed: " + failure);
        }
    }

    private ApkMetadata readMetadata() throws Exception {
        ApplicationInfo app = context.getPackageManager().getApplicationInfo(TARGET, 0);
        PackageInfo info = context.getPackageManager().getPackageInfo(TARGET, 0);
        synchronized (this) {
            if (otaTransport != null && !otaTransport.matches(app)) {
                otaTransport.close(); otaTransport = null; nextBrokerAttempt = 0;
                refreshBroker();
            }
        }
        return new ApkMetadata(new File(app.sourceDir).getCanonicalFile(), info, app.uid);
    }

    private static final class ApkMetadata {
        final File apk;
        final long length, modified, versionCode, updated;
        final String version;
        final int uid;
        ApkMetadata(File apk, PackageInfo info, int uid) throws IOException {
            if (!apk.isFile()) throw new IOException("Updater APK unavailable");
            this.apk = apk; this.length = apk.length(); this.modified = apk.lastModified();
            this.versionCode = info.getLongVersionCode(); this.updated = info.lastUpdateTime;
            this.version = info.versionName == null ? "" : info.versionName; this.uid = uid;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ApkMetadata)) return false;
            ApkMetadata a = (ApkMetadata) other;
            return apk.equals(a.apk) && length == a.length && modified == a.modified && versionCode == a.versionCode &&
                updated == a.updated && version.equals(a.version) && uid == a.uid;
        }
        @Override public int hashCode() { return Objects.hash(apk, length, modified, versionCode, updated, version, uid); }
    }

    private Map<Integer, Long> updaterProcesses() throws IOException {
        // A single AMS query replaces opening cmdline for every PID on normal
        // firmware. A blank/filtered result is not proof that the updater exited.
        Map<Integer, Long> fromActivityManager = activityManagerProcesses();
        if (fromActivityManager != null && !fromActivityManager.isEmpty()) {
            processDiscoverySource = "activity_manager";
            return fromActivityManager;
        }
        Map<Integer, Long> known = knownProcesses();
        long now = System.nanoTime();
        if (!known.isEmpty() && now < nextFullProcScan) {
            processDiscoverySource = "proc_cached";
            return known;
        }
        Map<Integer, Long> scanned = scanProc();
        nextFullProcScan = now + FULL_PROC_INTERVAL;
        if (scanned == null) {
            processDiscoverySource = known.isEmpty() ? "proc_unavailable" : "proc_cached";
            return known;
        }
        processDiscoverySource = "proc_full";
        return scanned;
    }

    private Map<Integer, Long> activityManagerProcesses() {
        try {
            if (activityManager == null) activityManager = context.getSystemService(ActivityManager.class);
            if (activityManager == null) return null;
            List<ActivityManager.RunningAppProcessInfo> list = activityManager.getRunningAppProcesses();
            if (list == null || list.isEmpty()) return null;
            Map<Integer, Long> known = knownProcessStarts();
            Map<Integer, Long> found = new HashMap<>();
            byte[] prefix = new byte[TARGET.length() + 1];
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (info == null || info.pid <= 0 || info.uid != targetUid ||
                    !(TARGET.equals(info.processName) || info.processName != null && info.processName.startsWith(TARGET + ":"))) continue;
                try {
                    File entry = new File("/proc", Integer.toString(info.pid));
                    long started = startTime(entry);
                    // PID/startTime is sufficient for an already authenticated
                    // session. New or reused PIDs get a fresh proc identity too,
                    // closing the race between the AMS snapshot and /proc read.
                    if (!Objects.equals(known.get(info.pid), started)) {
                        if (!targetName(entry, prefix) || !targetOwner(entry) || startTime(entry) != started) continue;
                    }
                    found.put(info.pid, started);
                } catch (IOException | NumberFormatException exited) { /* Raced with process exit. */ }
            }
            // Do not let a filtered AMS snapshot silently drop a process already
            // proved by the proc fallback. A live omission makes this list partial.
            for (Map.Entry<Integer, Long> previous : known.entrySet()) {
                if (found.containsKey(previous.getKey())) continue;
                try {
                    if (startTime(new File("/proc", Integer.toString(previous.getKey()))) == previous.getValue()) return null;
                } catch (IOException | NumberFormatException exited) { /* Omission is consistent with exit. */ }
            }
            return found.isEmpty() ? null : found;
        } catch (RuntimeException restricted) {
            // Some OEMs filter this API for standalone app_process callers.
            return null;
        }
    }

    private synchronized Map<Integer, Long> knownProcessStarts() {
        Map<Integer, Long> known = new HashMap<>();
        sessions.forEach((pid, session) -> known.put(pid, session.startTime));
        return known;
    }

    private Map<Integer, Long> knownProcesses() {
        Map<Integer, Long> alive = new HashMap<>();
        for (Map.Entry<Integer, Long> known : knownProcessStarts().entrySet()) {
            try {
                long started = startTime(new File("/proc", Integer.toString(known.getKey())));
                if (started == known.getValue()) alive.put(known.getKey(), started);
            } catch (IOException | NumberFormatException exited) { /* A missing/reused PID must be rediscovered. */ }
        }
        return alive;
    }

    private Map<Integer, Long> scanProc() throws IOException {
        Map<Integer, Long> found = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return null;
        byte[] prefix = new byte[TARGET.length() + 1];
        for (File entry : entries) {
            if (!numeric(entry.getName())) continue;
            try {
                if (!targetName(entry, prefix) || !targetOwner(entry)) continue;
                found.put(Integer.parseInt(entry.getName()), startTime(entry));
            } catch (IOException | NumberFormatException ignored) { /* Exited while enumerating. */ }
        }
        return found;
    }

    private static boolean numeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        return true;
    }

    private static boolean targetName(File entry, byte[] prefix) throws IOException {
        int length = 0;
        try (InputStream input = new FileInputStream(new File(entry, "cmdline"))) {
            while (length < prefix.length) {
                int n = input.read(prefix, length, prefix.length - length);
                if (n <= 0) break;
                length += n;
            }
        }
        if (length < TARGET.length()) return false;
        for (int i = 0; i < TARGET.length(); i++) if (prefix[i] != TARGET.charAt(i)) return false;
        return length == TARGET.length() || prefix[TARGET.length()] == 0 || prefix[TARGET.length()] == ':';
    }

    private boolean targetOwner(File entry) throws IOException {
        try (BufferedReader input = new BufferedReader(new FileReader(new File(entry, "status")))) {
            String line;
            while ((line = input.readLine()) != null) if (line.startsWith("Uid:"))
                return Integer.parseInt(line.trim().split("\\s+")[1]) == targetUid;
        }
        return false;
    }

    private static long startTime(File entry) throws IOException {
        String stat = new String(Files.readAllBytes(new File(entry, "stat").toPath()), StandardCharsets.US_ASCII);
        int end = stat.lastIndexOf(')');
        if (end < 0) throw new IOException("Invalid process stat");
        String[] fields = stat.substring(end + 2).trim().split("\\s+");
        if (fields.length <= 19) throw new IOException("Incomplete process stat");
        return Long.parseLong(fields[19]);
    }

    private boolean sameProcess(Session session, String stage) {
        Exception failure = null;
        try {
            File entry = new File("/proc", Integer.toString(session.pid));
            if (targetName(entry, new byte[TARGET.length() + 1]) && targetOwner(entry) && startTime(entry) == session.startTime)
                return true;
        } catch (IOException | NumberFormatException gone) { failure = gone; }
        logIdentityMismatch(session, stage, failure);
        return false;
    }

    /** Diagnostic resampling only; it never authorizes or overrides the failed predicate above. */
    private void logIdentityMismatch(Session session, String stage, Exception failure) {
        try {
            File entry = new File("/proc", Integer.toString(session.pid));
            JSONObject sample = new JSONObject().put("event", "identity_mismatch").put("stage", stage)
                .put("pid", session.pid).put("expected_start", session.startTime).put("target_uid", targetUid)
                .put("process_discovery_source", processDiscoverySource).put("sample_nanos", System.nanoTime())
                .put("predicate_exception", failure == null ? JSONObject.NULL : failure.toString());
            try { sample.put("actual_start", startTime(entry)); }
            catch (Exception error) { sample.put("start_error", error.toString()); }
            try {
                byte[] raw = Files.readAllBytes(new File(entry, "cmdline").toPath());
                int length = 0; while (length < raw.length && raw[length] != 0) length++;
                sample.put("cmdline", new String(raw, 0, length, StandardCharsets.UTF_8)).put("cmdline_bytes", raw.length);
            } catch (Exception error) { sample.put("cmdline_error", error.toString()); }
            try (BufferedReader input = new BufferedReader(new FileReader(new File(entry, "status")))) {
                String line; boolean seen = false;
                while ((line = input.readLine()) != null) if (line.startsWith("Uid:")) {
                    sample.put("uid_line", line); seen = true; break;
                }
                if (!seen) sample.put("uid_error", "Uid line absent");
            } catch (Exception error) { sample.put("uid_error", error.toString()); }
            System.err.println("[OUC identity] " + sample);
        } catch (Exception diagnosticFailure) {
            System.err.println("[OUC identity] pid=" + session.pid + " stage=" + stage + " diagnostic=" + diagnosticFailure);
        }
    }

    private void disableAutomaticSettings() throws Exception {
        File original = new File(STATE, "settings-before-module.json");
        if (!original.exists()) {
            JSONObject saved = new JSONObject();
            saved.put("ota_disable_automatic_update", capture("settings", "--user", "0", "get", "global", "ota_disable_automatic_update").trim());
            saved.put("hn_system_update_auto_download", capture("settings", "--user", "0", "get", "global", "hn_system_update_auto_download").trim());
            saved.put("hn_system_night_upgrade_switch", capture("settings", "--user", "0", "get", "secure", "hn_system_night_upgrade_switch").trim());
            atomic(original, saved.toString());
        }
        // These are settings protocol keys, not code locations. Semantic getter hooks
        // remain responsible for matching a different updater implementation.
        // Standalone app_process is not registered as an app in ActivityManager.
        // The platform's settings CLI supplies its own provider access token.
        command("settings", "--user", "0", "put", "global", "ota_disable_automatic_update", "1");
        command("settings", "--user", "0", "put", "global", "hn_system_update_auto_download", "0");
        command("settings", "--user", "0", "put", "secure", "hn_system_night_upgrade_switch", "0");
    }

    private final class Session {
        final int pid;
        final long startTime;
        volatile boolean ready;
        volatile boolean stopped;
        volatile boolean eternalized;
        volatile String error = "";
        volatile Process injector;
        final AgentReadiness protection = new AgentReadiness();
        final SessionEventJournal journal;
        Session(int pid, long startTime) { this.pid = pid; this.startTime = startTime;
            this.journal = new SessionEventJournal(pid,startTime,activationId,engineSha,OtaEventJournal::record); }
        void start() throws Exception {
            JSONObject parameters = new JSONObject().put("mapping", mapping).put("activation_id", activationId)
                .put("forceFullPackage", options.forceFullPackage)
                .put("ota_socket", otaTransport == null ? "" : otaTransport.path);
            if (stopping || stopped) return;
            final Process process = new ProcessBuilder(new File(module, "bin/frida-inject").getPath(), "-p", Integer.toString(pid), "-e",
                "-s", new File(module, "agent.bundle.js").getPath(), "-P", parameters.toString())
                .redirectErrorStream(true).start();
            injector = process;
            journal.start();
            everAttached = true;
            Thread monitor = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (stopped || stopping) return;
                        System.err.println("[OUC " + pid + "] " + line);
                        try {
                            JSONObject envelope = new JSONObject(line);
                            if ("error".equals(envelope.optString("type"))) {
                                failSession(this, envelope.optString("description", "Agent error")); return;
                            }
                            JSONObject event = envelope.optJSONObject("payload");
                            if (event == null) continue;
                            synchronized (HookController.this) {
                                if (stopped || stopping || sessions.get(pid) != this) return;
                                protection.accept(event);
                                journal.accept(event);
                                if (!protection.error.isEmpty()) { failSession(this, protection.error); return; }
                            }
                        } catch (Exception ignored) { /* Native diagnostic, not a readiness event. */ }
                    }
                    int result = process.waitFor();
                    synchronized (HookController.this) {
                        if (stopped || stopping || sessions.get(pid) != this) return;
                        boolean matching = sameProcess(this, "injector-exit");
                        boolean protectedSession = protection.completed(result, matching) && error.isEmpty();
                        journal.injectorExit(result,matching,protectedSession);
                        if (protectedSession) {
                            // -e detached the host only after init resolved. The
                            // hooks now live in the updater until this PID exits.
                            eternalized = true; ready = true;
                        } else {
                            failSession(this, "注入初始化未完成（退出码 " + result + "，ready=" + protection.declaredReady + "）");
                        }
                    }
                } catch (Exception failure) {
                    failSession(this, failure.toString());
                } finally {
                    if (injector == process) injector = null;
                }
            }, "hnubl-ouc-agent-" + pid);
            monitor.setDaemon(true); monitor.start();
        }
        void stop() {
            journal.end(error.isEmpty()?"controller_cleanup_or_target_exit":"session_failed");
            stopped = true; ready = false;
            Process process = injector;
            injector = null;
            // This is only the short-lived injector. Eternalized code is removed
            // by an explicit controller/package shutdown, never a late destructor.
            if (process != null) {
                process.destroy();
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
            }
        }
    }

    private synchronized void failSession(Session session, String error) {
        if (stopping || session.stopped || sessions.get(session.pid) != session) return;
        session.ready = false;
        session.error = error;
        session.journal.failure(error);
        lastHookError = error;
        readiness.invalidate();
        // The lock serializes failure cleanup with controller disposal. An old
        // monitor cannot issue am after ModuleMain has disposed this controller.
        boolean alive = sameProcess(session, "failure-cleanup");
        try { session.stop(); }
        catch (RuntimeException | LinkageError cleanup) { session.journal.failure("Critical session cleanup failed: " + cleanup); }
        try { writeStatus(false, error); } catch (Exception ignored) {}
        if (alive && !stopping) {
            try { command("am", "force-stop", "--user", "0", TARGET); session.journal.targetStop(true,"critical_session_failure",null); }
            catch (Exception | LinkageError stopError) { session.journal.targetStop(false,"critical_session_failure",stopError.toString()); }
        }
    }

    private synchronized void stopTargetsAfterFailure() {
        if (stopping) return;
        sessions.values().forEach(Session::stop); sessions.clear();
    }

    private synchronized void writeStatus(boolean ready, String error) throws Exception {
        boolean transportReady = otaTransport != null && otaTransport.ready() && !stopping;
        boolean criticalReady = !sessions.isEmpty();
        boolean agentBrokerReady = !sessions.isEmpty();
        org.json.JSONArray warningValues = new org.json.JSONArray();
        warnings.values().forEach(warningValues::put);
        if(!nativeInitWarning.isEmpty())warningValues.put(nativeInitWarning);
        if(nativeSlots!=null&&!nativeSlots.warning().isEmpty())warningValues.put(nativeSlots.warning());
        if(deferredSlots!=null&&!deferredSlots.warning().isEmpty())warningValues.put(deferredSlots.warning());
        for (Session session : sessions.values()) {
            criticalReady &= session.protection.criticalReady && session.error.isEmpty() && !session.stopped;
            agentBrokerReady &= session.protection.brokerReady && session.error.isEmpty() && !session.stopped;
            session.protection.warnings.forEach(warningValues::put);
        }
        boolean brokerReady = transportReady && (!readiness.updaterRunning || agentBrokerReady);
        JSONObject status = new JSONObject().put("hook_ready", ready && criticalReady && brokerReady && !stopping).put("activation_id", activationId)
            .put("controller_ready", readiness.controllerReady && !stopping)
            .put("broker_ready", brokerReady).put("critical_ready", criticalReady && !stopping)
            .put("updater_running", readiness.updaterRunning && !stopping)
            .put("waiting_for_updater", readiness.waitingForUpdater && transportReady && !stopping)
            .put("warnings", warningValues).put("phase", phase)
            .put("ota_socket", otaTransport == null ? "" : otaTransport.path)
            .put("broker_error", brokerError)
            .put("native_slot_ready",nativeSlots!=null&&nativeSlots.ready()).put("native_slot_waiting",nativeSlots!=null&&nativeSlots.waiting())
            .put("app_version", appVersion).put("apk_sha256", apkHash).put("error", error)
            .put("engine_hash", engineSha).put("discovery_cached", discoveryCached).put("scan_count", scanCount)
            .put("force_full_package", options.forceFullPackage)
            .put("process_discovery_source", processDiscoverySource);
        status.put("critical_mapping_failure", criticalMappingFailure != null)
            .put("critical_mapping_reason", criticalMappingFailure == null ? "" : criticalMappingFailure.code);
        org.json.JSONArray pids = new org.json.JSONArray();
        List<Integer> ordered = new ArrayList<>(sessions.keySet()); Collections.sort(ordered);
        ordered.forEach(pids::put); status.put("pids", pids);
        String comparable = status.toString();
        if (comparable.equals(lastStatus)) return;
        status.put("updated_at", System.currentTimeMillis());
        atomic(new File(STATE, "status.json"), status.toString());
        lastStatus = comparable;
    }

    private void shutdown() { shutdown(false); }

    private synchronized void shutdown(boolean explicitReplacement) {
        if (stopping) return;
        stopping = true; sessions.values().forEach(Session::stop);
        readiness.invalidate();
        if (everAttached && explicitReplacement) {
            // -e means killing an injector is not an unload. Stop the target once
            // here, before ModuleMain launches a replacement controller.
            try { command("am", "force-stop", "--user", "0", TARGET);
                OtaEventJournal.record("controller_target_stop", OtaEventJournal.fields("phase", "controller_replacement", "response_ok", true)); }
            catch (Exception stopError) { OtaEventJournal.record("controller_target_stop", OtaEventJournal.fields("phase", "controller_replacement", "response_ok", false, "error", stopError.toString())); }
        }
        sessions.clear();
        if (otaTransport != null) { otaTransport.close(); otaTransport = null; }
        if(nativeSlots!=null)nativeSlots.close();if(deferredFiles!=null)deferredFiles.close();
        try { writeStatus(false, terminalError.isEmpty() ? "模块服务已停止" : terminalError); } catch (Exception ignored) {}
    }

    private static void command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        Thread drain = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) { byte[] bytes = new byte[8192]; while (stream.read(bytes) != -1) {} }
            catch (IOException ignored) {}
        }, "hnubl-ouc-command-output");
        drain.setDaemon(true); drain.start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Command timed out: " + args[0]);
            }
            if (process.exitValue() != 0) throw new IOException("Command failed: " + args[0]);
        } finally {
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
        }
    }

    private static String capture(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = stream.read(bytes)) != -1) output.write(bytes, 0, count);
            } catch (IOException ignored) { }
        }, "hnubl-ouc-settings-output");
        drain.setDaemon(true); drain.start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Command timed out: " + args[0]);
            }
            drain.join();
            if (process.exitValue() != 0) throw new IOException("Command failed: " + args[0]);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) { byte[] bytes = new byte[65536]; int n; while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n); }
        StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format("%02x", b & 255)); return out.toString();
    }

    private static void atomic(File file, String text) throws IOException {
        File temp = File.createTempFile(".hnubl-status-", ".tmp", STATE);
        try {
            try (FileOutputStream stream = new FileOutputStream(temp)) { stream.write(text.getBytes(StandardCharsets.UTF_8)); stream.getFD().sync(); }
            temp.setReadable(false, false); temp.setWritable(false, false); temp.setReadable(true, true); temp.setWritable(true, true);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { temp.delete(); }
    }
}
