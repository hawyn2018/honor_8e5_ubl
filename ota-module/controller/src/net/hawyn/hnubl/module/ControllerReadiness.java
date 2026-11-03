package net.hawyn.hnubl.module;

/** Readiness after valid mapping discovery and a reliable process observation. */
final class ControllerReadiness {
    private boolean restartPending;
    boolean controllerReady, updaterRunning, waitingForUpdater, hookReady;

    ControllerReadiness(boolean restartRequested) { restartPending = restartRequested; }

    void observe(boolean running, boolean installed, boolean hasError) {
        controllerReady = true;
        updaterRunning = running;
        hookReady = running && installed && !hasError;
        if (hookReady) restartPending = false;
        // A requested restart that never produces a protected process must not
        // turn into successful idle Apply merely because am start returned 0.
        waitingForUpdater = !running && !restartPending && !hasError;
    }

    boolean awaitingRestart() { return restartPending; }

    void invalidate() {
        controllerReady = false;
        updaterRunning = false;
        waitingForUpdater = false;
        hookReady = false;
    }
}
