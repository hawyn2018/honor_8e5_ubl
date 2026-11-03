package net.hawyn.hnubl.module;

import android.net.Credentials;
import android.system.Os;
import android.system.StructStat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class NativeEngineIdentity {
    final int pid, uid;
    final long startTime, device, inode;
    final String path, domain;
    private NativeEngineIdentity(int pid, int uid, long start, String path, String domain, StructStat image) {
        this.pid=pid; this.uid=uid; startTime=start; this.path=path; this.domain=domain; device=image.st_dev; inode=image.st_ino;
    }
    static NativeEngineIdentity sample(int pid) throws IOException {
        Path process = java.nio.file.Paths.get("/proc", Integer.toString(pid));
        String stat = PlatformText.read(process.resolve("stat"), StandardCharsets.US_ASCII);
        long start = startTime(stat);
        String[] fields = stat.substring(stat.lastIndexOf(')')+1).trim().split("\\s+");
        if (!"1".equals(fields[1])) throw new IOException("Native daemon is not init-managed");
        byte[] command = Files.readAllBytes(process.resolve("cmdline")); int end=0;
        while(end<command.length && command[end]!=0) end++;
        String executable = new String(command,0,end,StandardCharsets.UTF_8);
        if (!executable.equals("update_engine") && !executable.endsWith("/update_engine")) throw new IOException("Not update_engine");
        int uid = effectiveUid(PlatformText.read(process.resolve("status"), StandardCharsets.US_ASCII));
        if (uid < 0 || uid >= 10000) throw new IOException("Native daemon UID rejected");
        try {
            String path = Os.readlink(process.resolve("exe").toString());
            if (!path.startsWith("/") || !path.endsWith("/update_engine")) throw new IOException("Native executable identity rejected");
            StructStat trusted = SecureFs.trustedExecutable(path), actual = Os.stat(process.resolve("exe").toString());
            if (trusted.st_dev!=actual.st_dev || trusted.st_ino!=actual.st_ino) throw new IOException("Native executable inode changed");
            String domain = NativePolicyTypes.type(PlatformText.read(process.resolve("attr/current"),StandardCharsets.US_ASCII));
            return new NativeEngineIdentity(pid,uid,start,path,domain,trusted);
        } catch(android.system.ErrnoException invalid) { throw invalid.rethrowAsIOException(); }
    }
    void require(Credentials peer) throws IOException {
        if (peer==null || peer.getPid()!=pid || peer.getUid()!=uid) throw new IOException("Native peer credentials rejected");
        NativeEngineIdentity current=sample(pid);
        if (current.uid!=uid || current.startTime!=startTime || current.device!=device || current.inode!=inode ||
                !current.path.equals(path) || !current.domain.equals(domain)) throw new IOException("Native process identity changed");
    }
    static long startTime(String stat) throws IOException {
        try {
            int close=stat.lastIndexOf(')');
            if(close<0) throw new IOException("Invalid native process stat");
            String[] words=stat.substring(close+1).trim().split("\\s+");
            if(words.length<20) throw new IOException("Incomplete native process stat");
            return Long.parseLong(words[19]);
        } catch(NumberFormatException invalid) { throw new IOException("Invalid native start time",invalid); }
    }
    static int effectiveUid(String status) throws IOException {
        for(String line:status.split("\n")) if(line.startsWith("Uid:")) {
            String[] words=line.substring(4).trim().split("\\s+");
            try { if(words.length>=2) return Integer.parseInt(words[1]); }
            catch(NumberFormatException invalid) { throw new IOException("Invalid native UID",invalid); }
        }
        throw new IOException("Native effective UID missing");
    }
}
