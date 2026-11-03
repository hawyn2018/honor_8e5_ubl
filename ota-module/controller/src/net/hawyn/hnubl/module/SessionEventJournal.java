package net.hawyn.hnubl.module;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Bounded event-driven proof summaries; never copies an agent mapping or changes readiness. */
final class SessionEventJournal {
    private final int pid;
    private final long start;
    private final String activation, engine;
    private final DeferredSlotStore.Observer observer;
    private final Map<String, String> proofs = new HashMap<>();
    private boolean started, ended;
    SessionEventJournal(int pid, long start, String activation, String engine, DeferredSlotStore.Observer observer) {
        this.pid=pid;this.start=start;this.activation=activation;this.engine=engine;this.observer=observer;
    }
    synchronized void start() { if (!started) { started=true;emit("session_start", new JSONObject()); } }
    synchronized void accept(JSONObject event) {
        String kind=event.optString("event");
        if (!kind.equals("critical_ready")&&!kind.equals("ready")&&!kind.equals("runtime_deoptimized")&&
                !kind.equals("terminal_bound")&&!kind.equals("continuation_bound")) return;
        try {
            JSONObject fields=new JSONObject().put("phase",kind);
            for(String key:new String[]{"critical_ready","broker_ready","hook_ready","deoptimized","terminal_ready","continuation_ready"})
                if(event.opt(key) instanceof Boolean)fields.put(key,event.get(key));
            JSONObject counts=event.optJSONObject("counts");
            if(counts!=null){count(fields,"terminal_count",counts.opt("reboot_terminal"));count(fields,"continuation_count",counts.opt("reboot_continuation"));}
            if(kind.equals("terminal_bound"))count(fields,"terminal_count",event.opt("count"));
            if(kind.equals("continuation_bound"))count(fields,"continuation_count",event.opt("count"));
            String value=fields.toString();if(value.equals(proofs.get(kind)))return;proofs.put(kind,value);
            emit("session_protection",fields);
        }catch(Exception|LinkageError ignored){ }
    }
    synchronized void injectorExit(int code,boolean sameProcess,boolean protectedSession){
        try{emit("session_injector_exit",new JSONObject().put("code",code).put("same_process",sameProcess)
            .put("hook_ready",protectedSession).put("phase",protectedSession?"eternalized":"initialization_failed"));}catch(Exception|LinkageError ignored){ }
    }
    synchronized void failure(String error){try{
        String summary=error;
        if(error!=null&&error.startsWith("{")) {
            try {JSONObject parsed=new JSONObject(error);summary=parsed.optString("message",parsed.optString("description","Agent initialization failed"));}
            catch(Exception ignored){summary="Agent initialization failed (invalid structured error)";}
        }
        emit("session_failure",new JSONObject().put("error",summary));
    }catch(Exception|LinkageError ignored){ }}
    synchronized void end(String reason){if(ended)return;ended=true;try{emit("session_end",new JSONObject().put("reason",reason));}catch(Exception|LinkageError ignored){ }}
    synchronized void targetStop(boolean ok,String reason,String error){
        try{JSONObject fields=new JSONObject().put("response_ok",ok).put("reason",reason);if(error!=null)fields.put("error",error);emit("session_target_stop",fields);}catch(Exception|LinkageError ignored){ }
    }
    private static void count(JSONObject fields,String key,Object value)throws org.json.JSONException{
        if(value instanceof Number&&((Number)value).longValue()>=0&&((Number)value).doubleValue()==(double)((Number)value).longValue())fields.put(key,value);
    }
    private void emit(String event,JSONObject fields){
        try{fields.put("target_pid",pid).put("target_start",start).put("activation_id",activation).put("engine_sha256",engine);observer.record(event,fields);}
        catch(Exception|LinkageError ignored){ }
    }
}
