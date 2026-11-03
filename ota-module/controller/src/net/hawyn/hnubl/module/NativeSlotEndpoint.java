package net.hawyn.hnubl.module;

import android.net.LocalSocket;
import android.net.Credentials;
import android.net.LocalSocketAddress;
import android.net.LocalServerSocket;
import java.io.Closeable;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import org.json.JSONObject;

/** Native-engine-only endpoint. OUC's UID is never accepted here. */
final class NativeSlotEndpoint implements Closeable {
    static final String DATA_DIRECTORY="/data/misc/update_engine";
    static final String PATH="/data/misc/update_engine/hnubl-module/native-slot.sock";
    private final NativeEngineIdentity identity;
    private final DeferredSlotStore store;
    private final String activation;
    private final SecureFs.Directory directory;
    private final LocalSocket owner;
    private final LocalServerSocket server;
    private final ExecutorService clients=Executors.newCachedThreadPool(task->{Thread t=new Thread(task,"hnubl-native-marker-client");t.setDaemon(true);return t;});
    private volatile boolean active=true;
    private volatile String error="";
    NativeSlotEndpoint(NativeEngineIdentity identity,DeferredSlotStore store,String activation)throws Exception {
        this.identity=identity;this.store=store;this.activation=activation;
        SecureFs.Directory pinned=SecureFs.privateServiceSocketDirectory(DATA_DIRECTORY,identity.uid);
        LocalSocket bound=null;LocalServerSocket listening=null;
        try {
            NativeSocketPolicy.ensure(identity,pinned);
            SecureFs.directoryOwner(pinned,0);SecureFs.removeStaleSocket(pinned,"native-slot.sock",identity.uid);
            bound=new LocalSocket(LocalSocket.SOCKET_STREAM);
            bound.bind(new LocalSocketAddress(pinned.child("native-slot.sock"),LocalSocketAddress.Namespace.FILESYSTEM));
            listening=new LocalServerSocket(bound.getFileDescriptor());
            SecureFs.finishSocket(pinned,"native-slot.sock",identity.uid);SecureFs.directoryOwner(pinned,identity.uid);
            directory=pinned;owner=bound;server=listening;
            Thread thread=new Thread(()->NativeFailureBoundary.run("endpoint listener",this::accept,this::unavailable),"hnubl-native-marker-endpoint");thread.setDaemon(true);thread.start();
        } catch(Exception | LinkageError failed) {
            if(listening!=null)try{listening.close();}catch(IOException ignored){}
            if(bound!=null)try{bound.close();}catch(IOException ignored){}
            try{SecureFs.directoryOwner(pinned,identity.uid);}catch(IOException ignored){}
            pinned.close();throw failed;
        }
    }
    boolean ready(){return active;}
    String error(){return error;}
    private void note(String message){error=message;System.err.println("[hnubl-native-slot] "+message);}
    private void unavailable(String message){note(message);NativeFailureBoundary.run("endpoint cleanup",this::close,this::note);}
    private void accept(){
        try{while(active){LocalSocket socket=server.accept();try{clients.execute(()->NativeFailureBoundary.run("endpoint request",()->serve(socket),this::unavailable));}catch(RuntimeException rejected){socket.close();throw rejected;}}}
        catch(Exception failed){if(active)error=failed.toString();}
        finally{NativeFailureBoundary.run("endpoint close",this::close,this::unavailable);}
    }
    private void serve(LocalSocket socket){
        Credentials peer=null;String event="",id=UUID.randomUUID().toString();
        try(LocalSocket client=socket){
            client.setSoTimeout(30000);peer=client.getPeerCredentials();identity.require(peer);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;
            while((value=client.getInputStream().read())!='\n'){
                if(value<0 || bytes.size()>=4096)throw new IOException("Native protocol frame rejected");bytes.write(value);
            }
            JSONObject request=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            String suppliedEvent=request.optString("event");
            event=suppliedEvent.matches("HELLO|DEFER|DONE|FAIL")?suppliedEvent:"INVALID";
            if(!activation.equals(request.optString("activation_id")))throw new IOException("Native activation changed");
            if(!"HELLO".equals(event))record("native_transport_request",id,event,peer,null,null);
            JSONObject response=store.nativeRequest(request,identity.pid,identity.startTime);
            response.put("activation_id",activation).put("boot_id",store.bootId()).put("diagnostic_id",id);
            if(!"HELLO".equals(event))record("native_transport_result",id,event,peer,response,null);
            client.getOutputStream().write((response.toString()+"\n").getBytes(StandardCharsets.UTF_8));client.getOutputStream().flush();
        }catch(Exception failed){record("native_transport_rejected",id,event,peer,null,failed);System.err.println("[hnubl-native-slot] request rejected: "+failed.getClass().getSimpleName());}
    }
    private void record(String name,String id,String event,Credentials peer,JSONObject response,Throwable error){
        try{
            JSONObject fields=OtaEventJournal.fields("request_id",id,"native_event",event,"boot_id",store.bootId(),
                "source_slot",store.sourceSlot(),"engine_pid",identity.pid,"engine_start",identity.startTime);
            if(peer!=null)fields.put("peer_pid",peer.getPid()).put("peer_uid",peer.getUid());
            if(response!=null){fields.put("response_ok",response.opt("ok")).put("decision",response.optString("decision"));
                for(String key:new String[]{"cycle_id","target_slot","durable"})if(response.has(key))fields.put(key,response.get(key));}
            if(error!=null)fields.put("exception_class",error.getClass().getSimpleName()).put("error",String.valueOf(error.getMessage()));
            OtaEventJournal.record(name,fields);
        }catch(Exception|LinkageError ignored){}
    }
    @Override public synchronized void close(){
        if(!active)return;active=false;
        NativeFailureBoundary.run("server close",()->server.close(),this::note);
        NativeFailureBoundary.run("socket close",()->owner.close(),this::note);
        NativeFailureBoundary.run("directory close",()->directory.close(),this::note);
        NativeFailureBoundary.run("client executor close",()->clients.shutdown(),this::note);
    }
}
