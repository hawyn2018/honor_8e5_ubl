package net.hawyn.hnubl.module;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;

/** Borrows the resident marker's lock and transaction implementation across updates. */
final class ResidentMarkerBridge {
    private static final String BROKER = "net.hawyn.hnubl.module.RootBroker";
    private static final String MARKER = "net.hawyn.hnubl.module.OtaMarker";
    private final Object receiver;
    private final Method request;
    final boolean legacy;

    private ResidentMarkerBridge(Object receiver, Method request, boolean legacy) {
        this.receiver = receiver;
        this.request = request;
        this.legacy = legacy;
    }

    static ResidentMarkerBridge connect(ClassLoader resident) throws IOException {
        try {
            Class<?> broker = Class.forName(BROKER, false, resident);
            if (broker.getClassLoader() != resident) throw new IOException("Unexpected resident broker loader");
            try {
                Method api = broker.getMethod("requestOtaMarker", String.class);
                if (!Modifier.isStatic(api.getModifiers()) || api.getReturnType() != String.class ||
                        api.getDeclaringClass() != broker) throw new IOException("Invalid resident marker ABI");
                return new ResidentMarkerBridge(null, api, false);
            } catch (NoSuchMethodException oldBase) {
                // 2.8.x kept its marker only in the two listener closures. Read
                // that one existing object; constructing another would lose the
                // instance lock or permit two CONSUME transactions.
                synchronized (broker) {
                    Field listenersField = broker.getDeclaredField("listeners");
                    if (!Modifier.isStatic(listenersField.getModifiers()) ||
                            listenersField.getType() != Thread[].class) throw new IOException("Invalid resident listeners ABI");
                    listenersField.setAccessible(true);
                    Thread[] listeners = (Thread[]) listenersField.get(null);
                    if (listeners == null || listeners.length == 0) throw new IOException("Resident listeners unavailable");
                    IdentityHashMap<Object, Boolean> markers = new IdentityHashMap<>();
                    Class<?> markerType = Class.forName(MARKER, false, resident);
                    if (markerType.getClassLoader() != resident) throw new IOException("Unexpected marker loader");
                    for (Thread listener : listeners) {
                        if (listener == null) continue;
                        for (Object runnable : listenerRunnables(listener)) {
                            if (runnable.getClass().getClassLoader() != resident) continue;
                            for (Field capture : runnable.getClass().getDeclaredFields()) {
                                if (Modifier.isStatic(capture.getModifiers()) || capture.getType() != markerType) continue;
                                capture.setAccessible(true);
                                Object marker = capture.get(runnable);
                                if (marker != null && marker.getClass() == markerType) markers.put(marker, Boolean.TRUE);
                            }
                        }
                    }
                    if (markers.size() != 1) throw new IOException("Unique resident marker required");
                    Method api = markerType.getDeclaredMethod("request", String.class);
                    if (Modifier.isStatic(api.getModifiers()) || !Modifier.isSynchronized(api.getModifiers()) ||
                            api.getReturnType() != String.class) throw new IOException("Invalid legacy marker ABI");
                    api.setAccessible(true);
                    ResidentMarkerBridge bridge = new ResidentMarkerBridge(markers.keySet().iterator().next(), api, true);
                    // Only the obsolete OTA retry loop is retired. ROOT listeners,
                    // accepted ROOT commands and the resident VM remain intact.
                    for (Thread listener : listeners) {
                        if (listener != null && "hnubl-ota-endpoint".equals(listener.getName())) listener.interrupt();
                    }
                    return bridge;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("Resident marker bridge unavailable", failure);
        }
    }

    private static List<Object> listenerRunnables(Thread listener) throws ReflectiveOperationException {
        List<Object> result = new ArrayList<>();
        // Libcore uses a direct Runnable field; newer OpenJDK stores it in a
        // Thread-owned holder. Match the type relationship, not either field's
        // implementation name. No arbitrary heap traversal is performed.
        for (Field field : Thread.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (Runnable.class.isAssignableFrom(field.getType()) && !Thread.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object value = field.get(listener); if (value != null) result.add(value);
            } else if (field.getType().getEnclosingClass() == Thread.class) {
                field.setAccessible(true);
                Object holder = field.get(listener); if (holder == null) continue;
                for (Field child : field.getType().getDeclaredFields()) {
                    if (Modifier.isStatic(child.getModifiers()) || !Runnable.class.isAssignableFrom(child.getType()) ||
                            Thread.class.isAssignableFrom(child.getType())) continue;
                    child.setAccessible(true);
                    Object value = child.get(holder); if (value != null) result.add(value);
                }
            }
        }
        return result;
    }

    String request(String command) throws IOException {
        requireCommand(command);
        try {
            Object response = request.invoke(receiver, command);
            if (!(response instanceof String)) throw new IOException("Invalid resident marker response");
            return (String) response;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Resident marker transaction failed", cause);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IOException("Resident marker transaction unavailable", failure);
        }
    }

    static void requireCommand(String command) throws IOException {
        if (!"STATE".equals(command) && !"RESET".equals(command) && !"CONSUME".equals(command)) {
            throw new IOException("OTA command rejected");
        }
    }
}
