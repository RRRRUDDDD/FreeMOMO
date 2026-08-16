package com.rud.freemomo

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.rud.freemomo.hook.CapabilityDiscovery
import com.rud.freemomo.hook.ClassDescriptor
import com.rud.freemomo.hook.DiscoveryStatus
import com.rud.freemomo.hook.HookDiscoveryPolicy
import com.rud.freemomo.hook.HookTargets
import com.rud.freemomo.hook.MethodSignature
import com.rud.freemomo.hook.SecNeoEarlyHook
import com.rud.freemomo.hook.StructuralDiscoveryResult
import com.rud.freemomo.hook.StructuralHookDiscovery
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
import java.lang.reflect.Modifier
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
                            doHook(context, classLoader)
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

    private fun doHook(context: Context, classLoader: ClassLoader) {
        val versionCode = context.packageManager
            .getPackageInfo(context.packageName, 0).longVersionCode.toInt()
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
        val cachedTargets = cached.targets
        val installedCache = installTargets(cachedTargets, classLoader, "cached:$versionCode")
        val current = cached.reconcileInstall(cachedTargets, installedCache)
        if (current != cached) {
            persist(context, versionCode, current)
            XposedBridge.log(
                "FreeMOMO: cached install failure returned capability to discovery -> " +
                    describeSnapshot(current)
            )
        }
        val snapshotState = AtomicReference(current)
        val installedState = AtomicReference(installedCache)

        if (current.hasPendingWordObservation) {
            showToast(context, SEARCHING_MESSAGE)
            observePendingWord(
                context,
                classLoader,
                versionCode,
                snapshotState,
                installedState,
                requireNotNull(current.wordLimit),
                "cached:$versionCode"
            )
        }
        if (!current.requiresStructuralScan) {
            XposedBridge.log(
                "FreeMOMO: structural scan skipped version=$versionCode -> " +
                    describeSnapshot(current)
            )
            if (installedState.get().presentCapabilityCount == 4) {
                showFoundIfNeeded(context, versionCode)
            }
            return
        }

        showToast(context, SEARCHING_MESSAGE)
        discoverUnknownVersion(
            context,
            classLoader,
            versionCode,
            snapshotState,
            installedState
        )
    }

    private fun discoverUnknownVersion(
        context: Context,
        classLoader: ClassLoader,
        versionCode: Int,
        snapshotState: AtomicReference<DiscoverySnapshot>,
        installedState: AtomicReference<HookTargets>
    ) {
        val startedAt = System.nanoTime()
        val enumerateStartedAt = System.nanoTime()
        val classNames = enumerateClassNames(classLoader)
        val enumerateMillis = elapsedMillis(enumerateStartedAt)
        val describeStartedAt = System.nanoTime()
        val descriptors = describeClasses(classLoader, classNames)
        val describeMillis = elapsedMillis(describeStartedAt)
        val methodCount = descriptors.sumOf { it.uniqueMethods.size }
        val discoveryStartedAt = System.nanoTime()
        val discovery = StructuralHookDiscovery.discover(descriptors)
        val discoveryMillis = elapsedMillis(discoveryStartedAt)
        XposedBridge.log(
            "FreeMOMO: structural scan version=$versionCode " +
                "dexClasses=${classNames.size} describedClasses=${descriptors.size} " +
                "methods=$methodCount enumerate=${enumerateMillis}ms " +
                "describe=${describeMillis}ms discover=${discoveryMillis}ms " +
                "elapsed=${elapsedMillis(startedAt)}ms"
        )

        val previous = snapshotState.get()
        logNewDiscoveries(previous, discovery)
        val merged = previous.fillUnknown(discovery)
        val newlyDiscoveredTargets = HookTargets(
            wordLimit = merged.targets.wordLimit.takeIf { previous.wordLimit == null },
            display = merged.targets.display.takeIf { previous.display == null },
            privilege = merged.targets.privilege.takeIf { previous.privilege == null },
            cloud = merged.targets.cloud.takeIf { previous.cloud == null }
        )
        val installedNow = installTargets(
            newlyDiscoveredTargets,
            classLoader,
            "discovered:$versionCode"
        )
        installedState.updateAndGet { installed -> mergeTargets(installed, installedNow) }
        val reconciled = merged.reconcileInstall(newlyDiscoveredTargets, installedNow)
        snapshotState.set(reconciled)
        persist(context, versionCode, reconciled)

        if (previous.wordLimit == null && reconciled.hasPendingWordObservation) {
            observePendingWord(
                context,
                classLoader,
                versionCode,
                snapshotState,
                installedState,
                requireNotNull(reconciled.wordLimit),
                "discovered:$versionCode"
            )
        }
        if (installedState.get().presentCapabilityCount == 4) {
            showFoundIfNeeded(context, versionCode)
        }
    }

    private fun observePendingWord(
        context: Context,
        classLoader: ClassLoader,
        versionCode: Int,
        snapshotState: AtomicReference<DiscoverySnapshot>,
        installedState: AtomicReference<HookTargets>,
        pending: WordLimitDiscovery,
        source: String
    ) {
        WordLimitHook().observe(
            pending,
            classLoader,
            onDiscovered = { discovered ->
                val target = requireNotNull(discovered.target)
                val updated = snapshotState.updateAndGet {
                    it.copy(wordLimit = discovered)
                }
                installedState.updateAndGet { it.copy(wordLimit = target) }
                persist(context, versionCode, updated)
                XposedBridge.log("FreeMOMO: word-limit discovered -> ${target.displayName}")
                if (installedState.get().presentCapabilityCount == 4) {
                    showFoundIfNeeded(context, versionCode)
                }
            },
            onAmbiguous = { ambiguous ->
                val updated = snapshotState.updateAndGet {
                    it.copy(wordLimit = ambiguous)
                }
                installedState.updateAndGet { it.copy(wordLimit = null) }
                persist(context, versionCode, updated)
                XposedBridge.log(
                    "FreeMOMO: word-limit ambiguous, disabled -> " +
                        ambiguous.conflicts.joinToString { it.displayName }
                )
            }
        ).also { installed ->
            if (!installed) {
                XposedBridge.log("FreeMOMO: word-limit $source observers unavailable")
            }
        }
    }

    private fun logNewDiscoveries(
        previous: DiscoverySnapshot,
        discovery: StructuralDiscoveryResult
    ) {
        if (previous.display == null) logDiscovery("display", discovery.display)
        if (previous.privilege == null) logDiscovery("privilege", discovery.privilege)
        if (previous.cloud == null) logDiscovery("cloud", discovery.cloud)
        if (previous.wordLimit == null) logWordDiscovery(discovery.wordLimit)
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

    private fun describeClasses(
        classLoader: ClassLoader,
        classNames: List<String>
    ): List<ClassDescriptor> = classNames.mapNotNull { className ->
        try {
            val cls = Class.forName(className, false, classLoader)
            val methods = cls.declaredMethods
                .asSequence()
                .filterNot { it.isSynthetic || it.isBridge }
                .map { method ->
                    MethodSignature(
                        className = cls.name,
                        methodName = method.name,
                        parameterTypes = method.parameterTypes.map { it.name },
                        returnType = method.returnType.name,
                        isStatic = Modifier.isStatic(method.modifiers)
                    )
                }
                .toList()
            ClassDescriptor(cls.name, methods)
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            if (BuildConfig.DEBUG) {
                Logger.error("structural class rejected: $className", error)
            }
            null
        }
    }

    // The hardened target exposes Dex elements only through this private runtime adapter.
    @SuppressLint("DiscouragedPrivateApi")
    @Suppress("DEPRECATION")
    private fun enumerateClassNames(classLoader: ClassLoader): List<String> {
        val result = linkedSetOf<String>()
        try {
            val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
            pathListField.isAccessible = true
            val dexPathList = pathListField.get(classLoader)
            val dexElementsField = dexPathList.javaClass.getDeclaredField("dexElements")
            dexElementsField.isAccessible = true
            val dexElements = dexElementsField.get(dexPathList) as Array<*>

            dexElements.filterNotNull().forEach { element ->
                val dexFileField = element.javaClass.getDeclaredField("dexFile")
                dexFileField.isAccessible = true
                val dexFile = dexFileField.get(element) as? dalvik.system.DexFile ?: return@forEach
                val entries = dexFile.entries()
                while (entries.hasMoreElements()) result += entries.nextElement()
            }
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            Logger.error("structural class enumeration failed", error)
            throw IllegalStateException("Structural class enumeration failed", error)
        }
        return result.toList()
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

    private fun persist(
        context: Context,
        versionCode: Int,
        snapshot: DiscoverySnapshot
    ) {
        HookCache.saveSnapshot(context, snapshot, versionCode)
    }

    private fun mergeTargets(current: HookTargets, added: HookTargets): HookTargets = HookTargets(
        wordLimit = current.wordLimit ?: added.wordLimit,
        display = current.display ?: added.display,
        privilege = current.privilege ?: added.privilege,
        cloud = current.cloud ?: added.cloud
    )

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
