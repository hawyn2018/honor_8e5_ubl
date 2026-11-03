package net.hawyn.hnubl.module;

import java.lang.reflect.Method;
import org.json.JSONObject;

/** Actual typed setSlot reflection with controlled objects, never Android Binder/HAL. */
public final class MarkerSlotExecutionTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    public static final class Engine {
        int calls, reads, status = 0, mainStatus = 6; boolean result = true, fail, statusFails;
        DeferredSlotStoreTest.Marker marker;
        public int getStatus() { reads++; if(statusFails)throw new IllegalStateException("patch status unavailable");return status; }
        public boolean setSlot() { calls++; if(marker!=null&&marker.token!=0)throw new AssertionError("setter before permission");if(fail)throw new IllegalStateException("official setter failed");return result; }
    }
    public static final class EngineWithoutStatus {
        int calls; DeferredSlotStoreTest.Marker marker;
        public boolean setSlot() { calls++; if(marker!=null&&marker.token!=0)throw new AssertionError("setter before permission");return true; }
    }
    public static final class DifferentStatusAbi {
        int calls, reads;
        public String getStatus() { reads++;throw new AssertionError("unrelated status ABI was read"); }
        public boolean setSlot() { calls++;return true; }
    }
    public static final class WrongAbi { public int setSlot() { return 1; } }
    static final class Access implements TypedSlotCommitter.Access {
        Object engine; Method method; int lookups; Runnable onMethod;
        Access() throws Exception { this(new Engine()); }
        Access(Object engine) throws Exception { this.engine=engine;method=engine.getClass().getMethod("setSlot"); }
        public Object proxy(){lookups++;return engine;}
        public Method method(){if(onMethod!=null)onMethod.run();return method;}
    }
    private static JSONObject run(DeferredSlotStoreTest.Fixture f,Access a)throws Exception {
        if(a.engine instanceof Engine)((Engine)a.engine).marker=f.marker;
        if(a.engine instanceof EngineWithoutStatus)((EngineWithoutStatus)a.engine).marker=f.marker;
        return new JSONObject(f.store.ota("CONSUME",f.marker,new TypedSlotCommitter(a)));
    }
    private static void noStatusEvidence(JSONObject response,String label) {
        check(!response.has("official_status")&&!response.has("expected_status")&&!response.has("official_pending"),label);
    }
    public static void main(String[] args)throws Exception {
        for(String phase:new String[]{"DEFERRED","DONE","COMMITTING","FAILED","UNKNOWN","COMMITTED"}){
            DeferredSlotStoreTest.Fixture f=new DeferredSlotStoreTest.Fixture();f.done();f.memory.record.put("phase",phase);
            Access a=new Access();Engine e=(Engine)a.engine;JSONObject r=run(f,a);
            check(r.getBoolean("authorized")&&r.getString("decision").equals("ALLOW")&&e.reads==0&&e.calls==1&&f.marker.token==0,"each local phase uses the actual official setter after file permission "+phase);
            check(r.getBoolean("setter_invoked")&&r.getBoolean("setter_confirmed"),"exact setter evidence exported "+phase);
            noStatusEvidence(r,"no derived main-status evidence "+phase);
        }
        for(String context:new String[]{"none","old_boot","old_source"}){
            DeferredSlotStoreTest.Fixture f=new DeferredSlotStoreTest.Fixture();
            if(!context.equals("none")){f.done();if(context.equals("old_boot"))f.memory.record.put("boot_id","bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");else f.memory.record.put("source_slot",1).put("target_slot",0);}
            f.marker.token=1;Access a=new Access();Engine e=(Engine)a.engine;JSONObject r=run(f,a);
            check(r.getString("decision").equals("ALLOW")&&e.reads==0&&e.calls==1,"missing or stale registry does not skip official execution "+context);
            check(r.getBoolean("recovered")&&f.memory.record.getBoolean("completed")&&f.memory.record.getString("activation_source").equals("official_update_engine"),"official success is registered as recovered "+context);
        }
        for(int patchStatus:new int[]{0,1,2,3,4,5,6,7,11}){
            DeferredSlotStoreTest.Fixture f=new DeferredSlotStoreTest.Fixture();f.done();Access a=new Access();Engine e=(Engine)a.engine;e.status=patchStatus;
            JSONObject r=run(f,a);
            check(r.getBoolean("authorized")&&r.getBoolean("ok")&&r.getString("decision").equals("ALLOW")&&r.getInt("state")==0,"patch status is not an extra permission condition "+patchStatus);
            check(e.mainStatus==6&&e.reads==0&&e.calls==1&&r.getBoolean("setter_invoked"),"real patch-status/main-status distinction never suppresses setter "+patchStatus);
            noStatusEvidence(r,"patch status is never mislabelled as official pending "+patchStatus);
        }
        DeferredSlotStoreTest.Fixture unreadable=new DeferredSlotStoreTest.Fixture();unreadable.done();Access throwingStatus=new Access();Engine unread=(Engine)throwingStatus.engine;unread.statusFails=true;
        check(run(unreadable,throwingStatus).getString("decision").equals("ALLOW")&&unread.reads==0&&unread.calls==1,"throwing getStatus is never called and cannot block the setter");
        DeferredSlotStoreTest.Fixture noStatus=new DeferredSlotStoreTest.Fixture();noStatus.done();EngineWithoutStatus setterOnly=new EngineWithoutStatus();
        check(run(noStatus,new Access(setterOnly)).getString("decision").equals("ALLOW")&&setterOnly.calls==1,"getStatus absence does not become an ABI requirement");
        DeferredSlotStoreTest.Fixture differentStatus=new DeferredSlotStoreTest.Fixture();differentStatus.done();DifferentStatusAbi unrelated=new DifferentStatusAbi();
        check(run(differentStatus,new Access(unrelated)).getString("decision").equals("ALLOW")&&unrelated.calls==1&&unrelated.reads==0,"unrelated getStatus return ABI is ignored");
        DeferredSlotStoreTest.Fixture denied=new DeferredSlotStoreTest.Fixture();Access deniedAccess=new Access();
        JSONObject deniedResult=run(denied,deniedAccess);
        check(!deniedResult.getBoolean("authorized")&&deniedResult.getString("decision").equals("DENY")&&deniedAccess.lookups==0&&((Engine)deniedAccess.engine).calls==0,"marker0 denies before looking up the official service");
        DeferredSlotStoreTest.Fixture missing=new DeferredSlotStoreTest.Fixture();missing.done();Access absent=new Access();Engine actual=(Engine)absent.engine;absent.engine=null;
        JSONObject unavailable=run(missing,absent);
        check(unavailable.getBoolean("authorized")&&unavailable.getString("decision").equals("EXECUTION_FAILED")&&actual.calls==0&&missing.marker.token==0,"missing service does not fall through to reboot");
        missing.store.ota("CONSUME",missing.marker,new TypedSlotCommitter(absent));
        check(absent.lookups==1,"no new file grant means no official lookup or implicit retry");
        missing.marker.token=1;absent.engine=actual;check(run(missing,absent).getString("decision").equals("ALLOW")&&actual.calls==1,"new grant after service restart uses the actual official setter");
        Access swap=new Access();Engine original=(Engine)swap.engine;Engine other=new Engine();swap.onMethod=()->swap.engine=other;
        DeferredSlotStoreTest.Fixture bound=new DeferredSlotStoreTest.Fixture();bound.done();JSONObject boundResult=run(bound,swap);
        check(boundResult.getString("decision").equals("ALLOW")&&swap.lookups==1&&original.reads==0&&original.calls==1&&other.calls==0,"setter uses the single captured proxy even if the accessor changes");
        for(boolean throwing:new boolean[]{false,true}){
            DeferredSlotStoreTest.Fixture f=new DeferredSlotStoreTest.Fixture();f.done();Access a=new Access();Engine e=(Engine)a.engine;e.result=false;e.fail=throwing;
            JSONObject r=run(f,a);check(r.getString("decision").equals("EXECUTION_FAILED")&&r.getBoolean("authorized")&&e.calls==1&&e.reads==0&&f.marker.token==0,"official false/throw blocks original reboot");
            check(r.getBoolean("setter_invoked")&&!r.getBoolean("setter_confirmed"),"false/throw reports actual invocation without fabricating confirmation");
            f.store.ota("CONSUME",f.marker,new TypedSlotCommitter(a));check(e.calls==1,"official failure never automatically replays");
        }
        DeferredSlotStoreTest.Fixture bad=new DeferredSlotStoreTest.Fixture();bad.done();
        check(run(bad,new Access(new WrongAbi())).getString("decision").equals("EXECUTION_FAILED"),"actual setSlot return ABI is still required");
        DeferredSlotStoreTest.Fixture mismatch=new DeferredSlotStoreTest.Fixture();mismatch.done();Access mismatched=new Access();mismatched.method=EngineWithoutStatus.class.getMethod("setSlot");
        check(run(mismatch,mismatched).getString("decision").equals("EXECUTION_FAILED")&&((Engine)mismatched.engine).calls==0,"setter/proxy type mismatch remains an actual capability failure");
        DeferredSlotStoreTest.Fixture save=new DeferredSlotStoreTest.Fixture();save.done();save.memory.failWrite="COMMITTED";Access saveAccess=new Access();
        JSONObject saved=run(save,saveAccess);check(saved.getString("decision").equals("ALLOW")&&saved.has("registry_warning")&&((Engine)saveAccess.engine).calls==1,"diagnostic save error does not override confirmed official success");
        DeferredSlotStoreTest.Marker token=new DeferredSlotStoreTest.Marker(new java.util.ArrayList<>());token.token=1;Access fallbackAccess=new Access();((Engine)fallbackAccess.engine).marker=token;
        JSONObject fallback=new JSONObject(new LegacyOtaFallback("registry unavailable",new TypedSlotCommitter(fallbackAccess)).request("CONSUME",token));
        check(fallback.getString("decision").equals("ALLOW")&&fallback.getBoolean("setter_confirmed")&&((Engine)fallbackAccess.engine).calls==1&&((Engine)fallbackAccess.engine).reads==0,"fallback uses the real setter without unrelated patch status");
        token.token=1;((Engine)fallbackAccess.engine).statusFails=true;
        JSONObject retried=new JSONObject(new LegacyOtaFallback("registry unavailable",new TypedSlotCommitter(fallbackAccess)).request("CONSUME",token));
        check(retried.getString("decision").equals("ALLOW")&&((Engine)fallbackAccess.engine).calls==2&&((Engine)fallbackAccess.engine).reads==0,"fresh grant in fallback does not consult a throwing patch-status query");
        System.out.println("MARKER_SLOT_EXECUTION_CHECKS="+checks);
    }
}
