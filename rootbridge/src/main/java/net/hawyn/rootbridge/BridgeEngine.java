package net.hawyn.rootbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class BridgeEngine {
    private static BridgeEngine instance;
    private static final long LIMIT = 32L * 1024 * 1024;
    private static final long TIMEOUT_MS = 120_000;
    private final File jobsDir;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Semaphore slots = new Semaphore(8);
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    static synchronized BridgeEngine get(Context context) {
        if (instance == null) instance = new BridgeEngine(context.getApplicationContext());
        return instance;
    }

    private BridgeEngine(Context context) {
        jobsDir = new File(context.getFilesDir(), "jobs");
        if (!jobsDir.isDirectory() && !jobsDir.mkdirs()) throw new IllegalStateException("Cannot create jobs directory");
        preferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        File[] previous = jobsDir.listFiles();
        if (previous != null) for (File directory : previous) {
            if (!directory.isDirectory() || !validId(directory.getName())) continue;
            try {
                Job job = new Job(directory.getName(), directory);
                JSONObject data = readJson(new File(directory, "status.json"));
                job.data = data;
                if (!data.optBoolean("finished", false)) {
                    job.data.put("state", "failed").put("finished", true).put("exitCode", JSONObject.NULL)
                        .put("error", "Application process ended before job completion").put("completedAt", System.currentTimeMillis());
                    persist(job);
                }
                jobs.put(job.id, job);
            } catch (Exception ignored) { /* Corrupt/incomplete jobs are never replayed. */ }
        }
    }

    boolean isEnabled() { return preferences.getBoolean("enabled", true); }
    void setEnabled(boolean enabled) { preferences.edit().putBoolean("enabled", enabled).apply(); }

    JSONObject submit(String command) {
        if (!isEnabled()) throw new IllegalStateException("Bridge is disabled");
        if (command == null || command.trim().isEmpty() || command.indexOf('\0') >= 0
                || command.getBytes(StandardCharsets.UTF_8).length > 32768) throw new IllegalArgumentException("Invalid command");
        if (!slots.tryAcquire()) throw new IllegalStateException("Job queue is full (8 outstanding jobs)");
        String id = UUID.randomUUID().toString();
        Job job = new Job(id, new File(jobsDir, id));
        try {
            if (!job.directory.mkdirs()) throw new IOException("Cannot create job directory");
            new FileOutputStream(new File(job.directory, "stdout")).close();
            new FileOutputStream(new File(job.directory, "stderr")).close();
            job.data.put("id", id).put("state", "queued").put("finished", false)
                .put("exitCode", JSONObject.NULL).put("error", JSONObject.NULL).put("submittedAt", System.currentTimeMillis());
            persist(job);
            jobs.put(id, job);
            worker.execute(() -> { try { run(job, command); } finally { slots.release(); } });
            return new JSONObject().put("id", id).put("state", "queued").put("finished", false);
        } catch (Exception e) {
            slots.release();
            throw new IllegalStateException("Job submission failed: " + e.getMessage(), e);
        }
    }

    JSONObject status(String id) {
        Job job = requireJob(id);
        synchronized (job) {
            try {
                JSONObject data = new JSONObject(job.data.toString());
                data.put("stdoutBytes", new File(job.directory, "stdout").length());
                data.put("stderrBytes", new File(job.directory, "stderr").length());
                return data;
            } catch (JSONException e) { throw new IllegalStateException(e); }
        }
    }

    File output(String id, String stream) {
        if (!"stdout".equals(stream) && !"stderr".equals(stream)) throw new IllegalArgumentException("Invalid output name");
        Job job = requireJob(id);
        synchronized (job) {
            if (!job.data.optBoolean("finished")) throw new IllegalArgumentException("Wait for finished=true before reading outputs");
        }
        return new File(job.directory, stream);
    }

    private Job requireJob(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid UUID");
        Job job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("Unknown job");
        return job;
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private void run(Job job, String command) {
        long deadline = android.os.SystemClock.elapsedRealtime() + TIMEOUT_MS;
        Process process = null;
        try {
            synchronized (job) {
                job.data.put("state", "checking_root").put("startedAt", System.currentTimeMillis());
                persist(job);
            }
            String su = findRoot(deadline);
            synchronized (job) { job.data.put("state", "running").put("suPath", su); persist(job); }
            // Only id probes may try another su path. A submitted command is executed exactly once.
            process = new ProcessBuilder(su, "-c", command).start();
            process.getOutputStream().close();
            AtomicReference<IOException> failure = new AtomicReference<>();
            try (FileOutputStream out = new FileOutputStream(new File(job.directory, "stdout"));
                 FileOutputStream err = new FileOutputStream(new File(job.directory, "stderr"))) {
                Thread outThread = pump(process.getInputStream(), out, LIMIT, failure, process);
                Thread errThread = pump(process.getErrorStream(), err, LIMIT, failure, process);
                boolean ended = process.waitFor(Math.max(1, deadline - android.os.SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                if (!ended) {
                    process.destroyForcibly();
                    closeQuietly(process.getInputStream());
                    closeQuietly(process.getErrorStream());
                    outThread.join(2000); errThread.join(2000);
                    throw new IOException("Job exceeded 120-second timeout");
                }
                // A descendant retaining stdout must not keep the bridge blocked indefinitely.
                long remaining = Math.max(1, deadline - android.os.SystemClock.elapsedRealtime());
                outThread.join(remaining);
                errThread.join(Math.max(1, deadline - android.os.SystemClock.elapsedRealtime()));
                if (outThread.isAlive() || errThread.isAlive()) {
                    closeQuietly(process.getInputStream()); closeQuietly(process.getErrorStream());
                    outThread.join(1000); errThread.join(1000);
                    throw new IOException("Output streams did not close within job timeout");
                }
                out.getFD().sync(); err.getFD().sync();
                if (failure.get() != null) throw failure.get();
                int code = process.exitValue();
                synchronized (job) {
                    job.data.put("exitCode", code).put("state", code == 0 ? "succeeded" : "failed")
                        .put("finished", true).put("completedAt", System.currentTimeMillis());
                    if (code != 0) job.data.put("error", "Command exited with status " + code);
                    persist(job);
                }
            }
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }
            for (String stream : new String[]{"stdout", "stderr"}) {
                try (FileOutputStream output = new FileOutputStream(new File(job.directory, stream), true)) {
                    output.getFD().sync();
                } catch (IOException ignored) { /* Preserve the original execution error. */ }
            }
            synchronized (job) {
                try {
                    job.data.put("state", "failed").put("finished", true)
                        .put("error", e.getClass().getSimpleName() + ": " + e.getMessage()).put("completedAt", System.currentTimeMillis());
                    if (process != null && !process.isAlive()) job.data.put("exitCode", process.exitValue());
                    persist(job);
                } catch (Exception persistError) { android.util.Log.e("RootBridge", "Status persistence failed", persistError); }
            }
        }
    }

    private String findRoot(long deadline) throws IOException, InterruptedException {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add("su");
        candidates.add("/data/adb/ksu/bin/su");
        candidates.add("/data/adb/magisk/bin/su");
        candidates.add("/data/adb/ap/bin/su");
        candidates.add("/system/xbin/su");
        candidates.add("/system/bin/su");
        candidates.add("/sbin/su");
        String lastError = "No su candidate returned UID 0";
        for (String candidate : candidates) {
            long remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new IOException("Root authorization exceeded 120-second timeout");
            Process probe = null;
            try {
                probe = new ProcessBuilder(candidate, "-c", "id -u").start();
                probe.getOutputStream().close();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                AtomicReference<IOException> failure = new AtomicReference<>();
                Thread stdout = pump(probe.getInputStream(), out, 65536, failure, probe);
                Thread stderr = pump(probe.getErrorStream(), err, 65536, failure, probe);
                if (!probe.waitFor(Math.min(30_000, remaining), TimeUnit.MILLISECONDS)) {
                    lastError = candidate + ": authorization timed out";
                    continue;
                }
                stdout.join(1000); stderr.join(1000);
                if (!stdout.isAlive() && !stderr.isAlive() && failure.get() == null
                        && probe.exitValue() == 0 && "0".equals(out.toString(StandardCharsets.UTF_8.name()).trim())) return candidate;
                lastError = candidate + ": did not return UID 0 (exit " + probe.exitValue() + ")";
            } catch (IOException e) { lastError = candidate + ": " + e.getMessage(); }
            finally {
                if (probe != null) {
                    probe.destroyForcibly();
                    closeQuietly(probe.getInputStream()); closeQuietly(probe.getErrorStream());
                }
            }
        }
        throw new IOException(lastError);
    }

    private static Thread pump(InputStream input, OutputStream output, long limit,
                               AtomicReference<IOException> failure, Process process) {
        Thread thread = new Thread(() -> {
            try (InputStream source = input) {
                byte[] buffer = new byte[32768]; long total = 0; int count;
                while ((count = source.read(buffer)) != -1) {
                    if (total + count > limit) throw new IOException("Output exceeded " + limit + " byte limit");
                    output.write(buffer, 0, count); total += count;
                }
                output.flush();
            } catch (IOException e) { failure.compareAndSet(null, e); process.destroyForcibly(); }
        }, "rootbridge-output");
        thread.setDaemon(true); thread.start(); return thread;
    }

    private static void closeQuietly(InputStream stream) { try { stream.close(); } catch (IOException ignored) {} }

    private static JSONObject readJson(File file) throws IOException, JSONException {
        try (FileInputStream input = new AtomicFile(file).openRead()) {
            return new JSONObject(readUtf8(input, 65536));
        }
    }

    static String readUtf8(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Text output exceeds display limit");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void persist(Job job) throws IOException, JSONException {
        job.data.put("stdoutBytes", new File(job.directory, "stdout").length());
        job.data.put("stderrBytes", new File(job.directory, "stderr").length());
        AtomicFile target = new AtomicFile(new File(job.directory, "status.json"));
        FileOutputStream output = target.startWrite();
        try {
            output.write(job.data.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            target.finishWrite(output);
        } catch (IOException e) { target.failWrite(output); throw e; }
    }

    private static final class Job {
        final String id;
        final File directory;
        JSONObject data = new JSONObject();
        Job(String id, File directory) { this.id = id; this.directory = directory; }
    }
}
