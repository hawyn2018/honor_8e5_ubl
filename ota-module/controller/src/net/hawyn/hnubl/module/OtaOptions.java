package net.hawyn.hnubl.module;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** Desired settings survive upgrades; active settings belong to one activation. */
public final class OtaOptions {
    public static final boolean DEFAULT_FORCE_FULL_PACKAGE = false;
    public static final boolean DEFAULT_PREFER_PATCH_PACKAGE = true;
    static final Path STATE = Paths.get("/data/adb/hnubl");
    static final String DESIRED = "ota-options.conf";
    static final String ACTIVE = "ota-options.active.conf";
    private static final String SCHEMA = "1";
    private static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "schema", "force_full_package", "prefer_patch_package", "activation_id")));

    private OtaOptions() {}

    public static final class Active {
        public final boolean forceFullPackage;
        public final boolean preferPatchPackage;
        public final String activationId;
        Active(boolean forceFullPackage, boolean preferPatchPackage, String id) {
            this.forceFullPackage = forceFullPackage;
            this.preferPatchPackage = preferPatchPackage;
            activationId = id;
        }
    }

    static final class Snapshot {
        final boolean desired, active, desiredPatch, activePatch, hasSavedValue, activeKnown;
        final String activationId, warning;
        Snapshot(boolean desired, boolean active, boolean desiredPatch, boolean activePatch,
                 boolean saved, boolean known, String id, String warning) {
            this.desired = desired; this.active = active; hasSavedValue = saved; activeKnown = known;
            this.desiredPatch = desiredPatch; this.activePatch = activePatch;
            activationId = id; this.warning = warning;
        }
        boolean pending() { return !activeKnown || desired != active || desiredPatch != activePatch; }
    }

    private static final class Parsed {
        final boolean value, patch, valid;
        final String id, error;
        Parsed(boolean value, boolean patch, boolean valid, String id, String error) {
            this.value = value; this.patch = patch; this.valid = valid; this.id = id; this.error = error;
        }
    }

    /** Explicit directory is used by local tests; production uses only STATE. */
    static final class Store {
        final Path directory;
        Store(Path directory) { this.directory = directory; }

        Snapshot snapshot() throws IOException {
            try (Guard guard = lock()) { return snapshotUnlocked(); }
        }

        Snapshot save(boolean value) throws IOException {
            try (Guard guard = lock()) {
                Path desired = directory.resolve(DESIRED);
                atomic(desired, desiredText(value, read(desired, false).patch));
                return snapshotUnlocked();
            }
        }

        Snapshot savePatch(boolean value) throws IOException {
            try (Guard guard = lock()) {
                Path desired = directory.resolve(DESIRED);
                atomic(desired, desiredText(read(desired, false).value, value));
                return snapshotUnlocked();
            }
        }

        Snapshot prepare(String activationId) throws IOException {
            requireId(activationId);
            try (Guard guard = lock()) {
                Parsed desired = read(directory.resolve(DESIRED), false);
                // Missing settings use the current defaults. Saved choices survive activation.
                String text = desiredText(desired.value, desired.patch);
                if (!desired.valid) atomic(directory.resolve(DESIRED), text);
                atomic(directory.resolve(ACTIVE), text + "activation_id=" + activationId + "\n");
                return new Snapshot(desired.value, desired.value, desired.patch, desired.patch,
                    true, true, activationId, desired.error);
            }
        }

        Active requireActive(String activationId) throws IOException {
            requireId(activationId);
            try (Guard guard = lock()) {
                Parsed active = read(directory.resolve(ACTIVE), true);
                if (!active.valid || !activationId.equals(active.id))
                    throw new IOException("OTA settings snapshot is missing, invalid or belongs to another activation");
                return new Active(active.value, active.patch, active.id);
            }
        }

        private Snapshot snapshotUnlocked() {
            Parsed desired = read(directory.resolve(DESIRED), false);
            Parsed active = read(directory.resolve(ACTIVE), true);
            String warning = !desired.error.isEmpty() ? desired.error : active.error;
            return new Snapshot(desired.value, active.value, desired.patch, active.patch,
                desired.valid, active.valid, active.id, warning);
        }

        private Guard lock() throws IOException {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Settings directory must be a real directory");
            Path lock = directory.resolve("ota-options.lock");
            if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Settings lock must be a regular file");
            FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { return new Guard(channel, channel.lock()); }
            catch (IOException | RuntimeException failure) { channel.close(); throw failure; }
        }
    }

    static Store production() { return new Store(STATE); }

    static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("Activation ID rejected");
    }

    private static String desiredText(boolean full, boolean patch) {
        return "schema=" + SCHEMA + "\nforce_full_package=" + (full ? "1" : "0") +
            "\nprefer_patch_package=" + (patch ? "1" : "0") + "\n";
    }

    private static Parsed read(Path file, boolean active) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            return new Parsed(DEFAULT_FORCE_FULL_PACKAGE, DEFAULT_PREFER_PATCH_PACKAGE, false, "", "");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular settings file");
            byte[] bytes = Files.readAllBytes(file);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, String> fields = new HashMap<>();
            for (String line : text.split("\n", -1)) {
                if (line.isEmpty()) continue;
                int split = line.indexOf('=');
                if (split <= 0 || split != line.lastIndexOf('=')) throw new IOException("Invalid settings line");
                String key = line.substring(0, split), value = line.substring(split + 1);
                if (!KEYS.contains(key) ||
                    fields.put(key, value) != null) throw new IOException("Unknown or repeated settings field");
            }
            if (!SCHEMA.equals(fields.get("schema"))) throw new IOException("Unknown settings schema");
            String value = fields.get("force_full_package");
            if (!"0".equals(value) && !"1".equals(value)) throw new IOException("Boolean setting must be 0 or 1");
            // Schema 1 files from before the patch switch have no patch field.
            String patch = fields.get("prefer_patch_package");
            if (patch != null && !"0".equals(patch) && !"1".equals(patch))
                throw new IOException("Patch setting must be 0 or 1");
            int savedFields = patch == null ? 2 : 3;
            String id = fields.getOrDefault("activation_id", "");
            if (active) { requireId(id); if (fields.size() != savedFields + 1) throw new IOException("Invalid active snapshot"); }
            else if (fields.size() != savedFields) throw new IOException("Invalid saved settings");
            // An older active snapshot records the behavior of that activation.
            boolean preferPatch = patch == null ? !active && DEFAULT_PREFER_PATCH_PACKAGE : "1".equals(patch);
            return new Parsed("1".equals(value), preferPatch, true, id, "");
        } catch (IOException | IllegalArgumentException invalid) {
            return new Parsed(DEFAULT_FORCE_FULL_PACKAGE, DEFAULT_PREFER_PATCH_PACKAGE, false, "",
                "设置文件无效，按默认设置处理：" + file.getFileName());
        }
    }

    private static void atomic(Path target, String text) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".ota-options-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            try { Files.setPosixFilePermissions(temp, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException ignored) { /* Host tests also run on Windows. */ }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static final class Guard implements AutoCloseable {
        final FileChannel channel; final FileLock lock;
        Guard(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
}
