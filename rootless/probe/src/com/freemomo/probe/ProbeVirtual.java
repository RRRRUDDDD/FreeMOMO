package com.freemomo.probe;

/**
 * Self-test: virtual-target dance fidelity. Base.bounce is virtual and
 * overridden; the engine's dance re-invokes the target via reflection after
 * restoring the original entrypoint. Java reflection re-dispatches virtual
 * methods to the most-derived override -- if that happens inside the dance, the
 * whole override chain runs twice per call (the mechanism that caused the
 * FragmentManager "destroyed" crashes behind an Activity.onDestroy hook).
 *
 * Clean dispatch:   derived=1 base=1 (bridge intercepts, original runs once).
 * Re-dispatch bug:  derived=2 base=2 (dance's Method.invoke re-enters Derived).
 */
public final class ProbeVirtual {
    public static volatile int derivedRuns;
    public static volatile int baseBodyRuns;
    public static volatile int bridgeBefore;
    public static volatile int bridgeAfter;

    private ProbeVirtual() {}

    public static class Base {
        public void bounce() {
            baseBodyRuns++;
        }
    }

    public static class Derived extends Base {
        @Override
        public void bounce() {
            derivedRuns++;
            super.bounce();
        }
    }
}
