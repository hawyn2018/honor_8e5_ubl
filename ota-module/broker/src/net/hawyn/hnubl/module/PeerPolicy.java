package net.hawyn.hnubl.module;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Credentials;

import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class PeerPolicy {
    static final String ROOT_PACKAGE = "net.hawyn.honor8e5ubl";
    static final String OTA_PACKAGE = "com.hihonor.ouc";
    static final String ROOT_CERTIFICATE_SHA256 = "2e07ebc470c25596f9d916a6bf3d15e32133a69cd246edce0cead0d0b7e4aa07";
    private final PackageManager packages;

    PeerPolicy(Context context) {
        packages = context.getPackageManager();
    }

    int rootUid() throws IOException {
        try {
            ApplicationInfo application = packages.getApplicationInfo(ROOT_PACKAGE, 0);
            String[] names = packages.getPackagesForUid(application.uid);
            if (names == null || names.length != 1 || !ROOT_PACKAGE.equals(names[0])) {
                throw new IOException("hnubl UID is shared or no longer exclusively assigned");
            }
            PackageInfo info = packages.getPackageInfo(ROOT_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signing = info.signingInfo;
            // A verified signing lineage permits legitimate certificate rotation.
            // A new independent multi-signer set is not the pinned single-signer identity.
            if (signing == null || signing.hasMultipleSigners()) throw new IOException("Unsupported signer identity");
            Signature[] current = signing.getApkContentsSigners();
            if (current == null || current.length != 1) throw new IOException("Missing signer identity");
            Signature[] history = signing.getSigningCertificateHistory();
            if (history == null || history.length == 0) history = current;
            boolean trusted = false;
            for (Signature certificate : history) {
                if (ROOT_CERTIFICATE_SHA256.equals(sha256(certificate.toByteArray()))) trusted = true;
            }
            if (!trusted) throw new IOException("hnubl certificate mismatch");
            return application.uid;
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IOException("hnubl package is not installed", failure);
        }
    }

    int otaUid() throws IOException {
        try {
            return packages.getApplicationInfo(OTA_PACKAGE, 0).uid;
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IOException("Software updater package is not installed", failure);
        }
    }

    String otaDataDirectory() throws IOException {
        try {
            ApplicationInfo application = packages.getApplicationInfo(OTA_PACKAGE, 0);
            if (application.uid != otaUid()) throw new IOException("Updater UID changed");
            ApplicationStorage.requireDataDirectory(application.dataDir);
            return application.dataDir;
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IOException("Software updater package is not installed", failure);
        }
    }

    void requireRoot(Credentials peer) throws IOException {
        if (peer == null || peer.getUid() != rootUid()) throw new IOException("Root peer identity rejected");
    }

    void requireOta(Credentials peer) throws IOException {
        if (peer == null || peer.getPid() <= 0 || peer.getUid() != otaUid()) {
            throw new IOException("OTA peer UID rejected");
        }
        final String process;
        try {
            requireEffectiveUid(peer.getPid(), peer.getUid());
            try (FileInputStream input = new FileInputStream("/proc/" + peer.getPid() + "/cmdline")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int value;
                while ((value = input.read()) != -1 && value != 0) {
                    bytes.write(value);
                }
                process = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (SecurityException failure) { throw new IOException("OTA peer process access rejected", failure); }
        if (!process.equals(OTA_PACKAGE) && !process.startsWith(OTA_PACKAGE + ":")) {
            throw new IOException("OTA process name rejected");
        }
    }

    private static void requireEffectiveUid(int pid, int uid) throws IOException {
        // Undumpable Android processes can have root-owned /proc directories.
        // Check the actual effective UID instead of that directory's inode owner.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/" + pid + "/status"), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("Uid:")) continue;
                String[] fields = line.substring(4).trim().split("\\s+");
                try {
                    if (fields.length >= 2 && Integer.parseInt(fields[1]) == uid) return;
                } catch (NumberFormatException ignored) { }
                throw new IOException("OTA peer PID identity changed");
            }
        }
        throw new IOException("OTA peer UID proof missing");
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] output = new char[digest.length * 2];
            final char[] hex = "0123456789abcdef".toCharArray();
            for (int n = 0; n < digest.length; n++) {
                output[n * 2] = hex[(digest[n] >>> 4) & 15];
                output[n * 2 + 1] = hex[digest[n] & 15];
            }
            return new String(output);
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 provider missing", failure);
        }
    }
}
