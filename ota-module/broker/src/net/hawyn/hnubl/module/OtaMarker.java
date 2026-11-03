package net.hawyn.hnubl.module;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.UUID;

final class OtaMarker implements Closeable {
    private final SecureFs.Directory directory;
    private final FileOutputStream lockStream;
    private final FileLock instanceLock;

    OtaMarker() throws IOException {
        directory = SecureFs.privateMarkerDirectory();
        FileOutputStream stream = null;
        FileLock acquired = null;
        try {
            FileDescriptor fd = SecureFs.openPrivateFile(directory, "broker.lock", OsConstants.O_RDWR, true);
            stream = new FileOutputStream(fd);
            FileChannel channel = stream.getChannel();
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Broker instance is already running");
            lockStream = stream;
            instanceLock = acquired;
        } catch (IOException | RuntimeException problem) {
            if (acquired != null) try { acquired.release(); } catch (IOException ignored) { }
            if (stream != null) try { stream.close(); } catch (IOException ignored) { }
            directory.close();
            throw problem;
        }
    }

    synchronized String request(String command) {
        try {
            if (command.equals("STATE")) {
                int value = read();
                return response(true, value, value == 1 ? "ALLOW" : "DENY");
            }
            if (command.equals("CONSUME")) {
                if (read() != 1) return response(true, 0, "DENY");
                writeZero(); // The original reboot may proceed only after the zero is durable.
                return response(true, 0, "ALLOW");
            }
            if (command.equals("RESET")) {
                writeZero();
                return response(true, 0, "DENY");
            }
            return response(false, 0, "DENY");
        } catch (IOException | RuntimeException failure) {
            if (!"STATE".equals(command)) try {
                OtaEventJournal.record("marker_transaction_failed", OtaEventJournal.fields("command", command,
                    "exception_class", failure.getClass().getSimpleName(), "error", String.valueOf(failure.getMessage())));
            } catch (RuntimeException | LinkageError ignored) { }
            return response(false, 0, "DENY");
        }
    }

    private int read() throws IOException {
        FileDescriptor fd;
        try {
            fd = SecureFs.openPrivateFile(directory, "freeotaok", OsConstants.O_RDONLY, false);
        } catch (IOException failure) {
            if (SecureFs.missing(failure)) return 0;
            throw failure;
        }
        try (FileInputStream input = new FileInputStream(fd)) {
            int first = input.read();
            int second = input.read();
            return first == '1' && second == -1 ? MarkerValue.strict(new byte[] { (byte) first }) : 0;
        }
    }

    private void writeZero() throws IOException {
        String temporary = ".freeotaok-" + UUID.randomUUID().toString();
        String path = directory.child(temporary);
        try {
            FileDescriptor fd = SecureFs.openPrivateFile(directory, temporary,
                    OsConstants.O_WRONLY | OsConstants.O_EXCL, true);
            try (FileOutputStream output = new FileOutputStream(fd)) {
                output.write('0');
                output.flush();
                Os.fsync(fd);
            }
            // rename replaces a directory entry, never a final symlink target.
            Os.rename(path, directory.child("freeotaok"));
            Os.fsync(directory.fd);
        } catch (ErrnoException failure) {
            throw failure.rethrowAsIOException();
        } finally {
            try { Os.remove(path); } catch (ErrnoException ignored) { }
        }
    }

    private static String response(boolean ok, int state, String decision) {
        return "{\"ok\":" + ok + ",\"state\":" + state + ",\"decision\":\"" + decision + "\"}\n";
    }

    @Override public synchronized void close() {
        try { instanceLock.release(); } catch (IOException ignored) { }
        try { lockStream.close(); } catch (IOException ignored) { }
        directory.close();
    }
}
