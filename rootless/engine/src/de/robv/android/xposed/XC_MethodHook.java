package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal XC_MethodHook. The compiled FreeMOMO module dex talks to exactly
 * this binary surface (verified against its dex string pool): no-arg and
 * int-arg constructors, void before/after hook methods, the public
 * method/thisObject/args fields, getResult/setResult/hasThrowable and the
 * object-extra accessors on MethodHookParam, plus the Unhook token.
 */
public abstract class XC_MethodHook {

    /** Sort key for callback order; higher runs first. Engine-visible on purpose. */
    public int priority;

    public XC_MethodHook() {}

    public XC_MethodHook(int priority) { this.priority = priority; }

    /** Public despite the official protected flag: the engine dispatches from another package. */
    public void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    public void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public boolean returnEarly;
        // Lazily allocated: most hooked methods never carry extras, and one param
        // instance is created per dispatch. Param instances are thread-confined
        // (created and consumed inside a single dispatch/delivery call).
        private Map<String, Object> extras;

        public Object getResult() { return result; }

        /** Marks the early-return path: the original is skipped, afters still run. */
        public void setResult(Object r) {
            result = r;
            throwable = null;
            returnEarly = true;
        }

        public Throwable getThrowable() { return throwable; }

        public void setThrowable(Throwable t) {
            throwable = t;
            result = null;
            returnEarly = true;
        }

        public boolean hasThrowable() { return returnEarly && throwable != null; }

        public Object getObjectExtra(String key) {
            Map<String, Object> e = extras;
            return e == null ? null : e.get(key);
        }

        public void setObjectExtra(String key, Object value) {
            Map<String, Object> e = extras;
            if (e == null) {
                e = new HashMap<>();
                extras = e;
            }
            e.put(key, value);
        }
    }

    public static final class Unhook {
        private final Object entry;
        private final XC_MethodHook hook;

        public Unhook(Object entry, XC_MethodHook hook) {
            this.entry = entry;
            this.hook = hook;
        }

        public void unhook() {
            com.repack.art.ArtHookEngine.unhook(entry, hook);
        }
    }
}
