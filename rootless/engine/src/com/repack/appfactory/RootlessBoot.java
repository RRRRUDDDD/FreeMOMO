package com.repack.appfactory;

import android.os.SystemClock;
import android.util.Log;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Bootstrap: arm the pure-Java hook engine, load the baked bridge table, then
 * dispatch the module's handleLoadPackage once. Everything runs inside the
 * factory static initializer, before the shell creates the application. No
 * stage touches business classes or Gson; the module only hooks
 * Application.attach here and re-derives the host classloader from the attach
 * context, after the shell has installed its own classloader chain.
 *
 * <p>The delivery thread also owns the fail-closed entry gate. It starts
 * unconditionally in the main process so that a bootstrap whose engine chain
 * failed still installs the splash gate on the first attached application --
 * readiness can then never be reported and the app stays on its splash instead
 * of entering unhooked. Readiness is polled at 25 ms over a shrinking
 * unresolved set, so the gate opens at the first observable moment.
 */
public final class RootlessBoot {

    static final String TAG = "FreeMOMO-Rootless";
    static final String TARGET_PACKAGE = "com.maimemo.android.momo";

    /** Phase A: wait for the attached application (25ms x 3600 = 90s). */
    private static final int APP_POLL_TRIES = 3600;
    /** Phase B: bridge-target resolution deadline before delivering anyway. */
    private static final long READINESS_DEADLINE_MS = 10_000;
    /** One poll interval for both phases; the old 100ms quantized away ~1/10s. */
    private static final long POLL_INTERVAL_MS = 25;
    /** Delivery may start only after the module stage has finished either way. */
    private static final long MODULE_DISPATCH_WAIT_MS = 30_000;

    private static final java.util.concurrent.atomic.AtomicBoolean BOOTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.CountDownLatch MODULE_DISPATCHED =
            new java.util.concurrent.CountDownLatch(1);

    private RootlessBoot() {}

    public static void boot() {
        if (!BOOTED.compareAndSet(false, true)) {
            Log.i(TAG, "already booted; skip");
            return;
        }
        long startedAt = SystemClock.uptimeMillis();
        // The delivery poller runs unconditionally in the main process: when a
        // chain stage fails the business-ready signal can never arrive, and
        // the gate installed by the poller holds the app on its launch
        // activity -- the intended fail-closed behavior.
        boolean engineChainOk = true;
        try {
            if (currentActivityThread() == null) {
                Log.i(TAG, "no ActivityThread (appZygote/pre-fork): skip");
                return;
            }
            // Start the gate poller before the engine chain (but after the
            // hidden-API exemption, which the poller's ActivityThread lookups
            // need). The gate itself only uses framework lifecycle calls, so
            // it can land on the first attached application while engine
            // detection and bridge warming are still running. The poller
            // waits for the module stage before delivering anything.
            engineChainOk = stage("hab", RootlessBoot::exemptHiddenApi);
            // A poller that failed to start leaves no gate and no delivery
            // channel; marking the chain incomplete keeps the fail-closed
            // contract instead of booting "normally" into an unhooked app.
            engineChainOk = stage("gate-poller", RootlessBoot::startGatePoller) && engineChainOk;
            engineChainOk = stage("engine", () -> {
                if (!com.repack.art.ArtHookEngine.init()) {
                    throw new IllegalStateException("engine init failed");
                }
            }) && engineChainOk;
            engineChainOk = stage("bridges", com.repack.art.BridgeRegistry::ensureLoaded)
                    && engineChainOk;
            engineChainOk = stage("module", RootlessBoot::dispatchModule) && engineChainOk;
        } finally {
            MODULE_DISPATCHED.countDown();
            Log.i(TAG, "bootstrap sequence ended in "
                    + (SystemClock.uptimeMillis() - startedAt) + "ms");
            if (!engineChainOk) {
                Log.w(TAG, "boot incomplete -- splash gate will hold entry (fail-closed)");
            }
        }
    }

    private static void dispatchModule() throws Exception {
        ClassLoader p1 = RootlessBoot.class.getClassLoader();
        Class<?> entryCls = Class.forName("com.rud.freemomo.MomoHookEntry", true, p1);
        Object entry = entryCls.getDeclaredConstructor().newInstance();
        Log.i(TAG, "module entry instantiated");

        XC_LoadPackage.LoadPackageParam lpp = new XC_LoadPackage.LoadPackageParam();
        lpp.packageName = TARGET_PACKAGE;
        lpp.processName = currentProcessName();
        lpp.classLoader = p1;
        lpp.isFirstApplication = true;
        entryCls.getMethod("handleLoadPackage", XC_LoadPackage.LoadPackageParam.class)
                .invoke(entry, lpp);
        Log.i(TAG, "module handleLoadPackage dispatched");
    }

    private static void startGatePoller() {
        // Every process of the shell loads the factory; delivery and the entry
        // gate are main-process concerns only. A sub-process lifecycle gate
        // would bounce activities it does not own, so filter here, before the
        // thread exists.
        String process = currentProcessName();
        if (!TARGET_PACKAGE.equals(process)) {
            Log.i(TAG, "gate/delivery poller skipped for process " + process);
            return;
        }
        Thread poller = new Thread(RootlessBoot::pollDeliverLoop, "FreeMOMO-AttachPoll");
        poller.setDaemon(true);
        poller.start();
        Log.i(TAG, "gate/delivery poller started before engine chain");
    }

    /**
     * Delivery channel: wait for the framework-created application to be
     * attached, gate on bridge-target class resolution (the observable form of
     * "the shell finished decrypting"), then deliver. This shell never drives
     * Application.attach through its ArtMethod (mInstrumentation is the stock
     * framework class at delivery time; boot-class hooks never dispatch
     * because their callers are internally linked in boot.oat, and JIT-compiled
     * business callers direct-link to already-compiled targets), so delivery
     * cannot depend on a framework entrypoint -- the poller is the delivery.
     */
    private static void pollDeliverLoop() {
        try {
            long pollStartedAt = SystemClock.uptimeMillis();

            // Phase A: the application is set before onCreate and therefore
            // before any activity, so the gate lands while only the splash
            // exists. Registering the gate first closes the window where a
            // slow decrypt would let the main UI come up unhooked.
            android.app.Application app = null;
            for (int i = 0; i < APP_POLL_TRIES; i++) {
                Object cur = currentApplication();
                if (cur instanceof android.app.Application) {
                    android.app.Application candidate = (android.app.Application) cur;
                    if (candidate.getBaseContext() != null) {
                        app = candidate;
                        break;
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            if (app == null) {
                Log.w(TAG, "attach poll: no attached application; giving up");
                return;
            }
            SplashGate.install(app);
            long gateInstalledAt = SystemClock.uptimeMillis();
            Log.i(TAG, "splash gate installed "
                    + (gateInstalledAt - pollStartedAt) + "ms after poll start");

            // Do not deliver until the module stage is done. The gate is
            // already live, so a module that never registers its attach
            // callback leaves the splash closed instead of entering unhooked.
            try {
                if (!MODULE_DISPATCHED.await(MODULE_DISPATCH_WAIT_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "module stage still running after "
                            + MODULE_DISPATCH_WAIT_MS + "ms");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "module wait interrupted; gate stays closed");
                return;
            }
            if (!de.robv.android.xposed.XposedBridge.hasPendingAttach()) {
                Log.i(TAG, "attach queue empty after the module stage; poller stops "
                        + "(the readiness signal, if any, still opens the gate)");
                return;
            }
            Log.i(TAG, "module stage done " + (SystemClock.uptimeMillis() - pollStartedAt)
                    + "ms after poll start; starting readiness probe");

            // Phase B: readiness-gated delivery. The old fixed 2.5s grace
            // blind-guessed at "the shell has finished decrypting the business
            // dex"; the actual condition is observable -- every bridge-target
            // class answers a definition-only Class.forName through the host
            // classloader. The set shrinks as classes resolve, so each round
            // only re-probes what is still missing.
            java.util.Set<String> unresolved =
                    new java.util.HashSet<>(com.repack.art.BridgeRegistry.registeredClasses());
            long deadline = gateInstalledAt + READINESS_DEADLINE_MS;
            while (!unresolved.isEmpty()) {
                if (bridgeTargetsResolvable(app, unresolved)) break;
                if (SystemClock.uptimeMillis() > deadline) {
                    Log.w(TAG, "readiness probe: deadline exceeded, "
                            + unresolved.size() + " class(es) unresolved; delivering anyway");
                    break;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            Log.i(TAG, "delivery gate open " + (SystemClock.uptimeMillis() - pollStartedAt)
                    + "ms after poll start (" + unresolved.size() + " unresolved)");
            // Bounded retry: deliverPendingAttach un-marks a callback that threw,
            // so a transient module failure gets two more passes before giving up.
            for (int attempt = 1; attempt <= 3; attempt++) {
                de.robv.android.xposed.XposedBridge.deliverPendingAttach(
                        app, app.getBaseContext(), "poll");
                if (!de.robv.android.xposed.XposedBridge.hasPendingAttach()) return;
                Log.w(TAG, "delivery attempt " + attempt + " left pending; retrying");
                Thread.sleep(1000);
            }
            Log.w(TAG, "delivery still pending after retries; giving up");
        } catch (Throwable t) {
            Log.w(TAG, "attach poll failed", t);
        }
    }

    /**
     * True when every still-unresolved registry target class now resolves
     * (definition only) via the host classloader. Resolved names are removed
     * from {@code unresolved}.
     */
    private static boolean bridgeTargetsResolvable(
            android.app.Application app, java.util.Set<String> unresolved) {
        try {
            ClassLoader cl = app.getBaseContext().getClassLoader();
            if (cl == null) return false;
            java.util.Iterator<String> it = unresolved.iterator();
            while (it.hasNext()) {
                try {
                    Class.forName(it.next(), false, cl);
                    it.remove();
                } catch (Throwable ignored) {
                    // Still encrypted / not on the classpath this round.
                }
            }
            return unresolved.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Resolved once: the attach poller calls this every 25ms for up to 90s. */
    private static java.lang.reflect.Method sCurrentApplication;

    private static Object currentApplication() {
        try {
            java.lang.reflect.Method m = sCurrentApplication;
            if (m == null) {
                m = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication");
                sCurrentApplication = m;
            }
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void exemptHiddenApi() throws Exception {
        if (!org.lsposed.hiddenapibypass.HiddenApiBypass.setHiddenApiExemptions("L")) {
            throw new IllegalStateException("setHiddenApiExemptions returned false");
        }
    }

    private static boolean stage(String name, Stage stage) {
        long t0 = SystemClock.uptimeMillis();
        try {
            stage.run();
            Log.i(TAG, "stage ok: " + name + " (" + (SystemClock.uptimeMillis() - t0) + "ms)");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "stage failed: " + name, t);
            return false;
        }
    }

    private interface Stage { void run() throws Exception; }

    private static Object currentActivityThread() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return at.getDeclaredMethod("currentActivityThread").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String currentProcessName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            String name = (String) at.getDeclaredMethod("currentProcessName").invoke(null);
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
            // Fall through to /proc: the gate poller starts before the engine
            // chain must still know whether it owns the main process even when
            // the hidden-API lookup is unavailable.
        }
        try (java.io.FileInputStream in =
                     new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            int end = 0;
            while (end < n && buf[end] != 0) end++;
            if (end > 0) return new String(buf, 0, end, "UTF-8");
        } catch (Throwable ignored) {
        }
        return null;
    }
}
