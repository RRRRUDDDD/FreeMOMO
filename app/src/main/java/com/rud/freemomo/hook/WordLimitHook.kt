package com.rud.freemomo.hook

import com.rud.freemomo.BuildConfig
import com.rud.freemomo.util.HookCache
import com.rud.freemomo.util.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WordLimitHook {

    fun apply(target: MethodSignature, classLoader: ClassLoader, source: String): Boolean {
        if (!isWordLimitTarget(target)) {
            XposedBridge.log("FreeMOMO: word-limit $source rejected -> ${target.displayName}")
            return false
        }
        return try {
            val method = resolveMethod(target, classLoader)
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
            Logger.error("word-limit $source install rejected: ${target.displayName}", error)
            false
        }
    }

    /** Installs only the static int() observers admitted by StructuralHookDiscovery. */
    fun observe(
        initial: WordLimitDiscovery,
        classLoader: ClassLoader,
        onDiscovered: (MethodSignature) -> Unit,
        onAmbiguous: (List<MethodSignature>) -> Unit
    ): Boolean {
        if (initial.status != DiscoveryStatus.PENDING || initial.candidates.isEmpty()) return false
        val resolved = initial.candidates.map { signature ->
            signature to (resolveMethod(signature, classLoader) ?: return false)
        }
        val state = AtomicReference(initial)
        val lock = Any()
        val firstResults = resolved.associate { it.first to AtomicBoolean(false) }
        val unhooks = mutableListOf<XC_MethodHook.Unhook>()
        return try {
            resolved.forEach { (signature, method) ->
                unhooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val original = param.result as? Int ?: return
                        if (BuildConfig.DEBUG && firstResults.getValue(signature)
                                .compareAndSet(false, true)
                        ) {
                            XposedBridge.log(
                                "FreeMOMO: word-limit observer first result -> " +
                                    "${signature.displayName} = $original"
                            )
                        }
                        val next = synchronized(lock) {
                            val previous = state.get()
                            val updated = previous.observe(signature, original)
                            state.set(updated)
                            if (previous.status != DiscoveryStatus.DISCOVERED &&
                                updated.status == DiscoveryStatus.DISCOVERED
                            ) {
                                onDiscovered(requireNotNull(updated.target))
                            }
                            if (previous.status != DiscoveryStatus.AMBIGUOUS &&
                                updated.status == DiscoveryStatus.AMBIGUOUS
                            ) {
                                onAmbiguous(updated.conflicts)
                            }
                            updated
                        }
                        if (next.status == DiscoveryStatus.DISCOVERED &&
                            next.target == signature
                        ) {
                            param.result = HookDiscoveryPolicy.WORD_LIMIT_REPLACEMENT
                        }
                    }
                })
            }
            XposedBridge.log(
                "FreeMOMO: word-limit pending observers installed -> " +
                    initial.candidates.joinToString { it.displayName }
            )
            true
        } catch (error: Throwable) {
            unhooks.forEach { it.unhook() }
            Logger.error("word-limit observer install rejected", error)
            false
        }
    }

    private fun resolveMethod(
        target: MethodSignature,
        classLoader: ClassLoader
    ): Method? = try {
        val cls = Class.forName(target.className, false, classLoader)
        val parameters = target.parameterTypes.map {
            HookCache.resolveType(it, classLoader)
        }.toTypedArray()
        cls.getDeclaredMethod(target.methodName, *parameters).takeIf { method ->
            method.returnType.name == target.returnType &&
                Modifier.isStatic(method.modifiers) == target.isStatic
        }
    } catch (_: Throwable) {
        null
    }

    private fun isWordLimitTarget(target: MethodSignature): Boolean =
        target.className == HookTargets.WORD_LIMIT_CLASS &&
            target.parameterTypes.isEmpty() &&
            target.returnType == HookTargets.INT &&
            target.isStatic
}
