package net.hawyn.hnubl.module;

import java.util.function.Consumer;

/** An optional native feature's missing framework ABI must not terminate the ROOT VM. */
final class NativeFailureBoundary {
    interface Action { void run() throws Exception; }
    private NativeFailureBoundary() { }
    static boolean run(String stage, Action action, Consumer<String> warning) {
        try { action.run(); return true; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (Exception | LinkageError unavailable) {
            String diagnostic="Optional native "+stage+" unavailable: "+unavailable.getClass().getSimpleName()+": "+unavailable.getMessage();
            try { warning.accept(diagnostic); }
            catch (RuntimeException | LinkageError loggingFailure) { System.err.println(diagnostic); }
            return false;
        }
    }
}
