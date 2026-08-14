package com.rud.freemomo.util

import android.content.Context
import com.rud.freemomo.hook.DisplayHookTargets
import com.rud.freemomo.hook.HookTargets
import com.rud.freemomo.hook.MethodSignature
import com.rud.freemomo.hook.PrivilegeHookTargets
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier

/** Versioned, strict cache for structurally discovered Java methods. */
object HookCache {
    internal const val SCHEMA_VERSION = 2
    private const val PREFS_NAME = "momo_hook_cache"
    private const val KEY_SCHEMA = "structural_schema"
    private const val KEY_VERSION_CODE = "structural_version_code"
    private const val KEY_WORD_PRESENT = "word.present"
    private const val KEY_DISPLAY_PRESENT = "display.present"
    private const val KEY_PRIVILEGE_PRESENT = "privilege.present"
    private const val KEY_CLOUD_PRESENT = "cloud.present"

    fun load(context: Context, versionCode: Int, classLoader: ClassLoader): HookTargets? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val values = prefs.all.mapValues { it.value?.toString().orEmpty() }
        val decoded = decode(values, versionCode)
        if (decoded == null) {
            if (values.isNotEmpty()) {
                XposedBridge.log(
                    "FreeMOMO: cache rejected -> schema/version/fields invalid for $versionCode"
                )
                prefs.edit().clear().apply()
            }
            return null
        }
        val signatureValidated = validate(decoded) { signature ->
            resolvesExactly(signature, classLoader)
        }
        val validated = signatureValidated.copy(
            wordLimit = signatureValidated.wordLimit?.takeIf { target ->
                isReusableWordTarget(target, declaredWordCandidates(classLoader))
            }
        )
        if (validated != decoded) {
            XposedBridge.log(
                "FreeMOMO: cache rejected invalid capabilities -> " +
                    rejectedCapabilities(decoded, validated)
            )
            if (validated.isEmpty()) clear(context) else save(context, validated, versionCode)
        } else {
            XposedBridge.log(
                "FreeMOMO: cache cached -> version=$versionCode " +
                    "capabilities=${validated.presentCapabilityCount}"
            )
        }
        return validated.takeUnless { it.isEmpty() }
    }

    fun save(context: Context, targets: HookTargets, versionCode: Int) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
        encode(targets, versionCode).forEach { (key, value) ->
            editor.putString(key, value)
        }
        editor.apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    internal fun encode(targets: HookTargets, versionCode: Int): Map<String, String> {
        val values = linkedMapOf(
            KEY_SCHEMA to SCHEMA_VERSION.toString(),
            KEY_VERSION_CODE to versionCode.toString(),
            KEY_WORD_PRESENT to (targets.wordLimit != null).toString(),
            KEY_DISPLAY_PRESENT to (targets.display != null).toString(),
            KEY_PRIVILEGE_PRESENT to (targets.privilege != null).toString(),
            KEY_CLOUD_PRESENT to (targets.cloud != null).toString()
        )
        targets.wordLimit?.let { putSignature(values, "word.0", it) }
        targets.display?.methods?.forEachIndexed { index, signature ->
            putSignature(values, "display.$index", signature)
        }
        targets.privilege?.methods?.forEachIndexed { index, signature ->
            putSignature(values, "privilege.$index", signature)
        }
        targets.cloud?.let { putSignature(values, "cloud.0", it) }
        return values
    }

    internal fun decode(values: Map<String, String>, versionCode: Int): HookTargets? {
        if (values[KEY_SCHEMA]?.toIntOrNull() != SCHEMA_VERSION) return null
        if (values[KEY_VERSION_CODE]?.toIntOrNull() != versionCode) return null
        return try {
            val word = if (requiredBoolean(values, KEY_WORD_PRESENT)) {
                readSignature(values, "word.0")
            } else {
                null
            }
            val display = if (requiredBoolean(values, KEY_DISPLAY_PRESENT)) {
                DisplayHookTargets(
                    readSignature(values, "display.0"),
                    readSignature(values, "display.1")
                )
            } else {
                null
            }
            val privilege = if (requiredBoolean(values, KEY_PRIVILEGE_PRESENT)) {
                PrivilegeHookTargets(
                    listOf(
                        readSignature(values, "privilege.0"),
                        readSignature(values, "privilege.1")
                    ),
                    readSignature(values, "privilege.2")
                )
            } else {
                null
            }
            val cloud = if (requiredBoolean(values, KEY_CLOUD_PRESENT)) {
                readSignature(values, "cloud.0")
            } else {
                null
            }
            HookTargets(word, display, privilege, cloud)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    internal fun validate(
        targets: HookTargets,
        verifier: (MethodSignature) -> Boolean
    ): HookTargets = HookTargets(
        wordLimit = targets.wordLimit?.takeIf { isWordTarget(it) && verifier(it) },
        display = targets.display?.takeIf { group ->
            isDisplayTarget(group) && group.methods.all(verifier)
        },
        privilege = targets.privilege?.takeIf { group ->
            isPrivilegeTarget(group) && group.methods.all(verifier)
        },
        cloud = targets.cloud?.takeIf { isCloudTarget(it) && verifier(it) }
    )

    /** A runtime-selected word target is reusable only when no peer can match next process. */
    internal fun isReusableWordTarget(
        target: MethodSignature,
        declaredCandidates: List<MethodSignature>
    ): Boolean = isWordTarget(target) &&
        declaredCandidates.filter(::isWordTarget).distinct().singleOrNull() == target

    private fun putSignature(
        values: MutableMap<String, String>,
        prefix: String,
        signature: MethodSignature
    ) {
        values["$prefix.class"] = signature.className
        values["$prefix.method"] = signature.methodName
        values["$prefix.return"] = signature.returnType
        values["$prefix.static"] = signature.isStatic.toString()
        values["$prefix.parameter_count"] = signature.parameterTypes.size.toString()
        signature.parameterTypes.forEachIndexed { index, type ->
            values["$prefix.parameter.$index"] = type
        }
    }

    private fun readSignature(
        values: Map<String, String>,
        prefix: String
    ): MethodSignature {
        val className = required(values, "$prefix.class")
        val methodName = required(values, "$prefix.method")
        val returnType = required(values, "$prefix.return")
        val isStatic = requiredBoolean(values, "$prefix.static")
        val parameterCount = required(values, "$prefix.parameter_count").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid parameter count")
        if (parameterCount !in 0..16) throw IllegalArgumentException("Invalid parameter count")
        val parameters = (0 until parameterCount).map { index ->
            required(values, "$prefix.parameter.$index")
        }
        return MethodSignature(className, methodName, parameters, returnType, isStatic)
    }

    private fun required(values: Map<String, String>, key: String): String =
        values[key]?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing cache field: $key")

    private fun requiredBoolean(values: Map<String, String>, key: String): Boolean =
        when (val value = required(values, key)) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Invalid boolean: $key=$value")
        }

    private fun resolvesExactly(
        signature: MethodSignature,
        classLoader: ClassLoader
    ): Boolean = try {
        val declaringClass = Class.forName(signature.className, false, classLoader)
        val parameterTypes = signature.parameterTypes.map { typeName ->
            resolveType(typeName, classLoader)
        }.toTypedArray()
        val method = declaringClass.getDeclaredMethod(signature.methodName, *parameterTypes)
        method.returnType.name == signature.returnType &&
            Modifier.isStatic(method.modifiers) == signature.isStatic
    } catch (_: Throwable) {
        false
    }

    private fun declaredWordCandidates(classLoader: ClassLoader): List<MethodSignature> = try {
        val declaringClass = Class.forName(HookTargets.WORD_LIMIT_CLASS, false, classLoader)
        declaringClass.declaredMethods
            .asSequence()
            .filterNot { it.isSynthetic || it.isBridge }
            .map { method ->
                MethodSignature(
                    className = declaringClass.name,
                    methodName = method.name,
                    parameterTypes = method.parameterTypes.map { it.name },
                    returnType = method.returnType.name,
                    isStatic = Modifier.isStatic(method.modifiers)
                )
            }
            .distinct()
            .toList()
    } catch (_: Throwable) {
        emptyList()
    }

    internal fun resolveType(typeName: String, classLoader: ClassLoader): Class<*> =
        when (typeName) {
            "boolean" -> Boolean::class.javaPrimitiveType!!
            "byte" -> Byte::class.javaPrimitiveType!!
            "char" -> Char::class.javaPrimitiveType!!
            "double" -> Double::class.javaPrimitiveType!!
            "float" -> Float::class.javaPrimitiveType!!
            "int" -> Int::class.javaPrimitiveType!!
            "long" -> Long::class.javaPrimitiveType!!
            "short" -> Short::class.javaPrimitiveType!!
            "void" -> Void.TYPE
            else -> Class.forName(typeName, false, classLoader)
        }

    private fun rejectedCapabilities(before: HookTargets, after: HookTargets): String = listOf(
        "word" to (before.wordLimit != null && after.wordLimit == null),
        "display" to (before.display != null && after.display == null),
        "privilege" to (before.privilege != null && after.privilege == null),
        "cloud" to (before.cloud != null && after.cloud == null)
    ).filter { it.second }.joinToString { it.first }

    private fun isWordTarget(target: MethodSignature): Boolean =
        target.className == HookTargets.WORD_LIMIT_CLASS &&
            target.parameterTypes.isEmpty() &&
            target.returnType == HookTargets.INT &&
            target.isStatic

    private fun isDisplayTarget(target: DisplayHookTargets): Boolean {
        val short = target.twoArgumentMethod
        val detailed = target.detailedMethod
        return short.className == detailed.className &&
            short.isStatic && short.returnType == HookTargets.VOID &&
            short.parameterTypes == listOf(HookTargets.TEXT_VIEW, HookTargets.INT) &&
            detailed.isStatic && detailed.returnType == HookTargets.VOID &&
            detailed.parameterTypes == listOf(
                HookTargets.TEXT_VIEW,
                HookTargets.INT,
                HookTargets.BOOLEAN,
                HookTargets.FLOAT
            )
    }

    private fun isPrivilegeTarget(target: PrivilegeHookTargets): Boolean =
        target.methods.all { method ->
            method.className == HookTargets.PRIVILEGE_CLASS &&
                method.isStatic &&
                method.returnType == HookTargets.BOOLEAN
        } &&
            target.singleArgumentMethods.all {
                it.parameterTypes == listOf(HookTargets.PRIVILEGE_CODE)
            } &&
            target.twoArgumentMethod.parameterTypes == listOf(
                HookTargets.PRIVILEGE_CODE,
                HookTargets.BOOLEAN
            )

    private fun isCloudTarget(target: MethodSignature): Boolean =
        '.' !in target.className &&
            '$' !in target.className &&
            target.parameterTypes.isEmpty() &&
            target.returnType == HookTargets.INT &&
            target.isStatic
}
