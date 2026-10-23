package com.rud.freemomo.hook

import com.rud.freemomo.util.Logger
import com.rud.freemomo.util.ThrowablePolicy
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the host's request bodies free of the word-limit replacement value. The daily-limit
 * getter is hooked process-wide, so numbers derived from it leak into uploads — observed in
 * 5.6.00 as debt_report_data.max_voc_count inside the sign/debt report. The sanitizer rewrites
 * those leaked values back to the real limit at the serialization funnels (Gson and org.json),
 * which keep their names across versions. Values that never equal the replacement are untouched.
 */
object SignDataSanitizer {

    /** JSON keys whose values are derived from the hooked daily-limit getter. */
    private val guardedKeys = listOf(
        "max_voc_count",      // 5.6.00 debt_report_data (confirmed leak)
        "day_new_limit",      // same payload family
        "today_plan_voc_count", // study progress payload
        "day_limit"
    )

    private val installed = AtomicBoolean(false)

    fun apply(classLoader: ClassLoader, source: String): Boolean {
        if (installed.get()) return true
        return try {
            var funnels = 0
            funnels += hookStringFunnel(classLoader, "com.google.gson.Gson", "toJson")
            funnels += hookStringFunnel(classLoader, "org.json.JSONObject", "toString")
            if (funnels == 0) {
                XposedBridge.log("FreeMOMO: sign-data sanitizer $source found no serialization funnels")
                return false
            }
            installed.set(true)
            XposedBridge.log("FreeMOMO: sign-data sanitizer $source installed on $funnels funnel(s)")
            true
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            Logger.error("sign-data sanitizer $source install failed", error)
            false
        }
    }

    private fun hookStringFunnel(classLoader: ClassLoader, className: String, methodName: String): Int {
        val clazz = try {
            Class.forName(className, false, classLoader)
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            return 0
        }
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name != methodName) continue
            try {
                if (method.returnType == String::class.java) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        private val firstHitLogged = AtomicBoolean(false)

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val original = param.result as? String ?: return
                            val sanitized = sanitize(original) ?: return
                            param.result = sanitized
                            if (firstHitLogged.compareAndSet(false, true)) {
                                XposedBridge.log("FreeMOMO: sign-data sanitizer rewrote leaked limit in $className.$methodName")
                            }
                        }
                    })
                    hooked++
                } else if (method.parameterCount > 0 &&
                    method.parameterTypes.last() == java.lang.Appendable::class.java
                ) {
                    // Stream variants: Gson fills the writer directly. StringWriter buffers can be
                    // rewritten in place after the call; other Appendables are surfaced once.
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        private val unsupportedLogged = AtomicBoolean(false)
                        private val firstHitLogged = AtomicBoolean(false)

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val sink = param.args.lastOrNull()
                            val writer = sink as? java.io.StringWriter
                            if (writer == null) {
                                if (unsupportedLogged.compareAndSet(false, true)) {
                                    XposedBridge.log(
                                        "FreeMOMO: sign-data sanitizer cannot inspect ${sink?.javaClass?.name ?: "null sink"} in $className.$methodName"
                                    )
                                }
                                return
                            }
                            val buffer = writer.buffer
                            val sanitized = sanitize(buffer.toString()) ?: return
                            buffer.setLength(0)
                            buffer.append(sanitized)
                            if (firstHitLogged.compareAndSet(false, true)) {
                                XposedBridge.log("FreeMOMO: sign-data sanitizer rewrote leaked limit in $className.$methodName")
                            }
                        }
                    })
                    hooked++
                }
            } catch (error: Throwable) {
                ThrowablePolicy.rethrowIfFatal(error)
                Logger.error("sign-data sanitizer hook failed: $className.$methodName", error)
            }
        }
        return hooked
    }

    /**
     * Rewrites `"key":<replacement>` occurrences back to the real limit. Returns null when the
     * body carries no leaked value so the common path stays allocation-free.
     */
    fun sanitize(body: String): String? {
        val original = WordLimitState.originalLimit ?: return null
        return sanitize(body, HookDiscoveryPolicy.WORD_LIMIT_REPLACEMENT, original)
    }

    /**
     * Needle/replacement strings depend only on (replacement, originalLimit), but the funnel
     * hooks run on every serialization. Build them once per parameter pair and reuse; the
     * original limit settles with its first observed value, so production rebuilds this once.
     */
    @Volatile private var prepared: PreparedNeedles? = null

    private class PreparedNeedles(val replacement: Int, val originalLimit: Int) {
        val token = replacement.toString()
        val leaked = Array(guardedKeys.size) { "\"${guardedKeys[it]}\":$replacement" }
        val fixed = Array(guardedKeys.size) { "\"${guardedKeys[it]}\":$originalLimit" }
    }

    internal fun sanitize(body: String, replacement: Int, originalLimit: Int): String? {
        var needles = prepared
        if (needles == null || needles.replacement != replacement || needles.originalLimit != originalLimit) {
            needles = PreparedNeedles(replacement, originalLimit)
            prepared = needles
        }
        // Every guarded needle contains the bare replacement token; one scan
        // rejects the common (clean) body before four per-key scans.
        if (body.indexOf(needles.token) < 0) return null
        var result: String? = null
        for (i in guardedKeys.indices) {
            val leaked = needles.leaked[i]
            if (firstCleanIndex(body, leaked, 0) < 0) continue
            if (result == null) result = body
            result = replaceClean(result!!, leaked, needles.fixed[i])
        }
        return result
    }

    /** A clean match is neither preceded by an escape nor followed by another digit. */
    private fun firstCleanIndex(body: String, needle: String, from: Int): Int {
        var index = body.indexOf(needle, from)
        while (index >= 0) {
            val escaped = index > 0 && body[index - 1] == '\\'
            val end = index + needle.length
            val extendsNumber = end < body.length && body[end] in '0'..'9'
            if (!escaped && !extendsNumber) return index
            index = body.indexOf(needle, index + 1)
        }
        return -1
    }

    private fun replaceClean(body: String, needle: String, replacement: String): String {
        val builder = StringBuilder(body.length)
        var copied = 0
        var searchFrom = 0
        while (true) {
            val index = firstCleanIndex(body, needle, searchFrom)
            if (index < 0) break
            builder.append(body, copied, index).append(replacement)
            copied = index + needle.length
            searchFrom = copied
        }
        builder.append(body, copied, body.length)
        return builder.toString()
    }
}
