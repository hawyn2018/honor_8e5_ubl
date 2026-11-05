package net.hawyn.hnubl.module;

import java.io.IOException;

final class NativePolicyTypes {
    private NativePolicyTypes() { }
    static String type(String context) throws IOException {
        String clean = context == null ? "" : context.replace("\0", "").trim();
        String[] fields = clean.split(":", 4);
        if (fields.length < 3 || !fields[2].matches("[A-Za-z0-9_]+")) throw new IOException("SELinux type rejected");
        return fields[2];
    }
    static String rules(String client, String server, String socket) throws IOException {
        for (String type : new String[] { client, server, socket }) if (type == null || !type.matches("[A-Za-z0-9_]+")) throw new IOException("SELinux rule type rejected");
        return "allow " + client + " " + server + " unix_stream_socket connectto\n" +
            "allow " + client + " " + socket + " sock_file { getattr write }\n" +
            // Only the already verified native daemon's actual SELinux type.
            "allow " + client + " " + client + " process execmem\n";
    }
}
