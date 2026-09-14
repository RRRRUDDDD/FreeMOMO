package com.rud.freemomo.hook

import com.rud.freemomo.BuildConfig
import com.rud.freemomo.util.Logger
import com.rud.freemomo.util.ThrowablePolicy
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

class WordLimitHook {

    fun apply(target: MethodSignature, classLoader: ClassLoader, source: String): Boolean {
        if (!HookSignatures.isWord(target)) {
            XposedBridge.log("FreeMOMO: word-limit $source rejected -> ${target.displayName}")
            return false
        }
        return try {
            val method = HookSignatures.resolve(target, classLoader)
                ?: throw NoSuchMethodException(target.displayName)
            val firstHitLogged = AtomicBoolean(false)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = param.result as? Int ?: return
                    param.result = HookDiscoveryPolicy.WORD_LIMIT_REPLACEMENT
                    if (firstHitLogged.compareAndSet(false, true)) {
                        XposedBridge.log(
                            "FreeMOMO: word-limit $source hit -> ${target.displayName} " +
                                "$original -> ${HookDiscoveryPolicy.WORD_LIMIT_REPLACEMENT}"
                        )
                    }
                }
            })
            XposedBridge.log("FreeMOMO: word-limit $source installed -> ${target.displayName}")
            true
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            Logger.error("word-limit $source install rejected: ${target.displayName}", error)
            false
        }
    }

    /** Installs only the static int() observers admitted by StructuralHookDiscovery. */
    fun observe(
        initial: WordLimitDiscovery,
        classLoader: ClassLoader,
        group: WordObserverGroup
    ): Boolean {
        if (initial.status != DiscoveryStatus.PENDING || initial.candidates.isEmpty()) return false
        if (initial.candidates.any { !HookSignatures.isWord(it) }) return false
        val methods = HookSignatures.resolveAll(initial.candidates, classLoader) ?: return false
        val resolved = initial.candidates.zip(methods).toMap()
        val installed = group.install(initial.candidates) { signature, observer ->
            val firstResult = AtomicBoolean(false)
            val unhook = XposedBridge.hookMethod(resolved.getValue(signature), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = param.result as? Int ?: return
                    if (BuildConfig.DEBUG && firstResult.compareAndSet(false, true)) {
                        XposedBridge.log(
                            "FreeMOMO: word-limit observer first result -> " +
                                "${signature.displayName} = $original"
                        )
                    }
                    observer(original) { replacement -> param.result = replacement }
                }
            })
            HookUnhook { unhook.unhook() }
        }
        if (installed) {
            XposedBridge.log(
                "FreeMOMO: word-limit pending observers installed -> " +
                    initial.candidates.joinToString { it.displayName }
            )
        }
        return installed
    }
}
