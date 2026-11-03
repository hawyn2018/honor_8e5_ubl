package net.hawyn.hnubl.module;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Optional native watcher. It never starts/stops update_engine or changes a slot by itself. */
final class NativeSlotRuntime implements AutoCloseable {
    private final File module;
    private final String activation;
    private final DeferredSlotStore store;
    private volatile boolean stopped,ready,waiting=true;
    private volatile String warning="";
    private volatile NativeEngineIdentity engine;
    private volatile NativeSlotEndpoint endpoint;
    private volatile Process injector;
    private Thread worker;
    private boolean attempted;
    private long nextInventoryScan;
    NativeSlotRuntime(File module,String activation,DeferredSlotStore store){this.module=module;this.activation=activation;this.store=store;}
    void start(){worker=new Thread(this::run,"hnubl-native-slot-watcher");worker.setDaemon(true);worker.start();}
    boolean ready(){NativeSlotEndpoint current=endpoint;return ready && current!=null && current.ready() && !stopped;}
    boolean waiting(){return waiting && !stopped;}
    String warning(){return warning;}
    private void run(){
        NativeFailureBoundary.run("watcher",this::runLoop,this::unavailable);
        NativeFailureBoundary.run("watcher cleanup",this::detach,this::unavailable);
    }
    private void unavailable(String message){ready=false;waiting=false;warning=message;System.err.println("[hnubl-native-slot] "+message);}
    private void runLoop(){
        long nextScan=0;
        try{
            while(!stopped){
                if(engine!=null){try{NativeEngineIdentity current=NativeEngineIdentity.sample(engine.pid);if(current.startTime!=engine.startTime||current.inode!=engine.inode)detach();}
                    catch(Exception gone){detach();}}
                if(engine==null && System.nanoTime()>=nextScan){
                    nextScan=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    engine=find();waiting=engine==null;attempted=false;
                }
                if(engine!=null && !attempted){attempted=true;attach(engine);}
                if(endpoint!=null && !endpoint.ready()){ready=false;warning="Native endpoint closed: "+endpoint.error();}
                Thread.sleep(engine==null?1000:2000);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(Exception optional){warning=optional.toString();ready=false;}
    }
    private NativeEngineIdentity find()throws IOException{
        try{
            String property=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,"init.svc_debug_pid.update_engine");
            if(property.matches("[1-9][0-9]*"))return NativeEngineIdentity.sample(Integer.parseInt(property));
        }catch(Exception unavailable){/* Verified inventory fallback for platforms without a debug PID property. */}
        long now=System.nanoTime();if(now<nextInventoryScan)return null;
        nextInventoryScan=now+TimeUnit.SECONDS.toNanos(10);
        try(java.nio.file.DirectoryStream<Path> entries=Files.newDirectoryStream(java.nio.file.Paths.get("/proc"))){
            NativeEngineIdentity found=null;
            for(Path entry:entries){String name=entry.getFileName().toString();if(!name.matches("[1-9][0-9]*"))continue;
                try{
                    byte[] command=Files.readAllBytes(entry.resolve("cmdline"));int end=0;while(end<command.length&&command[end]!=0)end++;
                    String executable=new String(command,0,end,StandardCharsets.UTF_8);
                    if(!executable.equals("update_engine")&&!executable.endsWith("/update_engine"))continue;
                    NativeEngineIdentity candidate=NativeEngineIdentity.sample(Integer.parseInt(name));
                    if(found!=null){warning="Multiple verified update_engine processes; native enhancement pending";return null;}found=candidate;
                }catch(Exception other){/* Not the native OTA daemon. */}}
            return found;
        }
    }
    private synchronized void attach(NativeEngineIdentity identity){
        try{
            if(stopped)return;
            if(!TypedSlotCommitter.available()){warning="Waiting for typed UpdateEngine service; original slot flow remains";attempted=false;return;}
            endpoint=new NativeSlotEndpoint(identity,store,activation);
            JSONObject parameters=new JSONObject().put("protocol",1).put("activation_id",activation).put("boot_id",store.bootId())
                .put("endpoint",NativeSlotEndpoint.PATH).put("engine_pid",identity.pid).put("engine_start",identity.startTime)
                .put("engine_path",identity.path).put("module_name",new File(identity.path).getName()).put("source_slot",store.sourceSlot());
            Process process=new ProcessBuilder(new File(module,"bin/frida-inject").getPath(),"-p",Integer.toString(identity.pid),
                "-s",new File(module,"native-slot.bundle.js").getPath(),"-P",parameters.toString()).redirectErrorStream(true).start();
            injector=process;warning="Native semantic hook initializing";waiting=false;
            Thread monitor=new Thread(()->NativeFailureBoundary.run("injector monitor",()->monitor(process,identity),this::unavailable),"hnubl-native-slot-agent");monitor.setDaemon(true);monitor.start();
        }catch(Exception failed){warning="Native slot enhancement unavailable; original slot flow remains: "+failed;ready=false;if(endpoint!=null){endpoint.close();endpoint=null;}}
    }
    private void monitor(Process process,NativeEngineIdentity identity){
        try(BufferedReader input=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){
            String line;
            while((line=input.readLine())!=null){
                if(stopped || injector!=process)return;
                System.err.println("[native-slot "+identity.pid+"] "+line);
                try{
                    JSONObject envelope=new JSONObject(line);JSONObject event=envelope.optJSONObject("payload");
                    if(event==null){if("error".equals(envelope.optString("type"))){ready=false;warning=envelope.optString("description","Native script error");}continue;}
                    String kind=event.optString("event");
                    if("native_ready".equals(kind)||"ready".equals(kind)){
                        NativeSlotEndpoint current=endpoint;
                        ready=Boolean.TRUE.equals(event.opt("native_ready")) && Boolean.TRUE.equals(event.opt("native_slot_ready")) && current!=null && current.ready();
                        warning=ready?"":event.toString();
                    }else if("warning".equals(kind)||"error".equals(kind)){warning=event.toString();if("error".equals(kind))ready=false;}
                }catch(Exception diagnostic){/* Plain native diagnostic. */}
            }
            if(!stopped && injector==process){ready=false;warning="Native injector disconnected before controller shutdown (exit "+process.waitFor()+")";}
        }catch(Exception failed){if(!stopped && injector==process){ready=false;warning=failed.toString();}}
    }
    private synchronized void detach(){
        ready=false;
        Process process=injector;injector=null;
        if(process!=null){
            // Unlike OUC's eternalized agent, this host owns its native session.
            // Let session teardown finish before a replacement controller attaches.
            process.destroy();
            try{if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);}}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();process.destroyForcibly();}
            try{process.getInputStream().close();}catch(IOException ignored){}try{process.getOutputStream().close();}catch(IOException ignored){}
        }
        if(endpoint!=null){endpoint.close();endpoint=null;}
        engine=null;attempted=false;
    }
    @Override public void close(){stopped=true;if(worker!=null)worker.interrupt();NativeFailureBoundary.run("shutdown",this::detach,this::unavailable);}
}
