package com.rud.freemomo

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern (libxposed API 101) entry, declared in META-INF/xposed/java_init.list.
 * Modern-aware frameworks load this instead of assets/xposed_init, and honor
 * staticScope=true so the manager locks the scope to META-INF/xposed/scope.list.
 * Legacy frameworks (LSPatch, pre-modern LSPosed) still use MomoHookEntry via
 * assets/xposed_init and never load this class.
 */
class ModernHookEntry : XposedModule() {

    // PackageLoadedParam carries no process name; capture it once at module load.
    private var processName: String? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        super.onModuleLoaded(param)
        processName = param.processName
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != MomoHookEntry.TARGET_PACKAGE) return
        val process = processName ?: return
        try {
            MomoHookEntry().handlePackage(param.packageName, process, param.classLoader)
        } catch (error: Throwable) {
            log(Log.ERROR, "FreeMOMO", "modern entry dispatch failed", error)
        }
    }
}
