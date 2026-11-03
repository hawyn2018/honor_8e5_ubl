package net.hawyn.hnubl.module;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Durable atomic record and per-transaction OS lock, shared by old/new controller loaders. */
final class DeferredSlotFiles implements DeferredSlotStore.Persistence, Closeable {
    private final SecureFs.Directory directory;
    private volatile boolean closed;
    private int transactions;

    DeferredSlotFiles() throws IOException { directory = SecureFs.privateMarkerDirectory(); }

    @Override public <T> T locked(DeferredSlotStore.Transaction<T> operation) throws IOException {
        return operate(operation, true);
    }

    @Override public <T> T diagnostic(DeferredSlotStore.Transaction<T> operation) throws IOException {
        return operate(operation, false);
    }

    private <T> T operate(DeferredSlotStore.Transaction<T> operation, boolean waitForLock) throws IOException {
        synchronized(this){if(closed)throw new IOException("Deferred slot store closed");transactions++;}
        try{return transaction(operation, waitForLock);}
        finally{synchronized(this){transactions--;if(closed&&transactions==0)directory.close();}}
    }

    private <T> T transaction(DeferredSlotStore.Transaction<T> operation, boolean waitForLock) throws IOException {
        FileDescriptor descriptor = SecureFs.openPrivateFile(directory, "deferred-slot.lock", OsConstants.O_RDWR, true);
        try (FileOutputStream lockStream = new FileOutputStream(descriptor)) {
            FileLock lock;
            for (;;) {
                if (closed) throw new IOException("Deferred slot store closed");
                try { lock = lockStream.getChannel().tryLock(); }
                catch (OverlappingFileLockException busy) { lock = null; }
                if (lock != null) break;
                if (!waitForLock) throw new IOException("Local diagnostic registry busy; official activation already confirmed");
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Deferred slot transaction interrupted", interrupted); }
            }
            try (FileLock held = lock) {
                return operation.run(new DeferredSlotStore.Access() {
                    @Override public JSONObject read() throws IOException { return readRecord(); }
                    @Override public void write(JSONObject value) throws IOException { writeRecord(value); }
                });
            }
        }
    }

    private JSONObject readRecord() throws IOException {
        FileDescriptor fd;
        try { fd = SecureFs.openPrivateFile(directory, "deferred-slot.json", OsConstants.O_RDONLY, false); }
        catch (IOException absent) { if (SecureFs.missing(absent)) return null; throw absent; }
        try (FileInputStream input = new FileInputStream(fd)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 16384) throw new IOException("Oversized deferred slot record");
                bytes.write(buffer, 0, count);
            }
            try { return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
            catch (org.json.JSONException invalid) { throw new IOException("Invalid deferred slot record JSON", invalid); }
        }
    }

    private void writeRecord(JSONObject value) throws IOException {
        String name = ".deferred-slot-" + UUID.randomUUID();
        String path = directory.child(name);
        try {
            FileDescriptor fd = SecureFs.openPrivateFile(directory, name, OsConstants.O_WRONLY | OsConstants.O_EXCL, true);
            try (FileOutputStream output = new FileOutputStream(fd)) {
                output.write(value.toString().getBytes(StandardCharsets.UTF_8)); output.flush(); Os.fsync(fd);
            }
            Os.rename(path, directory.child("deferred-slot.json")); Os.fsync(directory.fd);
        } catch (ErrnoException failure) { throw failure.rethrowAsIOException(); }
        finally { try { Os.remove(path); } catch (ErrnoException ignored) { } }
    }

    @Override public synchronized void close() { if(closed)return;closed = true;if(transactions==0)directory.close(); }
}
