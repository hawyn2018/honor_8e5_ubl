package net.hawyn.hnubl.module;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Fixed diagnostic-only frames from the already authenticated updater peer. */
final class OtaClientTrace {
    private static final Set<String> STAGES = new HashSet<>(Arrays.asList(
        "status", "open_start", "open_ok", "open_failed", "prepare_ready", "prepare_wait",
        "consume_allow", "consume_deny", "original_started", "original_returned", "original_failed", "lease_released",
        "frontier_pass", "terminal_enter", "terminal_blocked",
        "label_state_zero", "label_state_one", "label_read_error"));
    final String stage, attempt;
    private OtaClientTrace(String stage, String attempt) { this.stage = stage; this.attempt = attempt; }
    static OtaClientTrace parse(String frame) throws IOException {
        if (frame == null || frame.length() >= 64) throw new IOException("OTA trace frame rejected");
        String[] parts = frame.split(" ", -1);
        if (parts.length != 3 || !"TRACE".equals(parts[0]) || !STAGES.contains(parts[1]) ||
                !parts[2].matches("[A-Za-z0-9_-]{1,24}")) throw new IOException("OTA trace frame rejected");
        return new OtaClientTrace(parts[1], parts[2]);
    }
}
