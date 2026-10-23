package com.rud.freemomo.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.rud.freemomo.util.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/**
 * Temporary diagnostic probe: enumerates host-app networking structures at runtime and
 * logs every request flowing through the KMP SDK HTTP transport. Enabled only by the
 * fmApiProbe gradle property; results land in filesDir for offline analysis. Never shipped on.
 */
object ApiProbe {

    private const val OUTPUT_FILE = "fm-api-probe.txt"
    private const val SHAPE_FILE = "fm-shape-probe.txt"
    private const val NET_FILE = "fm-net-log.txt"
    private const val NETWORK_PACKAGE = "com.maimemo.momocmpcomponents.shared.network"
    private const val SYNC_PACKAGE = "com.maimemo.android.momo.sync"
    private const val MAIN_NETWORK_PACKAGE = "com.maimemo.android.momo.network"
    private const val SIGN_MODEL_PACKAGE = "com.maimemo.android.momo.model.sign"
    private const val TRANSPORT_INTERFACE = "com.maimemo.momocmpcomponents.shared.network.SdkHttpTransport"
    private const val KTOR_TRANSPORT = "com.maimemo.momocmpcomponents.shared.network.KtorSdkHttpTransport"

    private val interestingFieldKeywords = listOf(
        "sign", "daka", "checkin", "check_in",
        "studied_word", "word_count", "wordcount", "task_word",
        "learned_word", "word_num", "study_words", "daily_words"
    )
    private val bodyKeywords = listOf(
        "sign", "daka", "check_in", "studied", "word_count", "voc_count",
        "study_time", "learned", "ssr_"
    )
    @Volatile private var jsonLogCount = 0

    @Volatile private var filesDir: File? = null
    private val netLogLock = Any()

    fun launch(context: Context, classLoader: ClassLoader) {
        filesDir = context.filesDir
        Thread {
            val startedAt = System.currentTimeMillis()
            val transports = mutableListOf<Class<*>>()
            try {
                val report = scan(classLoader, transports)
                File(context.filesDir, OUTPUT_FILE).writeText(report)
            } catch (error: Throwable) {
                Logger.error("api probe failed", error)
            }
            try {
                installTransportLog(classLoader, transports)
            } catch (error: Throwable) {
                logLine("transport log setup failed: $error")
            }
            try {
                installJsonLog(classLoader)
            } catch (error: Throwable) {
                logLine("json log setup failed: $error")
            }
            notify(context, "FreeMOMO 探针完成 (${elapsed(startedAt)})")
        }.start()
    }

    private fun elapsed(startedAt: Long): String {
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        return "${seconds}s"
    }

    private fun notify(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- network log

    /** Hooks every concrete SdkHttpTransport so request contents land in the net log file. */
    private fun installTransportLog(classLoader: ClassLoader, discovered: List<Class<*>>) {
        val hooked = linkedSetOf<String>()
        fun hookTransport(clazz: Class<*>) {
            if (!hooked.add(clazz.name)) return
            val execute = clazz.declaredMethods.firstOrNull { it.name == "execute" } ?: return
            XposedBridge.hookMethod(execute, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val request = param.args.getOrNull(0) ?: return
                    logNetRequest(request)
                }
            })
            logLine("transport log installed on ${clazz.name}.execute")
        }
        try {
            val iface = Class.forName(TRANSPORT_INTERFACE, false, classLoader)
            hookTransport(iface.takeIf { !it.isInterface } ?: Class.forName(KTOR_TRANSPORT, false, classLoader))
        } catch (error: Throwable) {
            logLine("transport interface hook failed: $error")
        }
        discovered.forEach { clazz ->
            try {
                hookTransport(clazz)
            } catch (error: Throwable) {
                logLine("transport hook failed for ${clazz.name}: $error")
            }
        }
    }

    private fun logNetRequest(request: Any) {
        val text = describeRequestFields(request)
        synchronized(netLogLock) {
            appendNetLog("REQ ${request.javaClass.name}\n    $text")
        }
    }

    private fun appendNetLog(line: String) {
        val dir = filesDir ?: return
        try {
            File(dir, NET_FILE).appendText(line + "\n")
        } catch (error: Throwable) {
            logLine("net log append failed: $error")
        }
    }

    /** Reflective one-level field dump tuned for data-class shaped request objects. */
    private fun describeRequestFields(request: Any): String {
        val parts = mutableListOf<String>()
        var clazz: Class<*>? = request.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    parts += "${field.name}=" + describeValue(field.get(request))
                } catch (error: Throwable) {
                    parts += "${field.name}=<err ${error.javaClass.simpleName}>"
                }
            }
            clazz = clazz.superclass
        }
        return parts.joinToString(", ").take(8000)
    }

    private fun describeValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${value.take(4000)}\""
        is Boolean, is Int, is Long, is Short, is Byte, is Char, is Float, is Double -> value.toString()
        is ByteArray -> "bytes[${value.size}]:\"" + String(value, Charsets.UTF_8).take(4000) + "\""
        is Collection<*> -> "list[${value.size}]{" + value.take(8).joinToString { describeValue(it) } + "}"
        is Array<*> -> "arr[${value.size}]{" + value.take(8).joinToString { describeValue(it) } + "}"
        is Map<*, *> -> "map[${value.size}]{" +
            value.entries.take(8).joinToString { "${describeValue(it.key)}->${describeValue(it.value)}" } + "}"
        else -> value.toString().take(2000)
    }

    // ---------------------------------------------------------------- static scan

    private fun scan(classLoader: ClassLoader, transports: MutableList<Class<*>>): String {
        val inventory = DexInventoryReader.read(classLoader)
        val output = StringBuilder()
        output.appendLine("# inventory classes=${inventory.classNames.size} complete=${inventory.complete}")
        val bodyTypes = linkedSetOf<String>()
        val annotationUses = linkedMapOf<String, Int>()
        var loaded = 0
        var failed = 0

        for (name in inventory.classNames) {
            val clazz = try {
                Class.forName(name, false, classLoader)
            } catch (error: Throwable) {
                failed++
                continue
            }
            loaded++
            // Annotation/method resolution can itself throw NoClassDefFoundError for
            // optional-SDK classes; one bad class must not abort the whole probe.
            try {
                if (clazz.isInterface) describeEndpoints(clazz, output, bodyTypes)
            } catch (error: Throwable) {
                output.appendLine("#ERR endpoint ${clazz.name}: ${error.javaClass.simpleName}")
            }
            try {
                describeInterestingFields(clazz, output)
            } catch (error: Throwable) {
                output.appendLine("#ERR fields ${clazz.name}: ${error.javaClass.simpleName}")
            }
            try {
                collectAnnotationUses(clazz, annotationUses)
            } catch (error: Throwable) {
                // best effort
            }
            if (name.startsWith("$NETWORK_PACKAGE.") || name.startsWith("$SYNC_PACKAGE.") ||
                name.startsWith("$MAIN_NETWORK_PACKAGE.") || name.startsWith("$SIGN_MODEL_PACKAGE.") ||
                name.contains(".calendar.") || name.contains("SignManager") || name.endsWith(".SignDialog")
            ) {
                appendFullMembers(clazz, output)
                try {
                    if (TRANSPORT_INTERFACE.let { Class.forName(it, false, classLoader).isAssignableFrom(clazz) } &&
                        !clazz.isInterface
                    ) {
                        transports += clazz
                    }
                } catch (error: Throwable) {
                    // transport interface not present; skip
                }
            }
        }

        output.appendLine()
        output.appendLine("# body model details")
        for (typeName in bodyTypes) {
            val clazz = try {
                Class.forName(typeName, false, classLoader)
            } catch (error: Throwable) {
                continue
            }
            try {
                appendAllFields(clazz, output)
            } catch (error: Throwable) {
                output.appendLine("#ERR body ${clazz.name}: ${error.javaClass.simpleName}")
            }
        }

        output.appendLine()
        output.appendLine("# loaded=$loaded failed=$failed bodies=${bodyTypes.size} transports=${transports.size}")
        transports.forEach { output.appendLine("#TRANSPORT ${it.name}") }
        writeShapeReport(inventory.classNames, annotationUses, transports)
        return output.toString()
    }

    /** Full member dump for selected packages: every non-private member with annotations. */
    private fun appendFullMembers(clazz: Class<*>, output: StringBuilder) {
        output.appendLine("#MEMBERS ${clazz.name}")
        for (field in clazz.declaredFields) {
            val annotations = field.annotations.joinToString(" ") { describeAnnotation(it) }
            output.appendLine(
                "  F ${field.name} : ${field.type.name}" +
                    annotations.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
            )
        }
        for (method in clazz.declaredMethods) {
            val annotations = method.annotations.joinToString(" ") { describeAnnotation(it) }
            output.appendLine(
                "  M ${method.name}(${method.parameterTypes.joinToString { it.name }}) : ${method.returnType.name}" +
                    annotations.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
            )
        }
    }

    private fun collectAnnotationUses(clazz: Class<*>, annotationUses: MutableMap<String, Int>) {
        fun use(typeName: String) = annotationUses.merge(typeName, 1, Int::plus)
        clazz.annotations.forEach { use(annotationTypeOf(it).name) }
        if (clazz.isInterface) {
            for (method in clazz.declaredMethods) {
                method.annotations.forEach { use(annotationTypeOf(it).name) }
                method.parameterAnnotations.forEach { params -> params.forEach { use(annotationTypeOf(it).name) } }
            }
        }
        for (field in clazz.declaredFields) {
            field.annotations.forEach { use(annotationTypeOf(it).name) }
        }
    }

    private fun writeShapeReport(
        classNames: List<String>,
        annotationUses: Map<String, Int>,
        transports: List<Class<*>>
    ) {
        val report = StringBuilder()
        report.appendLine("# transports")
        transports.forEach { report.appendLine(it.name) }
        report.appendLine()
        report.appendLine("# annotation types in use (count)")
        annotationUses.entries.sortedByDescending { it.value }.forEach {
            report.appendLine("${it.value}\t${it.key}")
        }
        report.appendLine()
        report.appendLine("# all class names")
        classNames.forEach(report::appendLine)
        val dir = filesDir ?: return
        try {
            File(dir, SHAPE_FILE).writeText(report.toString())
        } catch (error: Throwable) {
            logLine("shape report write failed: $error")
        }
    }

    // ---------------------------------------------------------------- endpoints/fields

    private fun describeEndpoints(clazz: Class<*>, output: StringBuilder, bodyTypes: MutableSet<String>) {
        for (method in clazz.declaredMethods) {
            // Shaded Retrofit: match by annotation VALUE shape, not annotation type name.
            val httpAnnotations = method.annotations.filter { hasPathLikeValue(it) }
            if (httpAnnotations.isEmpty()) continue
            output.appendLine(
                "#API ${clazz.name} ${method.name} " +
                    httpAnnotations.joinToString(" ") { describeAnnotation(it) } +
                    " -> ${method.returnType.name}"
            )
            val generic = method.genericParameterTypes
            method.parameterAnnotations.forEachIndexed { index, annotations ->
                val descriptors = annotations.mapNotNull {
                    if (hasPathLikeValue(it) || annotationTypeOf(it).simpleName == "Body") describeAnnotation(it) else null
                }
                if (descriptors.isEmpty()) return@forEachIndexed
                val typeName = rawTypeName(generic.getOrNull(index) ?: method.parameterTypes[index])
                output.appendLine("  param[$index] $typeName ${descriptors.joinToString(" ")}")
                if (descriptors.any { it.startsWith("@Body") }) {
                    bodyTypes += typeName
                }
            }
        }
    }

    private fun hasPathLikeValue(annotation: Annotation): Boolean {
        if (!annotationTypeOf(annotation).name.contains(".http.")) return false
        val value = try {
            val valueMethod = annotationTypeOf(annotation).methods.firstOrNull {
                (it.name == "value" || it.name == "path") && it.parameterCount == 0 &&
                    it.returnType == String::class.java
            }
            valueMethod?.invoke(annotation) as? String
        } catch (error: Throwable) {
            null
        } ?: return false
        return value.startsWith("/")
    }

    private fun describeInterestingFields(clazz: Class<*>, output: StringBuilder) {
        if (clazz.isInterface || clazz.isEnum || clazz.isAnnotation) return
        val lines = mutableListOf<String>()
        var matched = false
        for (field in clazz.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            val annotations = field.annotations.mapNotNull { annotation ->
                when (annotationTypeOf(annotation).name) {
                    "com.google.gson.annotations.SerializedName",
                    "kotlinx.serialization.SerialName",
                    "com.squareup.moshi.Json" -> describeAnnotation(annotation)
                    else -> null
                }
            }
            val names = annotations + field.name
            if (names.any { candidate -> interestingFieldKeywords.any { candidate.lowercase().contains(it) } }) {
                matched = true
                lines += "  ${field.name} : ${field.type.name} ${annotations.joinToString(" ")}"
            }
        }
        if (matched) {
            output.appendLine("#FIELDS ${clazz.name}")
            lines.forEach(output::appendLine)
        }
    }

    private fun appendAllFields(clazz: Class<*>, output: StringBuilder) {
        output.appendLine("#BODY ${clazz.name}")
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val annotations = field.annotations.mapNotNull { annotation ->
                    when (annotationTypeOf(annotation).name) {
                        "com.google.gson.annotations.SerializedName",
                        "kotlinx.serialization.SerialName",
                        "com.squareup.moshi.Json" -> describeAnnotation(annotation)
                        else -> null
                    }
                }
                output.appendLine(
                    "  ${field.name} : ${field.type.name}" +
                        annotations.takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = " ") { it }.orEmpty()
                )
            }
            current = current.superclass
        }
    }

    // ---------------------------------------------------------------- reflection helpers

    private fun annotationTypeOf(annotation: Annotation): Class<out Annotation> =
        (annotation as java.lang.annotation.Annotation).annotationType()

    private fun describeAnnotation(annotation: Annotation): String {
        val type = annotationTypeOf(annotation)
        val simpleName = type.name.substringAfterLast('.')
        val value = try {
            val valueMethod = type.methods.firstOrNull {
                (it.name == "value" || it.name == "path") && it.parameterCount == 0 &&
                    it.returnType == String::class.java
            }
            valueMethod?.invoke(annotation)?.toString()
        } catch (error: Throwable) {
            null
        }
        return "@$simpleName(${value ?: "?"})"
    }

    private fun rawTypeName(type: java.lang.reflect.Type): String = when (type) {
        is ParameterizedType -> (type.rawType as? Class<*>)?.name ?: type.toString()
        is Class<*> -> type.name
        else -> type.toString()
    }

    // ---------------------------------------------------------------- json log

    /**
     * Hooks Gson.toJson and JSONObject.toString: the kept-name serialization funnels every
     * request body passes through. Logs entries whose text matches bodyKeywords, capped.
     */
    private fun installJsonLog(classLoader: ClassLoader) {
        val gson = try {
            Class.forName("com.google.gson.Gson", false, classLoader)
        } catch (error: Throwable) {
            logLine("gson not found: $error")
            return
        }
        var hooked = 0
        for (method in gson.declaredMethods) {
            if (method.name != "toJson") continue
            if (method.parameterCount < 1) continue
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logJsonCandidate(param.args.getOrNull(0), param.result)
                    }
                })
                hooked++
            } catch (error: Throwable) {
                logLine("toJson hook failed: $error")
            }
        }
        logLine("gson toJson hooked x$hooked")
        try {
            val jsonObject = Class.forName("org.json.JSONObject", false, classLoader)
            val toString = jsonObject.getDeclaredMethod("toString")
            XposedBridge.hookMethod(toString, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    logJsonCandidate(null, param.result)
                }
            })
            logLine("org.json JSONObject.toString hooked")
        } catch (error: Throwable) {
            logLine("org.json hook failed: $error")
        }
    }

    private fun logJsonCandidate(source: Any?, result: Any?) {
        if (jsonLogCount > 400) return
        val text = result as? String ?: return
        if (text.length < 8) return
        val low = text.lowercase()
        if (bodyKeywords.none { low.contains(it) }) return
        jsonLogCount++
        val label = source?.javaClass?.name ?: "JSONObject"
        synchronized(netLogLock) {
            appendNetLog("JSON $label\n    ${text.take(3000)}")
        }
    }

    private fun logLine(line: String) {
        XposedBridge.log("FreeMOMO: $line")
    }
}
