package com.rud.freemomo.hook

import com.rud.freemomo.util.HookCache
import com.rud.freemomo.util.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class UserLevelHook {

    fun applyDisplay(
        target: DisplayHookTargets,
        classLoader: ClassLoader,
        source: String
    ): Boolean {
        if (!isDisplayTarget(target)) return rejected("display", source)
        val methods = resolveAll(target.methods, classLoader) ?: return rejected("display", source)
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
        if (!isPrivilegeTarget(target)) return rejected("privilege", source)
        val methods = resolveAll(target.methods, classLoader)
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
        if (!isCloudTarget(target)) return rejected("cloud", source)
        val method = resolveMethod(target, classLoader) ?: return rejected("cloud", source)
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
            unhooks.forEach { it.unhook() }
            Logger.error("$capability $source group install rejected", error)
            false
        }
    }

    private fun resolveAll(
        signatures: List<MethodSignature>,
        classLoader: ClassLoader
    ): List<Method>? {
        val methods = signatures.map { resolveMethod(it, classLoader) ?: return null }
        return methods.takeIf { it.size == signatures.size }
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

    private fun rejected(capability: String, source: String): Boolean {
        XposedBridge.log("FreeMOMO: $capability $source rejected -> signature validation failed")
        return false
    }

    private fun isDisplayTarget(target: DisplayHookTargets): Boolean {
        val short = target.twoArgumentMethod
        val detailed = target.detailedMethod
        return short.className == detailed.className &&
            short.isStatic && short.returnType == HookTargets.VOID &&
            short.parameterTypes == listOf(HookTargets.TEXT_VIEW, HookTargets.INT) &&
            detailed.isStatic && detailed.returnType == HookTargets.VOID &&
            detailed.parameterTypes == listOf(
                HookTargets.TEXT_VIEW,
                HookTargets.INT,
                HookTargets.BOOLEAN,
                HookTargets.FLOAT
            )
    }

    private fun isPrivilegeTarget(target: PrivilegeHookTargets): Boolean =
        target.methods.all { method ->
            method.className == HookTargets.PRIVILEGE_CLASS &&
                method.isStatic &&
                method.returnType == HookTargets.BOOLEAN
        } &&
            target.singleArgumentMethods.all {
                it.parameterTypes == listOf(HookTargets.PRIVILEGE_CODE)
            } &&
            target.twoArgumentMethod.parameterTypes == listOf(
                HookTargets.PRIVILEGE_CODE,
                HookTargets.BOOLEAN
            )

    private fun isCloudTarget(target: MethodSignature): Boolean =
        '.' !in target.className &&
            '$' !in target.className &&
            target.parameterTypes.isEmpty() &&
            target.returnType == HookTargets.INT &&
            target.isStatic
}
