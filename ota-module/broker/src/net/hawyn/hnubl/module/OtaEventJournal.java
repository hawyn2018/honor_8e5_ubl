package net.hawyn.hnubl.module;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Optional bounded diagnostics, never an additional OTA permission condition. */
final class OtaEventJournal {
    static final String PATH = "/data/adb/hnubl/logs/ota-events.jsonl";
    static final String ROTATED_PATH = PATH + ".1";
    static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int CAPACITY = 128;
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
        "command", "request_id", "attempt_id", "peer_pid", "peer_uid", "activation_id", "engine_sha256",
        "boot_id", "source_slot", "cycle_id", "engine_pid", "engine_start", "target_slot", "phase",
        "marker_before", "marker_after", "plan_before", "plan_after", "response_ok", "response_state",
        "decision", "reason", "exception_class", "error", "legacy_marker", "fallback_can_consume",
        "native_available", "durable", "native_event", "code", "status", "duration_ms",
        "client_stage", "authorized", "action_completed", "activation_performed",
        "setter_invoked", "setter_confirmed", "recovered", "registry_warning",
        "target_pid", "target_start", "deoptimized", "terminal_ready", "continuation_ready", "critical_ready", "broker_ready", "hook_ready",
        "terminal_count", "continuation_count", "same_process"));
    private static final Set<String> SNAPSHOT_FIELDS = new HashSet<>(Arrays.asList(
        "ok", "state", "decision", "schema", "boot_id", "source_slot", "cycle_id", "phase",
        "engine_pid", "engine_start", "target_slot", "completion_code", "commit_protocol", "pending_activation", "completed",
        "setter_invoked", "setter_confirmed", "recovered", "activation_source"));
    private static final Engine ENGINE = new Engine(new SecureSink(), CAPACITY, true);
    private static volatile JSONObject context = new JSONObject();

    private OtaEventJournal() { }

    static void context(String activation, String engineSha, String boot, Integer source) {
        try {
            JSONObject value = new JSONObject();
            if (activation != null) value.put("activation_id", activation);
            if (engineSha != null) value.put("engine_sha256", engineSha);
            if (boot != null) value.put("boot_id", boot);
            if (source != null) value.put("source_slot", source);
            context = value;
        } catch (Exception | LinkageError ignored) { /* Diagnostic context is optional. */ }
    }

    static void record(String event, JSONObject fields) {
        try {
            JSONObject safe = filtered(context, FIELDS, true);
            JSONObject supplied = filtered(fields, FIELDS, true);
            for (java.util.Iterator<String> keys = supplied.keys(); keys.hasNext();) {
                String key = keys.next(); safe.put(key, supplied.get(key));
            }
            ENGINE.offer(event, safe);
        } catch (Exception | LinkageError ignored) { /* Never replace an OTA result. */ }
    }

    static JSONObject fields(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2)
                if (pairs[i] instanceof String && pairs[i + 1] != null) result.put((String) pairs[i], pairs[i + 1]);
        } catch (Exception | LinkageError ignored) { }
        return result;
    }

    static JSONObject snapshot(JSONObject value) {
        try { return filtered(value, SNAPSHOT_FIELDS, false); }
        catch (Exception | LinkageError ignored) { return new JSONObject(); }
    }

    private static JSONObject filtered(JSONObject input, Set<String> allowed, boolean nested) throws Exception {
        JSONObject result = new JSONObject();
        if (input == null) return result;
        for (java.util.Iterator<String> keys = input.keys(); keys.hasNext();) {
            String key = keys.next(); if (!allowed.contains(key)) continue;
            Object value = input.opt(key);
            if (nested && value instanceof JSONObject && (key.startsWith("marker_") || key.startsWith("plan_"))) {
                result.put(key, filtered((JSONObject) value, SNAPSHOT_FIELDS, false));
            } else if (value == JSONObject.NULL || value instanceof Boolean || value instanceof Number) {
                result.put(key, value);
            } else if (value instanceof String) {
                String text = (String) value;
                // No arbitrary command, payload, nonce, credentials or whole request is an accepted field.
                result.put(key, text.length() > 768 ? text.substring(0, 768) : text);
            }
        }
        return result;
    }

    interface Sink { void append(byte[] line) throws Exception; }

    /** A deterministic manual-drain mode is used only by host tests. */
    static final class Engine {
        private final Sink sink;
        private final int capacity;
        private final boolean background;
        private final ArrayDeque<String> queue = new ArrayDeque<>();
        private final String writer = UUID.randomUUID().toString();
        private final int pid = safePid();
        private long sequence, dropped;
        private Thread worker;
        Engine(Sink sink, int capacity, boolean background) {
            this.sink = sink; this.capacity = capacity; this.background = background;
        }
        synchronized void offer(String event, JSONObject fields) {
            try {
                if (event == null || !event.matches("[a-z][a-z0-9_]{0,63}")) return;
                if (fields != null && "STATE".equals(fields.optString("command"))) return;
                JSONObject value = filtered(fields, FIELDS, true);
                value.put("schema", 1).put("event", event).put("timestamp_ms", System.currentTimeMillis())
                    .put("monotonic_ns", System.nanoTime()).put("writer_id", writer).put("sequence", ++sequence)
                    .put("pid", pid).put("thread_id", Thread.currentThread().getId());
                if (queue.size() >= capacity) { dropped++; return; }
                queue.addLast(value.toString());
                if (background && worker == null) {
                    worker = new Thread(this::run, "hnubl-ota-journal");
                    worker.setDaemon(true);
                    try { worker.start(); } catch (RuntimeException | LinkageError failed) { worker = null; throw failed; }
                }
                notifyAll();
            } catch (Exception | LinkageError ignored) { dropped++; }
        }
        private void run() {
            for (;;) {
                synchronized (this) {
                    if (queue.isEmpty()) {
                        try { wait(60_000); } catch (InterruptedException ignored) { }
                        if (queue.isEmpty()) { worker = null; return; }
                    }
                }
                drainOne();
            }
        }
        void drainOne() {
            String item; long lost;
            synchronized (this) {
                item = queue.pollFirst(); if (item == null) return;
                lost = dropped; dropped = 0;
            }
            try {
                JSONObject value = new JSONObject(item);
                if (lost != 0) value.put("dropped_event_count", lost);
                sink.append((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception | LinkageError ignored) {
                synchronized (this) { dropped += lost + 1; }
            }
        }
        synchronized int queued() { return queue.size(); }
        synchronized long dropped() { return dropped; }
        private static int safePid() {
            try { return android.os.Process.myPid(); }
            catch (RuntimeException | LinkageError unsupportedHost) { return -1; }
        }
    }

    private static final class SecureSink implements Sink {
        @Override public void append(byte[] line) throws Exception {
            try (SecureFs.Directory parent = SecureFs.privateMarkerDirectory();
                 SecureFs.Directory logs = SecureFs.childDirectory(parent, "logs", 0, true)) {
                FileDescriptor lockFd = SecureFs.openPrivateFile(logs, "ota-events.lock", OsConstants.O_RDWR, true);
                try (FileOutputStream lockFile = new FileOutputStream(lockFd)) {
                    FileLock lock = lockFile.getChannel().tryLock();
                    if (lock == null) throw new IOException("Diagnostic journal writer busy");
                    try (FileLock held = lock) {
                        FileDescriptor fd = SecureFs.openPrivateFile(logs, "ota-events.jsonl",
                            OsConstants.O_WRONLY | OsConstants.O_APPEND, true);
                        try (FileOutputStream output = new FileOutputStream(fd)) {
                            if (Os.fstat(fd).st_size + line.length > MAX_FILE_BYTES) {
                                output.close();
                                // Validate an existing destination; never traverse an unknown object.
                                FileDescriptor prior = SecureFs.openPrivateFile(logs, "ota-events.jsonl.1", OsConstants.O_WRONLY, true);
                                new FileOutputStream(prior).close();
                                Os.rename(logs.child("ota-events.jsonl"), logs.child("ota-events.jsonl.1"));
                                Os.fsync(logs.fd);
                                FileDescriptor next = SecureFs.openPrivateFile(logs, "ota-events.jsonl", OsConstants.O_WRONLY | OsConstants.O_APPEND, true);
                                try (FileOutputStream replacement = new FileOutputStream(next)) {
                                    replacement.write(line); replacement.flush(); Os.fsync(next);
                                }
                                Os.fsync(logs.fd); Os.fsync(parent.fd);
                                return;
                            }
                            output.write(line); output.flush(); Os.fsync(fd);
                            Os.fsync(logs.fd); Os.fsync(parent.fd);
                        }
                    }
                }
            } catch (ErrnoException failure) { throw failure.rethrowAsIOException(); }
        }
    }
}
