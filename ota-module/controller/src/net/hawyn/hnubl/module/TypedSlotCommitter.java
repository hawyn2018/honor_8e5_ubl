package net.hawyn.hnubl.module;

import android.os.IBinder;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;

/** Invokes the official no-argument setter after the resident file permission is consumed. */
final class TypedSlotCommitter implements DeferredSlotStore.Committer {
    interface Access {
        Object proxy() throws ReflectiveOperationException, IOException;
        Method method() throws ReflectiveOperationException, IOException;
    }
    private static final Access PLATFORM = new Access() {
        public Object proxy() throws ReflectiveOperationException, IOException { return TypedSlotCommitter.proxy(); }
        public Method method() throws ReflectiveOperationException, IOException { return slotMethod(); }
    };
    private final Access access;
    private final ThreadLocal<JSONObject> lastEvidence = new ThreadLocal<>();
    TypedSlotCommitter() { this(PLATFORM); }
    TypedSlotCommitter(BootIdentity ignored) { this(PLATFORM); }
    TypedSlotCommitter(Access access) { this.access = access; }
    @Override public boolean commit(int source, int target) throws IOException {
        JSONObject proof = new JSONObject(); lastEvidence.set(proof);
        put(proof, "setter_invoked", false); put(proof, "setter_confirmed", false);
        try {
            Object engine = access.proxy();
            if (engine == null) throw new IOException("UpdateEngine service unavailable at reboot gate");
            Method setter = access.method();
            require(setter, "setSlot", boolean.class);
            if (!setter.getDeclaringClass().isInstance(engine))
                throw new IOException("Typed UpdateEngine proxy/method mismatch");
            // OEM getStatus() may report patch state, not the main update status; it is not a grant condition.
            // No target is synthesized from old local metadata: the official API selects and activates its own slot.
            put(proof, "setter_invoked", true);
            boolean confirmed = Boolean.TRUE.equals(setter.invoke(engine)); put(proof, "setter_confirmed", confirmed);
            return confirmed;
        } catch (InvocationTargetException rejected) {
            throw new IOException("Official UpdateEngine operation did not return a confirmed result", rejected.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IOException("Official typed OTA execution capability failed", failure);
        }
    }
    @Override public JSONObject evidence() {
        JSONObject value = lastEvidence.get();
        try { return value == null ? new JSONObject() : new JSONObject(value.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }
    private static void put(JSONObject value, String key, Object data) {
        try { value.put(key, data); } catch (Exception | LinkageError ignored) { /* Optional evidence never replaces the actual API result. */ }
    }
    static boolean available() throws IOException {
        try { slotMethod(); return proxy() != null; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { throw new IOException("Typed official OTA ABI unavailable", unavailable); }
    }
    private static Method slotMethod() throws ReflectiveOperationException, IOException {
        Method method = Class.forName("android.os.IUpdateEngine").getMethod("setSlot"); require(method, "setSlot", boolean.class); return method;
    }
    private static void require(Method method, String name, Class<?> result) throws IOException {
        if (method == null || !name.equals(method.getName()) || method.getParameterTypes().length != 0 || method.getReturnType() != result)
            throw new IOException("Typed official " + name + " ABI unavailable");
    }
    private static Object proxy() throws ReflectiveOperationException, IOException {
        Class<?> api = Class.forName("android.os.IUpdateEngine"), services = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder)services.getMethod("getService", String.class).invoke(null, "android.os.UpdateEngineService");
        if (binder == null) return null;
        Object engine = Class.forName("android.os.IUpdateEngine$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (engine == null || !api.isInstance(engine)) throw new IOException("Typed UpdateEngine proxy unavailable");
        return engine;
    }
}
