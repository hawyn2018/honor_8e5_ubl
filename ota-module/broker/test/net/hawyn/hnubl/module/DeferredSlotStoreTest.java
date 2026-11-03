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
    public static void main(String[] args)throws Exception{
        Fixture first=new Fixture();first.marker.token=1;first.begin();
        check(first.memory.events.equals(List.of("marker:RESET","persist:ARMED")),"BEGIN invalidates old token before durable ARM");
        check(first.marker.token==0,"BEGIN token zero");
        check(first.send("HELLO").getString("cycle_id").equals(first.cycle),"native HELLO current cycle");
        JSONObject defer=first.send("DEFER");
        check(defer.getBoolean("ok")&&defer.getBoolean("durable")&&first.memory.record.getString("phase").equals("DEFERRED"),"ACK only follows persisted DEFER");
        int writes=first.memory.events.size();
        check(first.send("DEFER").getBoolean("durable")&&first.memory.events.size()==writes,"same-cycle duplicate DEFER reuses durable record");
        first.store.ota("RESET",first.marker,(s,t)->false);
        check(first.memory.record.getString("phase").equals("DEFERRED"),"completion RESET preserves deferred plan");
        first.marker.token=1;check(new JSONObject(first.consume()).getString("decision").equals("DENY")&&first.commits==0,"no commit before native DONE");
        first.send("DONE");
        check(first.send("DEFER").getBoolean("durable")&&first.memory.record.getString("phase").equals("DONE"),"duplicate Complete after DONE preserves completion");
        check(first.send("DONE").getBoolean("ok")&&first.memory.record.getString("phase").equals("DONE"),"duplicate native completion is idempotent");
        first.store=new DeferredSlotStore(first.memory,BOOT,0);first.memory.events.clear();
        check(new JSONObject(first.consume()).getString("decision").equals("ALLOW"),"DONE reaches original reboot gate");
        check(first.memory.events.equals(List.of("marker:STATE","persist:COMMITTING","HAL","persist:COMMITTED","marker:CONSUME")),"exact durable/HAL/consume ordering");
        first.consume();check(first.commits==1,"repeat does not replay HAL");
        check(first.send("DEFER").getBoolean("durable")&&first.memory.record.getString("phase").equals("COMMITTED"),"late duplicate DEFER never erases committed receipt or falls through HAL");
        check(first.send("DONE").getBoolean("ok")&&first.memory.record.getString("phase").equals("COMMITTED"),"late duplicate DONE preserves committed receipt");

        Fixture multi=new Fixture();multi.done();String oldCycle=multi.cycle;multi.begin();
        check(!oldCycle.equals(multi.cycle)&&multi.memory.record.getInt("target_slot")==-1&&multi.marker.token==0,"next payload BEGIN replaces old package plan");
        JSONObject old=multi.request("DONE").put("cycle_id",oldCycle);
        check(!multi.store.nativeRequest(old,123,456).getBoolean("ok"),"old package DONE cannot finish new cycle");
        multi.send("DEFER");multi.send("FAIL");multi.marker.token=1;
        check(new JSONObject(multi.consume()).getString("decision").equals("DENY")&&multi.commits==0,"failed native update never commits");

        Fixture stale=new Fixture();stale.done();stale.store=new DeferredSlotStore(stale.memory,OTHER,0);
        check(new JSONObject(stale.consume()).getString("decision").equals("DENY")&&stale.commits==0,"cross-boot activation rejected");
        Fixture wrongSource=new Fixture();wrongSource.done();wrongSource.store=new DeferredSlotStore(wrongSource.memory,BOOT,1);
        check(new JSONObject(wrongSource.consume()).getString("decision").equals("DENY")&&wrongSource.commits==0,"source slot change rejected");

        Fixture bad=new Fixture();bad.begin();
        for(String key:new String[]{"protocol","pid","start_time"}){JSONObject r=bad.request("DEFER").put(key,"1");rejects(()->bad.store.nativeRequest(r,123,456));}
        check(!bad.store.nativeRequest(bad.request("DEFER").put("target_slot",0),123,456).getBoolean("ok"),"current-slot defer rejected");
        check(!bad.store.nativeRequest(bad.request("DEFER").put("target_slot","1"),123,456).getBoolean("ok"),"truthy target rejected");
        rejects(()->bad.store.nativeRequest(bad.request("DEFER").put("nonce","bad\nnonce"),123,456));
        rejects(()->bad.store.nativeRequest(bad.request("DEFER").put("boot_id",OTHER),123,456));
        check(bad.memory.record.getString("phase").equals("ARMED"),"bad requests preserve ARM");
        bad.memory.failWrite="DEFERRED";rejects(()->bad.send("DEFER"));
        check(bad.memory.record.getString("phase").equals("ARMED"),"failed persist produces no durable ACK or changed record");

        Fixture falseCommit=new Fixture();falseCommit.done();AtomicInteger calls=new AtomicInteger();
        String falseResult=falseCommit.store.ota("CONSUME",falseCommit.marker,(s,t)->{calls.incrementAndGet();return false;});
        check(new JSONObject(falseResult).getString("decision").equals("DENY")&&falseCommit.marker.token==1,"false HAL result does not consume token");
        falseCommit.consume();check(calls.get()==1&&falseCommit.commits==0&&falseCommit.memory.record.getString("phase").equals("COMMITTING"),"uncertain commit is never replayed");
        Fixture thrown=new Fixture();thrown.done();rejects(()->thrown.store.ota("CONSUME",thrown.marker,(s,t)->{throw new IOException("binder disconnected");}));
        thrown.consume();check(thrown.commits==0&&thrown.marker.token==1,"Binder exception stays fail closed without replay");
        Fixture afterHal=new Fixture();afterHal.done();afterHal.memory.failWrite="COMMITTED";rejects(afterHal::consume);
        afterHal.memory.failWrite="";afterHal.consume();check(afterHal.commits==1&&afterHal.marker.token==1,"persist failure after HAL never replays or allows reboot");
        Fixture consumeRetry=new Fixture();consumeRetry.done();consumeRetry.marker.failConsume=true;consumeRetry.consume();
        check(new JSONObject(consumeRetry.consume()).getString("decision").equals("ALLOW")&&consumeRetry.commits==1,"committed record can retry token consume without HAL replay");

        Fixture optional=new Fixture();optional.begin();optional.marker.token=1;
        JSONObject optionalResult=new JSONObject(optional.consume());
        check(optionalResult.getString("decision").equals("ALLOW")&&optional.commits==0&&optionalResult.getJSONArray("warnings").length()==1,"no native DEFER preserves original slot flow with warning");
        Fixture noPlan=new Fixture();noPlan.marker.token=1;
        check(new JSONObject(noPlan.consume()).getString("decision").equals("ALLOW")&&noPlan.commits==0,"no record preserves original slot flow");
        Fixture corrupt=new Fixture();corrupt.done();corrupt.memory.record.put("schema",2);rejects(corrupt::consume);check(corrupt.commits==0,"corrupt record is not treated as no pending");

        Fixture concurrent=new Fixture();concurrent.done();AtomicInteger allowed=new AtomicInteger(),errors=new AtomicInteger();CountDownLatch start=new CountDownLatch(1);
        Thread[] readers=new Thread[16];
        for(int i=0;i<readers.length;i++){readers[i]=new Thread(()->{try{start.await();if(new JSONObject(concurrent.consume()).getString("decision").equals("ALLOW"))allowed.incrementAndGet();}catch(Exception error){errors.incrementAndGet();}});readers[i].start();}
        start.countDown();for(Thread reader:readers)reader.join();
        check(allowed.get()==1&&errors.get()==0&&concurrent.commits==1,"concurrent original reboot gates commit and consume once");
        System.out.println("DEFERRED_SLOT_STORE_CHECKS="+checks);
    }
}
