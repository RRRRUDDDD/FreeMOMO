package org.lsposed.lspatch.metaloader;

/** Separate diagnostic APK. Registers the same JNI ABI without a framework or host APK. */
public final class NativeBridge {
    private NativeBridge() {}

    public static String runSmokeCheck() {
        System.loadLibrary("freemomo_native");
        int before = nativeGetState();
        int first = nativeStart(50);
        int repeated = nativeStart(50);
        int after = nativeGetState();
        if ((before != 0 && before != 10) || first != 10 || repeated != 10 || after != 10) {
            throw new IllegalStateException("Smoke package must remain NOT_TARGET: "
                    + before + "/" + first + "/" + repeated + "/" + after);
        }
        return "JNI load/registration OK\nState: NOT_TARGET (expected)\nNo monitor scan was authorized.";
    }

    private static native int nativeStart(int readyTimeoutMs);
    private static native int nativeGetState();
}
