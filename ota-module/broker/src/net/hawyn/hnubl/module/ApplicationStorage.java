package net.hawyn.hnubl.module;

import java.io.IOException;

/** PM supplies the updater's actual CE/DE/user directory; never guess another one. */
final class ApplicationStorage {
    private ApplicationStorage() { }

    static void requireDataDirectory(String path) throws IOException {
        if (path == null || !path.startsWith("/data/") || path.endsWith("/") || path.indexOf('\0') >= 0) {
            throw new IOException("Invalid application data directory");
        }
        for (String component : path.substring(1).split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Invalid application data directory component");
            }
        }
    }

    static String otaSocket(String dataDirectory) throws IOException {
        requireDataDirectory(dataDirectory);
        return dataDirectory + "/files/hnubl-module/ota.sock";
    }
}
