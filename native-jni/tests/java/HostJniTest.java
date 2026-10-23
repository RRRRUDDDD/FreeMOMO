import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Path;

public final class HostJniTest {
    private static final String BRIDGE = "org.lsposed.lspatch.metaloader.NativeBridge";

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }

    private static WeakReference<ClassLoader> exercise(String library, Path classes) throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, null)) {
            Class<?> bridge = loader.loadClass(BRIDGE);
            bridge.getMethod("load", String.class).invoke(null, library);
            check((int) bridge.getMethod("state").invoke(null) == 0, "JNI_OnLoad leaves monitor NOT_STARTED");
            check((int) bridge.getMethod("start").invoke(null) == 10, "host process is rejected as NOT_TARGET");
            check((int) bridge.getMethod("start").invoke(null) == 10, "repeat nativeStart retains its rejection");
            check((int) bridge.getMethod("state").invoke(null) == 10, "native query publishes the failure state");
            try (var other = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, null)) {
                try {
                    other.loadClass(BRIDGE).getMethod("load", String.class).invoke(null, library);
                    throw new AssertionError("Library must not bind to a second loader");
                } catch (InvocationTargetException expected) {
                    check(expected.getCause() instanceof UnsatisfiedLinkError,
                            "second ClassLoader cannot own the same native library");
                }
            }
            return new WeakReference<>(loader);
        }
    }

    public static void main(String[] args) throws Exception {
        var retained = exercise(Path.of(args[0]).toRealPath().toString(), Path.of(args[1]));
        for (int i = 0; i < 5; ++i) {
            System.gc();
            Thread.sleep(20);
        }
        check(retained.get() != null, "JNI global class reference retains its defining ClassLoader");
    }
}
