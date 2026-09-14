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
    @SuppressLint("DiscouragedPrivateApi")
    @Suppress("DEPRECATION")
    fun read(classLoader: ClassLoader): ClassInventory {
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
