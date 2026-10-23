package org.lsposed.lspatch.metaloader;

// Host VM fixture for the real JNI_OnLoad. Does not enter any Android process.
public final class NativeBridge {
    public static void load(String library) { System.load(library); }
    public static int start() { return nativeStart(50); }
    public static int state() { return nativeGetState(); }
    private static native int nativeStart(int timeout);
    private static native int nativeGetState();
}
