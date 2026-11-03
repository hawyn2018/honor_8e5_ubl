package net.hawyn.hnubl.module;

import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Optional narrowly scoped rules are applied only for types actually observed on this boot. */
final class NativeSocketPolicy {
    private static final Set<String> APPLIED = new HashSet<>();
    private NativeSocketPolicy() { }
    static synchronized void ensure(NativeEngineIdentity engine, SecureFs.Directory socketDirectory) throws IOException {
        String server=NativePolicyTypes.type(PlatformText.read(java.nio.file.Paths.get("/proc/self/attr/current"),StandardCharsets.US_ASCII));
        String socket=SecureFs.contextType(socketDirectory);
        String rules=NativePolicyTypes.rules(engine.domain,server,socket);
        if(APPLIED.contains(rules)) return;
        try(SecureFs.Directory state=SecureFs.privateMarkerDirectory()) {
            String name=".native-policy-"+UUID.randomUUID();
            try {
                FileDescriptor descriptor=SecureFs.openPrivateFile(state,name,OsConstants.O_WRONLY|OsConstants.O_EXCL,true);
                try(FileOutputStream output=new FileOutputStream(descriptor)) { output.write(rules.getBytes(StandardCharsets.US_ASCII)); output.getFD().sync(); }
                String argument=state.child(name).replace("/proc/self/","/proc/"+android.os.Process.myPid()+"/");
                Process process=new ProcessBuilder("/data/adb/ksud","sepolicy","apply",argument).redirectErrorStream(true).start();
                Thread drain=new Thread(()->{try(InputStream input=process.getInputStream()){byte[] b=new byte[1024];while(input.read(b)!=-1){}}catch(IOException ignored){}},"hnubl-native-policy-output");
                drain.setDaemon(true);drain.start();
                try {
                    if(!process.waitFor(10,TimeUnit.SECONDS)) {process.destroyForcibly();throw new IOException("Optional native policy timed out");}
                    if(process.exitValue()!=0) throw new IOException("Optional native policy rejected");
                    APPLIED.add(rules);
                } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();process.destroy();throw new IOException("Native policy interrupted",interrupted);}
                finally {try{process.getInputStream().close();}catch(IOException ignored){}}
            } finally {try{Os.remove(state.child(name));}catch(android.system.ErrnoException ignored){}}
        }
    }
}
