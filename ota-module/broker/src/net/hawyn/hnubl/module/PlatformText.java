package net.hawyn.hnubl.module;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/** Uses the Android NIO API instead of desktop-only Java 11 convenience methods. */
final class PlatformText {
    private PlatformText() { }
    static String read(Path path, Charset charset) throws IOException {
        return new String(Files.readAllBytes(path), charset);
    }
}
