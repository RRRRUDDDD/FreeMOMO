package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Minimal XposedBridge facade: logging, hookMethod and the readiness handshake. */
public final class XposedBridge {

    public static final String TAG = "FreeMOMO";

    private XposedBridge() {}

    public static void log(String text) {
        android.util.Log.i(TAG, text);
    }

    public static void log(Throwable t) {
        android.util.Log.e(TAG, "module reported error", t);
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        // Pass the callback's own priority through: the module dex constructs the
        // update guard with XC_MethodHook(PRIORITY_HIGHEST) (verified in its source
        // and string pool), and everything else uses the no-arg ctor (priority 0).
        // The old hardcoded 50 flattened that signal; relative order among equal
        // priorities is insertion order either way, so plain hooks keep their order.
        Object entry = com.repack.art.ArtHookEngine.hook(method, callback, callback.priority);
        if (isApplicationAttach(method)) {
            registerAttachDelivery(method, callback);
        }
        return new XC_MethodHook.Unhook(entry, callback);
    }

    private static boolean isApplicationAttach(Member method) {
        return method instanceof java.lang.reflect.Method
                && "android.app.Application".equals(method.getDeclaringClass().getName())
                && "attach".equals(method.getName());
    }

    private static final java.util.List<XC_MethodHook> ATTACH_DELIVERABLES =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.IdentityHashMap<XC_MethodHook, Boolean> DELIVERED =
            new java.util.IdentityHashMap<>();
    private static volatile java.lang.reflect.Method sAttachMethod;

    private static void registerAttachDelivery(Member attach, XC_MethodHook callback) {
        synchronized (DELIVERED) {
            if (DELIVERED.containsKey(callback)) return;
        }
        ATTACH_DELIVERABLES.add(callback);
        if (attach instanceof java.lang.reflect.Method) {
            sAttachMethod = (java.lang.reflect.Method) attach;
        }
    }

    private static boolean markDelivered(XC_MethodHook cb) {
        synchronized (DELIVERED) {
            return DELIVERED.put(cb, Boolean.TRUE) == null;
        }
    }

    /**
     * The 5.6 shell never drives Application.attach through its ArtMethod:
     * boot.oat is unpatched and the same hook works in plain apps, yet zero
     * bridges dispatch in this process, including shell-class hooks on
     * callsites that must be interpreted/JIT. The most consistent explanation
     * is a replaced Instrumentation driving attachBaseContext directly.
     * Delivery therefore does not depend on attach: RootlessBoot arms
     * ApplicationWrapper.attachBaseContext (the shell's own init runs inside
     * that call, so the after side sees the decrypted business dex) plus
     * Application.attachBaseContext, and a poller delivers as a last resort --
     * all with an attach-shaped param. The module's install-state guard makes
     * whichever fires first win.
     */
    public static void deliverPendingAttach(Object app, Object base, String via) {
        if (!(app instanceof android.app.Application)) return;
        if (!(base instanceof android.content.Context)) return;
        java.lang.reflect.Method attach = sAttachMethod;
        if (attach == null) return;
        boolean any = false;
        for (XC_MethodHook cb : ATTACH_DELIVERABLES) {
            if (!markDelivered(cb)) continue;
            any = true;
            try {
                XC_MethodHook.MethodHookParam fake = new XC_MethodHook.MethodHookParam();
                fake.method = attach;
                fake.thisObject = app;
                fake.args = new Object[]{base};
                cb.afterHookedMethod(fake);
                android.util.Log.i(TAG, "attach delivered via " + via);
            } catch (Throwable t) {
                // Release the delivered mark so the poller's bounded retry can
                // hand this callback another pass (the module's own install
                // state machine keeps whichever attempt first wins).
                synchronized (DELIVERED) {
                    DELIVERED.remove(cb);
                }
                android.util.Log.e(TAG, "attach delivery failed via " + via, t);
            }
        }
        if (!any) {
            android.util.Log.i(TAG, "attach delivery via " + via + ": nothing pending");
        }
    }

    /** True while at least one attach callback still awaits delivery. */
    public static boolean hasPendingAttach() {
        for (XC_MethodHook cb : ATTACH_DELIVERABLES) {
            synchronized (DELIVERED) {
                if (!DELIVERED.containsKey(cb)) return true;
            }
        }
        return false;
    }

    /**
     * Business-readiness handshake. Two independent facts must meet:
     * the module has registered every entry capability (this is what
     * markBusinessReady records), and the engine has actually swapped the
     * word-limit getter's entrypoint to its bridge (onWordBridgeArmed). A
     * merely registered callback is not enough: hook() defers the swap to the
     * warmer when the baked bridge is not JIT-compiled yet, and a gate opened
     * at install time would let the main page read the original limit first --
     * exactly the "original value, then 99666 much later" symptom.
     *
     * Lives here because the bridge class is the one classloader-shared
     * surface between the engine side (RootlessBoot, which cannot reach module
     * classes directly) and the module dex. Default false, never reset: a
     * process whose module never arms stays closed.
     */
    private static volatile boolean sBusinessReady;
    private static volatile boolean sBusinessReadyRequested;

    /** Module side: all entry capabilities were registered. */
    public static void markBusinessReady() {
        sBusinessReadyRequested = true;
        boolean armed = com.repack.art.ArtHookEngine.isArmedById("b_word");
        if (armed) sBusinessReady = true;
        android.util.Log.i(TAG, armed
                ? "business-ready: module confirmed, word bridge already armed"
                : "business-ready: module confirmed; waiting for word bridge arm");
    }

    /** Engine side: the word-limit bridge swap is live. */
    public static void onWordBridgeArmed() {
        if (!sBusinessReadyRequested) {
            android.util.Log.i(TAG,
                    "word bridge armed; readiness waits for the module's confirmation");
            return;
        }
        sBusinessReady = true;
        android.util.Log.i(TAG, "business-ready: word bridge armed and module confirmed");
    }

    public static boolean isBusinessReady() {
        return sBusinessReady;
    }
}
