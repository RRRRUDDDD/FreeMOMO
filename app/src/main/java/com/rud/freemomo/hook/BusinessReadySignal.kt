package com.rud.freemomo.hook

import de.robv.android.xposed.XposedBridge

/**
 * r45/r46: tells the split-side splash gate that every entry capability was
 * registered. The split bridge only opens the gate when this confirmation is
 * paired with the engine's arm-time signal (`onWordBridgeArmed`), so a hook
 * that is merely queued on the warmer cannot release the splash early.
 *
 * The marker method lives on the in-process bridge (`XposedBridge.markBusinessReady`,
 * implemented by the r39 split shell) and deliberately does not exist on the
 * stock Xposed API 82 bridge used by the root/LSPosed variant. The lookup is
 * reflective so this source still compiles against api:82; on root the signal
 * degrades to a no-op and the gate does not exist in the first place.
 */
object BusinessReadySignal {

    private val marker: java.lang.reflect.Method? by lazy {
        try {
            XposedBridge::class.java.getMethod("markBusinessReady")
        } catch (error: Throwable) {
            null
        }
    }

    /** Called after the entry-capability hook group has been registered. */
    fun wordHookArmed(source: String) {
        val method = marker ?: return
        try {
            method.invoke(null)
            XposedBridge.log("FreeMOMO: business-ready signaled ($source)")
        } catch (error: Throwable) {
            XposedBridge.log(
                "FreeMOMO: business-ready signal failed ($source): ${error.javaClass.simpleName}"
            )
        }
    }
}
