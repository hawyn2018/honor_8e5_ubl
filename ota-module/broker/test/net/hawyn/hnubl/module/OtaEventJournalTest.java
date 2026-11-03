package net.hawyn.hnubl.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Host queue/serialization and actual store tests; Android AF_UNIX and fs permissions are separate. */
public final class OtaEventJournalTest {
    private static int checks;
    private static final String BOOT="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        List<JSONObject> lines=new ArrayList<>();
        OtaEventJournal.Engine journal=new OtaEventJournal.Engine(line->lines.add(new JSONObject(new String(line,StandardCharsets.UTF_8))),2,false);
        JSONObject unsafe=new JSONObject().put("command","CONSUME").put("request_id","request-one")
            .put("nonce","DO_NOT_EXPORT_NONCE").put("payload","DO_NOT_EXPORT_PAYLOAD").put("socket_token","DO_NOT_EXPORT_TOKEN")
            .put("plan_after",new JSONObject().put("phase","DEFERRED").put("cycle_id","test-cycle").put("nonce","DROP_NESTED_NONCE"));
        journal.offer("ota_request_result",unsafe);
        unsafe.put("request_id","mutated-after-enqueue");
        journal.offer("ota_request_result",new JSONObject().put("command","RESET"));
        journal.offer("ota_request_result",new JSONObject().put("command","BEGIN"));
        check(journal.queued()==2&&journal.dropped()==1,"bounded queue accounts overflow without blocking");
        journal.drainOne();journal.drainOne();
        check(lines.size()==2,"accepted queue events written once");
        JSONObject first=lines.get(0);
        check(first.getString("request_id").equals("request-one"),"enqueue snapshots rather than retaining a mutable source");
        check(!first.has("nonce")&&!first.has("payload")&&!first.has("socket_token")&&!first.getJSONObject("plan_after").has("nonce"),"strict top-level and nested export field whitelist");
        check(first.getJSONObject("plan_after").getString("phase").equals("DEFERRED"),"safe native state preserved");
        check(first.getLong("dropped_event_count")==1,"next persisted record accounts dropped event");
        check(first.getLong("sequence")<lines.get(1).getLong("sequence"),"per-writer ordering retained");
        check(first.has("timestamp_ms")&&first.has("monotonic_ns")&&first.has("pid")&&first.has("thread_id")&&first.has("writer_id"),"time process and writer correlation present");
        journal.offer("ignored_poll",new JSONObject().put("command","STATE"));
        check(journal.queued()==0,"successful STATE poll data cannot enter the journal");
        journal.offer("invalid event name",new JSONObject());
        check(journal.queued()==0,"invalid event names are ignored");
        journal.offer("bounded_reason",new JSONObject().put("reason","x".repeat(2000)));
        journal.drainOne();check(lines.get(2).getString("reason").length()==768,"diagnostic strings are bounded");
        journal.offer("ota_client_trace",new JSONObject().put("command","TRACE").put("attempt_id","abc123").put("client_stage","open_ok"));
        journal.offer("ota_request_result",new JSONObject().put("command","CONSUME").put("authorized",true)
            .put("action_completed",false).put("activation_performed",false).put("phase","COMMITTING").put("cycle_id","actual-cycle"));
        journal.drainOne();journal.drainOne();
        check(lines.get(3).getString("attempt_id").equals("abc123")&&lines.get(3).getString("client_stage").equals("open_ok"),"fixed client lifecycle trace survives private-journal field filtering");
        check(lines.get(4).getBoolean("authorized")&&!lines.get(4).getBoolean("action_completed")&&!lines.get(4).getBoolean("activation_performed"),"journal separates the file grant from actual action success");
        check(lines.get(4).getString("phase").equals("COMMITTING"),"journal retains diagnostic execution phase");
        check(lines.get(4).getString("cycle_id").equals("actual-cycle"),"journal retains actual task identity without authorizing by it");
        journal.offer("session_protection",new JSONObject().put("target_pid",77).put("target_start",1234).put("deoptimized",true)
            .put("terminal_ready",true).put("continuation_ready",true).put("terminal_count",1).put("continuation_count",2).put("mapping","must-not-export"));
        journal.drainOne();
        check(lines.get(5).getInt("target_pid")==77&&lines.get(5).getBoolean("deoptimized")&&lines.get(5).getInt("continuation_count")==2&&!lines.get(5).has("mapping"),"private journal retains bounded real session proof fields without the mapping");
        journal.offer("slot_execution_failed",new JSONObject().put("authorized",true).put("official_status",0).put("expected_status",6)
            .put("official_pending",false).put("setter_invoked",true).put("setter_confirmed",false));
        journal.drainOne();
        check(lines.get(6).getBoolean("setter_invoked")&&!lines.get(6).getBoolean("setter_confirmed")&&lines.get(6).getBoolean("authorized"),"journal distinguishes actual setter failure from granted file permission");
        check(!lines.get(6).has("official_status")&&!lines.get(6).has("expected_status")&&!lines.get(6).has("official_pending"),"obsolete patch-status-derived pending fields are excluded from new diagnostics");

        int[] writes={0};List<JSONObject> recovered=new ArrayList<>();
        OtaEventJournal.Engine unavailable=new OtaEventJournal.Engine(line->{
            if(writes[0]++==0)throw new NoSuchMethodError("optional fixture writer ABI");
            recovered.add(new JSONObject(new String(line,StandardCharsets.UTF_8)));
        },3,false);
        unavailable.offer("first",new JSONObject());unavailable.drainOne();
        check(unavailable.dropped()==1,"writer LinkageError is isolated and counted");
        unavailable.offer("second",new JSONObject());unavailable.drainOne();
        check(recovered.size()==1&&recovered.get(0).getLong("dropped_event_count")==1,"later successful write includes diagnostic loss");

        DeferredSlotStoreTest.Fixture f=new DeferredSlotStoreTest.Fixture();List<JSONObject> observed=new ArrayList<>();
        f.store=new DeferredSlotStore(f.memory,BOOT,0,(event,fields)->{
            try{observed.add(new JSONObject(fields.toString()).put("event",event));}catch(Exception failed){throw new RuntimeException(failed);}
        });
        f.begin();JSONObject defer=f.send("DEFER");f.marker.token=1;
        JSONObject denied=new JSONObject(f.store.ota("CONSUME",f.marker,(s,t)->{throw new IOException("fixture service unavailable");},"consume-deny"));
        check(denied.getBoolean("authorized")&&!denied.getBoolean("ok")&&denied.getString("decision").equals("EXECUTION_FAILED")&&f.marker.token==0,"instrumented pending action consumes permission before actual execution failure");
        JSONObject denial=observed.stream().filter(x->"consume-deny".equals(x.optString("request_id")) &&
            "ota_plan_result".equals(x.optString("event"))).findFirst().orElseThrow();
        check(denial.getJSONObject("marker_after").getInt("state")==0&&denial.getBoolean("authorized"),"actual consumed marker and grant remain distinct from execution failure");
        check(denial.getString("reason").contains("fixture service unavailable"),"actual service failure is exported without inventing a phase authorization condition");
        check(defer.getBoolean("durable")&&defer.getInt("target_slot")==1,"original durable DEFER contract retained");
        JSONObject done=f.send("DONE");
        check(done.getBoolean("durable")&&done.getInt("target_slot")==1&&f.memory.record.getString("phase").equals("DONE"),"DONE durable target follows real persistence");
        int persistenceEvents=f.memory.events.size();JSONObject repeated=f.send("DONE");
        check(repeated.getBoolean("durable")&&repeated.getInt("target_slot")==1&&f.memory.events.size()==persistenceEvents,"idempotent DONE acknowledges already durable state");
        f.marker.token=1;
        check(new JSONObject(f.consume()).getString("decision").equals("ALLOW")&&f.marker.token==0&&f.commits==1,"new user grant permits a retry after the logged execution failure");
        int observedCount=observed.size();f.store.ota("STATE",f.marker,(s,t)->false);
        check(observed.size()==observedCount,"actual store STATE produces no journal event");

        DeferredSlotStoreTest.Fixture failed=new DeferredSlotStoreTest.Fixture();failed.begin();failed.send("DEFER");failed.memory.failWrite="DONE";
        try{failed.send("DONE");throw new AssertionError("undurable DONE accepted");}catch(IOException expected){checks++;}
        check(failed.memory.record.getString("phase").equals("DEFERRED"),"failed DONE persistence leaves unresolved phase");
        failed.memory.failWrite="";JSONObject failAck=failed.send("FAIL");
        check(failAck.getBoolean("durable")&&failAck.getInt("target_slot")==1&&failed.memory.record.getString("phase").equals("FAILED"),"FAIL durable acknowledgment follows persisted terminal failure");
        check(failed.send("DONE").getBoolean("ok")&&failed.marker.token==0,"native diagnostic completion never grants file permission");

        DeferredSlotStoreTest.Fixture ignored=new DeferredSlotStoreTest.Fixture();
        ignored.store=new DeferredSlotStore(ignored.memory,BOOT,0,(event,fields)->{throw new NoSuchMethodError("diagnostic fixture");});
        ignored.begin();ignored.marker.token=1;
        check(new JSONObject(ignored.consume()).getString("decision").equals("ALLOW")&&ignored.marker.token==0,"observer LinkageError never replaces original transaction outcome");
        System.out.println("OTA_EVENT_JOURNAL_CHECKS="+checks);
    }
}
