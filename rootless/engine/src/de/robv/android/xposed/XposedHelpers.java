package de.robv.android.xposed;

/** Minimal XposedHelpers facade; only the two calls the module makes. */
public final class XposedHelpers {

    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(e);
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1]
                        instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("expected a XC_MethodHook as last argument");
        }
        XC_MethodHook callback =
                (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        int paramCount = parameterTypesAndCallback.length - 1;
        Class<?>[] types = new Class<?>[paramCount];
        for (int i = 0; i < paramCount; i++) {
            Object p = parameterTypesAndCallback[i];
            if (p instanceof Class) {
                types[i] = (Class<?>) p;
            } else if (p instanceof String) {
                try {
                    types[i] = Class.forName((String) p, false, clazz.getClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new ClassNotFoundError(e);
                }
            } else {
                throw new IllegalArgumentException("parameter type must be Class or String: " + p);
            }
        }
        try {
            java.lang.reflect.Method m = clazz.getDeclaredMethod(methodName, types);
            m.setAccessible(true);
            return XposedBridge.hookMethod(m, callback);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "." + methodName);
        }
    }

    /** Official-compatible marker error. */
    public static final class ClassNotFoundError extends Error {
        public ClassNotFoundError(ClassNotFoundException cause) { super(cause); }
    }

    /** Official-compatible marker error. */
    public static final class NoSuchMethodError extends Error {
        public NoSuchMethodError(String detail) { super(detail); }
    }
}
