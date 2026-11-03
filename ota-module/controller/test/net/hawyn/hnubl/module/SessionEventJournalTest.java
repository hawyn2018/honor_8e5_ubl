package net.hawyn.hnubl.module;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Actual journal summary and injector-start wrapper; no process is started or stopped. */
public final class SessionEventJournalTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        List<JSONObject> lines=new ArrayList<>();
        SessionEventJournal log=new SessionEventJournal(77,1234,"activation-fixture","a".repeat(64),(event,fields)->{
            try{lines.add(new JSONObject(fields.toString()).put("event",event));}catch(Exception invalid){throw new AssertionError(invalid);}
        });
        log.start();log.start();
        check(lines.size()==1&&lines.get(0).getString("event").equals("session_start"),"start is event-driven once");
        check(lines.get(0).getInt("target_pid")==77&&lines.get(0).getLong("target_start")==1234&&lines.get(0).getString("activation_id").equals("activation-fixture"),"target identity and activation bind every session");
        JSONObject proof=new JSONObject().put("event","critical_ready").put("critical_ready",true).put("broker_ready",false).put("hook_ready",false)
            .put("deoptimized",true).put("terminal_ready",true).put("continuation_ready",true)
            .put("counts",new JSONObject().put("reboot_terminal",1).put("reboot_continuation",2).put("unknown",999))
            .put("mapping",new JSONObject().put("SECRET_MAPPING","must-not-export")).put("nonce","must-not-export");
        log.accept(proof);log.accept(proof);
        check(lines.size()==2,"unchanged readiness does not spam the journal");
        JSONObject summary=lines.get(1);
        check(summary.getBoolean("deoptimized")&&summary.getBoolean("terminal_ready")&&summary.getBoolean("continuation_ready")&&summary.getInt("terminal_count")==1&&summary.getInt("continuation_count")==2,"actual terminal and continuation bind proof preserved");
        check(!summary.has("mapping")&&!summary.has("nonce")&&!summary.has("counts"),"no whole mapping or secret payload copied");
        log.accept(new JSONObject().put("event","version_filter").put("mapping","ignored"));
        check(lines.size()==2,"unrelated runtime events are not persisted as session polls");
        log.accept(new JSONObject(proof.toString()).put("event","ready").put("broker_ready",true).put("hook_ready",true));
        check(lines.size()==3&&lines.get(2).getBoolean("hook_ready"),"completed readiness transition persisted");
        log.injectorExit(0,true,true);
        check(lines.get(3).getString("event").equals("session_injector_exit")&&lines.get(3).getString("phase").equals("eternalized")&&lines.get(3).getBoolean("same_process"),"injector exit is distinguished from target process exit");
        log.failure(new JSONObject().put("message","critical terminal bind failed").put("mapping",proof).toString());
        check(lines.get(4).getString("error").equals("critical terminal bind failed"),"structured error is reduced to message instead of dumping mapping");
        log.targetStop(false,"critical_session_failure","fixture command failure");
        check(!lines.get(5).getBoolean("response_ok")&&lines.get(5).getString("event").equals("session_target_stop"),"failed stop attempt remains truthful");
        log.end("controller_cleanup");log.end("duplicate");
        check(lines.size()==7&&lines.get(6).getString("event").equals("session_end"),"cleanup lifecycle is recorded once");
        SessionEventJournal unavailable=new SessionEventJournal(1,2,"a","b",(event,fields)->{throw new NoSuchMethodError("optional journal failure");});
        unavailable.start();unavailable.accept(proof);unavailable.failure("fixture");unavailable.end("done");checks++;

        int[] starts={0},failures={0};
        HookController.startCriticalSession(()->starts[0]++,error->failures[0]++);
        check(starts[0]==1&&failures[0]==0,"actual startup wrapper calls successful launch once");
        for(Throwable failure:new Throwable[]{new IOException("injector missing"),new IllegalStateException("init failed"),new NoSuchMethodError("platform ABI")}){
            int before=failures[0];
            HookController.startCriticalSession(()->{starts[0]++;if(failure instanceof Exception)throw (Exception)failure;throw (LinkageError)failure;},error->{check(error==failure,"failure callback preserves exact launch cause");failures[0]++;});
            check(failures[0]==before+1,"real injector startup failure reaches existing critical cleanup once");
        }
        check(starts[0]==4,"startup failures are not silently retried");
        System.out.println("SESSION_EVENT_JOURNAL_CHECKS="+checks);
    }
}
