package com.repack.art;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime registry linking hooked members to their baked bridge methods.
 * Registrations come from the generated com.repack.bridge.Bridges static
 * initializer; keying matches BridgeRegistry.memberKey(Member) exactly, so a
 * build-time spec and a runtime Member can only meet when class, member name,
 * parameter list, staticness and ctor-ness all agree. Unknown members are
 * rejected loudly -- the module treats that as a failed capability install.
 */
public final class BridgeRegistry {

    private static final Map<String, String> MEMBER_TO_BRIDGE = new HashMap<>();
    private static final Map<String, Method> BRIDGES = new HashMap<>();
    private static final Map<String, Method> BACKUPS = new HashMap<>();
    private static boolean sLoaded;

    private BridgeRegistry() {}

    public static synchronized void ensureLoaded() {
        if (sLoaded) return;
        try {
            Class.forName("com.repack.bridge.Bridges", true, BridgeRegistry.class.getClassLoader());
            sLoaded = true;
        } catch (Throwable t) {
            throw new IllegalStateException("bridge table class missing", t);
        }
    }

    /** Called from the generated Bridges class initializer. */
    public static synchronized void register(String bridgeId, String className, String memberName,
                                             String[] paramTypes, boolean isStatic, boolean isCtor,
                                             String bridgeMethod, String backupMethod) {
        String key = memberKey(className, memberName, paramTypes, isStatic, isCtor);
        MEMBER_TO_BRIDGE.put(key, bridgeId);
        try {
            Class<?> holder = Class.forName("com.repack.bridge.Bridges");
            Class<?>[] sig = bridgeSignature(paramTypes, isCtor || !isStatic);
            BRIDGES.put(bridgeId, holder.getDeclaredMethod(bridgeMethod, sig));
            if (backupMethod != null) {
                BACKUPS.put(bridgeId, holder.getDeclaredMethod(backupMethod, sig));
            }
        } catch (Throwable t) {
            throw new IllegalStateException("bridge registration failed: " + bridgeId, t);
        }
    }

    private static Class<?>[] bridgeSignature(String[] paramTypes, boolean receiver) {
        Class<?>[] sig = new Class[paramTypes.length + (receiver ? 1 : 0)];
        int i = 0;
        if (receiver) sig[i++] = Object.class;
        for (String p : paramTypes) {
            if (p.equals("int")) sig[i] = int.class;
            else if (p.equals("boolean")) sig[i] = boolean.class;
            else if (p.equals("long")) sig[i] = long.class;
            else if (p.equals("float")) sig[i] = float.class;
            else if (p.equals("double")) sig[i] = double.class;
            else if (p.equals("short")) sig[i] = short.class;
            else if (p.equals("char")) sig[i] = char.class;
            else if (p.equals("byte")) sig[i] = byte.class;
            else sig[i] = Object.class;   // any reference category shares the 'L' shorty
            i++;
        }
        return sig;
    }

    static String memberKey(Member m) {
        String name = m instanceof Constructor ? "<init>" : m.getName();
        Class<?>[] params = parameterTypes(m);
        String[] names = new String[params.length];
        for (int i = 0; i < params.length; i++) names[i] = params[i].getName();
        boolean isStatic = Modifier.isStatic(m.getModifiers());
        return memberKey(m.getDeclaringClass().getName(), name, names, isStatic, m instanceof Constructor);
    }

    private static Class<?>[] parameterTypes(Member m) {
        if (m instanceof Method) return ((Method) m).getParameterTypes();
        if (m instanceof Constructor) return ((Constructor<?>) m).getParameterTypes();
        throw new IllegalArgumentException("unsupported member: " + m);
    }

    static String memberKey(String className, String memberName, String[] paramTypes,
                            boolean isStatic, boolean isCtor) {
        StringBuilder sb = new StringBuilder(className).append('#').append(memberName).append('(');
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(paramTypes[i]);
        }
        sb.append(')');
        if (isCtor) sb.append("#ctor");
        else sb.append(isStatic ? "#static" : "#virtual");
        return sb.toString();
    }

    static synchronized String bridgeIdFor(String memberKey) {
        ensureLoaded();
        return MEMBER_TO_BRIDGE.get(memberKey);
    }

    static synchronized Method bridgeMethod(String bridgeId) {
        ensureLoaded();
        return BRIDGES.get(bridgeId);
    }

    static synchronized Method backupMethod(String bridgeId) {
        ensureLoaded();
        return BACKUPS.get(bridgeId);
    }

    public static synchronized java.util.Set<String> allBridgeIds() {
        ensureLoaded();
        return new java.util.HashSet<>(BRIDGES.keySet());
    }

    /**
     * Distinct target class names baked into the registry. The poller probes
     * exactly these with a definition-only Class.forName: the moment every one
     * resolves through the host classloader, the shell's decrypted dex is up
     * and module delivery can start without the old fixed grace sleep.
     */
    public static synchronized java.util.Set<String> registeredClasses() {
        ensureLoaded();
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String key : MEMBER_TO_BRIDGE.keySet()) {
            int cut = key.indexOf('#');
            if (cut > 0) out.add(key.substring(0, cut));
        }
        return out;
    }
}
