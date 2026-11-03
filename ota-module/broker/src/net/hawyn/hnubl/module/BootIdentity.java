package net.hawyn.hnubl.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class BootIdentity {
    final String bootId;
    final int sourceSlot;
    BootIdentity(String bootId, int sourceSlot) { this.bootId = bootId; this.sourceSlot = sourceSlot; }
    static BootIdentity current() throws IOException {
        String id = PlatformText.read(java.nio.file.Paths.get("/proc/sys/kernel/random/boot_id"), StandardCharsets.US_ASCII).trim();
        try {
            String suffix = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "ro.boot.slot_suffix");
            return parse(id, suffix);
        } catch (ReflectiveOperationException | RuntimeException error) { throw new IOException("Current source slot unavailable", error); }
    }
    static BootIdentity parse(String id, String suffix) throws IOException {
        int slot = "_a".equals(suffix) || "a".equals(suffix) ? 0 : "_b".equals(suffix) || "b".equals(suffix) ? 1 : -1;
        if (id == null || !id.matches("[0-9a-fA-F-]{36}") || slot < 0) throw new IOException("Current A/B boot identity unavailable");
        return new BootIdentity(id, slot);
    }
}
