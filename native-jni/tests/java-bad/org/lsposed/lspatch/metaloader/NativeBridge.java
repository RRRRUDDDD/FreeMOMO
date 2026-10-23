package org.lsposed.lspatch.metaloader;

// First method matches; second deliberately fails RegisterNatives.
public final class NativeBridge {
    public static void load(String library) { System.load(library); }
    public static int probe() { return nativeStart(50); }
    private static native int nativeStart(int timeout);
    private static native long nativeGetState();
}
