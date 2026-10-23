package com.freemomo.probe;

/** Hook fodder: a static int getter and a constructor with a side effect. */
public class ProbeTarget {

    public int stored;

    public ProbeTarget(int v) {
        stored = v;
    }

    public static int value() {
        return 4242;
    }

    /** Clean target for H: value() stays hooked by C's leftover setResult(9). */
    public static int value2() {
        return 4242;
    }
}
