import java.lang.reflect.InvocationTargetException;

public final class JniLoadFailure {
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            try {
                System.load(args[0]);
                throw new AssertionError("Missing bridge must reject JNI_OnLoad");
            } catch (UnsatisfiedLinkError expected) {
                System.out.println("PASS: missing bridge rejects JNI_OnLoad");
            }
            return;
        }
        Class<?> bridge = Class.forName("org.lsposed.lspatch.metaloader.NativeBridge");
        try {
            bridge.getMethod("load", String.class).invoke(null, args[0]);
            throw new AssertionError("Wrong native signature must reject registration");
        } catch (InvocationTargetException expected) {
            if (!(expected.getCause() instanceof UnsatisfiedLinkError)) throw expected;
            System.out.println("PASS: incomplete registration rejects JNI_OnLoad");
        }
        try {
            bridge.getMethod("probe").invoke(null);
            throw new AssertionError("Partially registered native method must be unbound");
        } catch (InvocationTargetException expected) {
            if (!(expected.getCause() instanceof UnsatisfiedLinkError)) throw expected;
            System.out.println("PASS: failed registration leaves no callable partial binding");
        }
    }
}
