package com.rud.freemomo

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.rud.freemomo.hook.CapabilityDiscovery
import com.rud.freemomo.hook.ClassInventory
import com.rud.freemomo.hook.DexInventoryReader
import com.rud.freemomo.hook.DiscoveryCoordinator
import com.rud.freemomo.hook.DiscoveryRetryPolicy
import com.rud.freemomo.hook.DiscoveryScanner
import com.rud.freemomo.hook.DiscoveryStatus
import com.rud.freemomo.hook.DiscoveryTrigger
import com.rud.freemomo.hook.HookCapability
import com.rud.freemomo.hook.HookDiscoveryPolicy
import com.rud.freemomo.hook.HookSignatures
import com.rud.freemomo.hook.HookTargets
import com.rud.freemomo.hook.SecNeoEarlyHook
import com.rud.freemomo.hook.UpdateHook
import com.rud.freemomo.hook.UserLevelHook
import com.rud.freemomo.hook.WordLimitDiscovery
import com.rud.freemomo.hook.WordLimitHook
import com.rud.freemomo.util.DiscoverySnapshot
import com.rud.freemomo.util.HookCache
import com.rud.freemomo.util.HookNotificationState
import com.rud.freemomo.util.Logger
import com.rud.freemomo.util.ThrowablePolicy
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Xposed entry point for exact mappings and fail-closed structural Java discovery. */
class MomoHookEntry : IXposedHookLoadPackage {

    private enum class InstallState {
        IDLE,
        INSTALLING,
        INSTALLED,
        FAILED
    }

    companion object {
        private const val TARGET_PACKAGE = "com.maimemo.android.momo"
        private const val SEARCHING_MESSAGE = "FreeMOMO 正在寻找 Hook 函数..."
        private const val FOUND_MESSAGE = "FreeMOMO 已找到 Hook 函数"
        private val installState = AtomicReference(InstallState.IDLE)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE || lpparam.processName != TARGET_PACKAGE) return

        try {
            // Must stay independent from Java discovery. Native fingerprint mismatch is fail-closed.
            SecNeoEarlyHook().apply(lpparam.classLoader)

            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context
                        if (context == null) {
                            XposedBridge.log("FreeMOMO: Application.attach context unavailable")
                            return
                        }
                        val classLoader = context.classLoader
                        if (classLoader == null) {
                            XposedBridge.log("FreeMOMO: Application.attach classLoader unavailable")
                            return
                        }
                        if (!beginInstall()) return

                        try {
                            doHook(context, classLoader, param.thisObject as? Application)
                            installState.set(InstallState.INSTALLED)
                        } catch (error: Throwable) {
                            installState.set(InstallState.FAILED)
                            ThrowablePolicy.rethrowIfFatal(error)
                            Logger.error("top-level Hook installation failed", error)
                        }
                    }
                }
            )
        } catch (error: Throwable) {
            installState.set(InstallState.FAILED)
            ThrowablePolicy.rethrowIfFatal(error)
            Logger.error("Xposed entry setup failed", error)
        }
    }

    private fun beginInstall(): Boolean {
        while (true) {
            when (val current = installState.get()) {
                InstallState.INSTALLING,
                InstallState.INSTALLED -> return false
                InstallState.IDLE,
                InstallState.FAILED -> if (
                    installState.compareAndSet(current, InstallState.INSTALLING)
                ) {
                    return true
                }
            }
        }
    }

    private fun doHook(context: Context, classLoader: ClassLoader, application: Application?) {
        val versionCode = context.packageManager
            .getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        try {
            val installed = UpdateHook.apply(classLoader, versionCode)
            XposedBridge.log("FreeMOMO: update:$versionCode install result -> $installed")
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            Logger.error("update:$versionCode independent install failed", error)
        }
        val exact = HookDiscoveryPolicy.exactTargets(versionCode)
        if (exact != null) {
            // Exact versions never consume structural cache, including legacy range-scan entries.
            HookCache.clear(context)
            val installed = installTargets(exact, classLoader, "exact:$versionCode")
            if (installed != exact) {
                XposedBridge.log(
                    "FreeMOMO: exact:$versionCode rejected incomplete install -> " +
                        describeCapabilities(installed)
                )
            } else {
                showFoundIfNeeded(context, versionCode)
            }
            return
        }

        val cached = HookCache.loadSnapshot(context, versionCode, classLoader)
            ?: DiscoverySnapshot()
        val scanner = DiscoveryScanner { className -> HookSignatures.describe(className, classLoader) }
        // Probe prior failures before considering another full reflection pass.
        val readableFailures = scanner.probeFailed(cached)
        val inventoryStartedAt = System.nanoTime()
        val inventory = DexInventoryReader.read(classLoader)
        val inventoryMillis = elapsedMillis(inventoryStartedAt)
        val initial = cached.forInventory(inventory.fingerprint)
        val coordinator = DiscoveryCoordinator(
            initial = initial,
            installTargets = { installTargets(it, classLoader, "structural:$versionCode") },
            installWordObservers = { pending, group ->
                WordLimitHook().observe(pending, classLoader, group)
            },
            persist = { HookCache.saveSnapshot(context, it, versionCode) },
            onChange = { before, after ->
                if (before?.snapshot?.wordLimit != after.snapshot.wordLimit) {
                    after.snapshot.wordLimit?.let(::logWordDiscovery)
                }
                XposedBridge.log(
                    "FreeMOMO: structural commit version=$versionCode -> " +
                        describeSnapshot(after.snapshot) + " installed=" +
                        describeCapabilities(after.installed)
                )
                if (after.installed.presentCapabilityCount == 4) {
                    showFoundIfNeeded(context, versionCode)
                }
            },
            onError = { Logger.error("structural installation/observer failure", it) }
        )
        val retryPolicy = DiscoveryRetryPolicy()
        val current = coordinator.start().snapshot
        if (current.requiresStructuralScan || current.hasPendingWordObservation) {
            showToast(context, SEARCHING_MESSAGE)
        }
        discoverUnknownVersion(
            classLoader, versionCode, coordinator, scanner, retryPolicy,
            DiscoveryTrigger.ATTACH, inventory, readableFailures, inventoryMillis
        )
        if (coordinator.state().snapshot.requiresStructuralScan && application != null) {
            scheduleDiscoveryRetries(application) { trigger ->
                discoverUnknownVersion(classLoader, versionCode, coordinator, scanner, retryPolicy, trigger)
            }
        }
    }

    private fun discoverUnknownVersion(
        classLoader: ClassLoader,
        versionCode: Int,
        coordinator: DiscoveryCoordinator,
        scanner: DiscoveryScanner,
        retryPolicy: DiscoveryRetryPolicy,
        trigger: DiscoveryTrigger,
        initialInventory: ClassInventory? = null,
        initialReadableFailures: Set<String>? = null,
        initialInventoryMillis: Long = 0
    ) {
        val startedAt = System.nanoTime()
        val snapshot = coordinator.state().snapshot
        val readable = initialReadableFailures ?: scanner.probeFailed(snapshot)
        val inventoryStartedAt = System.nanoTime()
        val inventory = initialInventory ?: DexInventoryReader.read(classLoader)
        val inventoryMillis = if (initialInventory == null) elapsedMillis(inventoryStartedAt)
            else initialInventoryMillis
        val capabilities = retryPolicy.select(trigger, snapshot, readable, inventory)
        if (capabilities.isEmpty()) {
            XposedBridge.log(
                "FreeMOMO: structural scan skipped version=$versionCode trigger=$trigger -> " +
                    describeSnapshot(snapshot)
            )
            return
        }
        val scanStartedAt = System.nanoTime()
        val scan = scanner.scan(capabilities, inventory)
        val scanMillis = elapsedMillis(scanStartedAt)
        scan.metadata.forEach { (capability, metadata) ->
            if (!metadata.complete) {
                XposedBridge.log(
                    "FreeMOMO: ${capability.cacheKey} scan incomplete retry=${metadata.retryReason} " +
                        "failedClasses=${metadata.failedClasses.size}"
                )
            } else {
                when (capability) {
                    HookCapability.WORD_LIMIT -> logWordDiscovery(scan.result.wordLimit)
                    HookCapability.DISPLAY -> logDiscovery("display", scan.result.display)
                    HookCapability.PRIVILEGE -> logDiscovery("privilege", scan.result.privilege)
                    HookCapability.CLOUD -> logDiscovery("cloud", scan.result.cloud)
                }
            }
        }
        coordinator.publish(scan)
        XposedBridge.log(
            "FreeMOMO: structural scan version=$versionCode trigger=$trigger " +
                "capabilities=${capabilities.joinToString { it.cacheKey }} " +
                "dexClasses=${inventory.classNames.size} describedClasses=${scan.describedClassCount} " +
                "methods=${scan.describedMethodCount} enumerate=${inventoryMillis}ms " +
                "describe/discover=${scanMillis}ms " +
                "elapsed=${elapsedMillis(startedAt) + initialInventoryMillis}ms"
        )
    }

    private fun scheduleDiscoveryRetries(
        application: Application,
        retry: (DiscoveryTrigger) -> Unit
    ) {
        val resumed = AtomicBoolean(false)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!resumed.compareAndSet(false, true)) return
                application.unregisterActivityLifecycleCallbacks(this)
                Handler(Looper.getMainLooper()).postDelayed({
                    runDiscoveryRetry { retry(DiscoveryTrigger.AFTER_RESUME_DELAY) }
                }, 1_000L)
                runDiscoveryRetry { retry(DiscoveryTrigger.FIRST_RESUME) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun runDiscoveryRetry(retry: () -> Unit) {
        try {
            retry()
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            Logger.error("structural lifecycle retry failed", error)
        }
    }

    private fun installTargets(
        targets: HookTargets,
        classLoader: ClassLoader,
        source: String
    ): HookTargets {
        if (targets.isEmpty()) return targets
        val userLevelHook = UserLevelHook()
        val word = targets.wordLimit?.takeIf {
            WordLimitHook().apply(it, classLoader, source)
        }
        val display = targets.display?.takeIf {
            userLevelHook.applyDisplay(it, classLoader, source)
        }
        val privilege = targets.privilege?.takeIf {
            userLevelHook.applyPrivilege(it, classLoader, source)
        }
        val cloud = targets.cloud?.takeIf {
            userLevelHook.applyCloud(it, classLoader, source)
        }
        val installed = HookTargets(word, display, privilege, cloud)
        XposedBridge.log(
            "FreeMOMO: $source install result -> ${describeCapabilities(installed)}"
        )
        return installed
    }

    private fun <T> logDiscovery(label: String, result: CapabilityDiscovery<T>) {
        val detail = when (result.status) {
            DiscoveryStatus.DISCOVERED -> result.candidates.joinToString { it.displayName }
            DiscoveryStatus.AMBIGUOUS -> result.conflicts.joinToString { it.displayName }
            DiscoveryStatus.REJECTED -> result.rejectedCandidates.joinToString { it.displayName }
            else -> ""
        }
        XposedBridge.log(
            "FreeMOMO: $label ${result.status.name.lowercase()}" +
                detail.takeIf { it.isNotEmpty() }?.let { " -> $it" }.orEmpty()
        )
    }

    private fun logWordDiscovery(result: WordLimitDiscovery) {
        val detail = when (result.status) {
            DiscoveryStatus.PENDING -> result.candidates
            DiscoveryStatus.AMBIGUOUS -> result.conflicts
            DiscoveryStatus.REJECTED -> result.rejectedCandidates
            DiscoveryStatus.DISCOVERED -> listOfNotNull(result.target)
            DiscoveryStatus.MISSING -> emptyList()
        }
        XposedBridge.log(
            "FreeMOMO: word-limit ${result.status.name.lowercase()}" +
                detail.takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = " -> ") { it.displayName }
                    .orEmpty()
        )
    }

    private fun describeCapabilities(targets: HookTargets): String = listOf(
        "word=${targets.wordLimit != null}",
        "display=${targets.display != null}",
        "privilege=${targets.privilege != null}",
        "cloud=${targets.cloud != null}"
    ).joinToString()

    private fun describeSnapshot(snapshot: DiscoverySnapshot): String = listOf(
        "word=${snapshot.wordLimit?.status?.name ?: "unknown"}",
        "display=${snapshot.display?.status?.name ?: "unknown"}",
        "privilege=${snapshot.privilege?.status?.name ?: "unknown"}",
        "cloud=${snapshot.cloud?.status?.name ?: "unknown"}"
    ).joinToString()

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFoundIfNeeded(context: Context, versionCode: Int) {
        if (!HookNotificationState.claimFoundNotification(context, versionCode)) return
        showToast(context, FOUND_MESSAGE)
    }
}
