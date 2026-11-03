package net.hawyn.hnubl.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** One activation-time observation, before disposing the previous controller. */
final class UpdaterProcessProbe {
    private static final byte[] TARGET = "com.hihonor.ouc".getBytes(StandardCharsets.US_ASCII);

    static boolean isRunning(Path proc) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(proc)) {
            for (Path entry : entries) {
                if (!numeric(entry.getFileName().toString())) continue;
                try {
                    if (isUpdater(Files.readAllBytes(entry.resolve("cmdline")))) return true;
                } catch (NoSuchFileException exited) { /* The process exited during the snapshot. */ }
                catch (IOException failure) {
                    // Linux can report ESRCH as a generic IOException when a
                    // process exits during read. Only a confirmed vanished PID
                    // may be ignored; permission/observation failures stay errors.
                    if (!Files.notExists(entry, LinkOption.NOFOLLOW_LINKS)) throw failure;
                }
            }
        }
        // An unreadable directory/cmdline is an error, not evidence of idle.
        return false;
    }

    static boolean isUpdater(byte[] cmdline) {
        if (cmdline.length < TARGET.length) return false;
        for (int i = 0; i < TARGET.length; i++) if (cmdline[i] != TARGET[i]) return false;
        return cmdline.length == TARGET.length || cmdline[TARGET.length] == 0 || cmdline[TARGET.length] == ':';
    }

    private static boolean numeric(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < '0' || name.charAt(i) > '9') return false;
        return true;
    }
}
