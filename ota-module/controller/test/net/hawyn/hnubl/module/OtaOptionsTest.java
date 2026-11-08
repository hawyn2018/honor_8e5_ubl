package net.hawyn.hnubl.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Host JVM tests of migration and activation isolation, without Android/root. */
public final class OtaOptionsTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void invalidActive(OtaOptions.Store store, String id) throws IOException {
        checks++;
        try { store.requireActive(id); throw new AssertionError("Invalid/stale active snapshot accepted"); }
        catch (IOException expected) { }
    }
    private static void text(Path directory, String name, String value) throws IOException {
        Files.write(directory.resolve(name), value.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        Path parent = args.length == 1 ? Paths.get(args[0]) : Paths.get(System.getProperty("java.io.tmpdir"));
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "ota-options-test-");
        try {
            Path normal = root.resolve("normal"); OtaOptions.Store store = new OtaOptions.Store(normal);
            OtaOptions.Snapshot initial = store.snapshot();
            check(!initial.desired && !initial.active, "First installation must default off");
            check(initial.desiredPatch, "First installation must default patch on");
            check(!initial.hasSavedValue && !initial.activeKnown && initial.pending(), "Missing state must not claim applied");
            invalidActive(store, "boot-1");
            store.prepare("boot-1");
            check(!store.requireActive("boot-1").forceFullPackage, "First boot must activate off");
            check(store.requireActive("boot-1").preferPatchPackage, "First boot must activate patch on");
            check(store.snapshot().hasSavedValue && !store.snapshot().pending(), "Initial choice must be persisted");

            OtaOptions.Active captured = store.requireActive("boot-1");
            OtaOptions.Snapshot saved = store.save(true);
            check(saved.desired && !saved.active && saved.pending(), "Saving on must not change active off");
            check(!captured.forceFullPackage && !store.requireActive("boot-1").forceFullPackage, "Existing activation must stay off");
            store.prepare("apply-2");
            check(store.requireActive("apply-2").forceFullPackage, "Apply must activate the saved on choice");
            invalidActive(store, "boot-1");
            check(!store.snapshot().pending(), "Saved and active on must agree");
            store.save(false);
            check(store.requireActive("apply-2").forceFullPackage && store.snapshot().pending(), "Saving off must retain active on until apply");
            store.prepare("boot-3");
            check(!store.requireActive("boot-3").forceFullPackage, "Reboot activation must use saved off");

            text(normal, OtaOptions.DESIRED, "schema=1\nforce_full_package=1\n");
            text(normal, OtaOptions.ACTIVE, "schema=1\nforce_full_package=1\nactivation_id=boot-3\n");
            OtaOptions.Store upgrade = new OtaOptions.Store(normal);
            check(!upgrade.requireActive("boot-3").preferPatchPackage, "A legacy live snapshot retains its original patch-off behavior");
            check(upgrade.snapshot().desiredPatch && upgrade.snapshot().pending(), "Legacy desired settings pick up the patch default");
            upgrade.prepare("upgrade-4");
            OtaOptions.Active legacy = upgrade.requireActive("upgrade-4");
            check(legacy.forceFullPackage && legacy.preferPatchPackage, "Legacy full-on choice survives with patch default on");
            check(upgrade.snapshot().desired && upgrade.snapshot().hasSavedValue, "Upgrade must not overwrite a valid saved choice");
            OtaOptions.Snapshot patchSaved = upgrade.savePatch(false);
            check(patchSaved.desired && patchSaved.active && !patchSaved.desiredPatch &&
                patchSaved.activePatch && patchSaved.pending(), "Saving patch off must preserve full and defer activation");
            OtaOptions.Store upgradedAgain = new OtaOptions.Store(normal);
            upgradedAgain.prepare("patch-off");
            check(!upgradedAgain.requireActive("patch-off").preferPatchPackage, "An explicit patch opt-out survives an upgrade");
            upgrade.savePatch(true);
            upgrade.prepare("patch-5");
            OtaOptions.Active both = upgrade.requireActive("patch-5");
            check(both.forceFullPackage && both.preferPatchPackage && !upgrade.snapshot().pending(),
                "One activation must take both saved choices");
            check(upgrade.save(false).desiredPatch, "Saving full must preserve the patch choice");
            check(upgrade.requireActive("patch-5").forceFullPackage, "Saving full must leave the live fallback intact");
            upgrade.prepare("upgrade-4");
            check(!upgrade.requireActive("upgrade-4").forceFullPackage && upgrade.requireActive("upgrade-4").preferPatchPackage,
                "Patch on and full off must remain independent");
            boolean badId = false;
            try { upgrade.prepare("bad\nactivation"); } catch (IllegalArgumentException expected) { badId = true; }
            check(badId, "Activation identity must be strict");

            String[] invalid = {
                "schema=2\nforce_full_package=1\n",
                "schema=1\nforce_full_package=1\nforce_full_package=0\n",
                "schema=1\nforce_full_package=true\n",
                "schema=1\nforce_full_package=1;echo injected\n",
                "schema=1\nforce_full_package=1\nunknown=1\n",
                "schema=1\nforce_full_package=1\nactivation_id=wrong-place\n",
                "schema=1\nforce_full_package=1\r\n",
                "force_full_package=1\n",
                "schema=1\nforce_full_package=\n",
                "schema=1\nforce_full_package=1\nprefer_patch_package=true\n",
                "schema=1\nforce_full_package=0\nprefer_patch_package=1\nprefer_patch_package=0\n",
            };
            for (int i = 0; i < invalid.length; i++) {
                Path folder = root.resolve("invalid-" + i); Files.createDirectories(folder);
                text(folder, OtaOptions.DESIRED, invalid[i]); OtaOptions.Store broken = new OtaOptions.Store(folder);
                OtaOptions.Snapshot snapshot = broken.snapshot();
                check(!snapshot.desired && !snapshot.hasSavedValue && !snapshot.warning.isEmpty(), "Invalid saved setting must default off");
                broken.prepare("recovered-" + i);
                check(!broken.requireActive("recovered-" + i).forceFullPackage, "Invalid settings must not migrate as on");
                check(broken.requireActive("recovered-" + i).preferPatchPackage, "Invalid settings must recover to the patch default");
                check(broken.snapshot().hasSavedValue, "Recovered default must be a valid saved setting");
            }

            Path malformed = root.resolve("bad-utf8"); Files.createDirectories(malformed);
            Files.write(malformed.resolve(OtaOptions.DESIRED), new byte[] {(byte)0xc3,(byte)0x28});
            check(!new OtaOptions.Store(malformed).snapshot().hasSavedValue, "Malformed UTF-8 rejected");
            text(normal, OtaOptions.ACTIVE, "schema=1\nforce_full_package=1\nactivation_id=wrong\n");
            invalidActive(store, "upgrade-4");
            text(normal, OtaOptions.ACTIVE, "schema=1\nforce_full_package=truthy\nactivation_id=upgrade-4\n");
            invalidActive(store, "upgrade-4");
            store.save(false);
            invalidActive(store, "upgrade-4"); // save cannot repair/activate a snapshot

            boolean symlinksTested = false;
            Path links = root.resolve("links"); Files.createDirectories(links);
            Path outside = root.resolve("outside.conf");
            Files.write(outside, "schema=1\nforce_full_package=1\n".getBytes(StandardCharsets.UTF_8));
            try {
                Files.createSymbolicLink(links.resolve(OtaOptions.DESIRED), outside);
                OtaOptions.Store linkStore = new OtaOptions.Store(links);
                check(!linkStore.snapshot().hasSavedValue, "Settings symlink must not be read as a saved choice");
                linkStore.prepare("links-1");
                check(!linkStore.requireActive("links-1").forceFullPackage, "Invalid symlink setting defaults off");
                check(new String(Files.readAllBytes(outside), StandardCharsets.UTF_8).contains("force_full_package=1"), "Replacement cannot mutate symlink target");
                symlinksTested = true;
            } catch (UnsupportedOperationException | FileSystemException hostRestriction) { }
            System.out.println("OtaOptions checks passed: " + checks + "; symlink checks=" + symlinksTested);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
}
