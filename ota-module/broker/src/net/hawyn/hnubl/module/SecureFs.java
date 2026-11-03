package net.hawyn.hnubl.module;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Directory descriptors pin every parent; the final component is always opened NOFOLLOW. */
final class SecureFs {
    private SecureFs() {}

    static final class Directory implements Closeable {
        final FileDescriptor fd;
        private final String anchor;

        Directory(FileDescriptor fd) throws IOException {
            this.fd = fd;
            this.anchor = "/proc/self/fd/" + descriptorNumber(fd);
        }

        String child(String name) throws IOException {
            if (name.length() == 0 || name.equals(".") || name.equals("..") ||
                    name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) {
                throw new IOException("Invalid single path component");
            }
            return anchor + "/" + name;
        }

        @Override public void close() {
            closeQuietly(fd);
        }
    }

    static Directory openAbsoluteDirectory(String path) throws IOException {
        if (!path.startsWith("/")) throw new IOException("Absolute directory required");
        Directory current = globalRoot();
        try {
            for (String name : path.split("/")) {
                if (name.length() == 0) continue;
                Directory next = openDirectory(current.child(name));
                current.close();
                current = next;
            }
            return current;
        } catch (IOException | RuntimeException problem) {
            current.close();
            throw problem;
        }
    }

    static Directory privateAppSocketDirectory(String packageName, int uid) throws IOException {
        if (!packageName.matches("[a-zA-Z0-9_.]+")) throw new IOException("Invalid package");
        return privateAppSocketDirectoryAt("/data/user_de/0/" + packageName, uid);
    }

    static Directory privateAppSocketDirectoryAt(String dataDirectory, int uid) throws IOException {
        ApplicationStorage.requireDataDirectory(dataDirectory);
        try (Directory app = openAbsoluteDirectory(dataDirectory)) {
            checkOwner(app.fd, uid, false);
            try (Directory files = childDirectory(app, "files", uid, false)) {
                return childDirectory(files, "hnubl-module", uid, true);
            }
        }
    }

    static Directory privateMarkerDirectory() throws IOException {
        try (Directory adb = openAbsoluteDirectory("/data/adb")) {
            checkOwner(adb.fd, 0, false);
            return childDirectory(adb, "hnubl", 0, true);
        }
    }

    static Directory privateServiceSocketDirectory(String dataDirectory, int uid) throws IOException {
        try (Directory service = openAbsoluteDirectory(dataDirectory)) {
            checkOwner(service.fd, uid, true);
            return childDirectory(service, "hnubl-module", uid, true);
        }
    }

    static String contextType(Directory directory) throws IOException {
        try { return NativePolicyTypes.type(new String(Os.getxattr(directory.anchor, "security.selinux"), java.nio.charset.StandardCharsets.US_ASCII)); }
        catch (ErrnoException problem) { throw problem.rethrowAsIOException(); }
    }

    static StructStat trustedExecutable(String path) throws IOException {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) throw new IOException("Absolute executable required");
        try (Directory parent = openAbsoluteDirectory(path.substring(0, slash))) {
            FileDescriptor fd = null;
            try {
                fd = Os.open(parent.child(path.substring(slash + 1)), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
                StructStat stat = Os.fstat(fd);
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != 0 || (stat.st_mode & 0022) != 0 || (stat.st_mode & 0111) == 0) {
                    throw new IOException("Root-owned system executable required");
                }
                return stat;
            } catch (ErrnoException problem) { throw problem.rethrowAsIOException(); }
            finally { closeQuietly(fd); }
        }
    }

    static Directory childDirectory(Directory parent, String name, int uid, boolean enforcePrivate)
            throws IOException {
        String path = parent.child(name);
        boolean created = false;
        try {
            Os.mkdir(path, 0700);
            created = true;
        } catch (ErrnoException failure) {
            if (failure.errno != OsConstants.EEXIST) throw failure.rethrowAsIOException();
        }
        Directory directory = openDirectory(path);
        try {
            checkOwner(directory.fd, uid, true);
            boolean incompleteOwnership = uid != 0 && Os.fstat(directory.fd).st_uid == 0;
            if (created || enforcePrivate || incompleteOwnership) {
                Os.fchown(directory.fd, uid, uid);
                Os.fchmod(directory.fd, 0700);
            }
            if (uid != 0) inheritContext(parent.anchor, directory.anchor);
            return directory;
        } catch (ErrnoException failure) {
            directory.close();
            throw failure.rethrowAsIOException();
        } catch (IOException | RuntimeException failure) {
            directory.close();
            throw failure;
        }
    }

    static FileDescriptor openPrivateFile(Directory directory, String name, int flags, boolean create)
            throws IOException {
        FileDescriptor fd = null;
        try {
            fd = Os.open(directory.child(name), flags | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW |
                    (create ? OsConstants.O_CREAT : 0), 0600);
            StructStat stat = Os.fstat(fd);
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != 0 || stat.st_nlink != 1 ||
                    (stat.st_mode & 0777) != 0600) {
                throw new IOException("Private regular root-owned file required");
            }
            return fd;
        } catch (ErrnoException failure) {
            closeQuietly(fd);
            throw failure.rethrowAsIOException();
        } catch (IOException | RuntimeException failure) {
            closeQuietly(fd);
            throw failure;
        }
    }

    static void removeStaleSocket(Directory directory, String name, int uid) throws IOException {
        String path = directory.child(name);
        try {
            StructStat stat = Os.lstat(path);
            if (!OsConstants.S_ISSOCK(stat.st_mode) || (stat.st_uid != uid && stat.st_uid != 0)) {
                throw new IOException("Refusing non-socket endpoint");
            }
            Os.remove(path); // unlink never follows the final component.
        } catch (ErrnoException failure) {
            if (failure.errno != OsConstants.ENOENT) throw failure.rethrowAsIOException();
        }
    }

    static void finishSocket(Directory directory, String name, int uid) throws IOException {
        try {
            String path = directory.child(name);
            StructStat stat = Os.lstat(path);
            if (!OsConstants.S_ISSOCK(stat.st_mode)) throw new IOException("Missing filesystem socket");
            inheritContext(directory.anchor, path);
            // The caller temporarily owns the pinned parent as root while binding.
            // lchown additionally avoids following the last component.
            Os.lchown(path, uid, uid);
            Os.chmod(path, 0600);
        } catch (ErrnoException failure) {
            throw failure.rethrowAsIOException();
        }
    }

    static void directoryOwner(Directory directory, int uid) throws IOException {
        try {
            Os.fchown(directory.fd, uid, uid);
            Os.fchmod(directory.fd, 0700);
        } catch (ErrnoException failure) {
            throw failure.rethrowAsIOException();
        }
    }

    private static void inheritContext(String source, String target) throws IOException {
        try {
            // FD anchors pin the inode while applying the existing application's
            // SELinux label, including its user categories. No recursive relabel.
            byte[] label = Os.getxattr(source, "security.selinux");
            Os.setxattr(target, "security.selinux", label, 0);
        } catch (ErrnoException failure) {
            throw failure.rethrowAsIOException();
        }
    }

    static boolean missing(IOException failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ErrnoException && ((ErrnoException) cause).errno == OsConstants.ENOENT) return true;
            cause = cause.getCause();
        }
        return false;
    }

    static void closeQuietly(FileDescriptor fd) {
        if (fd == null) return;
        try { Os.close(fd); } catch (ErrnoException ignored) { }
    }

    private static Directory openDirectory(String path) throws IOException {
        FileDescriptor fd = null;
        try {
            fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK |
                    OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw new IOException("Directory required");
            return new Directory(fd);
        } catch (ErrnoException failure) {
            closeQuietly(fd);
            throw failure.rethrowAsIOException();
        } catch (IOException | RuntimeException failure) {
            closeQuietly(fd);
            throw failure;
        }
    }

    private static Directory globalRoot() throws IOException {
        // An app-authorized su process may retain Android's per-app mount view.
        // UID 0 does not make another application's data directories visible in
        // that view. Pin init's root once, then traverse every untrusted child
        // through directory FDs with NOFOLLOW as usual. This is the sole
        // intentional procfs magic-link follow, not a filesystem-path fallback.
        FileDescriptor fd = null;
        try {
            if (android.os.Process.myUid() != 0 || Os.stat("/proc/1").st_uid != 0 ||
                    !"/".equals(Os.readlink("/proc/1/root"))) {
                throw new IOException("Verified init root required");
            }
            fd = Os.open("/proc/1/root", OsConstants.O_RDONLY | OsConstants.O_NONBLOCK |
                    OsConstants.O_CLOEXEC, 0);
            StructStat pinned = Os.fstat(fd), actual = Os.stat("/proc/1/root");
            if (!OsConstants.S_ISDIR(pinned.st_mode) || pinned.st_uid != 0 ||
                    pinned.st_dev != actual.st_dev || pinned.st_ino != actual.st_ino) {
                throw new IOException("Init root identity changed");
            }
            return new Directory(fd);
        } catch (ErrnoException failure) {
            closeQuietly(fd);
            throw failure.rethrowAsIOException();
        } catch (IOException | RuntimeException failure) {
            closeQuietly(fd);
            throw failure;
        }
    }

    private static void checkOwner(FileDescriptor fd, int uid, boolean allowRoot) throws IOException {
        try {
            int actual = Os.fstat(fd).st_uid;
            if (actual != uid && !(allowRoot && actual == 0)) throw new IOException("Unexpected directory owner");
        } catch (ErrnoException failure) {
            throw failure.rethrowAsIOException();
        }
    }

    private static int descriptorNumber(FileDescriptor fd) throws IOException {
        // Android exposes the descriptor to framework processes as getInt$(). A
        // field fallback covers older libcore releases; it is never a path guess.
        try {
            Method method = FileDescriptor.class.getDeclaredMethod("getInt$");
            method.setAccessible(true);
            return (Integer) method.invoke(fd);
        } catch (ReflectiveOperationException | RuntimeException first) {
            for (String name : new String[] { "descriptor", "fd" }) {
                try {
                    Field field = FileDescriptor.class.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.getInt(fd);
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
            throw new IOException("File descriptor access is unavailable", first);
        }
    }
}
