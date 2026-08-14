package com.rud.freemomo

import android.app.Application
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
import com.rud.freemomo.hook.StructuralHookDiscovery
import com.rud.freemomo.hook.UserLevelHook
import com.rud.freemomo.hook.WordLimitDiscovery
import com.rud.freemomo.hook.WordLimitHook
import com.rud.freemomo.util.HookCache
import com.rud.freemomo.util.Logger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Xposed entry point for exact mappings and fail-closed structural Java discovery. */
class MomoHookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TARGET_PACKAGE = "com.maimemo.android.momo"
        private const val SEARCHING_MESSAGE = "FreeMOMO 正在寻找 Hook 函数..."
        private const val FOUND_MESSAGE = "FreeMOMO 已找到 Hook 函数"
        private val applicationHookStarted = AtomicBoolean(false)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE || lpparam.processName != TARGET_PACKAGE) return

        // Must stay independent from Java discovery. Native fingerprint mismatch remains fail-closed.
        SecNeoEarlyHook().apply(lpparam.classLoader)

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!applicationHookStarted.compareAndSet(false, true)) return
                    val context = param.args[0] as Context
                    val classLoader = context.classLoader ?: return
                    doHook(context, classLoader)
                }
            }
        )
    }

    private fun doHook(context: Context, classLoader: ClassLoader) {
        val versionCode = context.packageManager
            .getPackageInfo(context.packageName, 0).versionCode
        val exact = HookDiscoveryPolicy.exactTargets(versionCode)
        if (exact != null) {
            // Exact versions never consume structural cache, including legacy range-scan entries.
            HookCache.clear(context)
            val installed = installTargets(exact, classLoader, "exact:$versionCode")
            if (installed != exact) {
                XposedBridge.log(
                    "FreeMOMO: exact:$versionCode rejected incomplete install -> " +
                        "${describeCapabilities(installed)}"
                )
            } else {
                showToast(context, FOUND_MESSAGE)
            }
            return
        }

        val cached = HookCache.load(context, versionCode, classLoader) ?: HookTargets()
        val installedCache = installTargets(cached, classLoader, "cached:$versionCode")
        val cacheState = AtomicReference(installedCache)
        if (cached != installedCache) persist(context, versionCode, installedCache)
        if (installedCache.presentCapabilityCount == 4) {
            showToast(context, FOUND_MESSAGE)
            return
        }

        showToast(context, SEARCHING_MESSAGE)
        discoverUnknownVersion(context, classLoader, versionCode, cacheState)
    }

    private fun discoverUnknownVersion(
        context: Context,
        classLoader: ClassLoader,
        versionCode: Int,
        cacheState: AtomicReference<HookTargets>
    ) {
        val startTime = System.currentTimeMillis()
        val classNames = enumerateClassNames(classLoader)
        val descriptors = describeClasses(classLoader, classNames)
        XposedBridge.log(
            "FreeMOMO: structural scan version=$versionCode classes=${descriptors.size} " +
                "elapsed=${System.currentTimeMillis() - startTime}ms"
        )
        val discovery = StructuralHookDiscovery.discover(descriptors)
        val current = cacheState.get()

        var installed = current
        if (current.display == null) {
            logDiscovery("display", discovery.display)
            discovery.display.target?.takeIf {
                discovery.display.status == DiscoveryStatus.DISCOVERED
            }?.let { target ->
                if (UserLevelHook().applyDisplay(target, classLoader, "discovered:$versionCode")) {
                    installed = installed.copy(display = target)
                }
            }
        }
        if (current.privilege == null) {
            logDiscovery("privilege", discovery.privilege)
            discovery.privilege.target?.takeIf {
                discovery.privilege.status == DiscoveryStatus.DISCOVERED
            }?.let { target ->
                if (UserLevelHook().applyPrivilege(
                        target,
                        classLoader,
                        "discovered:$versionCode"
                    )
                ) {
                    installed = installed.copy(privilege = target)
                }
            }
        }
        if (current.cloud == null) {
            logDiscovery("cloud", discovery.cloud)
            discovery.cloud.target?.takeIf {
                discovery.cloud.status == DiscoveryStatus.DISCOVERED
            }?.let { target ->
                if (UserLevelHook().applyCloud(target, classLoader, "discovered:$versionCode")) {
                    installed = installed.copy(cloud = target)
                }
            }
        }

        cacheState.set(installed)
        persist(context, versionCode, installed)
        if (installed.presentCapabilityCount == 4) {
            showToast(context, FOUND_MESSAGE)
        }

        if (current.wordLimit == null) {
            logWordDiscovery(discovery.wordLimit)
            if (discovery.wordLimit.status == DiscoveryStatus.PENDING) {
                WordLimitHook().observe(
                    discovery.wordLimit,
                    classLoader,
                    onDiscovered = { target ->
                        val updated = cacheState.updateAndGet { it.copy(wordLimit = target) }
                        persist(context, versionCode, updated)
                        XposedBridge.log(
                            "FreeMOMO: word-limit discovered -> ${target.displayName}"
                        )
                        if (updated.presentCapabilityCount == 4) {
                            showToast(context, FOUND_MESSAGE)
                        }
                    },
                    onAmbiguous = { conflicts ->
                        cacheState.updateAndGet { it.copy(wordLimit = null) }
                        // Do not keep a partial positive cache after a conflict; the next launch
                        // must repeat the full read-only scan instead of bypassing this evidence.
                        HookCache.clear(context)
                        XposedBridge.log(
                            "FreeMOMO: word-limit ambiguous, disabled -> " +
                                conflicts.joinToString { it.displayName }
                        )
                    }
                )
            }
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

    private fun describeClasses(
        classLoader: ClassLoader,
        classNames: List<String>
    ): List<ClassDescriptor> = classNames.distinct().mapNotNull { className ->
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
                .distinct()
                .toList()
            ClassDescriptor(cls.name, methods)
        } catch (error: Throwable) {
            if (BuildConfig.DEBUG) {
                Logger.error("structural class rejected: $className", error)
            }
            null
        }
    }

    private fun enumerateClassNames(classLoader: ClassLoader): List<String> {
        val result = mutableListOf<String>()
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
            Logger.error("structural class enumeration failed", error)
        }
        return result.distinct()
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

    private fun persist(context: Context, versionCode: Int, targets: HookTargets) {
        if (targets.isEmpty()) {
            HookCache.clear(context)
        } else {
            HookCache.save(context, targets, versionCode)
        }
    }

    private fun describeCapabilities(targets: HookTargets): String = listOf(
        "word=${targets.wordLimit != null}",
        "display=${targets.display != null}",
        "privilege=${targets.privilege != null}",
        "cloud=${targets.cloud != null}"
    ).joinToString()

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
