package com.rud.freemomo.util

import android.content.Context
import com.rud.freemomo.hook.CapabilityDiscovery
import com.rud.freemomo.hook.CapabilityScanMetadata
import com.rud.freemomo.hook.DiscoveryStatus
import com.rud.freemomo.hook.DisplayHookTargets
import com.rud.freemomo.hook.HookCapability
import com.rud.freemomo.hook.HookSignatures
import com.rud.freemomo.hook.HookTargets
import com.rud.freemomo.hook.MethodSignature
import com.rud.freemomo.hook.PrivilegeHookTargets
import com.rud.freemomo.hook.ScanRetryReason
import com.rud.freemomo.hook.WordLimitDiscovery
import de.robv.android.xposed.XposedBridge

/** Versioned, strict cache for structural discovery state and Java method identities. */
object HookCache {
    internal const val SCHEMA_VERSION = 4
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
            verifier = { signature -> HookSignatures.resolve(signature, classLoader) != null },
            declaredWordCandidates = declaredWords
        )
        if (validated != decoded) {
            XposedBridge.log(
                "FreeMOMO: cache rejected invalid capabilities -> " +
                    rejectedCapabilities(decoded, validated)
            )
            // The coordinator persists validation and installation together, in commit order.
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
        snapshot.scanMetadata.forEach { (capability, metadata) ->
            putScanMetadata(values, capability, metadata)
        }
        return values
    }

    internal fun decode(
        values: Map<String, String>,
        versionCode: Int
    ): DiscoverySnapshot? {
        if (values[KEY_SCHEMA]?.toIntOrNull() != SCHEMA_VERSION) return null
        if (values[KEY_VERSION_CODE]?.toIntOrNull() != versionCode) return null
        return discardIncompleteStates(DiscoverySnapshot(
            wordLimit = decodeWord(values),
            display = decodeDisplay(values),
            privilege = decodePrivilege(values),
            cloud = decodeCloud(values),
            scanMetadata = HookCapability.entries.mapNotNull { capability ->
                decodeScanMetadata(values, capability)?.let { capability to it }
            }.toMap()
        ))
    }

    internal fun validate(
        targets: HookTargets,
        verifier: (MethodSignature) -> Boolean
    ): HookTargets = HookTargets(
        wordLimit = targets.wordLimit?.takeIf { HookSignatures.isWord(it) && verifier(it) },
        display = targets.display?.takeIf { group ->
            HookSignatures.isDisplay(group) && group.methods.all(verifier)
        },
        privilege = targets.privilege?.takeIf { group ->
            HookSignatures.isPrivilege(group) && group.methods.all(verifier)
        },
        cloud = targets.cloud?.takeIf { HookSignatures.isCloud(it) && verifier(it) }
    )

    internal fun validateSnapshot(
        snapshot: DiscoverySnapshot,
        verifier: (MethodSignature) -> Boolean,
        declaredWordCandidates: List<MethodSignature>
    ): DiscoverySnapshot {
        val trusted = discardIncompleteStates(snapshot)
        val targetValidation = validate(trusted.targets, verifier)
        return trusted.copy(
            wordLimit = validateWordDiscovery(
                trusted.wordLimit,
                declaredWordCandidates,
                verifier
            ),
            display = validateCapability(
                trusted.display,
                targetValidation.display != null
            ),
            privilege = validateCapability(
                trusted.privilege,
                targetValidation.privilege != null
            ),
            cloud = validateCapability(
                trusted.cloud,
                targetValidation.cloud != null
            )
        )
    }

    /** A runtime-selected word target is reusable only when no peer can match next process. */
    internal fun isReusableWordTarget(
        target: MethodSignature,
        declaredCandidates: List<MethodSignature>
    ): Boolean = HookSignatures.isWord(target) &&
        declaredCandidates.filter(HookSignatures::isWord).distinct().singleOrNull() == target

    private fun putScanMetadata(
        values: MutableMap<String, String>,
        capability: HookCapability,
        metadata: CapabilityScanMetadata
    ) {
        val prefix = "${capability.cacheKey}.scan"
        values["$prefix.complete"] = metadata.complete.toString()
        values["$prefix.enumeration_complete"] = metadata.enumerationComplete.toString()
        values["$prefix.attempts"] = metadata.attempts.toString()
        metadata.inventoryFingerprint?.let { values["$prefix.inventory"] = it }
        metadata.retryReason?.let { values["$prefix.retry"] = it.name }
        values["$prefix.failed_count"] = metadata.failedClasses.size.toString()
        metadata.failedClasses.sorted().forEachIndexed { index, className ->
            values["$prefix.failed.$index"] = className
        }
    }

    private fun decodeScanMetadata(
        values: Map<String, String>,
        capability: HookCapability
    ): CapabilityScanMetadata? = try {
        val prefix = "${capability.cacheKey}.scan"
        if ("$prefix.complete" !in values) {
            null
        } else {
            val count = required(values, "$prefix.failed_count").toInt()
            require(count in 0..200_000)
            CapabilityScanMetadata(
                complete = requiredBoolean(values, "$prefix.complete"),
                failedClasses = (0 until count).mapTo(linkedSetOf()) {
                    required(values, "$prefix.failed.$it")
                },
                inventoryFingerprint = values["$prefix.inventory"],
                enumerationComplete = requiredBoolean(values, "$prefix.enumeration_complete"),
                attempts = required(values, "$prefix.attempts").toInt(),
                retryReason = values["$prefix.retry"]?.let(ScanRetryReason::valueOf)
            )
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun discardIncompleteStates(snapshot: DiscoverySnapshot): DiscoverySnapshot {
        val statuses = mapOf(
            HookCapability.WORD_LIMIT to snapshot.wordLimit?.status,
            HookCapability.DISPLAY to snapshot.display?.status,
            HookCapability.PRIVILEGE to snapshot.privilege?.status,
            HookCapability.CLOUD to snapshot.cloud?.status
        )
        val untrusted = statuses.filter { (capability, status) ->
            val metadata = snapshot.scanMetadata[capability]
            status != null && metadata?.isCompleteFor(capability) != true
        }.keys
        return snapshot.clear(untrusted)
    }

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
        val declaredExact = declaredCandidates.filter(HookSignatures::isWord).distinct()
        if (cachedCandidates.isEmpty() || cachedCandidates.distinct().size != cachedCandidates.size ||
            cachedCandidates.any { !HookSignatures.isWord(it) || !verifier(it) } ||
            cachedCandidates.toSet() != declaredExact.toSet()
        ) {
            return null
        }
        if (discovery.status == DiscoveryStatus.PENDING) {
            return discovery.copy(target = null)
        }
        val target = discovery.target
            ?.takeIf { it in cachedCandidates && HookSignatures.isWord(it) && verifier(it) }
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

    private fun declaredWordCandidates(classLoader: ClassLoader): List<MethodSignature> = try {
        HookSignatures.describe(HookTargets.WORD_LIMIT_CLASS, classLoader).methods
    } catch (error: Throwable) {
        ThrowablePolicy.rethrowIfFatal(error)
        emptyList()
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
}
