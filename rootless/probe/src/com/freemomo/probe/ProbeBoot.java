package com.freemomo.probe;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Probe harness: arm the engine and bridges, hook the boot-class pattern, and
 * record every result as a line. Files under the app's own directories are the
 * primary channel because logcat may be unavailable on the target device.
 */
public final class ProbeBoot {

    static final String TAG = "FreeMOMO-Probe";
    static volatile boolean armed;
    static volatile String engineError;
    static volatile boolean attachFired;
    static volatile String attachContextClass;
    static volatile ClassLoader attachClassLoader;
    static volatile boolean resumeHookFired;

    private ProbeBoot() {}

    static volatile android.app.Application app;

    static void arm() {
        if (armed) return;
        synchronized (ProbeBoot.class) {
            if (armed) return;
            try {
                if (!org.lsposed.hiddenapibypass.HiddenApiBypass.setHiddenApiExemptions("L")) {
                    throw new IllegalStateException("setHiddenApiExemptions returned false");
                }
                if (!com.repack.art.ArtHookEngine.init()) {
                    throw new IllegalStateException("engine init failed: " + com.repack.art.ArtHookEngine.initError);
                }
                com.repack.art.BridgeRegistry.ensureLoaded();
                try {
                    backupCtorMethod = com.repack.bridge.Bridges.class.getDeclaredMethod(
                            "b_ctor_orig", Object.class, int.class);
                } catch (Throwable t) {
                    Log.w(TAG, "backup capture failed", t);
                }
                com.repack.art.ArtHookEngine.hook(
                        Application.class.getDeclaredMethod("attach", android.content.Context.class),
                        new XC_MethodHook() {
                            @Override
                            public void afterHookedMethod(MethodHookParam param) {
                                ProbeApp.onAttachFired(
                                        param.args.length > 0 ? (android.content.Context) param.args[0] : null);
                            }
                        }, 50);
                com.repack.art.ArtHookEngine.hook(
                        android.app.Activity.class.getDeclaredMethod("onResume"),
                        new XC_MethodHook() {
                            @Override
                            public void afterHookedMethod(MethodHookParam param) {
                                resumeHookFired = true;
                                Log.i(TAG, "PROOF onResume hook fired on " + param.thisObject);
                            }
                        }, 50);
                armed = true;
                Log.i(TAG, "armed ok");
            } catch (Throwable t) {
                engineError = t.getClass().getName() + ": " + t.getMessage();
                Log.e(TAG, "arm FAILED", t);
            }
        }
    }

    private static final List<String> functionalLines = new ArrayList<>();
    static volatile int writeResultsPass;
    static volatile int writeResultsTotal;
    /**
     * Captured at arm time, BEFORE any hook runs: the shadow clone rewrites the
     * backup's ArtMethod identity (declaring_class/dex_method_index), so after
     * arming, getDeclaredMethod can no longer resolve it by its baked name --
     * the lookup must ride a reference taken while it was still findable.
     */
    static volatile java.lang.reflect.Method backupCtorMethod;

    /**
     * The JIT races every swap: a hot loop compiles (and may inline) the target,
     * overwriting the bridge entrypoint until the keeper re-arms (<=4s). Tests
     * that measure a SINGLE call must wait for the swap to be in place first.
     */
    private static boolean awaitArmed(java.lang.reflect.Member m, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!com.repack.art.ArtHookEngine.isArmed(m)) {
            if (System.currentTimeMillis() > deadline) return false;
            Thread.sleep(100);
        }
        return true;
    }

    static void runFunctionalTests() {
        List<String> lines = functionalLines;
        int pass = 0, total = 0;
        lines.add("engine=" + (armed ? "OK" : "FAILED " + engineError));
        if (armed) {
            try {
                XC_MethodHook.Unhook u1 = de.robv.android.xposed.XposedBridge.hookMethod(
                        ProbeTarget.class.getDeclaredMethod("value"),
                        new XC_MethodHook() {
                            final java.util.concurrent.atomic.AtomicInteger lastOrig =
                                    new java.util.concurrent.atomic.AtomicInteger(-1);

                            @Override
                            public void afterHookedMethod(MethodHookParam param) {
                                lastOrig.set((Integer) param.result);
                                param.setResult(7);
                            }
                        });
                // drive through interpreter AND jit thresholds
                int wrong = 0;
                for (int i = 0; i < 5000; i++) {
                    if (ProbeTarget.value() != 7) wrong++;
                }
                total++;
                // The loop above may be inlined/JIT'd past the ArtMethod, so the
                // swapped-in bridge is judged on a gated single call afterwards.
                boolean a = awaitArmed(ProbeTarget.class.getDeclaredMethod("value"), 15_000)
                        && ProbeTarget.value() == 7;
                lines.add("A dance-replace: " + (a ? "PASS" : "FAIL wrong=" + wrong) + " (5000 calls + gated)");
                if (a) pass++;

                total++;
                u1.unhook();
                int after = ProbeTarget.value();
                boolean b = after == 4242;
                lines.add("B unhook: " + (b ? "PASS" : "FAIL got " + after));
                if (b) pass++;

                total++;
                de.robv.android.xposed.XposedBridge.hookMethod(ProbeTarget.class.getDeclaredMethod("value"),
                        new XC_MethodHook() {
                            @Override
                            public void afterHookedMethod(MethodHookParam param) {
                                param.setResult(9);
                            }
                        });
                boolean cok = awaitArmed(ProbeTarget.class.getDeclaredMethod("value"), 15_000)
                        && ProbeTarget.value() == 9;
                lines.add("C re-hook: " + (cok ? "PASS" : "FAIL got " + ProbeTarget.value()));
                if (cok) pass++;

                total++;
                final boolean[] ctorSawArg = {false};
                final int[] ctorSawStored = {-1};
                de.robv.android.xposed.XposedBridge.hookMethod(ProbeTarget.class.getConstructor(int.class),
                        new XC_MethodHook() {
                            @Override
                            public void afterHookedMethod(MethodHookParam param) {
                                ctorSawArg[0] = param.args.length == 1 && ((Integer) param.args[0]) == 11;
                                ctorSawStored[0] = ((ProbeTarget) param.thisObject).stored;
                            }
                        });
                boolean d = awaitArmed(ProbeTarget.class.getConstructor(int.class), 15_000);
                ProbeTarget t = null;
                if (d) {
                    t = new ProbeTarget(11);
                    d = ctorSawArg[0] && ctorSawStored[0] == 11 && t.stored == 11;
                }
                lines.add("D ctor backup: " + (d ? "PASS" : "FAIL arg=" + ctorSawArg[0]
                        + " stored=" + ctorSawStored[0] + " field=" + (t == null ? "?" : t.stored)));
                if (d) pass++;
                // ArtMethod layout forensics: target ctor vs backup holder vs a
                // static method, AFTER the shadow clone ran.
                try {
                    java.lang.reflect.Constructor<?> ctor = ProbeTarget.class.getConstructor(int.class);
                    lines.add("LAYOUT target-ctor:  " + com.repack.art.ArtHookEngine.dumpMethod(ctor));
                    lines.add("LAYOUT static-value: " + com.repack.art.ArtHookEngine.dumpMethod(
                            ProbeTarget.class.getDeclaredMethod("value")));
                    if (backupCtorMethod != null) {
                        lines.add("LAYOUT backup:       " + com.repack.art.ArtHookEngine.dumpMethod(
                                backupCtorMethod));
                    } else {
                        lines.add("LAYOUT backup:       SKIP (reference not captured at arm)");
                    }
                } catch (Throwable t2) {
                    lines.add("layout dump failed: " + t2);
                }

                // G: virtual-target dance fidelity (see ProbeVirtual). The hook
                // must be swapped in BEFORE the measured call -- awaitArmed
                // gates it; the warm-up drives the BRIDGE via reflection and
                // never exercises the Derived path.
                total++;
                try {
                    java.lang.reflect.Member bounce =
                            ProbeVirtual.Base.class.getDeclaredMethod("bounce");
                    de.robv.android.xposed.XposedBridge.hookMethod(bounce,
                            new XC_MethodHook() {
                                @Override
                                public void beforeHookedMethod(MethodHookParam param) {
                                    ProbeVirtual.bridgeBefore++;
                                }

                                @Override
                                public void afterHookedMethod(MethodHookParam param) {
                                    ProbeVirtual.bridgeAfter++;
                                }
                            });
                    if (!awaitArmed(bounce, 15_000)) {
                        lines.add("G virtual-fidelity: SKIP (bridge never armed)");
                    } else {
                        String verdict = "SKIP";
                        boolean g = false;
                        for (int attempt = 0; attempt < 5 && !g; attempt++) {
                            ProbeVirtual.derivedRuns = 0;
                            ProbeVirtual.baseBodyRuns = 0;
                            ProbeVirtual.bridgeBefore = 0;
                            ProbeVirtual.bridgeAfter = 0;
                            new ProbeVirtual.Derived().bounce();
                            if (ProbeVirtual.bridgeBefore == 0) continue; // swap not consulted this call
                            boolean clean = ProbeVirtual.derivedRuns == 1 && ProbeVirtual.baseBodyRuns == 1;
                            verdict = "derived=" + ProbeVirtual.derivedRuns
                                    + " base=" + ProbeVirtual.baseBodyRuns
                                    + " bridge=" + ProbeVirtual.bridgeBefore;
                            g = clean;
                        }
                        lines.add("G virtual-fidelity: " + (g ? "PASS" : "FAIL " + verdict)
                                + (g ? "" : "  <- reflection re-dispatch double-runs the override chain"));
                        if (g) pass++;
                    }
                } catch (Throwable t3) {
                    lines.add("G virtual-fidelity: ERROR " + t3);
                    Log.e(TAG, "G test failed", t3);
                }

                // H: the fix-plan 1.1 rollback race. unhook() must be allowed to
                // complete while a dispatch is in flight, and the in-flight
                // dispatch then enters the per-target critical section. Pre-fix
                // the unconditional finally re-mounted the (now callback-less)
                // bridge on the unhooked target and every later call silently
                // returned null/0; post-fix the stillArmed snapshot leaves the
                // target restored and the in-flight call still runs the original
                // through the shadow backup. beforeHookedMethod runs OUTSIDE the
                // per-target lock, so parking the first dispatch there makes the
                // "unhook completed, dispatch enters critical section after"
                // ordering deterministic. value() is still hooked by C's leftover
                // setResult(9) callback (never unhooked), so H races on value2 --
                // a clean target whose only hook is H's own.
                total++;
                try {
                    final java.util.concurrent.CountDownLatch inBefore =
                            new java.util.concurrent.CountDownLatch(1);
                    final java.util.concurrent.CountDownLatch resumeDispatch =
                            new java.util.concurrent.CountDownLatch(1);
                    // The park arms only when the dispatch thread is the caller:
                    // the diagnostic pre-call (and any other early traffic) must
                    // not consume it, or the race never actually happens.
                    final java.util.concurrent.atomic.AtomicBoolean raceArmed =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    final java.util.concurrent.atomic.AtomicBoolean firstEntry =
                            new java.util.concurrent.atomic.AtomicBoolean(true);
                    XC_MethodHook.Unhook u2 = de.robv.android.xposed.XposedBridge.hookMethod(
                            ProbeTarget.class.getDeclaredMethod("value2"),
                            new XC_MethodHook() {
                                final java.util.concurrent.atomic.AtomicInteger seenOrig =
                                        new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);

                                @Override
                                public void beforeHookedMethod(MethodHookParam param) throws Exception {
                                    if (raceArmed.get() && firstEntry.compareAndSet(true, false)) {
                                        inBefore.countDown();
                                        resumeDispatch.await(20, java.util.concurrent.TimeUnit.SECONDS);
                                    }
                                }

                                @Override
                                public void afterHookedMethod(MethodHookParam param) {
                                    // What the ORIGINAL returned inside this
                                    // entry's own dispatch, before any unhook.
                                    seenOrig.set(param.result instanceof Number
                                            ? ((Number) param.result).intValue() : -1);
                                }
                            });
                    if (!awaitArmed(ProbeTarget.class.getDeclaredMethod("value2"), 15_000)) {
                        lines.add("H unhook-race: SKIP (bridge never armed)");
                    } else {
                        // Diagnostics: which entrypoint each player carries, and
                        // whether the chain answers with the original before the
                        // race starts. value is dumped alongside value2 in the
                        // SAME batch -- the declaring_class word moves with heap
                        // compaction, so only same-batch values compare.
                        try {
                            int pre = ProbeTarget.value2();
                            lines.add("H-LAYOUT value2:        " + com.repack.art.ArtHookEngine.dumpMethod(
                                    ProbeTarget.class.getDeclaredMethod("value2")) + " pre=" + pre);
                            lines.add("H-LAYOUT b_value2:      " + com.repack.art.ArtHookEngine.dumpMethod(
                                    com.repack.bridge.Bridges.class.getDeclaredMethod("b_value2")));
                            lines.add("H-LAYOUT b_value:       " + com.repack.art.ArtHookEngine.dumpMethod(
                                    com.repack.bridge.Bridges.class.getDeclaredMethod("b_value")));
                            lines.add("H-LAYOUT value:         " + com.repack.art.ArtHookEngine.dumpMethod(
                                    ProbeTarget.class.getDeclaredMethod("value")));
                        } catch (Throwable td) {
                            lines.add("H-LAYOUT dump failed: " + td);
                        }
                        final int[] inflightResult = {Integer.MIN_VALUE};
                        Thread dispatch = new Thread(() -> {
                            try {
                                inflightResult[0] = ProbeTarget.value2();
                            } catch (Throwable t5) {
                                inflightResult[0] = Integer.MIN_VALUE + 1;
                            }
                        }, "h-dispatch");
                        raceArmed.set(true);
                        dispatch.start();
                        boolean raced = inBefore.await(15, java.util.concurrent.TimeUnit.SECONDS);
                        if (raced) {
                            // The dispatch sits parked in beforeHookedMethod
                            // (outside e.lock): unhook completes fully here.
                            u2.unhook();
                        }
                        resumeDispatch.countDown();
                        dispatch.join(20_000);
                        // Post-fix: the in-flight call returned the ORIGINAL
                        // (through the shadow), the unhook stands, and the host
                        // stays live afterwards. Pre-fix both would read 0.
                        int later = ProbeTarget.value2();
                        boolean h = raced && inflightResult[0] == 4242 && later == 4242;
                        lines.add("H unhook-race: " + (h ? "PASS" : "FAIL")
                                + " raced=" + raced + " inflight=" + inflightResult[0]
                                + " later=" + later);
                        if (h) pass++;
                    }
                } catch (Throwable t4) {
                    lines.add("H unhook-race: ERROR " + t4);
                    Log.e(TAG, "H test failed", t4);
                }

                // H2: the arm-publish vs unhook window. finishArm used to write
                // the target entrypoint before armed and without entry.lock, so
                // an unhook landing in between removed HOOKS while the target
                // kept pointing at the bridge -- every later call answered 0.
                // The probe barrier parks finishArm after the shadow clone and
                // before its lock; installing it for this id also defers hook()
                // to the warmer (an inline arm would park holding the callbacks
                // monitor, and unhook could never run).
                total++;
                try {
                    final java.util.concurrent.CountDownLatch atBarrier =
                            new java.util.concurrent.CountDownLatch(1);
                    final java.util.concurrent.CountDownLatch releaseArm =
                            new java.util.concurrent.CountDownLatch(1);
                    com.repack.art.ArtHookEngine.setArmBarrier("b_value2", () -> {
                        atBarrier.countDown();
                        try {
                            releaseArm.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    boolean parked = false;
                    XC_MethodHook.Unhook u3 = null;
                    try {
                        u3 = de.robv.android.xposed.XposedBridge.hookMethod(
                                ProbeTarget.class.getDeclaredMethod("value2"),
                                new XC_MethodHook() {
                                    @Override
                                    public void afterHookedMethod(MethodHookParam param) {
                                        param.setResult(1234);
                                    }
                                });
                        parked = atBarrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        // Parked: unhook runs while finishArm sits before its
                        // lock and holds no callbacks monitor. Not parked: the
                        // entry is still queued or mid-compile; unhook removes
                        // it and any later finishArm must abort.
                        if (u3 != null) u3.unhook();
                    } finally {
                        releaseArm.countDown();
                        com.repack.art.ArtHookEngine.setArmBarrier(null, null);
                    }
                    Thread.sleep(1000); // let a released finishArm take the abort branch
                    int later = ProbeTarget.value2();
                    boolean armedAfter = com.repack.art.ArtHookEngine.isArmedById("b_value2");
                    boolean h2 = parked && later == 4242 && !armedAfter;
                    lines.add("H2 arm-vs-unhook: " + (h2 ? "PASS" : "FAIL")
                            + " parked=" + parked + " later=" + later
                            + " armed=" + armedAfter);
                    if (h2) pass++;
                } catch (Throwable t5) {
                    lines.add("H2 arm-vs-unhook: ERROR " + t5);
                    Log.e(TAG, "H2 test failed", t5);
                }
            } catch (Throwable t) {
                lines.add("functional test error: " + t);
                lines.add(android.util.Log.getStackTraceString(t));
                Log.e(TAG, "functional tests failed", t);
            }
        }
        writeResults(pass, total);
    }

    /**
     * Re-composes and rewrites the result file. Called from onCreate (A-D) and
     * again from onResume, because the boot-class hooks (E attach, F onResume)
     * can only be judged after the lifecycle calls they intercept have run.
     */
    static void writeResults(int functionalPass, int functionalTotal) {
        List<String> lines = new ArrayList<>(functionalLines);
        int pass = functionalPass, total = functionalTotal;
        total++;
        lines.add("E attach hook: " + (attachFired ? "PASS context=" + attachContextClass : "PENDING"));
        if (attachFired) pass++;
        total++;
        lines.add("F onResume hook: " + (resumeHookFired ? "PASS" : "PENDING"));
        if (resumeHookFired) pass++;
        lines.add("SUMMARY " + pass + "/" + total);
        writeResultsPass = pass;
        writeResultsTotal = total;

        File out = new File(app.getExternalFilesDir(null), "result.txt");
        write(out, lines);
        File out2 = new File(app.getFilesDir(), "result.txt");
        write(out2, lines);
    }

    private static void write(File f, List<String> lines) {
        try (FileWriter w = new FileWriter(f)) {
            for (String l : lines) w.write(l + "\n");
        } catch (Throwable t) {
            Log.e(TAG, "result write failed: " + f, t);
        }
        // Non-debuggable builds have no run-as channel; logcat main is the
        // reliable readout on the tablet.
        for (String l : lines) {
            Log.i(TAG, "RESULT " + l);
        }
    }
}
