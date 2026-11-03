package net.hawyn.hnubl.module;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Host fixtures only: no Android process, OTA download, reboot or device I/O. */
public final class IdleApplyTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    public static void main(String[] args) throws Exception {
        Path fixture = Paths.get(args[0]); Files.createDirectories(fixture);
        check(!UpdaterProcessProbe.isRunning(fixture), "Empty process inventory is idle");
        Path ignored = Files.createDirectories(fixture.resolve("self"));
        Files.write(ignored.resolve("cmdline"), bytes("com.hihonor.ouc\0"));
        check(!UpdaterProcessProbe.isRunning(fixture), "Non-numeric proc entries must not count");
        Path pid = Files.createDirectories(fixture.resolve("101"));
        Files.write(pid.resolve("cmdline"), new byte[0]);
        check(!UpdaterProcessProbe.isRunning(fixture), "Kernel/empty cmdline is not the updater");
        Files.write(pid.resolve("cmdline"), bytes("com.hihonor.ouc.other\0"));
        check(!UpdaterProcessProbe.isRunning(fixture), "A matching text prefix is not the updater");
        Files.write(pid.resolve("cmdline"), bytes("com.hihonor.ouc\0argument"));
        boolean runningBeforeDisposal = UpdaterProcessProbe.isRunning(fixture);
        check(runningBeforeDisposal, "Exact updater process must trigger restart");
        Files.delete(pid.resolve("cmdline"));
        check(runningBeforeDisposal && !UpdaterProcessProbe.isRunning(fixture), "Capture survives the old controller stopping its attached process");
        Files.write(pid.resolve("cmdline"), bytes("com.hihonor.ouc:download\0"));
        check(UpdaterProcessProbe.isRunning(fixture), "Updater child process must trigger restart");
        Files.write(pid.resolve("cmdline"), bytes("com.hihonor.ouc2:download\0"));
        check(!UpdaterProcessProbe.isRunning(fixture), "Other package subprocess must not trigger restart");
        boolean failed = false;
        try { UpdaterProcessProbe.isRunning(fixture.resolve("missing-proc")); }
        catch (java.io.IOException expected) { failed = true; }
        check(failed, "Unobservable process inventory is an error, never idle");
        Files.delete(pid.resolve("cmdline")); Files.createDirectory(pid.resolve("cmdline"));
        failed = false;
        try { UpdaterProcessProbe.isRunning(fixture); }
        catch (java.io.IOException expected) { failed = true; }
        check(failed, "Unreadable cmdline must not silently count as idle");

        ControllerReadiness idle = new ControllerReadiness(false);
        check(!idle.controllerReady && !idle.waitingForUpdater, "Snapshot alone is not readiness");
        idle.observe(false, false, false);
        check(idle.controllerReady && idle.waitingForUpdater && !idle.hookReady, "Idle Apply initializes watcher without requiring an OUC launch");
        idle.observe(true, false, false);
        check(idle.controllerReady && idle.updaterRunning && !idle.waitingForUpdater && !idle.hookReady, "Later manual updater launch must wait for hooks");
        idle.observe(true, true, false);
        check(idle.hookReady && !idle.waitingForUpdater, "Manual launch becomes hook-ready after injection");
        idle.invalidate();
        check(!idle.controllerReady && !idle.waitingForUpdater && !idle.hookReady, "Mapping/process failure clears all readiness");

        ControllerReadiness restart = new ControllerReadiness(true);
        restart.observe(false, false, false);
        check(restart.controllerReady && !restart.waitingForUpdater && restart.awaitingRestart(), "Restart that never starts must not become idle success");
        restart.observe(true, false, false);
        check(!restart.hookReady && !restart.waitingForUpdater, "Running Apply waits for actual injection");
        restart.observe(false, false, true);
        check(!restart.waitingForUpdater, "Failed restarted process remains unsuccessful");
        restart.observe(true, true, false);
        check(restart.hookReady && !restart.awaitingRestart(), "Successful injection clears restart pending");
        restart.observe(false, false, false);
        check(restart.waitingForUpdater, "Normally closed protected updater can return to watching");
        System.out.println("IdleApply checks passed: " + checks);
    }
}
