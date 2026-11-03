package net.hawyn.hnubl.module;

import android.os.IBinder;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Calls the current platform's typed API; no Binder transaction numbers or shell slot commands. */
final class TypedSlotCommitter implements DeferredSlotStore.Committer {
    private final BootIdentity boot;
    TypedSlotCommitter(BootIdentity boot) { this.boot = boot; }
    @Override public boolean commit(int source, int target) throws IOException {
        BootIdentity now = BootIdentity.current();
        if (!boot.bootId.equals(now.bootId) || boot.sourceSlot != now.sourceSlot || source != now.sourceSlot || target != 1 - source) {
            throw new IOException("Current boot/source changed before slot commit");
        }
        try {
            Object engine=proxy();
            if(engine==null)throw new IOException("UpdateEngine service unavailable at reboot gate");
            return Boolean.TRUE.equals(slotMethod().invoke(engine));
        } catch (InvocationTargetException rejected) {
            throw new IOException("Typed slot commit did not return a confirmed result", rejected.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            throw new IOException("Typed slot commit unavailable", unavailable);
        }
    }
    static boolean available() throws IOException {
        try { slotMethod();return proxy()!=null; }
        catch(ReflectiveOperationException | RuntimeException unavailable){throw new IOException("Typed slot ABI unavailable",unavailable);}
    }
    private static Method slotMethod()throws ReflectiveOperationException,IOException{
        Method method=Class.forName("android.os.IUpdateEngine").getMethod("setSlot");
        if(method.getParameterTypes().length!=0 || method.getReturnType()!=boolean.class)throw new IOException("Typed setSlot boolean ABI unavailable");
        return method;
    }
    private static Object proxy()throws ReflectiveOperationException,IOException{
        Class<?> api=Class.forName("android.os.IUpdateEngine");
        Class<?> services=Class.forName("android.os.ServiceManager");
        IBinder binder=(IBinder)services.getMethod("getService",String.class).invoke(null,"android.os.UpdateEngineService");
        if(binder==null)return null;
        Object engine=Class.forName("android.os.IUpdateEngine$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        if(engine==null || !api.isInstance(engine))throw new IOException("Typed UpdateEngine proxy unavailable");
        return engine;
    }
}
