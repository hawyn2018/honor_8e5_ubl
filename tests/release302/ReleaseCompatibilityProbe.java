import dev.honor.bootforge.core.EfispImagePatcher;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.hawyn.ablefisp.api.AblEfisp;
import net.hawyn.ablefisp.patch.FastbootRecoveryPatcher;
import net.hawyn.ablefisp.patch.KblPatcher;
import net.hawyn.ablefisp.patch.VmDtboCompatPatcher;
import net.hawyn.ablefisp.text.TextEntry;

/** Runs the real file-only Android adapter on one local fixture; no device I/O. */
public final class ReleaseCompatibilityProbe {
    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
    private static String hash(byte[] data) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
    private static String array(List<String> values) {
        return "[" + String.join(",", values.stream().map(ReleaseCompatibilityProbe::quote).toList()) + "]";
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("INPUT OUTPUT REPORT fakebl|freedom");
        File input = new File(args[0]);
        File output = new File(args[1]);
        Path report = Path.of(args[2]);
        boolean fake = args[3].equals("fakebl");
        if (!fake && !args[3].equals("freedom")) throw new IllegalArgumentException("Unknown mode");
        if (output.exists() || Files.exists(report)) throw new IllegalStateException("Preserve earlier results");
        long start = System.nanoTime();
        String sourceHash = hash(Files.readAllBytes(input.toPath()));
        List<String> logs = new ArrayList<>();
        String body;
        int rc = 0;
        try {
            EfispImagePatcher.Result result = EfispImagePatcher.INSTANCE.patch(input, output, fake,
                line -> { logs.add(line); return kotlin.Unit.INSTANCE; });
            byte[] image = Files.readAllBytes(output.toPath());
            byte[] vmImage = KblPatcher.INSTANCE.removeAdapter(FastbootRecoveryPatcher.INSTANCE.remove(image));
            byte[] nativeImage = VmDtboCompatPatcher.INSTANCE.remove(vmImage);
            if (Arrays.equals(vmImage, nativeImage))
                throw new AssertionError("Final text-customized candidate lost its VM selection interface");
            VmDtboCompatPatcher.Result rebuiltVm = VmDtboCompatPatcher.INSTANCE.patch(nativeImage);
            if (rebuiltVm.getFacts() == null || !Arrays.equals(vmImage, rebuiltVm.getBytes()))
                throw new AssertionError("Final VM interface receipt/rebuild is not canonical");
            if (!hash(Files.readAllBytes(input.toPath())).equals(sourceHash))
                throw new AssertionError("Input bytes changed");
            String label = null;
            for (TextEntry entry : AblEfisp.scanText(image).getEntries())
                if (entry.getId().equals("fastboot-unlocked")) {
                    if (label != null) throw new AssertionError("Duplicate label");
                    label = entry.getText();
                }
            if (!EfispImagePatcher.FASTBOOT_TEXT.equals(label))
                throw new AssertionError("Final label differs");
            if (!hash(image).equals(result.getSha256())) throw new AssertionError("Published image hash differs");
            body = "{\"passed\":true,\"source\":" + quote(input.getAbsolutePath())
                + ",\"sourceSha256\":" + quote(sourceHash)
                + ",\"output\":" + quote(output.getAbsolutePath())
                + ",\"outputBytes\":" + image.length + ",\"outputSha256\":" + quote(result.getSha256())
                + ",\"mode\":" + quote(args[3]) + ",\"sourcePreserved\":true,\"actualAppAdapter\":true"
                + ",\"adapterFinalReimportPassed\":true,\"patchCount\":" + result.getPatchCount()
                + ",\"finalVmLayerPresent\":true,\"finalVmReceiptStrict\":true,\"finalVmRebuildIdentical\":true"
                + ",\"textChanges\":" + result.getTextChanges() + ",\"label\":" + quote(label)
                + ",\"notes\":" + array(result.getNotes()) + ",\"adapterLogs\":" + array(logs)
                + ",\"elapsedSeconds\":" + ((System.nanoTime()-start)/1_000_000_000.0) + "}";
        } catch (Throwable failure) {
            rc = 1;
            body = "{\"passed\":false,\"source\":" + quote(input.getAbsolutePath())
                + ",\"sourceSha256\":" + quote(sourceHash) + ",\"mode\":" + quote(args[3])
                + ",\"error\":" + quote(failure.toString()) + ",\"adapterLogs\":" + array(logs)
                + ",\"elapsedSeconds\":" + ((System.nanoTime()-start)/1_000_000_000.0) + "}";
            failure.printStackTrace(System.err);
        }
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, body + "\n", StandardCharsets.UTF_8);
        System.out.println("passed=" + (rc == 0) + " report=" + report.toAbsolutePath());
        if (rc != 0) System.exit(rc);
    }
}
