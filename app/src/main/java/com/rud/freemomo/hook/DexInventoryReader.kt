package com.rud.freemomo.hook

import android.annotation.SuppressLint
import com.rud.freemomo.util.Logger
import com.rud.freemomo.util.ThrowablePolicy
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** Enumerates identities without reflecting every class; incomplete enumeration stays explicit. */
object DexInventoryReader {
    @Volatile private var memo: Pair<List<String>, ClassInventory>? = null

    /**
     * Reuses the last complete inventory while the cheap per-element identities (loader, dex
     * path, size, mtime) are unchanged. Any Dex insertion, removal or reorder changes the key;
     * the only weakened case is an in-place dex edit that preserves size and mtime within one
     * process. A failed probe or an incomplete prior enumeration always re-enumerates fully.
     */
    fun read(classLoader: ClassLoader): ClassInventory {
        val (identities, probeComplete) = cheapIdentities(classLoader)
        val (inventory, entry) = resolveInventory(identities, probeComplete, memo) {
            enumerate(classLoader)
        }
        memo = entry
        return inventory
    }

    /** Pure memo decision so reuse rules stay testable without dalvik classes. */
    internal fun resolveInventory(
        identities: List<String>,
        probeComplete: Boolean,
        cached: Pair<List<String>, ClassInventory>?,
        enumerate: () -> ClassInventory
    ): Pair<ClassInventory, Pair<List<String>, ClassInventory>> {
        val hit = cached?.takeIf { (key, value) ->
            probeComplete && key == identities && value.complete
        }
        if (hit != null) return hit.second to hit
        val fresh = enumerate()
        return fresh to (identities to fresh)
    }

    /** Cheap identity probe; never calls entries(), so a memo hit cannot return a stale class list. */
    @SuppressLint("DiscouragedPrivateApi")
    @Suppress("DEPRECATION")
    private fun cheapIdentities(classLoader: ClassLoader): Pair<List<String>, Boolean> {
        val identities = mutableListOf<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<dalvik.system.DexFile, Boolean>())
        var complete = true
        fun failed(error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            complete = false
        }
        try {
            val baseClass = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListField = baseClass.getDeclaredField("pathList").apply { isAccessible = true }
            var currentLoader: ClassLoader? = classLoader
            while (currentLoader != null) {
                val loader = currentLoader
                currentLoader = loader.parent
                if (!baseClass.isInstance(loader)) continue
                try {
                    val pathList = pathListField.get(loader)
                    val elementsField = pathList.javaClass.getDeclaredField("dexElements")
                        .apply { isAccessible = true }
                    val elements = elementsField.get(pathList) as Array<*>
                    if (elements.any { it == null }) complete = false
                    elements.filterNotNull().forEach { element ->
                        try {
                            val dexField = element.javaClass.getDeclaredField("dexFile")
                                .apply { isAccessible = true }
                            val dex = dexField.get(element) as? dalvik.system.DexFile
                                ?: return@forEach
                            if (!seen.add(dex)) return@forEach
                            val dexName = dex.name.orEmpty()
                            val file = File(dexName)
                            identities += "${loader.javaClass.name}:$dexName:${file.length()}:${file.lastModified()}"
                        } catch (error: Throwable) {
                            failed(error)
                        }
                    }
                } catch (error: Throwable) {
                    failed(error)
                }
            }
        } catch (error: Throwable) {
            failed(error)
        }
        if (seen.isEmpty()) complete = false
        return identities to complete
    }

    @SuppressLint("DiscouragedPrivateApi")
    @Suppress("DEPRECATION")
    private fun enumerate(classLoader: ClassLoader): ClassInventory {
        val names = linkedSetOf<String>()
        val identities = mutableListOf<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<dalvik.system.DexFile, Boolean>())
        var complete = true
        fun failed(error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            complete = false
            Logger.error("structural Dex inventory incomplete", error)
        }
        try {
            val baseClass = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListField = baseClass.getDeclaredField("pathList").apply { isAccessible = true }
            var currentLoader: ClassLoader? = classLoader
            while (currentLoader != null) {
                val loader = currentLoader
                currentLoader = loader.parent
                if (!baseClass.isInstance(loader)) continue
                try {
                    val pathList = pathListField.get(loader)
                    val elementsField = pathList.javaClass.getDeclaredField("dexElements")
                        .apply { isAccessible = true }
                    val elements = elementsField.get(pathList) as Array<*>
                    if (elements.any { it == null }) complete = false
                    elements.filterNotNull().forEach { element ->
                        try {
                            val dexField = element.javaClass.getDeclaredField("dexFile")
                                .apply { isAccessible = true }
                            val dex = dexField.get(element) as? dalvik.system.DexFile
                                ?: return@forEach
                            if (!seen.add(dex)) return@forEach
                            val dexName = dex.name.orEmpty()
                            val file = File(dexName)
                            val identity = "${loader.javaClass.name}:$dexName:${file.length()}:${file.lastModified()}"
                            identities += identity
                            val dexNames = linkedSetOf<String>()
                            val entries = dex.entries()
                            while (entries.hasMoreElements()) {
                                val name = entries.nextElement()
                                names += name
                                dexNames += name
                            }
                            // Per-element identities also detect reordered anonymous Dex elements.
                            identities += fingerprint(dexNames.sorted())
                        } catch (error: Throwable) {
                            failed(error)
                        }
                    }
                } catch (error: Throwable) {
                    failed(error)
                }
            }
        } catch (error: Throwable) {
            failed(error)
        }
        if (seen.isEmpty()) complete = false
        return ClassInventory(names.toList(), fingerprint(identities + names.sorted() + "complete=$complete"), complete)
    }

    private fun fingerprint(values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
