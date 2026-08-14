package com.rud.freemomo.hook

import com.rud.freemomo.util.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 在目标 Application 启动前布置的 SecNeo 壳 Hook。
 *
 * 5.5.91 的壳在真实 Application 初始化前调用 H.is(0) 作为门控，并在壳
 * Application.onCreate() 末尾新增 callSW()/callBS() native 保护入口。
 * 这里只恢复正常初始化分支并抑制新增保护入口，不触碰负责解密、替换
 * ClassLoader 和修复 Provider 的 pn()/hn()。
 */
class SecNeoEarlyHook {

    companion object {
        @Volatile
        private var applied = false
    }

    fun apply(classLoader: ClassLoader) {
        if (applied) return

        val helperClass = try {
            XposedHelpers.findClass(SecNeoHookPolicy.HELPER_CLASS, classLoader)
        } catch (error: Throwable) {
            XposedBridge.log("FreeMOMO: SecNeo 壳类尚不可用 - ${error.message}")
            return
        }

        synchronized(SecNeoEarlyHook::class.java) {
            if (applied) return

            val unhooks = mutableListOf<XC_MethodHook.Unhook>()
            try {
                hookNormalApplicationGate(helperClass, unhooks)
                SecNeoHookPolicy.protectionWrapperMethods.forEach { methodName ->
                    hookProtectionWrapper(helperClass, methodName, unhooks)
                }
                applied = true
            } catch (error: Throwable) {
                unhooks.asReversed().forEach { unhook ->
                    try {
                        unhook.unhook()
                    } catch (rollbackError: Throwable) {
                        Logger.error("SecNeo early hook rollback failed", rollbackError)
                    }
                }
                Logger.error("SecNeo early hook group install rejected", error)
            }
        }
    }

    private fun hookNormalApplicationGate(
        helperClass: Class<*>,
        unhooks: MutableList<XC_MethodHook.Unhook>
    ) = hookMethods(
        helperClass,
        "is",
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val argument = param.args.firstOrNull() as? Int
                if (SecNeoHookPolicy.shouldForceNormalPath(param.method.name, argument)) {
                    XposedBridge.log("FreeMOMO: SecNeo H.is(0) -> false（继续初始化真实 Application）")
                    param.result = false
                }
            }
        },
        unhooks
    )

    private fun hookProtectionWrapper(
        helperClass: Class<*>,
        methodName: String,
        unhooks: MutableList<XC_MethodHook.Unhook>
    ) = hookMethods(
        helperClass,
        methodName,
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (SecNeoHookPolicy.shouldSuppressProtectionWrapper(param.method.name)) {
                    XposedBridge.log("FreeMOMO: 已阻止 SecNeo H.${param.method.name}()")
                    param.result = null
                }
            }
        },
        unhooks
    )

    private fun hookMethods(
        helperClass: Class<*>,
        methodName: String,
        callback: XC_MethodHook,
        unhooks: MutableList<XC_MethodHook.Unhook>
    ) {
        val methods = helperClass.declaredMethods.filter { it.name == methodName }
        check(methods.isNotEmpty()) { "SecNeo H.$methodName has no hookable methods" }
        methods.forEach { method ->
            unhooks += XposedBridge.hookMethod(method, callback)
        }
    }
}
