package com.rud.freemomo.util

import android.content.Context
import com.rud.freemomo.hook.CapabilityDiscovery
import com.rud.freemomo.hook.DiscoveryStatus
import com.rud.freemomo.hook.DisplayHookTargets
import com.rud.freemomo.hook.HookTargets
import com.rud.freemomo.hook.MethodSignature
import com.rud.freemomo.hook.PrivilegeHookTargets
import com.rud.freemomo.hook.StructuralDiscoveryResult
import com.rud.freemomo.hook.WordLimitDiscovery
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Modifier

/**
 * Per-capability discovery state. A null entry means that capability is unknown and must be
 * structurally discovered. Terminal failures and pending word candidates are complete states.
 */
data class DiscoverySnapshot(
    val wordLimit: WordLimitDiscovery? = null,
    val display: CapabilityDiscovery<DisplayHookTargets>? = null,
    val privilege: CapabilityDiscovery<PrivilegeHookTargets>? = null,
    val cloud: CapabilityDiscovery<MethodSignature>? = null
) {
    val targets: HookTargets = HookTargets(
        wordLimit = wordLimit?.target.takeIf {
            wordLimit?.status == DiscoveryStatus.DISCOVERED
        },
        display = display?.target.takeIf { display?.status == DiscoveryStatus.DISCOVERED },
        privilege = privilege?.target.takeIf {
            privilege?.status == DiscoveryStatus.DISCOVERED
        },
        cloud = cloud?.target.takeIf { cloud?.status == DiscoveryStatus.DISCOVERED }
    )

    val requiresStructuralScan: Boolean
        get() = wordLimit == null || display == null || privilege == null || cloud == null

    val hasPendingWordObservation: Boolean
        get() = wordLimit?.status == DiscoveryStatus.PENDING &&
            wordLimit.candidates.isNotEmpty()

    fun fillUnknown(result: StructuralDiscoveryResult): DiscoverySnapshot = copy(
        wordLimit = wordLimit ?: result.wordLimit,
        display = display ?: result.display,
        privilege = privilege ?: result.privilege,
        cloud = cloud ?: result.cloud
    )

    /** A discovered target remains cached only after that exact requested Hook installs. */
    fun reconcileInstall(requested: HookTargets, installed: HookTargets): DiscoverySnapshot = copy(
        wordLimit = wordLimit.takeUnless {
            requested.wordLimit != null && installed.wordLimit != requested.wordLimit
        },
        display = display.takeUnless {
            requested.display != null && installed.display != requested.display
        },
        privilege = privilege.takeUnless {
            requested.privilege != null && installed.privilege != requested.privilege
        },
        cloud = cloud.takeUnless {
            requested.cloud != null && installed.cloud != requested.cloud
        }
    )

    companion object {
        fun fromTargets(targets: HookTargets): DiscoverySnapshot = DiscoverySnapshot(
            wordLimit = targets.wordLimit?.let { target ->
                WordLimitDiscovery(
                    status = DiscoveryStatus.DISCOVERED,
                    target = target,
                    candidates = listOf(target)
                )
            },
            display = targets.display?.let { target ->
                CapabilityDiscovery(
                    status = DiscoveryStatus.DISCOVERED,
                    target = target,
                    candidates = target.methods
                )
            },
            privilege = targets.privilege?.let { target ->
                CapabilityDiscovery(
                    status = DiscoveryStatus.DISCOVERED,
                    target = target,
                    candidates = target.methods
                )
            },
            cloud = targets.cloud?.let { target ->
                CapabilityDiscovery(
                    status = DiscoveryStatus.DISCOVERED,
                    target = target,
                    candidates = listOf(target)
                )
            }
        )
    }
}

/** Versioned, strict cache for structural discovery state and Java method identities. */
object HookCache {
    internal const val SCHEMA_VERSION = 3
    private const val PREFS_NAME = "momo_hook_cache"
    private const val KEY_SCHEMA = "structural_schema"
    private const val KEY_VERSION_CODE = "structural_version_code"
    private const val UNKNOWN = "UNKNOWN"

    fun loadSnapshot(
        context: Context,
        versionCode: Int,
        classLoader: ClassLoader
    ): DiscoverySnapshot? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val values = prefs.all.mapValues { it.value?.toString().orEmpty() }
        val decoded = decode(values, versionCode)
        if (decoded == null) {
            if (values.isNotEmpty()) {
                XposedBridge.log(
                    "FreeMOMO: cache rejected -> schema/version invalid for $versionCode"
                )
                prefs.edit().clear().apply()
            }
            return null
        }

        val needsWordDeclarations = decoded.wordLimit?.status in setOf(
            DiscoveryStatus.PENDING,
            DiscoveryStatus.DISCOVERED
        )
        val declaredWords = if (needsWordDeclarations) {
            declaredWordCandidates(classLoader)
        } else {
            emptyList()
        }
        val validated = validateSnapshot(
            snapshot = decoded,
            verifier = { signature -> resolvesExactly(signature, classLoader) },
            declaredWordCandidates = declaredWords
        )
        if (validated != decoded) {
            XposedBridge.log(
                "FreeMOMO: cache rejected invalid capabilities -> " +
                    rejectedCapabilities(decoded, validated)
            )
            saveSnapshot(context, validated, versionCode)
        } else {
            XposedBridge.log(
                "FreeMOMO: discovery snapshot cached -> version=$versionCode " +
                    "states=${describeStates(validated)}"
            )
        }
        return validated
    }

    /** Compatibility view for callers that only understand successfully discovered targets. */
    fun load(context: Context, versionCode: Int, classLoader: ClassLoader): HookTargets? =
        loadSnapshot(context, versionCode, classLoader)?.targets?.takeUnless { it.isEmpty() }

    fun saveSnapshot(context: Context, snapshot: DiscoverySnapshot, versionCode: Int) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
        encode(snapshot, versionCode).forEach { (key, value) ->
            editor.putString(key, value)
        }
        editor.apply()
    }

    fun save(context: Context, targets: HookTargets, versionCode: Int) {
        saveSnapshot(context, DiscoverySnapshot.fromTargets(targets), versionCode)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    internal fun encode(targets: HookTargets, versionCode: Int): Map<String, String> =
        encode(DiscoverySnapshot.fromTargets(targets), versionCode)

    internal fun encode(
        snapshot: DiscoverySnapshot,
        versionCode: Int
    ): Map<String, String> {
        val values = linkedMapOf(
            KEY_SCHEMA to SCHEMA_VERSION.toString(),
            KEY_VERSION_CODE to versionCode.toString()
        )
        putWord(values, snapshot.wordLimit)
        putCapability(values, "display", snapshot.display) { target ->
            target.methods.forEachIndexed { index, signature ->
                putSignature(values, "display.target.$index", signature)
            }
        }
        putCapability(values, "privilege", snapshot.privilege) { target ->
            target.methods.forEachIndexed { index, signature ->
                putSignature(values, "privilege.target.$index", signature)
            }
        }
        putCapability(values, "cloud", snapshot.cloud) { target ->
            putSignature(values, "cloud.target.0", target)
        }
        return values
    }

    internal fun decode(
        values: Map<String, String>,
        versionCode: Int
    ): DiscoverySnapshot? {
        if (values[KEY_SCHEMA]?.toIntOrNull() != SCHEMA_VERSION) return null
        if (values[KEY_VERSION_CODE]?.toIntOrNull() != versionCode) return null
        return DiscoverySnapshot(
            wordLimit = decodeWord(values),
            display = decodeDisplay(values),
            privilege = decodePrivilege(values),
            cloud = decodeCloud(values)
        )
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

    internal fun validateSnapshot(
        snapshot: DiscoverySnapshot,
        verifier: (MethodSignature) -> Boolean,
        declaredWordCandidates: List<MethodSignature>
    ): DiscoverySnapshot {
        val targetValidation = validate(snapshot.targets, verifier)
        return snapshot.copy(
            wordLimit = validateWordDiscovery(
                snapshot.wordLimit,
                declaredWordCandidates,
                verifier
            ),
            display = validateCapability(
                snapshot.display,
                targetValidation.display != null
            ),
            privilege = validateCapability(
                snapshot.privilege,
                targetValidation.privilege != null
            ),
            cloud = validateCapability(
                snapshot.cloud,
                targetValidation.cloud != null
            )
        )
    }

    /** A runtime-selected word target is reusable only when no peer can match next process. */
    internal fun isReusableWordTarget(
        target: MethodSignature,
        declaredCandidates: List<MethodSignature>
    ): Boolean = isWordTarget(target) &&
        declaredCandidates.filter(::isWordTarget).distinct().singleOrNull() == target

    private fun putWord(
        values: MutableMap<String, String>,
        discovery: WordLimitDiscovery?
    ) {
        values["word.status"] = discovery?.status?.name ?: UNKNOWN
        val stored = discovery ?: return
        if (stored.status !in setOf(DiscoveryStatus.PENDING, DiscoveryStatus.DISCOVERED)) {
            return
        }
        values["word.candidate_count"] = stored.candidates.size.toString()
        stored.candidates.forEachIndexed { index, signature ->
            putSignature(values, "word.candidate.$index", signature)
        }
        stored.target?.let { putSignature(values, "word.target", it) }
    }

    private fun <T> putCapability(
        values: MutableMap<String, String>,
        prefix: String,
        discovery: CapabilityDiscovery<T>?,
        putTarget: (T) -> Unit
    ) {
        values["$prefix.status"] = discovery?.status?.name ?: UNKNOWN
        if (discovery?.status == DiscoveryStatus.DISCOVERED) {
            discovery.target?.let(putTarget)
        }
    }

    private fun decodeWord(values: Map<String, String>): WordLimitDiscovery? = try {
        when (val status = readStatus(values, "word.status")) {
            null -> null
            DiscoveryStatus.PENDING -> WordLimitDiscovery(
                status = status,
                candidates = readSignatureList(values, "word.candidate")
            )
            DiscoveryStatus.DISCOVERED -> WordLimitDiscovery(
                status = status,
                target = readSignature(values, "word.target"),
                candidates = readSignatureList(values, "word.candidate")
            )
            DiscoveryStatus.MISSING,
            DiscoveryStatus.AMBIGUOUS,
            DiscoveryStatus.REJECTED -> WordLimitDiscovery(status)
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodeDisplay(
        values: Map<String, String>
    ): CapabilityDiscovery<DisplayHookTargets>? = decodeCapability(
        values,
        "display"
    ) {
        val target = DisplayHookTargets(
            readSignature(values, "display.target.0"),
            readSignature(values, "display.target.1")
        )
        CapabilityDiscovery(
            status = DiscoveryStatus.DISCOVERED,
            target = target,
            candidates = target.methods
        )
    }

    private fun decodePrivilege(
        values: Map<String, String>
    ): CapabilityDiscovery<PrivilegeHookTargets>? = decodeCapability(
        values,
        "privilege"
    ) {
        val target = PrivilegeHookTargets(
            singleArgumentMethods = listOf(
                readSignature(values, "privilege.target.0"),
                readSignature(values, "privilege.target.1")
            ),
            twoArgumentMethod = readSignature(values, "privilege.target.2")
        )
        CapabilityDiscovery(
            status = DiscoveryStatus.DISCOVERED,
            target = target,
            candidates = target.methods
        )
    }

    private fun decodeCloud(
        values: Map<String, String>
    ): CapabilityDiscovery<MethodSignature>? = decodeCapability(values, "cloud") {
        val target = readSignature(values, "cloud.target.0")
        CapabilityDiscovery(
            status = DiscoveryStatus.DISCOVERED,
            target = target,
            candidates = listOf(target)
        )
    }

    private fun <T> decodeCapability(
        values: Map<String, String>,
        prefix: String,
        discovered: () -> CapabilityDiscovery<T>
    ): CapabilityDiscovery<T>? = try {
        when (val status = readStatus(values, "$prefix.status")) {
            null -> null
            DiscoveryStatus.DISCOVERED -> discovered()
            DiscoveryStatus.MISSING,
            DiscoveryStatus.AMBIGUOUS,
            DiscoveryStatus.REJECTED -> CapabilityDiscovery(status)
            DiscoveryStatus.PENDING -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readStatus(values: Map<String, String>, key: String): DiscoveryStatus? {
        val value = required(values, key)
        if (value == UNKNOWN) return null
        return DiscoveryStatus.entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Invalid discovery status: $key=$value")
    }

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

    private fun readSignatureList(
        values: Map<String, String>,
        prefix: String
    ): List<MethodSignature> {
        val count = required(values, "${prefix.substringBeforeLast('.')}.candidate_count")
            .toIntOrNull()
            ?: throw IllegalArgumentException("Invalid candidate count")
        if (count !in 1..64) throw IllegalArgumentException("Invalid candidate count")
        return (0 until count).map { index -> readSignature(values, "$prefix.$index") }
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

    private fun validateWordDiscovery(
        discovery: WordLimitDiscovery?,
        declaredCandidates: List<MethodSignature>,
        verifier: (MethodSignature) -> Boolean
    ): WordLimitDiscovery? {
        discovery ?: return null
        if (discovery.status !in setOf(DiscoveryStatus.PENDING, DiscoveryStatus.DISCOVERED)) {
            return discovery
        }
        val cachedCandidates = discovery.candidates
        val declaredExact = declaredCandidates.filter(::isWordTarget).distinct()
        if (cachedCandidates.isEmpty() || cachedCandidates.distinct().size != cachedCandidates.size ||
            cachedCandidates.any { !isWordTarget(it) || !verifier(it) } ||
            cachedCandidates.toSet() != declaredExact.toSet()
        ) {
            return null
        }
        if (discovery.status == DiscoveryStatus.PENDING) {
            return discovery.copy(target = null)
        }
        val target = discovery.target
            ?.takeIf { it in cachedCandidates && isWordTarget(it) && verifier(it) }
            ?: return null
        return if (cachedCandidates.size == 1) {
            discovery.copy(target = target)
        } else {
            WordLimitDiscovery(
                status = DiscoveryStatus.PENDING,
                candidates = cachedCandidates
            )
        }
    }

    private fun <T> validateCapability(
        discovery: CapabilityDiscovery<T>?,
        discoveredTargetValid: Boolean
    ): CapabilityDiscovery<T>? {
        discovery ?: return null
        return if (discovery.status == DiscoveryStatus.DISCOVERED && !discoveredTargetValid) {
            null
        } else {
            discovery
        }
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
    } catch (error: Throwable) {
        ThrowablePolicy.rethrowIfFatal(error)
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
            .toList()
    } catch (error: Throwable) {
        ThrowablePolicy.rethrowIfFatal(error)
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

    private fun rejectedCapabilities(
        before: DiscoverySnapshot,
        after: DiscoverySnapshot
    ): String = listOf(
        "word" to (before.wordLimit != null && after.wordLimit == null),
        "display" to (before.display != null && after.display == null),
        "privilege" to (before.privilege != null && after.privilege == null),
        "cloud" to (before.cloud != null && after.cloud == null)
    ).filter { it.second }.joinToString { it.first }

    private fun describeStates(snapshot: DiscoverySnapshot): String = listOf(
        "word=${snapshot.wordLimit?.status?.name ?: UNKNOWN}",
        "display=${snapshot.display?.status?.name ?: UNKNOWN}",
        "privilege=${snapshot.privilege?.status?.name ?: UNKNOWN}",
        "cloud=${snapshot.cloud?.status?.name ?: UNKNOWN}"
    ).joinToString()

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
