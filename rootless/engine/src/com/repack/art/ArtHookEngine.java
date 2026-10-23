package com.repack.art;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pure-Java ART hook engine.
 *
 * The target shell rejects two otherwise common carriers: a foreign native
 * library is killed shortly after load, and classes defined through
 * InMemoryDexClassLoader are killed even faster. The only tolerated carrier is
 * platform-fused split code (classes.dex / classes2.dex). Hooking therefore
 * has to happen with zero native code and zero dynamic class definition: raw
 * ArtMethod entrypoint replacement through sun.misc.Unsafe, with per-target
 * bridge methods baked into this dex at build time
 * (com.repack.bridge.Bridges, generated).
 *
 * Mechanics (arm64 only):
 *   - ArtMethod address of a target comes from the light-grey hidden API
 *     java.lang.reflect.Executable#getArtMethod (reachable after the HAB
 *     "L" exemption RootlessBoot installs).
 *   - entry_point_from_quick_compiled_code_ sits at offset 24 on arm64 (stable
 *     since API 26); the offset is validated at runtime: a candidate is accepted
 *     only if two never-compiled methods share a plausible pointer there while
 *     a method the engine just JIT-compiled holds a different one. Wrong offset
 *     or no JIT => engine disables itself (fail-closed).
 *   - A bridge must be JIT-compiled before it can serve as an entrypoint: an
 *     uncompiled method's entrypoint is art_quick_to_interpreter_bridge, which
 *     would just interpret the TARGET (x0 carries the callee ArtMethod) -- a
 *     silent miss, never a crash. warmUpBridge() hot-loops the bridge via
 *     reflection (never a direct call, so it cannot be inlined into the loop)
 *     until its entrypoint leaves the interpreter trampoline.
 *   - Dispatch ("dance mode"): the bridge repacks the registers into an
 *     Object[], runs before-callbacks, then under the per-target lock restores
 *     the ORIGINAL entrypoint and reflectively invokes the target (x0 = the
 *     target's own ArtMethod, so a deoptimization re-enters the real dex code),
 *     and finally re-swaps the bridge in. Constructors cannot be reflectively
 *     re-invoked on an existing receiver, so they use a generated backup
 *     trampoline whose entrypoint is overwritten with the original's; a deopt
 *     there surfaces as a loud UnsupportedOperationException, not silent
 *     corruption. Native targets keep entry_point_from_jni_ untouched, so the
 *     dance re-enters the JNI trampoline normally (5.6's shell H.is(I)Z is
 *     native and hooks fine this way).
 *   - A keeper daemon re-invokes every bridge (warm-up short-circuit) so the
 *     JIT never considers them cold -- an evicted bridge would leave hooked
 *     targets pointing at freed code -- and re-arms any swap that the JIT or a
 *     background dexopt overwrote.
 *
 * Everything is fail-closed: any init/hook failure logs and leaves the app
 * running unhooked.
 */
public final class ArtHookEngine {

    static final String TAG = "FreeMOMO-Hook";

    private static volatile boolean sInited;
    private static volatile boolean sFailed;
    private static Object sUnsafe;                    // sun.misc.Unsafe, reflection-typed
    private static Method sGetRaw;                    // raw-address getter
    private static Method sPutRaw;                    // raw-address setter
    private static Method sGetArtMethod;              // Executable#getArtMethod
    private static int sEpOffset = -1;
    private static long sInterpEp;                    // art_quick_to_interpreter_bridge

    private static final Map<String, HookEntry> HOOKS = new ConcurrentHashMap<>();
    /** Entries whose swap was deferred to the warmer thread (main thread must not block). */
    private static final java.util.concurrent.ConcurrentLinkedQueue<HookEntry> PENDING =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Bridges the warmer has actually compiled. A deferred entry may only be
     * armed from the prewarm path after its bridge is in here: reading a
     * non-interpreter entrypoint is not proof of compilation (a resolution or
     * JNI trampoline passes the same check, see compileBridge), and trusting
     * one would let b_word publish readiness with a no-op swap.
     */
    private static final Set<String> WARM_BRIDGES =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
     * Probe-only H2 hook, never set in production. When it matches an entry
     * id, hook() parks that entry on PENDING instead of arming inline, and
     * finishArm runs the barrier after the shadow clone but before entry.lock.
     * That makes the arm-publish vs unhook interleaving deterministic: the
     * callbacks monitor is free, so unhook can complete while the swap is
     * still unpublished. Production pays one volatile read per finishArm.
     */
    public static volatile Runnable sArmBarrier;
    public static volatile String sArmBarrierId;

    /** Probe-only (H2): null clears. The barrier only trips for this exact id. */
    public static void setArmBarrier(String id, Runnable barrier) {
        if (barrier == null) {
            sArmBarrier = null;
            sArmBarrierId = null;
        } else {
            sArmBarrierId = id;
            sArmBarrier = barrier;
        }
    }

    /**
     * Prewarm order. The entry gate waits for b_word to be actually swapped in,
     * so its baked bridge must be the first one the warmer compiles; an
     * arbitrary order could leave the word bridge behind ~30 update bridges
     * and keep the gate closed for far longer than needed. The rest of the
     * entry-critical set follows so the first main-page frame is already fully
     * hooked.
     */
    private static final List<String> PREWARM_PRIORITY = Arrays.asList(
            "b_word",
            "b_secneo_is", "b_secneo_callsw", "b_secneo_callbs",
            "b_attach",
            "b_disp_g", "b_disp_h",
            "b_priv_k", "b_priv_m", "b_priv_l",
            "b_cloud",
            "b_gson_toj_el5", "b_gson_toj_obj", "b_gson_toj_obj_type",
            "b_gson_toj_el5_app", "b_gson_toj_obj_app", "b_gson_toj_obj_type_app",
            "b_json_tostr", "b_json_tostr_i");

    /** Structured init-failure reason for devices without readable logcat. */
    public static volatile String initError = "not attempted";
    /**
     * Warm-up short-circuit is PER-THREAD on purpose: only the thread currently
     * driving a warm-up loop (module install / keeper) may see bridges return
     * their default; a real business call arriving mid-warm-up must dispatch
     * normally. Real calls never originate on the warming thread (the keeper is
     * a daemon, and a target being warmed is not yet swapped on the installer
     * thread), so the flag can never eat a genuine hook dispatch.
     */
    private static final ThreadLocal<Boolean> sWarming = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ArtHookEngine() {}

    // ------------------------------------------------------------------ init

    /** Returns true when the engine is armed and hooking is possible. */
    public static synchronized boolean init() {
        if (sInited) return true;
        if (sFailed) return false;
        try {
            sUnsafe = obtainUnsafe();
            sGetRaw = findRawMethod(sUnsafe.getClass(), "get");
            sPutRaw = findRawMethod(sUnsafe.getClass(), "put");
            if (sGetRaw == null || sPutRaw == null) throw new IllegalStateException("raw Unsafe accessors missing");
            Class<?> executable = Class.forName("java.lang.reflect.Executable");
            sGetArtMethod = executable.getDeclaredMethod("getArtMethod");
            sGetArtMethod.setAccessible(true);

            sEpOffset = detectEntrypointOffset();
            if (sEpOffset < 0) throw new IllegalStateException(detectionTrace.toString().trim());
            sInited = true;
            Log.i(TAG, "engine armed: epOffset=" + sEpOffset
                    + " interpEp=0x" + Long.toHexString(sInterpEp));
            Thread warmer = new Thread(ArtHookEngine::warmerLoop, "FreeMOMO-warmer");
            warmer.setDaemon(true);
            warmer.start();
            Thread keeper = new Thread(ArtHookEngine::keeperLoop, "FreeMOMO-keeper");
            keeper.setDaemon(true);
            keeper.start();
            return true;
        } catch (Throwable t) {
            sFailed = true;
            initError = t.getClass().getSimpleName() + ": " + t.getMessage()
                    + " | at " + (t.getStackTrace().length > 0 ? t.getStackTrace()[0] : "?");
            Log.e(TAG, "engine init FAILED -- hooking disabled", t);
            return false;
        }
    }

    private static Object obtainUnsafe() throws Exception {
        Class<?> uf = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = uf.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    /** Finds the first raw-address get/put usable for 8-byte values, e.g. getAddress/putAddress. */
    private static Method findRawMethod(Class<?> uf, String prefix) {
        int argCount = "get".equals(prefix) ? 1 : 2;
        for (Method m : uf.getDeclaredMethods()) {
            if (!m.getName().startsWith(prefix) || m.getParameterTypes().length != argCount) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean longArgs = true;
            for (Class<?> c : p) if (c != long.class) { longArgs = false; break; }
            if (!longArgs) continue;
            Class<?> ret = m.getReturnType();
            boolean ok = "get".equals(prefix) ? ret == long.class : ret == void.class;
            if (ok) { m.setAccessible(true); return m; }
        }
        return null;
    }

    private static long artMethodOf(Member m) throws Exception {
        return (Long) sGetArtMethod.invoke(m);
    }

    private static long rawGet(long address) {
        try { return (Long) sGetRaw.invoke(sUnsafe, address); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void rawPut(long address, long value) {
        try { sPutRaw.invoke(sUnsafe, address, value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static long readEntrypoint(Member m) {
        try { return rawGet(artMethodOf(m) + sEpOffset); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void writeEntrypoint(Member m, long value) {
        try { rawPut(artMethodOf(m) + sEpOffset, value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static boolean plausible(long v) {
        return v != 0L && (v & 3L) == 0L && v > 0x10000L && v < 0x800000000000L;
    }

    /**
     * Offset candidates for entry_point_from_quick_compiled_code_ (arm64).
     * 24 is the stable value since API 26; the rest are only tried if the
     * runtime validation rejects 24 (layout drift on future ART releases).
     */
    private static final int[] OFFSET_CANDIDATES = {24, 32, 40, 20, 16, 28, 36, 44};

    private static final StringBuilder detectionTrace = new StringBuilder();

    private static int detectEntrypointOffset() throws Exception {
        Method neverA = ArtHookEngine.class.getDeclaredMethod("unusedNeverA");
        Method neverB = ArtHookEngine.class.getDeclaredMethod("unusedNeverB");
        Method probe = ArtHookEngine.class.getDeclaredMethod("jitProbe", int.class);
        probe.setAccessible(true);
        long aAddr = artMethodOf(neverA);
        long bAddr = artMethodOf(neverB);
        long pAddr = artMethodOf(probe);
        // The JIT thread can be starved right after install (dex verification
        // competes with it), so compile-and-validate is retried before giving up.
        for (int attempt = 0; attempt < 4; attempt++) {
            forceCompile(probe);
            for (int off : OFFSET_CANDIDATES) {
                try {
                    long aVal = rawGet(aAddr + off);
                    // Two DIFFERENT never-compiled methods can only share an entrypoint
                    // value through a common runtime trampoline (interp / jni). Compiled
                    // methods own distinct code addresses, so equality here discriminates
                    // the trampoline slots from method-specific ones.
                    if (aVal != rawGet(bAddr + off) || !plausible(aVal)) {
                        continue;
                    }
                    long pVal = rawGet(pAddr + off);
                    if (pVal == aVal || !plausible(pVal)) {
                        continue;
                    }
                    sInterpEp = aVal;
                    detectionTrace.append("ACCEPT ").append(off).append(" attempt=").append(attempt);
                    return off;
                } catch (Throwable ignored) {
                    // reading past the struct on a wrong candidate is expected; try next
                }
            }
            detectionTrace.append("attempt").append(attempt).append(":no-candidate; ");
            Thread.sleep(100);
        }
        return -1;
    }

    /** Only ever invoked via reflection (forceCompile/warm-up) so it is never inlined. */
    private static int jitProbe(int x) { return x * 3 + 1; }
    @SuppressWarnings("unused")
    private static void unusedNeverA() {}
    @SuppressWarnings("unused")
    private static void unusedNeverB() {}

    /**
     * Reflection-driven JIT warm-up: interpreted invocations drive hotness until
     * the JIT thread compiles the method. No entrypoint polling here -- during
     * offset detection the offset is not decided yet; the caller reads values.
     * Polls the primary candidate (24) each round so a probe compiled early
     * does not keep the boot thread through the full 64-round budget.
     */
    private static void forceCompile(Method m) {
        try {
            Class<?>[] pt = m.getParameterTypes();
            Object[] args = new Object[pt.length];
            for (int i = 0; i < pt.length; i++) {
                Class<?> p = pt[i];
                if (p == int.class || p == long.class) args[i] = 7;
                else if (p == boolean.class) args[i] = Boolean.TRUE;
                else args[i] = null;
            }
            long addr = artMethodOf(m);
            long initial = rawGet(addr + 24);
            long before = 0;
            boolean compiled = false;
            for (int round = 0; round < 64; round++) {
                for (int i = 0; i < 1024; i++) m.invoke(null, args);
                Thread.sleep(2);
                long ep24 = rawGet(addr + 24);
                if (round == 0) before = ep24;
                // A change of the candidate-24 slot is the compile signal; a
                // wrong-layout field that merely differs from another method
                // must not end the warm-up early.
                if (ep24 != initial && plausible(ep24)) {
                    compiled = true;
                    break;
                }
            }
            detectionTrace.append("compiled24=").append(Long.toHexString(rawGet(addr + 24)))
                    .append(compiled ? "(early)" : "")
                    .append("(was ").append(Long.toHexString(before)).append("); ");
        } catch (Throwable t) {
            detectionTrace.append("forceCompile:").append(t).append("; ");
            Log.w(TAG, "forceCompile failed: " + m, t);
        }
    }

    // ------------------------------------------------------------------ hooking

    static final class HookEntry {
        final String id;
        final Member target;
        final boolean isStatic;
        final boolean isCtor;
        final Method bridge;      // dispatch bridge (never null)
        final Method backup;      // ctor original-invocation trampoline (ctors only)
        final Object lock = new Object();
        final CopyOnWriteArrayList<XC_MethodHook> callbacks = new CopyOnWriteArrayList<>();
        /** Dispatch reads this instead of allocating toArray() per call; rebuilt inside
         *  the callbacks monitor whenever the list mutates. Volatile because dispatch
         *  never takes the callbacks lock. */
        volatile XC_MethodHook[] snapshot = new XC_MethodHook[0];
        /** ArtMethod addresses cached at finishArm so dispatch skips the
         *  Executable#getArtMethod reflection per read/write. Plain longs on
         *  purpose: same publication family as origEp/bridgeEp (written before
         *  the entrypoint swap that opens the dispatch window). */
        long targetAddr;
        long backupAddr;
        long origEp;
        long bridgeEp;
        /** Published last under lock (armed=true after the swap), read without
         *  entry.lock by isArmedById and the keeper. */
        volatile boolean armed;
        boolean armPending;
        /** Warmer-thread only: failed arm retries back off instead of retrying a
         *  full 512-round warm-up every drain tick. Not a hard cap -- the bridge
         *  may still become compilable once the JIT catches up. */
        int armAttempts;
        long nextArmAt;
        boolean firstDispatchLogged;
        /** Probe-only: remaining anomaly-audit passes for this entry (0 = silent).
         *  Consumed by the audit itself, so a healthy entry does not keep paying
         *  two reflective entrypoint reads on every dispatch forever. */
        int anomalyBudget = 4;
        /** data_ captured at arm (the cloned CodeItem locator). The keeper
         *  restores it if a JIT profiling pass clobbers it with a ProfilingInfo.
         *  Same publication family as origEp/bridgeEp: written before the
         *  entrypoint swap that opens the dispatch window. */
        long shadowData;
        HookEntry(String id, Member target, boolean isStatic, boolean isCtor,
                  Method bridge, Method backup) {
            this.id = id;
            this.target = target;
            this.isStatic = isStatic;
            this.isCtor = isCtor;
            this.bridge = bridge;
            this.backup = backup;
        }
    }

    /**
     * Registers a callback on a target whose bridge is baked. Returns the
     * HookEntry so Unhook can remove the callback later.
     */
    public static Object hook(Member target, XC_MethodHook callback, int priority) {
        if (!sInited) throw new IllegalStateException("engine not armed");
        String key = BridgeRegistry.memberKey(target);
        String bridgeId = BridgeRegistry.bridgeIdFor(key);
        if (bridgeId == null) {
            throw new IllegalArgumentException("no baked bridge for " + key);
        }
        HookEntry entry = HOOKS.get(bridgeId);
        if (entry == null) {
            Method bridge = BridgeRegistry.bridgeMethod(bridgeId);
            if (bridge == null) throw new IllegalStateException("bridge method missing: " + bridgeId);
            boolean isCtor = target instanceof Constructor;
            boolean isStatic = Modifier.isStatic(target.getModifiers());
            Method backup = BridgeRegistry.backupMethod(bridgeId);
            // Every baked bridge carries a shadow backup -- the dance
            // dispatches through it exclusively (see dispatchR). A table without
            // one cannot be hooked fail-closed.
            if (backup == null) throw new IllegalStateException("bridge lacks backup: " + bridgeId);
            entry = new HookEntry(bridgeId, target, isStatic, isCtor, bridge, backup);
            HookEntry raced = HOOKS.putIfAbsent(bridgeId, entry);
            if (raced != null) entry = raced;
        }
        synchronized (entry.callbacks) {
            insertByPriority(entry.callbacks, callback, priority);
            entry.snapshot = entry.callbacks.toArray(new XC_MethodHook[0]);
            if (!entry.armed && !entry.armPending) {
                entry.armPending = true;
                // H2 probe: an installed barrier forces the deferred path so
                // the test parks finishArm on the warmer. An inline arm would
                // park while holding this callbacks monitor and block unhook.
                boolean probeDeferred = sArmBarrier != null && entry.id.equals(sArmBarrierId);
                if ((probeDeferred || !tryArm(entry, 4)) && HOOKS.get(entry.id) == entry) {
                    // Main thread must never sit through a JIT warm-up: the
                    // 33-hook update install at attach otherwise starves the
                    // launch into a bg ANR. The warmer finishes the swap.
                    // A finishArm that aborted because unhook won is no longer
                    // in HOOKS -- never re-queue it.
                    entry.armAttempts = 0;
                    entry.nextArmAt = 0;
                    PENDING.add(entry);
                    if ("b_word".equals(entry.id)) {
                        Log.i(TAG, "arm b_word deferred: bridge not compiled yet; "
                                + "splash gate stays closed until the warmer swaps it");
                    }
                }
            }
        }
        Log.i(TAG, "hooked " + key + " id=" + bridgeId + " callbacks=" + entry.callbacks.size());
        return entry;
    }

    /** Arms with a bounded inline warm-up budget (rounds x 256 invocations). */
    private static boolean tryArm(HookEntry entry, int rounds) {
        long bridgeEp = readEntrypoint(entry.bridge);
        if (!plausible(bridgeEp) || bridgeEp == sInterpEp) {
            if (!compileBridge(entry.bridge, rounds)) {
                return false;
            }
            bridgeEp = readEntrypoint(entry.bridge);
        }
        return finishArm(entry, bridgeEp);
    }

    /**
     * Publishes the swap: bridge/orig fields, then the target entrypoint, then
     * armed -- all under entry.lock after re-checking that the entry is still
     * registered and still has callbacks. Without that re-check an unhook that
     * won while the warmer prepared the arm left HOOKS empty while the target
     * pointed at a dispatching bridge that could only answer defaults.
     *
     * Lock order: hook() calls tryArm under the callbacks monitor and
     * finishArm then takes entry.lock (callbacks -> lock). unhook() releases
     * the callbacks monitor before taking entry.lock, and the other
     * entry.lock users (dispatch, keeper) take no callbacks monitor inside it
     * -- no cycle. compileBridge and the shadow clone stay outside entry.lock
     * so a slow JIT warm-up never blocks dispatch or unhook.
     */
    private static boolean finishArm(HookEntry entry, long bridgeEp) {
        // The dance reflectively invokes the target from this engine class; a
        // protected target (Application.attach!) needs the override or every
        // hooked call dies with IllegalAccessException.
        try { ((java.lang.reflect.AccessibleObject) entry.target).setAccessible(true); } catch (Throwable ignored) {}
        // Cache the ArtMethod addresses once; the dance re-reads/writes them on
        // every dispatch and must not re-derive them reflectively each time.
        // Loud failure: continuing with a zero address would make the dance
        // rawGet/rawPut outside any mapped region.
        try {
            entry.targetAddr = artMethodOf(entry.target);
            if (entry.backup != null) entry.backupAddr = artMethodOf(entry.backup);
        } catch (Exception e) {
            throw new IllegalStateException("artMethodOf failed for " + entry.id, e);
        }
        long origEp = rawGet(entry.targetAddr + sEpOffset);
        if (entry.backup != null) {
            // Unified shadow backup: after the identity clone the shadow's ART
            // proto IS the target's, so its entrypoint (the original) runs the
            // target implementation with the receiver riding in as the first
            // reflective argument -- interpreted targets via the cloned data_
            // code-item pointer, compiled targets via the cloned code address
            // (a deopt re-enters the target's dex through the cloned
            // dex_method_index_).
            try { entry.backup.setAccessible(true); } catch (Throwable ignored) {}
            copyArtMethodIdentity(entry.target, entry.backup);
            if (!entry.isCtor && !entry.isStatic) makeDirect(entry.backup);
            shieldShadowFromJit(entry.backup);
            entry.shadowData = rawGet(entry.backupAddr + 16);
            writeEntrypoint(entry.backup, origEp);
        }
        // Probe-only H2 barrier: after the shadow clone, before the publish
        // lock. In production this is a volatile read of null.
        Runnable barrier = sArmBarrier;
        if (barrier != null && entry.id.equals(sArmBarrierId)) barrier.run();
        synchronized (entry.lock) {
            if (HOOKS.get(entry.id) != entry || entry.callbacks.isEmpty()) {
                // unhook won during the shadow clone / barrier: it already
                // removed HOOKS/PENDING and never touched the target entrypoint.
                // The backup was rewritten but nothing points at it; the next
                // hook for this id clones afresh.
                entry.armPending = false;
                Log.i(TAG, "arm aborted (unhooked): " + entry.id);
                return false;
            }
            entry.bridgeEp = bridgeEp;
            entry.origEp = origEp;
            writeEntrypoint(entry.target, bridgeEp);  // target swap after the cached fields
            entry.armed = true;                       // publish mark, strictly last
            entry.armPending = false;
        }
        // Always log the arm-time entrypoint so silent arms (interpreted
        // targets) are visible in the audit too.
        Log.i(TAG, "arm " + entry.id + ": origEp=0x" + Long.toHexString(origEp)
                + " interp=0x" + Long.toHexString(sInterpEp)
                + (origEp == sInterpEp ? " (interpreted)" : " (compiled/other)")
                + " [shadow]");
        if ("b_word".equals(entry.id)) {
            // This, not registration, is what opens the splash gate.
            Log.i(TAG, "arm b_word: word-limit getter entrypoint swapped");
            de.robv.android.xposed.XposedBridge.onWordBridgeArmed();
        }
        return true;
    }

    private static void insertByPriority(List<XC_MethodHook> list, XC_MethodHook cb, int priority) {
        cb.priority = priority;
        int i = 0;
        while (i < list.size() && list.get(i).priority >= priority) i++;
        list.add(i, cb);
    }

    /**
     * Makes the ctor backup a FULL shadow of the target: declaring_class +
     * access_flags (so reflection consumes a receiver and the proto matches),
     * dex_method_index + method_index (+hotness), and data_ -- which for an
     * interpreted method IS the code-item locator (ArtMethod::GetCodeItem reads
     * it), so the shadow interprets the target's bytecode; for compiled code it
     * makes a deopt re-enter the target's dex as well. Constructors cannot be
     * native, so the flags cannot carry a native bit into the holder.
     */
    private static void copyArtMethodIdentity(Member target, Member backup) {
        try {
            long t = artMethodOf(target);
            long b = artMethodOf(backup);
            rawPut(b, rawGet(t));          // declaring_class_ + access_flags_
            rawPut(b + 8, rawGet(t + 8));  // dex_method_index_ + method_index_ + hotness
            rawPut(b + 16, rawGet(t + 16)); // data_ = code item ptr (interpreted) / jni ptr
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * kAccPublic=0x1 kAccPrivate=0x2 kAccProtected=0x4. ART routes a reflective
     * invoke through vtable dispatch for non-direct methods; direct means
     * static|private|ctor. Patching the identity-cloned shadow to private makes
     * Method.invoke enter THE SHADOW'S ArtMethod -- the target's own code at
     * hook-arm time -- instead of re-dispatching into the most-derived override
     * (the FragmentManager double-run root cause).
     *
     * access_flags_ sits at offset 4 (compressed-pointer layout: declaring
     * class 0-3, flags 4-7, dex_method_index 8-11) -- the probe LAYOUT dump
     * [0] word low half carries the class pointer, high half the flags
     * (0x...01 public ctor / 0x...09 public static). Offset 8 is the
     * dex_method_index word and must NOT be touched.
     */
    private static void makeDirect(Member shadow) {
        try {
            long a = artMethodOf(shadow);
            rawPut(a + 4, (rawGet(a + 4) & ~0x5L) | 0x2L);
            // A direct (private) method has no vtable slot. The identity
            // clone copied the target's virtual method_index_/hotness; leaving
            // a vtable index on a private method is an invalid ArtMethod state
            // (arming such a shadow corrupted the process and killed Glide's
            // Engine.load with a monitor-enter NPE). Clear method_index_ +
            // hotness_count_ while keeping dex_method_index_.
            long idx = rawGet(a + 8);
            rawPut(a + 8, idx & 0xFFFFFFFFL);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The identity clone carries the target's hotness_count, so the shadow
     * of an already-hot method is itself near the JIT trigger. The JIT's
     * profiling pass then writes a ProfilingInfo into data_ -- clobbering the
     * CodeItem locator the interpreter cloned for it. Execution keeps running,
     * but every stack walk through such a shadow frame re-reads data_ as a
     * CodeItem and misjudges the frame layout: an exception built across the
     * dance (Throwable ctor -> fillInStackTrace) dies in
     * ArtMethod::PrettyMethod with SIGSEGV fault 0x38. Observed on relaunch
     * with app data: the Gson sync made the toJson shadows hot ~1s after
     * process start, exactly the crash window. kAccCompileDontBother
     * (0x02000000, access_flags_ at offset 4 = low half of the 8-byte read)
     * takes the shadow out of every JIT decision; the keeper additionally
     * restores data_ should a path ignore the flag.
     */
    private static void shieldShadowFromJit(Member shadow) {
        try {
            long a = artMethodOf(shadow);
            long v = rawGet(a + 4);
            long flags = (v & 0xFFFFFFFFL) | 0x02000000L;
            rawPut(a + 4, (v & 0xFFFFFFFF00000000L) | flags);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Hot-loops the bridge via reflection (per-thread warm-up short-circuit)
     * until the JIT compiles it, polling every 256 invocations so a low JIT
     * threshold exits fast.
     */
    private static boolean compileBridge(Method bridge, int rounds) {
        try {
            bridge.setAccessible(true);
            Class<?>[] pt = bridge.getParameterTypes();
            Object[] args = warmupArgsFor(pt);
            sWarming.set(Boolean.TRUE);
            try {
                // No instant-true fast path on a pre-existing entrypoint: a
                // never-invoked fresh method must earn its non-interpreter ep
                // through THIS loop; trusting a pre-existing pointer would let
                // a generic trampoline (JNI / resolution) pass the check and
                // arm a no-op swap.
                for (int round = 0; round < rounds; round++) {
                    for (int i = 0; i < 256; i++) bridge.invoke(null, args);
                    long ep = readEntrypoint(bridge);
                    if (plausible(ep) && ep != sInterpEp) return true;
                    Thread.sleep(2);
                }
            } finally {
                sWarming.set(Boolean.FALSE);
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "bridge compile failed: " + bridge, t);
            return false;
        }
    }

    /**
     * Synchronously compiles the bridge of one hooked member (long budget).
     * For one-shot lifecycle targets ONLY (Application.attach/onCreate,
     * attachBaseContext, Instrumentation.callApplicationOnCreate): they fire
     * within the first second, so a deferred swap would miss them forever, and
     * the factory <clinit> window has no ANR clock yet. Never call this for
     * the bulk hook set. Takes the TARGET (as resolved by the caller) and maps
     * it through the registry -- an AOT'd target would otherwise pass a
     * bridge check spuriously while its actual bridge stayed interpreted.
     */
    public static boolean precompile(Member target) {
        String bridgeId = BridgeRegistry.bridgeIdFor(BridgeRegistry.memberKey(target));
        if (bridgeId == null) {
            Log.w(TAG, "precompile: no registered bridge for " + target);
            return false;
        }
        Method bridge = BridgeRegistry.bridgeMethod(bridgeId);
        if (bridge == null) {
            Log.w(TAG, "precompile: bridge method missing for " + target);
            return false;
        }
        return compileBridge(bridge, 512);
    }

    /** A queued entry is only worth arming while it still has callbacks and its HOOKS slot. */
    private static boolean armable(HookEntry e) {
        return !e.callbacks.isEmpty() && HOOKS.get(e.id) == e;
    }

    private static int prewarmRank(String id) {
        int i = PREWARM_PRIORITY.indexOf(id);
        return i >= 0 ? i : PREWARM_PRIORITY.size();
    }

    /** Entry-critical bridges first; the bulk update set keeps registry order. */
    private static List<String> prewarmOrder(Set<String> ids) {
        List<String> out = new ArrayList<>(ids);
        out.sort((a, b) -> Integer.compare(prewarmRank(a), prewarmRank(b)));
        return out;
    }

    /**
     * One bounded pass over the deferred queue. hook() on the delivery thread
     * only tries a tiny inline budget before parking an entry here, so the
     * warmer is the only place a swap becomes real. Failed entries stay queued.
     *
     * compile=false arms only entries whose bridge is already in WARM_BRIDGES;
     * the prewarm loop calls this after each compileBridge so a waiting b_word
     * is swapped the moment its bridge is compiled, instead of paying a fresh
     * 512-round warm-up for every unrelated pending entry.
     */
    private static void drainPending(int rounds, boolean compile) {
        int n = PENDING.size();
        long now = System.nanoTime() / 1_000_000L;
        for (int i = 0; i < n; i++) {
            HookEntry e = PENDING.poll();
            if (e == null) return;
            if (e.armed || !armable(e)) continue;
            if (!compile && !WARM_BRIDGES.contains(e.id)) {
                PENDING.add(e);
                continue;
            }
            if (now < e.nextArmAt) {
                PENDING.add(e);
                continue;
            }
            try {
                // A finishArm that aborted under unhook is no longer in HOOKS;
                // re-queueing it would keep a rolled-back entry alive forever.
                if (!tryArm(e, rounds) && armable(e)) deferArm(e, now);
            } catch (Throwable t) {
                if (armable(e)) {
                    deferArm(e, now);
                    Log.w(TAG, "deferred arm failed: " + e.id, t);
                }
            }
        }
    }

    /** Backs a failing arm off exponentially (cap 30s) so one bridge the JIT
     *  never compiles cannot pin the warmer in a 512-round retry every 200ms. */
    private static void deferArm(HookEntry e, long now) {
        e.armAttempts = Math.min(e.armAttempts + 1, 12);
        long delay = Math.min(30_000L, 200L << Math.min(e.armAttempts, 7));
        e.nextArmAt = now + delay;
        PENDING.add(e);
    }

    /**
     * Pre-compiles every baked bridge off the critical path, then keeps
     * draining the deferred-arm queue. b_word and the first-frame hooks are
     * compiled first, and the pending queue is drained after each bridge
     * compile -- re-arming pending entries only on the next arbitrary registry
     * slot could leave the word bridge queued far behind the 30+ update
     * bridges. Until a swap is real, business calls run unhooked and the splash
     * gate stays closed.
     */
    private static void warmerLoop() {
        try {
            BridgeRegistry.ensureLoaded();
            drainPending(512, false);
            for (String id : prewarmOrder(BridgeRegistry.allBridgeIds())) {
                drainPending(512, false);
                Method bridge = BridgeRegistry.bridgeMethod(id);
                if (bridge != null && compileBridge(bridge, 512)) {
                    WARM_BRIDGES.add(id);
                }
                // Arm the entry for the bridge that just became warm before
                // moving on to the next one.
                drainPending(512, false);
            }
            drainPending(512, false);
            Log.i(TAG, "warmer prewarm done");
            while (true) {
                drainPending(512, true);
                Thread.sleep(200);
            }
        } catch (Throwable t) {
            Log.w(TAG, "warmer failed", t);
        }
    }

    private static Object[] warmupArgsFor(Class<?>[] pt) {
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) {
            Class<?> p = pt[i];
            if (p == int.class || p == long.class) args[i] = 0;
            else if (p == boolean.class) args[i] = Boolean.FALSE;
            else if (p == float.class) args[i] = 0f;
            else if (p == double.class) args[i] = 0d;
            else if (p == short.class) args[i] = (short) 0;
            else if (p == char.class) args[i] = (char) 0;
            else if (p == byte.class) args[i] = (byte) 0;
            else args[i] = null;
        }
        return args;
    }

    /** Test/diagnostic: true when the member's bridge swap is in place. */
    public static boolean isArmed(Member m) {
        String bridgeId = BridgeRegistry.bridgeIdFor(BridgeRegistry.memberKey(m));
        if (bridgeId == null) return false;
        return isArmedById(bridgeId);
    }

    /** Readiness-handshake support: true only after the entrypoint swap. */
    public static boolean isArmedById(String bridgeId) {
        HookEntry e = HOOKS.get(bridgeId);
        return e != null && e.armed;
    }

    /** Removes a callback; restores the captured original when the last one leaves. */
    public static void unhook(Object entryObj, XC_MethodHook callback) {
        if (!(entryObj instanceof HookEntry)) return;
        HookEntry entry = (HookEntry) entryObj;
        // The snapshot rebuild must observe the same list state as the removal,
        // so both run under the callbacks monitor (same discipline as hook()).
        synchronized (entry.callbacks) {
            entry.callbacks.remove(callback);
            entry.snapshot = entry.callbacks.toArray(new XC_MethodHook[0]);
        }
        if (!entry.callbacks.isEmpty()) return;
        synchronized (entry.lock) {
            if (!entry.callbacks.isEmpty()) return;
            if (entry.armed) {
                long cur = readEntrypoint(entry.target);
                if (cur == entry.bridgeEp) {
                    writeEntrypoint(entry.target, entry.origEp);
                }
                entry.armed = false;
            }
            // A never-armed entry (swap still queued on the warmer) must leave
            // HOOKS/PENDING too, or it lingers and the warmer arms a bridge for
            // a target nobody is hooking anymore. Two-arg remove so a newer
            // registration reusing this id survives.
            entry.armPending = false;
            HOOKS.remove(entry.id, entry);
            PENDING.remove(entry);
        }
    }

    // ------------------------------------------------------------------ dispatch

    public static final Object[] EMPTY_ARGS = new Object[0];

    public static Object dispatchR(String id, Object thiz, Object[] args) throws Throwable {
        if (sWarming.get()) return null;
        HookEntry e = HOOKS.get(id);
        if (e == null) return null; // fully unhooked; entrypoint should have been restored
        // Entrypoint audit. Between dispatches the invariant is
        // targetEp == bridgeEp and shadowEp == origEp. Any deviation means an
        // external writer (JIT / dexopt / trampoline fixup) touched the dance
        // structures; log the first few per entry with all reference values.
        if (e.anomalyBudget > 0 && e.targetAddr != 0 && e.backup != null) {
            try {
                long tEp = rawGet(e.targetAddr + sEpOffset);
                long sEp = e.backup != null ? rawGet(e.backupAddr + sEpOffset) : 0;
                if (tEp != e.bridgeEp || (e.backup != null && sEp != e.origEp)) {
                    Log.w(TAG, "ANOMALY " + e.id + ": targetEp=0x" + Long.toHexString(tEp)
                            + " bridge=0x" + Long.toHexString(e.bridgeEp)
                            + " orig=0x" + Long.toHexString(e.origEp)
                            + " shadowEp=0x" + Long.toHexString(sEp)
                            + " interp=0x" + Long.toHexString(sInterpEp));
                }
            } catch (Throwable ignored) {}
            // Spend the budget whether or not this pass found a deviation: a
            // healthy entry must not audit every dispatch for the process lifetime.
            e.anomalyBudget--;
        }
        if (!e.firstDispatchLogged) {
            e.firstDispatchLogged = true;
            Log.i(TAG, "dispatch " + id + " (first) thiz=" + (thiz == null ? "null" : thiz.getClass().getName())
                    + " args=" + (args == null ? 0 : args.length));
        }
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = e.target;
        param.thisObject = thiz;
        param.args = args != null ? args : EMPTY_ARGS;
        XC_MethodHook[] cbs = e.snapshot;
        for (XC_MethodHook cb : cbs) {
            try { cb.beforeHookedMethod(param); }
            catch (Throwable t) { Log.e(TAG, "before callback failed: " + id, t); }
            if (param.returnEarly) break;
        }
        if (!param.returnEarly) {
            synchronized (e.lock) {
                // armed flips only under this lock (unhook), so this snapshot
                // is stable for the whole critical section.
                final boolean stillArmed = e.armed;
                // The target's entrypoint is NEVER restored during the dance.
                // The bridge frame's method slot is the TARGET ArtMethod
                // (callers pass it in x0), so leaving the target's entrypoint on
                // the bridge is what lets ART resolve that frame's code header
                // while a Throwable is built across it: GetOatQuickMethodHeader
                // checks Contains(pc) against the current entrypoint. Restoring
                // origEp here made the walk pick the wrong header/size and die
                // in ArtMethod::PrettyMethod (SIGSEGV fault 0x38). The original
                // runs through the shadow, whose own entrypoint carries origEp.
                // If the JIT or a background dexopt overwrote the target's
                // entrypoint since arm, the captured origEp may reference freed
                // code. Under this lock any value other than the bridge IS
                // fresh live original code -- adopt it for the shadow and
                // re-mount the bridge.
                long cur = rawGet(e.targetAddr + sEpOffset);
                if (stillArmed && cur != e.bridgeEp) {
                    Log.w(TAG, "adopt " + e.id + ": cur=0x" + Long.toHexString(cur)
                            + " orig=0x" + Long.toHexString(e.origEp)
                            + " bridge=0x" + Long.toHexString(e.bridgeEp)
                            + " interp=0x" + Long.toHexString(sInterpEp));
                    e.origEp = cur;
                    if (e.backup != null) rawPut(e.backupAddr + sEpOffset, cur);
                    rawPut(e.targetAddr + sEpOffset, e.bridgeEp);
                }
                // Pre-invoke audit. The shadow must carry origEp and
                // the target the bridge; anything else means a writer acted
                // inside the dance window.
                if (e.anomalyBudget > 0 && e.backup != null) {
                    try {
                        long sEp = rawGet(e.backupAddr + sEpOffset);
                        long tEp = rawGet(e.targetAddr + sEpOffset);
                        if (sEp != e.origEp || tEp != e.bridgeEp) {
                            Log.w(TAG, "PREINVOKE " + e.id + ": shadowEp=0x" + Long.toHexString(sEp)
                                    + " targetEp=0x" + Long.toHexString(tEp)
                                    + " orig=0x" + Long.toHexString(e.origEp)
                                    + " bridge=0x" + Long.toHexString(e.bridgeEp)
                                    + " interp=0x" + Long.toHexString(sInterpEp));
                        }
                    } catch (Throwable ignored) {}
                    e.anomalyBudget--;
                }
                try {
                    Object r;
                    if (e.isCtor) {
                        // Constructors run on the existing receiver: after the
                        // identity clone the shadow's ART proto IS the target's,
                        // so the receiver rides in as the first argument.
                        e.backup.invoke(thiz, param.args);
                        r = null;
                    } else if (!e.isStatic) {
                        // Instance method: full shadow. makeDirect made it
                        // private, so reflection enters the shadow itself --
                        // the target implementation, not a most-derived
                        // override (the double-run problem). The baked backup
                        // return type matches the target, so the value survives.
                        r = e.backup.invoke(thiz, param.args);
                    } else {
                        // Static target: the shadow is itself static, so
                        // reflection enters it directly.
                        r = e.backup.invoke(null, param.args);
                    }
                    param.result = r;
                } catch (IllegalArgumentException arity) {
                    throw new IllegalStateException("dispatch arity mismatch id=" + e.id
                            + " target=" + e.target + " args=" + param.args.length
                            + " static=" + e.isStatic + " ctor=" + e.isCtor
                            + " entryBridge=" + entryArityDebug(e), arity);
                } catch (InvocationTargetException ite) {
                    param.throwable = ite.getCause();
                    param.returnEarly = true;
                }
            }
        }
        for (int i = cbs.length - 1; i >= 0; i--) {
            try { cbs[i].afterHookedMethod(param); }
            catch (Throwable t) { Log.e(TAG, "after callback failed: " + id, t); }
            if (param.returnEarly) break;
        }
        if (param.hasThrowable()) throw param.throwable;
        return param.result;
    }

    public static int dispatchI(String id, Object thiz, Object[] args) throws Throwable {
        Object r = dispatchR(id, thiz, args);
        return r instanceof Number ? ((Number) r).intValue() : 0;
    }

    public static boolean dispatchZ(String id, Object thiz, Object[] args) throws Throwable {
        Object r = dispatchR(id, thiz, args);
        return r instanceof Boolean ? (Boolean) r : false;
    }

    public static void dispatchV(String id, Object thiz, Object[] args) throws Throwable {
        dispatchR(id, thiz, args);
    }

    private static String entryArityDebug(HookEntry e) {
        try {
            Class<?>[] p = e.target instanceof Method
                    ? ((Method) e.target).getParameterTypes()
                    : ((Constructor<?>) e.target).getParameterTypes();
            return "targetParamCount=" + p.length;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Diagnostics: hex-dump the first 8 words of a member's ArtMethod. */
    public static String dumpMethod(Member m) {
        StringBuilder sb = new StringBuilder(m.toString());
        try {
            long a = artMethodOf(m);
            for (int off = 0; off < 32; off += 8) {
                long v = rawGet(a + off);
                sb.append(String.format(" [%d]=%016x", off, v));
            }
        } catch (Throwable t) {
            sb.append(" dump-failed ").append(t);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ keeper

    /**
     * Keeps every bridge hot (JIT never evicts recently-invoked code, which
     * would leave hooked targets pointing at freed code) and re-arms any swap
     * the JIT or a background dexopt overwrote.
     */
    private static void keeperLoop() {
        while (true) {
            try {
                Thread.sleep(4000);
                sWarming.set(Boolean.TRUE);
                try {
                    for (HookEntry e : HOOKS.values()) {
                        try {
                                    if (e.armed) {
                                        synchronized (e.lock) {
                                            long cur = readEntrypoint(e.target);
                                            if (e.armed && cur != e.bridgeEp) {
                                                // The overwrite is fresh original
                                                // code (JIT recompile / background
                                                // dexopt) -- adopt it for the shadow
                                                // before re-swapping.
                                                e.origEp = cur;
                                                if (e.backup != null) {
                                                    writeEntrypoint(e.backup, cur);
                                                }
                                                writeEntrypoint(e.target, e.bridgeEp);
                                                Log.i(TAG, "keeper re-armed " + e.id
                                                        + ": cur=0x" + Long.toHexString(cur)
                                                        + " bridge=0x" + Long.toHexString(e.bridgeEp));
                                            }
                                            // Restore the shadow's cloned CodeItem locator
                                            // if a JIT profiling pass wrote a ProfilingInfo over
                                            // it. Under e.lock, so it never races a dance using
                                            // this shadow.
                                            if (e.backup != null) {
                                                long d = rawGet(e.backupAddr + 16);
                                                if (d != e.shadowData) {
                                                    rawPut(e.backupAddr + 16, e.shadowData);
                                                    Log.w(TAG, "keeper restored shadow data_ " + e.id
                                                            + ": had=0x" + Long.toHexString(d)
                                                            + " want=0x" + Long.toHexString(e.shadowData));
                                                }
                                            }
                                        }
                                    }
                            e.bridge.invoke(null, warmupArgsFor(e.bridge.getParameterTypes()));
                        } catch (Throwable t) {
                            Log.w(TAG, "keeper pass item failed: " + e.id, t);
                        }
                    }
                } finally {
                    sWarming.set(Boolean.FALSE);
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.w(TAG, "keeper pass failed", t);
            }
        }
    }
}
