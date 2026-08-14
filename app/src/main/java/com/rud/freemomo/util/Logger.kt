package com.rud.freemomo.util

import com.rud.freemomo.BuildConfig
import de.robv.android.xposed.XposedBridge

object Logger {

    fun error(message: String, error: Throwable) {
        XposedBridge.log(
            "FreeMOMO: $message: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        )
        if (BuildConfig.DEBUG) {
            XposedBridge.log(error)
        }
    }
}
