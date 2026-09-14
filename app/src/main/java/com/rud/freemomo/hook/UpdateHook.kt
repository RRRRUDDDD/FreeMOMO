package com.rud.freemomo.hook

import android.content.Intent
import com.rud.freemomo.util.Logger
import com.rud.freemomo.util.ThrowablePolicy
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Independently installed, version-specific update policy. Business hooks never depend on it. */
object UpdateHook {
    private var installedLoader: ClassLoader? = null

    @Synchronized
    fun apply(classLoader: ClassLoader, versionCode: Int): Boolean {
        val profile = UpdateHookTargets.forVersion(versionCode)
        if (profile == null) {
            XposedBridge.log("FreeMOMO: update:$versionCode unavailable -> no verified signature profile")
            return false
        }
        if (installedLoader === classLoader) return true
        val adapter = Adapter(classLoader)
        return try {
            // Resolve the entire profile before the first hook has a side effect.
            if (!adapter.resolve(profile)) {
                XposedBridge.log("FreeMOMO: update:$versionCode unavailable -> signature validation failed")
                false
            } else {
                adapter.install()
                installedLoader = classLoader
                XposedBridge.log("FreeMOMO: update:$versionCode installed -> manual operations only")
                true
            }
        } catch (error: Throwable) {
            adapter.rollback()
            ThrowablePolicy.rethrowIfFatal(error)
            Logger.error("update:$versionCode unavailable; installation rolled back", error)
            false
        }
    }

    private class Adapter(private val loader: ClassLoader) {
        private val policy = UpdateFlowPolicy()
        private val methods = linkedMapOf<String, Method>()
        private val constructors = linkedMapOf<String, Constructor<*>>()
        private val fields = linkedMapOf<String, Field>()
        private val handles = mutableListOf<XC_MethodHook.Unhook>()
        private val enabled = AtomicBoolean(false)
        private val loggedBlocks = ConcurrentHashMap.newKeySet<String>()
        private val scopeKey = "freemomo.update.scope"
        private val operationKey = "freemomo.update.operation"
        private val enteredKey = "freemomo.update.entered"
        private val transferKey = "com.rud.freemomo.manual_upgrade_operation"

        fun resolve(profile: UpdateHookTargets.Profile): Boolean {
            val valid = profile.validate(
                method = { signature ->
                    HookSignatures.resolve(signature, loader)?.let { method ->
                        methods[profile.methods.entries.first { it.value == signature }.key] = method
                        true
                    } ?: false
                },
                constructor = { signature ->
                    val cls = Class.forName(signature.className, false, loader)
                    val parameters = signature.parameterTypes.map { HookSignatures.resolveType(it, loader) }
                    val constructor = cls.getDeclaredConstructor(*parameters.toTypedArray())
                    constructor.isAccessible = true
                    constructors[profile.constructors.entries.first { it.value == signature }.key] = constructor
                    true
                },
                field = { signature ->
                    val field = Class.forName(signature.className, false, loader).getDeclaredField(signature.name)
                    if (field.type.name != signature.type || Modifier.isStatic(field.modifiers) != signature.isStatic) {
                        false
                    } else {
                        field.isAccessible = true
                        fields[profile.fields.entries.first { it.value == signature }.key] = field
                        true
                    }
                }
            )
            return valid && fields.getValue("notification.upgrade").get(null) == "app_upgrade" &&
                fields.getValue("notification.dialog").get(null) == "app_upgrade_dialog"
        }

        fun install() {
            constructors.forEach { (id, constructor) ->
                hook(constructor, after = { param ->
                    if (!param.hasThrowable() && captureConstructor(id, param)) policy.capture(param.thisObject)
                })
            }
            for (page in listOf("page.upgrade", "page.about")) {
                hook(methods.getValue(page), before = { param ->
                    policy.finish(policy.operationFor(param.thisObject))
                    enter(param, policy.begin(UpdateFlowPolicy.Kind.CHECK))
                    policy.capture(param.thisObject) // Only used for cancellation when this page is destroyed.
                }, after = { param ->
                    if (param.hasThrowable()) policy.finish(operation(param))
                })
            }
            hook(methods.getValue("page.destroy"), before = { param ->
                if (param.thisObject.javaClass.name in setOf(UpdateHookTargets.ACTIVITY, UpdateHookTargets.ABOUT)) {
                    policy.finish(policy.operationFor(param.thisObject))
                }
            })
            hook(methods.getValue("request.get"), before = { param ->
                if (selector("request", param.thisObject) == 22) {
                    enterBound(param)
                    if (!policy.mayCheck()) block(param, "automatic check", null)
                }
            }, after = { param ->
                if (selector("request", param.thisObject) == 22 && (param.hasThrowable() || param.result == null)) {
                    policy.finish(operation(param))
                }
            })
            hook(methods.getValue("result.upgrade"), before = ::enterBound, after = ::finishCheck)
            hook(methods.getValue("result.about"), before = { param ->
                if (isAboutResult(param.thisObject)) enterBound(param)
            }, after = { param -> if (isAboutResult(param.thisObject)) finishCheck(param) })

            hook(methods.getValue("click.upgrade"), before = { param ->
                if (selector("upgrade", param.thisObject) == 0) {
                    enter(param, policy.begin(UpdateFlowPolicy.Kind.UPGRADE))
                }
            }, after = { param -> if (param.hasThrowable()) policy.finish(operation(param)) })
            hook(methods.getValue("click.dialog"), before = { param ->
                val model = requireNotNull(fields.getValue("dialog.owner").get(param.thisObject))
                val previous = policy.operationFor(model)
                if (selector("dialog", param.thisObject) == 2) {
                    // Only the verified approval callback creates an operation, never a download callback.
                    val operation = previous?.takeIf { it.kind == UpdateFlowPolicy.Kind.UPGRADE }
                        ?: policy.begin(UpdateFlowPolicy.Kind.UPGRADE).also { policy.finish(previous) }
                    enter(param, operation)
                    policy.capture(model)
                } else {
                    enter(param, previous)
                }
            }, after = { param ->
                if (selector("dialog", param.thisObject) == 1 || param.hasThrowable()) {
                    policy.finish(operation(param))
                }
            })

            hook(methods.getValue("dispatch"), before = { param ->
                val info = param.args[1]
                val immediateDownload = info != null && fields.getValue("info.title").get(info) == null &&
                    fields.getValue("info.message").get(info) == null
                // This shape immediately calls model.approve and sets the host in-progress flag.
                // Reject before that side effect when a check has not included a user approval.
                if (!policy.mayDispatchUpgrade(immediateDownload)) {
                    block(param, "automatic dispatch", null)
                }
            })
            for (id in listOf("model.approve", "model.download", "model.install", "model.close")) {
                hook(methods.getValue(id), before = { param ->
                    enterBound(param)
                    if (id != "model.close" && !policy.mayDownload()) {
                        block(param, "unowned upgrade operation", if (id == "model.download") false else null)
                    }
                }, after = { param ->
                    if (id == "model.install" || (id == "model.close" && param.args[0] == true)) {
                        policy.finish(operation(param))
                    }
                })
            }

            for (id in listOf("callback.precheck", "callback.proceed", "callback.retry",
                "connection.connected", "connection.disconnected", "foreground.progress",
                "foreground.failure", "foreground.complete", "background.progress",
                "background.failure", "background.complete")) {
                hook(methods.getValue(id), before = ::enterBound)
            }
            hook(methods.getValue("callback.cancel"), before = { param ->
                if (selector("cancel", param.thisObject) == 1 && isModel(fields.getValue("cancel.owner").get(param.thisObject))) {
                    enterBound(param)
                }
            }, after = { param -> policy.finish(operation(param)) })
            hook(methods.getValue("callback.install"), before = { param ->
                if (selector("install", param.thisObject) == 26 && isModel(fields.getValue("install.owner").get(param.thisObject))) {
                    enterBound(param)
                }
            })

            hook(methods.getValue("helper.network"), before = { param ->
                val operation = policy.currentOperation()
                if (operation?.kind != UpdateFlowPolicy.Kind.UPGRADE) {
                    block(param, "automatic download precheck", null)
                } else {
                    (param.args[2] as? Runnable)?.let {
                        param.args[2] = policy.wrapRunnable(it, operation)
                    }
                    (param.args[3] as? Runnable)?.let {
                        param.args[3] = policy.wrapRunnable(it, operation, finishAfter = true)
                    }
                }
            })
            hook(methods.getValue("helper.start"), before = { param ->
                val operation = policy.currentOperation()
                param.setObjectExtra(operationKey, operation)
                if (operation?.kind != UpdateFlowPolicy.Kind.UPGRADE) {
                    block(param, "automatic download", null)
                } else {
                    val listener = param.args[3]
                    if (listener != null && !policy.capture(listener)) {
                        block(param, "download callback from another operation", null)
                    }
                }
            }, after = { param ->
                val operation = operation(param)
                if (operation != null && !param.hasThrowable() && param.result != null) {
                    policy.capture(param.result)
                    policy.markDownload(operation)
                } else {
                    policy.finish(operation)
                }
            })
            hook(methods.getValue("helper.retry"), before = { param ->
                if (!policy.mayDownload()) block(param, "automatic retry", null)
            })
            hook(methods.getValue("helper.stop"), after = { param ->
                if (!param.hasThrowable()) policy.stopDownloads()
            })
            hook(methods.getValue("retry.pendingIntent"), before = { param ->
                val intent = param.args[2] as? Intent
                if (intent?.component?.className == UpdateHookTargets.RETRY_RECEIVER &&
                    intent.getIntExtra("action", -1) == 1
                ) {
                    val token = policy.currentOperation()?.let(policy::exportUpgrade)
                    if (token == null) intent.removeExtra(transferKey) else intent.putExtra(transferKey, token)
                }
            })
            hook(methods.getValue("retry.receive"), before = { param ->
                val intent = param.args[1] as? Intent
                if (intent?.getIntExtra("action", -1) == 1) {
                    val operation = policy.importedUpgrade(intent.getStringExtra(transferKey))
                    enter(param, operation)
                    if (operation == null) block(param, "unowned notification retry", null)
                }
            })

            hook(methods.getValue("notifications.sync"), before = { param ->
                enter(param, null)
                filterNotifications(param.args[0])
            })
            hook(methods.getValue("notifications.consume"), before = { param ->
                if (selector("notifications", param.thisObject) == 7) {
                    enter(param, null)
                    filterNotifications(param.args[0])
                }
            })
            hook(methods.getValue("notifications.dialog"), before = { param ->
                if (isAutomaticNotification(param.args[2])) block(param, "automatic notification dialog", null)
            })
            hook(methods.getValue("notifications.im"), before = { param ->
                if (isAutomaticNotification(param.thisObject)) block(param, "automatic notification", false)
            })
            enabled.set(true)
        }

        private fun captureConstructor(id: String, param: XC_MethodHook.MethodHookParam): Boolean {
            if (policy.currentOperation() == null) return false
            return when (id) {
                "request" -> param.args[0] == 22 && policy.mayCheck()
                "result.about" -> param.args[0] == 2 && param.args[1]?.javaClass?.name == UpdateHookTargets.ABOUT
                "cancel" -> param.args[0] == 1 && isModel(param.args[1])
                "install" -> param.args[1] == 26 && isModel(param.args[0])
                "background" -> param.args[0] in listOf(0, 1)
                else -> true
            }
        }

        private fun isModel(value: Any?): Boolean = value?.javaClass?.name == UpdateHookTargets.MODEL

        private fun selector(prefix: String, value: Any): Int = fields.getValue("$prefix.selector").getInt(value)

        private fun isAboutResult(value: Any): Boolean = selector("about", value) == 2 &&
            fields.getValue("about.owner").get(value)?.javaClass?.name == UpdateHookTargets.ABOUT

        private fun isAutomaticNotification(notification: Any?): Boolean = notification != null &&
            policy.isAutomaticUpgradeNotification(fields.getValue("notification.code").get(notification) as? String)

        private fun filterNotifications(response: Any?) {
            if (response == null) return
            val data = fields.getValue("response.data").get(response) ?: return
            val field = fields.getValue("response.notifications")
            val notifications = field.get(data) as? Set<*> ?: return
            val filtered = notifications.filterNotTo(linkedSetOf(), ::isAutomaticNotification)
            if (filtered.size != notifications.size) {
                field.set(data, filtered)
                logBlock("automatic notification payload")
            }
        }

        private fun enterBound(param: XC_MethodHook.MethodHookParam) =
            enter(param, policy.operationFor(param.thisObject))

        private fun enter(param: XC_MethodHook.MethodHookParam, operation: UpdateFlowPolicy.Operation?) {
            param.setObjectExtra(operationKey, operation)
            param.setObjectExtra(scopeKey, policy.enter(operation))
        }

        private fun operation(param: XC_MethodHook.MethodHookParam): UpdateFlowPolicy.Operation? =
            param.getObjectExtra(operationKey) as? UpdateFlowPolicy.Operation

        private fun finishCheck(param: XC_MethodHook.MethodHookParam) {
            operation(param)?.takeIf { it.kind == UpdateFlowPolicy.Kind.CHECK }?.let(policy::finish)
        }

        private fun block(param: XC_MethodHook.MethodHookParam, reason: String, result: Any?) {
            param.result = result
            logBlock(reason)
        }

        private fun logBlock(reason: String) {
            if (loggedBlocks.add(reason)) XposedBridge.log("FreeMOMO: update blocked -> $reason")
        }

        private fun hook(
            member: Member,
            before: (XC_MethodHook.MethodHookParam) -> Unit = {},
            after: (XC_MethodHook.MethodHookParam) -> Unit = {}
        ) {
            handles += XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled.get()) return
                    param.setObjectExtra(enteredKey, true)
                    try {
                        before(param)
                    } catch (error: Throwable) {
                        ThrowablePolicy.rethrowIfFatal(error)
                        Logger.error("update callback rejected: $member", error)
                        // All guarded boundaries return void/reference/boolean. A failed guard grants nothing.
                        param.result = if (member is Method && member.returnType == Boolean::class.javaPrimitiveType) false else null
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra(enteredKey) != true) return
                    try {
                        if (enabled.get()) after(param)
                    } catch (error: Throwable) {
                        ThrowablePolicy.rethrowIfFatal(error)
                        Logger.error("update callback completion failed: $member", error)
                    } finally {
                        (param.getObjectExtra(scopeKey) as? UpdateFlowPolicy.Scope)?.close()
                    }
                }
            })
        }

        fun rollback() {
            enabled.set(false)
            var fatal: Throwable? = null
            handles.asReversed().forEach { handle ->
                try {
                    handle.unhook()
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) {
                        if (fatal == null) fatal = error
                    } else {
                        Logger.error("update rollback failed", error)
                    }
                }
            }
            handles.clear()
            fatal?.let { throw it }
        }
    }
}
