package net.hawyn.hnubl.module;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** A synthetic missing optional API must not kill the VM or spend a completion token. */
public final class NativeFailureBoundaryTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        List<String> warnings=new ArrayList<>();AtomicInteger uncaught=new AtomicInteger();
        DeferredSlotStoreTest.Fixture fixture=new DeferredSlotStoreTest.Fixture();fixture.begin();fixture.marker.token=1;
        NativeSlotRuntime runtime=new NativeSlotRuntime(new java.io.File(args[0]),"fixture",fixture.store);
        java.lang.reflect.Method sink=NativeSlotRuntime.class.getDeclaredMethod("unavailable",String.class);sink.setAccessible(true);
        for(String stage:new String[]{"watcher","injector monitor","endpoint listener","endpoint request","shutdown"}){
            Thread worker=new Thread(()->NativeFailureBoundary.run(stage,()->{throw new NoSuchMethodError("fixture missing Android NIO convenience API");},warning->{
                warnings.add(warning);try{sink.invoke(runtime,warning);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            }));
            worker.setUncaughtExceptionHandler((thread,error)->uncaught.incrementAndGet());worker.start();worker.join();
        }
        check(uncaught.get()==0&&warnings.size()==5,"all optional thread boundary linkage errors are contained");
        check(warnings.stream().allMatch(w->w.contains("NoSuchMethodError")),"warning retains exact missing-ABI diagnosis");
        check(!runtime.ready()&&!runtime.waiting()&&runtime.warning().contains("NoSuchMethodError"),"actual runtime warning state retains missing ABI and never claims native readiness");
        check(fixture.marker.token==1,"optional feature failure does not RESET or consume completed token");
        check(new JSONObject(fixture.consume()).getString("decision").equals("ALLOW")&&fixture.commits==0,"current ARMED token1 still continues original slot flow");
        check(new JSONObject(fixture.consume()).getString("decision").equals("DENY"),"token0 stays denied after original one-shot consume");
        check(NativeFailureBoundary.run("healthy",()->{},warnings::add),"normal execution succeeds");
        boolean vmError=false,death=false;
        try{NativeFailureBoundary.run("VM",()->{throw new OutOfMemoryError("fixture");},warnings::add);}catch(OutOfMemoryError expected){vmError=true;}
        try{NativeFailureBoundary.run("death",()->{throw new ThreadDeath();},warnings::add);}catch(ThreadDeath expected){death=true;}
        check(vmError&&death,"VM fatal errors and ThreadDeath are not silently swallowed");
        System.out.println("NATIVE_FAILURE_BOUNDARY_CHECKS="+checks);
    }
}
