package com.rud.freemomo.hook

import com.rud.freemomo.util.Logger
import com.rud.freemomo.util.ThrowablePolicy
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class UserLevelHook {

    fun applyDisplay(
        target: DisplayHookTargets,
        classLoader: ClassLoader,
        source: String
    ): Boolean {
        if (!HookSignatures.isDisplay(target)) return rejected("display", source)
        val methods = HookSignatures.resolveAll(target.methods, classLoader)
            ?: return rejected("display", source)
        val firstHitLogged = AtomicBoolean(false)
        return installGroup("display", source, target.methods, methods) { signature ->
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args.getOrNull(1) as? Int ?: return
                    val replacement = HookDiscoveryPolicy.displayedLevelReplacement(original)
                        ?: return
                    param.args[1] = replacement
                    if (firstHitLogged.compareAndSet(false, true)) {
                        XposedBridge.log(
                            "FreeMOMO: display $source hit -> ${signature.displayName} " +
                                "$original -> $replacement"
                        )
                    }
                }
            }
        }
    }

    fun applyPrivilege(
        target: PrivilegeHookTargets,
        classLoader: ClassLoader,
        source: String
    ): Boolean {
        if (!HookSignatures.isPrivilege(target)) return rejected("privilege", source)
        val methods = HookSignatures.resolveAll(target.methods, classLoader)
            ?: return rejected("privilege", source)
        val changedPrivileges = ConcurrentHashMap.newKeySet<String>()
        return installGroup("privilege", source, target.methods, methods) { signature ->
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                    val code = param.args.firstOrNull()?.toString().orEmpty()
                    if (changedPrivileges.add("${signature.methodName}:$code")) {
                        XposedBridge.log(
                            "FreeMOMO: privilege $source hit -> " +
                                "${signature.displayName}($code) -> true"
                        )
                    }
                }
            }
        }
    }

    fun applyCloud(
        target: MethodSignature,
        classLoader: ClassLoader,
        source: String
    ): Boolean {
        if (!HookSignatures.isCloud(target)) return rejected("cloud", source)
        val method = HookSignatures.resolve(target, classLoader) ?: return rejected("cloud", source)
        val firstHitLogged = AtomicBoolean(false)
        return try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = HookDiscoveryPolicy.CLOUD_LIBRARY_LIMIT_REPLACEMENT
                    if (firstHitLogged.compareAndSet(false, true)) {
                        XposedBridge.log(
                            "FreeMOMO: cloud $source hit -> ${target.displayName} -> " +
                                HookDiscoveryPolicy.CLOUD_LIBRARY_LIMIT_REPLACEMENT
                        )
                    }
                }
            })
            XposedBridge.log("FreeMOMO: cloud $source installed -> ${target.displayName}")
            true
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            Logger.error("cloud $source install rejected: ${target.displayName}", error)
            false
        }
    }

    private fun installGroup(
        capability: String,
        source: String,
        signatures: List<MethodSignature>,
        methods: List<Method>,
        hook: (MethodSignature) -> XC_MethodHook
    ): Boolean {
        val unhooks = mutableListOf<XC_MethodHook.Unhook>()
        return try {
            methods.forEachIndexed { index, method ->
                unhooks += XposedBridge.hookMethod(method, hook(signatures[index]))
            }
            XposedBridge.log(
                "FreeMOMO: $capability $source installed -> " +
                    signatures.joinToString { it.displayName }
            )
            true
        } catch (error: Throwable) {
            rollback(unhooks, capability, source)
            ThrowablePolicy.rethrowIfFatal(error)
            Logger.error("$capability $source group install rejected", error)
            false
        }
    }

    private fun rollback(
        unhooks: List<XC_MethodHook.Unhook>,
        capability: String,
        source: String
    ) {
        unhooks.asReversed().forEach { unhook ->
            try {
                unhook.unhook()
            } catch (rollbackError: Throwable) {
                ThrowablePolicy.rethrowIfFatal(rollbackError)
                Logger.error("$capability $source rollback failed", rollbackError)
            }
        }
    }

    private fun rejected(capability: String, source: String): Boolean {
        XposedBridge.log("FreeMOMO: $capability $source rejected -> signature validation failed")
        return false
    }
}
