package net.hawyn.hnubl.module;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class DeferredSlotStoreTest {
    private static final String BOOT="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action)throws Exception{try{action.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Rejected transaction accepted");}
    static final class Memory implements DeferredSlotStore.Persistence {
        JSONObject record;
        String failWrite="";
        final List<String> events=new ArrayList<>();
        @Override public synchronized <T>T locked(DeferredSlotStore.Transaction<T> operation)throws IOException{
            return operation.run(new DeferredSlotStore.Access(){
                public JSONObject read()throws IOException{try{return record==null?null:new JSONObject(record.toString());}catch(Exception error){throw new IOException(error);}}
                public void write(JSONObject value)throws IOException{
                    String phase=value.optString("phase");events.add("persist:"+phase);
                    if(failWrite.equals(phase))throw new IOException("fixture persistence failure");
                    try{record=new JSONObject(value.toString());}catch(Exception error){throw new IOException(error);}
                }
            });
        }
    }
    static final class Marker implements DeferredSlotStore.Marker {
        int token;
        boolean failConsume;
        final List<String> events;
        Marker(List<String> events){this.events=events;}
        public synchronized String request(String command)throws IOException{
            events.add("marker:"+command);
            if(command.equals("RESET"))token=0;
            if(command.equals("CONSUME")){
                if(failConsume){failConsume=false;return "{\"ok\":false,\"state\":1,\"decision\":\"DENY\"}";}
                boolean allow=token==1;token=0;return "{\"ok\":true,\"state\":0,\"decision\":\""+(allow?"ALLOW":"DENY")+"\"}";
            }
            return "{\"ok\":true,\"state\":"+token+",\"decision\":\""+(token==1?"ALLOW":"DENY")+"\"}";
        }
    }
    static final class Fixture {
        final Memory memory=new Memory();final Marker marker=new Marker(memory.events);
        DeferredSlotStore store=new DeferredSlotStore(memory,BOOT,0);int commits;
        String cycle;
        JSONObject request(String event)throws Exception{return new JSONObject().put("protocol",1).put("boot_id",BOOT).put("pid",123).put("start_time",456)
            .put("nonce","request-1").put("event",event).put("cycle_id",cycle==null?"":cycle).put("target_slot",1).put("code",0);}
        void begin()throws Exception{store.ota("BEGIN",marker,(s,t)->{throw new AssertionError("BEGIN invoked HAL");});cycle=memory.record.getString("cycle_id");}
        JSONObject send(String event)throws Exception{return store.nativeRequest(request(event),123,456);}
        void done()throws Exception{begin();check(send("DEFER").getBoolean("durable"),"DEFER durable ack");check(send("DONE").getBoolean("ok"),"native completion acknowledged");marker.token=1;}
        String consume()throws Exception{return store.ota("CONSUME",marker,(s,t)->{check(s==0&&t==1,"typed commit target remains other slot");commits++;memory.events.add("HAL");return true;});}
    }
    public static void main(String[] args)throws Exception {
        Fixture first=new Fixture();first.marker.token=1;first.begin();
        check(first.memory.events.equals(List.of("marker:RESET","persist:ARMED")),"BEGIN resets marker and registers task");
        check(first.marker.token==0&&!first.memory.record.getBoolean("pending_activation"),"new task is not a pending activation yet");
        check(first.send("HELLO").getString("cycle_id").equals(first.cycle),"native HELLO current cycle");
        check(first.send("DEFER").getBoolean("durable")&&first.memory.record.getBoolean("pending_activation"),"DEFER durably registers pending activation");
        int writes=first.memory.events.size();
        check(first.send("DEFER").getBoolean("durable")&&first.memory.events.size()==writes,"duplicate DEFER does not duplicate record writes");
        first.store.ota("RESET",first.marker,(s,t)->false);
        check(first.memory.record.getBoolean("pending_activation"),"completion RESET does not erase pending work");
        check(new JSONObject(first.consume()).getString("decision").equals("DENY")&&first.commits==0,"file0 grants no attempt");
        first.marker.token=1;first.memory.events.clear();
        JSONObject allowed=new JSONObject(first.consume());
        check(allowed.getBoolean("authorized")&&allowed.getBoolean("action_completed")&&allowed.getString("decision").equals("ALLOW"),"file1 grants action without DONE");
        check(first.memory.events.equals(List.of("marker:CONSUME","HAL","persist:COMMITTED")),"permission consumed before official action, diagnostic persistence follows confirmation");
        check(first.marker.token==0&&first.commits==1&&first.memory.record.getBoolean("completed")&&!first.memory.record.getBoolean("pending_activation"),"confirmed action completes registry");
        first.consume();check(first.commits==1,"no new grant means zero replay");
        first.marker.token=1;first.consume();check(first.commits==2,"a new grant consults the official executor instead of trusting completed local metadata");
        first.send("DEFER");first.send("DONE");first.marker.token=1;first.consume();
        check(first.commits==3&&first.memory.record.getBoolean("completed"),"late local diagnostics do not replace a fresh official execution");

        for(String phase:new String[]{"DEFERRED","DONE","COMMITTING","FAILED","ARMED","FUTURE_NATIVE_PHASE","COMMITTED"}){
            Fixture legacy=new Fixture();legacy.done();legacy.memory.record.remove("pending_activation");legacy.memory.record.remove("completed");
            legacy.memory.record.put("phase",phase).put("commit_protocol",2);
            JSONObject r=new JSONObject(legacy.consume());
            check(r.getBoolean("authorized")&&r.getString("decision").equals("ALLOW")&&legacy.marker.token==0,"legacy file1 grants independent of diagnostic phase "+phase);
            check(legacy.commits==1,"local phase never substitutes actual official execution "+phase);
        }
        Fixture missingPhase=new Fixture();missingPhase.done();missingPhase.memory.record.remove("phase");
        check(new JSONObject(missingPhase.consume()).getBoolean("authorized")&&missingPhase.commits==1,"missing diagnostic phase is not an authorization requirement");
        Fixture stale=new Fixture();stale.done();stale.store=new DeferredSlotStore(stale.memory,OTHER,0);
        check(new JSONObject(stale.consume()).getString("decision").equals("ALLOW")&&stale.commits==1&&stale.marker.token==0,"old boot is recovered through the official executor instead of skipped");
        Fixture otherSource=new Fixture();otherSource.done();otherSource.store=new DeferredSlotStore(otherSource.memory,BOOT,1);
        check(new JSONObject(otherSource.store.ota("CONSUME",otherSource.marker,(s,t)->{otherSource.commits++;return true;})).getString("decision").equals("ALLOW")&&otherSource.commits==1,"old-source registry cannot direct or skip the official executor");
        Fixture noPlan=new Fixture();noPlan.marker.token=1;
        check(new JSONObject(noPlan.consume()).getString("decision").equals("ALLOW")&&noPlan.commits==1&&noPlan.memory.record.getBoolean("recovered"),"no local plan is identified by the official executor and registered as recovered");
        Fixture armed=new Fixture();armed.begin();armed.marker.token=1;
        check(new JSONObject(armed.consume()).getString("decision").equals("ALLOW")&&armed.commits==1,"no local target still requires the official executor");
        Fixture failedNative=new Fixture();failedNative.begin();failedNative.send("DEFER");
        failedNative.store.nativeRequest(failedNative.request("FAIL").put("code",52),123,456);failedNative.marker.token=1;
        check(new JSONObject(failedNative.consume()).getBoolean("authorized")&&failedNative.commits==1,"native failure remains diagnostic and file1 authorizes the real attempt");

        for(boolean throwing:new boolean[]{false,true}){
            Fixture f=new Fixture();f.done();AtomicInteger calls=new AtomicInteger();
            DeferredSlotStore.Committer c=(s,t)->{calls.incrementAndGet();if(throwing)throw new IOException("service unavailable");return false;};
            JSONObject failed=new JSONObject(f.store.ota("CONSUME",f.marker,c));
            check(failed.getBoolean("authorized")&&!failed.getBoolean("ok")&&failed.getString("decision").equals("EXECUTION_FAILED")&&failed.getInt("state")==0,"actual action failure is distinct from permission denial");
            check(f.marker.token==0&&calls.get()==1&&!f.memory.record.getBoolean("completed"),"failed action consumes one attempt without claiming completion");
            check(new JSONObject(f.store.ota("CONSUME",f.marker,c)).getString("decision").equals("DENY")&&calls.get()==1,"failure has no timer or automatic replay");
            f.marker.token=1;
            check(new JSONObject(f.consume()).getString("decision").equals("ALLOW")&&f.commits==1,"fresh user grant retries despite previous COMMITTING");
        }
        Fixture persist=new Fixture();persist.done();persist.memory.failWrite="COMMITTED";
        JSONObject persistFail=new JSONObject(persist.consume());
        check(persistFail.getBoolean("authorized")&&persistFail.getString("decision").equals("ALLOW")&&persist.marker.token==0&&persist.commits==1&&persistFail.has("warnings"),"diagnostic storage failure cannot override a confirmed official action");
        persist.memory.failWrite="";persist.consume();check(persist.commits==1,"diagnostic failure never replays the confirmed action");
        Fixture afterAction=new Fixture();afterAction.done();afterAction.memory.failWrite="COMMITTED";
        JSONObject saveFailure=new JSONObject(afterAction.consume());
        check(saveFailure.getBoolean("authorized")&&saveFailure.getString("decision").equals("ALLOW")&&saveFailure.getBoolean("action_completed")&&saveFailure.has("registry_warning")&&afterAction.commits==1&&afterAction.marker.token==0,"confirmed official call remains success with an explicit diagnostic save warning");
        afterAction.consume();check(afterAction.commits==1,"no grant after save failure causes no automatic replay");
        Fixture invalid=new Fixture();invalid.done();invalid.memory.record.put("schema",2);
        JSONObject invalidResult=new JSONObject(invalid.consume());
        check(invalidResult.getBoolean("authorized")&&invalidResult.getString("decision").equals("ALLOW")&&invalid.marker.token==0&&invalid.commits==1&&invalid.memory.record.getBoolean("recovered"),"unusable local metadata does not select or veto the official operation");
        DeferredSlotStore broken=new DeferredSlotStore(new DeferredSlotStore.Persistence(){public <T>T locked(DeferredSlotStore.Transaction<T> op)throws IOException{throw new IOException("fixture lock failed");}},BOOT,0);
        Marker token=new Marker(new ArrayList<>());token.token=1;
        JSONObject brokenReply=new JSONObject(broken.ota("CONSUME",token,(s,t)->true));
        check(brokenReply.getBoolean("authorized")&&brokenReply.getString("decision").equals("ALLOW")&&brokenReply.has("registry_warning")&&token.token==0,"diagnostic registry lock failure is a warning after official success");
        for(String command:new String[]{"BEGIN","RESET"}) {
            token.token=1;
            JSONObject reset=new JSONObject(broken.ota(command,token,(s,t)->{throw new AssertionError("reset executed official action");}));
            check(reset.getBoolean("ok")&&reset.getInt("state")==0&&token.token==0,"registered lock failure cannot preserve an old grant "+command);
        }
        Fixture armSave=new Fixture();armSave.marker.token=1;armSave.memory.failWrite="ARMED";
        JSONObject armFailure=new JSONObject(armSave.store.ota("BEGIN",armSave.marker,(s,t)->false));
        check(armFailure.getBoolean("ok")&&armFailure.getInt("state")==0&&armFailure.has("registry_warning")&&armSave.marker.token==0,"BEGIN save failure reports native registration warning after real reset");
        check(armSave.memory.record==null,"failed native registration is not fabricated as durable");
        Marker rootReset=new Marker(new ArrayList<>());rootReset.token=1;
        rejects(()->broken.ota("BEGIN",command->{if(command.equals("RESET"))throw new IOException("resident reset failed");return rootReset.request(command);},(s,t)->true));
        check(rootReset.token==1,"actual resident RESET failure is propagated without claiming zero");

        Fixture bad=new Fixture();bad.begin();
        for(String key:new String[]{"protocol","pid","start_time"}){JSONObject r=bad.request("DEFER").put(key,"1");rejects(()->bad.store.nativeRequest(r,123,456));}
        check(!bad.store.nativeRequest(bad.request("DEFER").put("target_slot",0),123,456).getBoolean("ok"),"native target context remains authenticated");
        rejects(()->bad.store.nativeRequest(bad.request("DEFER").put("nonce","bad\nnonce"),123,456));
        rejects(()->bad.store.nativeRequest(bad.request("DEFER").put("boot_id",OTHER),123,456));
        check(!bad.store.nativeRequest(bad.request("DEFER").put("cycle_id",OTHER),123,456).getBoolean("ok"),"native cycle remains authenticated");
        check(bad.memory.record.getInt("target_slot")==-1,"bad native request does not register an action");
        bad.memory.failWrite="DEFERRED";rejects(()->bad.send("DEFER"));
        check(!bad.memory.record.getBoolean("pending_activation"),"no durable DEFER ACK before registration");

        Fixture concurrent=new Fixture();concurrent.done();AtomicInteger granted=new AtomicInteger(),errors=new AtomicInteger();CountDownLatch start=new CountDownLatch(1);
        Thread[] readers=new Thread[16];for(int i=0;i<readers.length;i++){readers[i]=new Thread(()->{try{start.await();if(new JSONObject(concurrent.consume()).getBoolean("authorized"))granted.incrementAndGet();}catch(Exception e){errors.incrementAndGet();}});readers[i].start();}
        start.countDown();for(Thread reader:readers)reader.join();
        check(granted.get()==1&&errors.get()==0&&concurrent.commits==1,"one atomic file grant authorizes exactly one concurrent attempt");
        System.out.println("DEFERRED_SLOT_STORE_CHECKS="+checks);
    }
}
