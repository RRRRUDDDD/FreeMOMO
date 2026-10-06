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
import java.util.concurrent.atomic.AtomicLong

/** Independently installed, version-specific update policy. Business hooks never depend on it. */
object UpdateHook {
    private val nextAdapterId = AtomicLong()
    private val installations = java.util.IdentityHashMap<ClassLoader, Installation>()

    @Synchronized
    fun apply(classLoader: ClassLoader, versionCode: Int, trigger: DiscoveryTrigger): UpdateInstallState.Status =
        installations.getOrPut(classLoader) { Installation(classLoader, versionCode) }.attempt(trigger)

    @Synchronized
    fun needsRetry(classLoader: ClassLoader): Boolean = installations[classLoader]?.state?.needsRetry == true

    private class MemberFailure(val id: String, cause: Throwable) : RuntimeException(cause)

    private class Installation(private val loader: ClassLoader, private val version: Int) {
        private val profile = UpdateHookTargets.forVersion(version)
        val state = UpdateInstallState(UpdateHookTargets.guardMethodIds, profile != null)
        private val loggedBlocks = ConcurrentHashMap.newKeySet<String>()

        fun attempt(trigger: DiscoveryTrigger): UpdateInstallState.Status {
            val result = state.attempt(trigger, installGuard = { id ->
                val signature = requireNotNull(profile).methods.getValue(id)
                val method = HookSignatures.requireMethod(signature, loader)
                XposedBridge.hookMethod(method, object : XC_MethodHook(PRIORITY_HIGHEST) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (state.manualReady) return
                        // Also deny manual upgrades until their entire authorization chain is installed.
                        param.result = null
                        if (loggedBlocks.add(id)) {
                            XposedBridge.log("FreeMOMO: update:$version guarded -> $id (manual adapter unavailable)")
                        }
                    }
                })
            }, installManual = {
                val adapter = Adapter(loader)
                try {
                    adapter.resolve(requireNotNull(profile))
                    adapter.install()
                } catch (error: Throwable) {
                    adapter.rollback()
                    throw error
                }
            }, onFailure = { stage, error ->
                val member = (error as? MemberFailure)?.id ?: stage
                val cause = (error as? MemberFailure)?.cause ?: error
                // Never log exception messages or host arguments.
                XposedBridge.log("FreeMOMO: update:$version $trigger failed -> $member:${cause.javaClass.simpleName}")
            })
            XposedBridge.log("FreeMOMO: update:$version $trigger status -> $result")
            return result
        }
    }

    private class Adapter(private val loader: ClassLoader) {
        private val policy = UpdateFlowPolicy()
        private val methods = linkedMapOf<String, Method>()
        private val constructors = linkedMapOf<String, Constructor<*>>()
        private val fields = linkedMapOf<String, Field>()
        private val group = UpdateHookGroup {
            XposedBridge.log("FreeMOMO: update rollback failed -> ${it.javaClass.simpleName}")
        }
        private val loggedBlocks = ConcurrentHashMap.newKeySet<String>()
        // A failed unhook from a previous attempt must never see another adapter's scope extras.
        private val keyPrefix = "freemomo.update.${nextAdapterId.incrementAndGet()}"
        private val scopeKey = "$keyPrefix.scope"
        private val operationKey = "$keyPrefix.operation"
        private val enteredKey = "$keyPrefix.entered"
        private val transferKey = "com.rud.freemomo.manual_upgrade_operation"

        fun resolve(profile: UpdateHookTargets.Profile) {
            check(profile.validate(method = { id, signature ->
                resolveMember(id) { methods[id] = HookSignatures.requireMethod(signature, loader) }
                true
            }, constructor = { id, signature ->
                resolveMember("constructor:$id") {
                    val cls = Class.forName(signature.className, false, loader)
                    val parameters = signature.parameterTypes.map { HookSignatures.resolveType(it, loader) }
                    constructors[id] = cls.getDeclaredConstructor(*parameters.toTypedArray()).apply {
                        isAccessible = true
                    }
                }
                true
            }, field = { id, signature ->
                resolveMember("field:$id") {
                    val field = Class.forName(signature.className, false, loader).getDeclaredField(signature.name)
                    require(field.type.name == signature.type && Modifier.isStatic(field.modifiers) == signature.isStatic)
                    field.isAccessible = true
                    fields[id] = field
                }
                true
            }))
            for ((id, expected) in mapOf("notification.upgrade" to "app_upgrade",
                "notification.dialog" to "app_upgrade_dialog")) {
                resolveMember("constant:$id") { require(fields.getValue(id).get(null) == expected) }
            }
        }

        private fun resolveMember(id: String, resolve: () -> Unit) {
            try {
                resolve()
            } catch (error: Throwable) {
                ThrowablePolicy.rethrowIfFatal(error)
                throw MemberFailure(id, error)
            }
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
            group.activate()
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
            resolveMember("hook:${member.declaringClass.name}.${member.name}") {
                val handle = XposedBridge.hookMethod(member, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!group.active) return
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
                            if (group.active) after(param)
                        } catch (error: Throwable) {
                            ThrowablePolicy.rethrowIfFatal(error)
                            Logger.error("update callback completion failed: $member", error)
                        } finally {
                            (param.getObjectExtra(scopeKey) as? UpdateFlowPolicy.Scope)?.close()
                        }
                    }
                })
                group.add(HookUnhook { handle.unhook() })
            }
        }

        fun rollback() = group.rollback()
    }
}
