package com.rud.freemomo.hook

enum class DiscoveryStatus {
    PENDING,
    DISCOVERED,
    MISSING,
    AMBIGUOUS,
    REJECTED
}

data class CapabilityDiscovery<T>(
    val status: DiscoveryStatus,
    val target: T? = null,
    val candidates: List<MethodSignature> = emptyList(),
    val conflicts: List<MethodSignature> = emptyList(),
    val rejectedCandidates: List<MethodSignature> = emptyList()
)

/**
 * Stateful evidence collector for the sole value-assisted fingerprint: the word limit.
 * A rejected observation can be retried; ambiguity is terminal for the current process.
 */
@ConsistentCopyVisibility
data class WordLimitDiscovery internal constructor(
    val status: DiscoveryStatus,
    val target: MethodSignature? = null,
    val candidates: List<MethodSignature> = emptyList(),
    val conflicts: List<MethodSignature> = emptyList(),
    val rejectedCandidates: List<MethodSignature> = emptyList(),
    private val matchingCandidates: List<MethodSignature> = emptyList()
) {
    fun observe(method: MethodSignature, result: Number): WordLimitDiscovery {
        if (status == DiscoveryStatus.AMBIGUOUS) return this
        if (method !in candidates) {
            return copy(
                status = if (target == null) DiscoveryStatus.REJECTED else status,
                rejectedCandidates = (rejectedCandidates + method).distinct()
            )
        }
        if (result.toLong() !in WORD_LIMIT_RANGE) {
            return if (target == null) {
                copy(
                    status = DiscoveryStatus.REJECTED,
                    rejectedCandidates = (rejectedCandidates + method).distinct()
                )
            } else {
                this
            }
        }

        val matches = (matchingCandidates + method).distinct()
        return if (matches.size == 1) {
            copy(
                status = DiscoveryStatus.DISCOVERED,
                target = matches.single(),
                conflicts = emptyList(),
                matchingCandidates = matches
            )
        } else {
            copy(
                status = DiscoveryStatus.AMBIGUOUS,
                target = null,
                conflicts = matches,
                matchingCandidates = matches
            )
        }
    }

    private companion object {
        val WORD_LIMIT_RANGE = 601L..19_999L
    }
}

data class StructuralDiscoveryResult(
    val wordLimit: WordLimitDiscovery,
    val display: CapabilityDiscovery<DisplayHookTargets>,
    val privilege: CapabilityDiscovery<PrivilegeHookTargets>,
    val cloud: CapabilityDiscovery<MethodSignature>
) {
    val targets: HookTargets = HookTargets(
        wordLimit = wordLimit.target.takeIf { wordLimit.status == DiscoveryStatus.DISCOVERED },
        display = display.target.takeIf { display.status == DiscoveryStatus.DISCOVERED },
        privilege = privilege.target.takeIf { privilege.status == DiscoveryStatus.DISCOVERED },
        cloud = cloud.target.takeIf { cloud.status == DiscoveryStatus.DISCOVERED }
    )
}

internal data class StructuralDiscoveryDiagnostics(
    val result: StructuralDiscoveryResult,
    val indexBuildCount: Int,
    val normalizedClassCount: Int,
    val normalizedMethodCount: Int
)

/** Pure structural matching. Android reflection and Xposed installation live outside this type. */
object StructuralHookDiscovery {
    private val displayShortParameters = listOf(HookTargets.TEXT_VIEW, HookTargets.INT)
    private val displayDetailedParameters = listOf(
        HookTargets.TEXT_VIEW,
        HookTargets.INT,
        HookTargets.BOOLEAN,
        HookTargets.FLOAT
    )
    private val privilegeSingleParameters = listOf(HookTargets.PRIVILEGE_CODE)
    private val privilegeDetailedParameters = listOf(
        HookTargets.PRIVILEGE_CODE,
        HookTargets.BOOLEAN
    )

    fun discover(
        classes: List<ClassDescriptor>,
        wordLimitResults: Map<MethodSignature, Number> = emptyMap()
    ): StructuralDiscoveryResult = discoverWithDiagnostics(classes, wordLimitResults).result

    internal fun discoverWithDiagnostics(
        classes: List<ClassDescriptor>,
        wordLimitResults: Map<MethodSignature, Number> = emptyMap()
    ): StructuralDiscoveryDiagnostics {
        val index = DiscoveryIndex.from(classes)
        var word = discoverWordLimit(index)
        word.candidates.forEach { candidate ->
            wordLimitResults[candidate]?.let { result -> word = word.observe(candidate, result) }
        }
        val result = StructuralDiscoveryResult(
            wordLimit = word,
            display = discoverDisplay(index),
            privilege = discoverPrivilege(index),
            cloud = discoverCloud(index)
        )
        return StructuralDiscoveryDiagnostics(
            result = result,
            indexBuildCount = 1,
            normalizedClassCount = index.classes.size,
            normalizedMethodCount = index.classes.sumOf { it.methods.size }
        )
    }

    fun discoverWordLimit(
        classes: List<ClassDescriptor>
    ): WordLimitDiscovery = discoverWordLimit(DiscoveryIndex.from(classes))

    private fun discoverWordLimit(index: DiscoveryIndex): WordLimitDiscovery {
        val fixedClassMethods = index.methodsFor(HookTargets.WORD_LIMIT_CLASS)
        if (fixedClassMethods.isEmpty()) {
            return WordLimitDiscovery(DiscoveryStatus.MISSING)
        }
        val exact = fixedClassMethods.filter(::isWordLimitShape)
        if (exact.isEmpty()) {
            return WordLimitDiscovery(
                status = DiscoveryStatus.REJECTED,
                rejectedCandidates = fixedClassMethods
            )
        }
        return WordLimitDiscovery(
            status = DiscoveryStatus.PENDING,
            candidates = exact,
            rejectedCandidates = fixedClassMethods.filterNot(::isWordLimitShape)
        )
    }

    fun discoverDisplay(
        classes: List<ClassDescriptor>
    ): CapabilityDiscovery<DisplayHookTargets> = discoverDisplay(DiscoveryIndex.from(classes))

    private fun discoverDisplay(
        index: DiscoveryIndex
    ): CapabilityDiscovery<DisplayHookTargets> {
        val relevantByClass = index.classes.mapNotNull { descriptor ->
            val relevant = descriptor.methods.filter(::isDisplayRelevant)
            relevant.takeIf { it.isNotEmpty() }?.let {
                Triple(descriptor.className, descriptor.methods, relevant)
            }
        }
        if (relevantByClass.isEmpty()) return CapabilityDiscovery(DiscoveryStatus.MISSING)

        val exactPairs = relevantByClass.mapNotNull { (_, allMethods, _) ->
            val short = allMethods.filter {
                it.isExact(displayShortParameters, HookTargets.VOID)
            }
            val detailed = allMethods.filter {
                it.isExact(displayDetailedParameters, HookTargets.VOID)
            }
            if (short.size == 1 && detailed.size == 1) {
                DisplayHookTargets(short.single(), detailed.single())
            } else {
                null
            }
        }
        return when {
            exactPairs.size == 1 -> CapabilityDiscovery(
                DiscoveryStatus.DISCOVERED,
                target = exactPairs.single(),
                candidates = exactPairs.single().methods
            )
            exactPairs.size > 1 -> CapabilityDiscovery(
                DiscoveryStatus.AMBIGUOUS,
                conflicts = exactPairs.flatMap { it.methods }
            )
            else -> CapabilityDiscovery(
                DiscoveryStatus.REJECTED,
                rejectedCandidates = relevantByClass.flatMap { it.third }.distinct()
            )
        }
    }

    fun discoverPrivilege(
        classes: List<ClassDescriptor>
    ): CapabilityDiscovery<PrivilegeHookTargets> =
        discoverPrivilege(DiscoveryIndex.from(classes))

    private fun discoverPrivilege(
        index: DiscoveryIndex
    ): CapabilityDiscovery<PrivilegeHookTargets> {
        val methods = index.methodsFor(HookTargets.PRIVILEGE_CLASS)
        if (methods.isEmpty()) return CapabilityDiscovery(DiscoveryStatus.MISSING)

        val relevant = methods.filter(::isPrivilegeRelevant)
        val singles = relevant.filter {
            it.isExact(privilegeSingleParameters, HookTargets.BOOLEAN)
        }
        val detailed = relevant.filter {
            it.isExact(privilegeDetailedParameters, HookTargets.BOOLEAN)
        }
        if (singles.size == 2 && detailed.size == 1 && relevant.size == 3) {
            val target = PrivilegeHookTargets(singles, detailed.single())
            return CapabilityDiscovery(
                DiscoveryStatus.DISCOVERED,
                target = target,
                candidates = target.methods
            )
        }
        return CapabilityDiscovery(
            DiscoveryStatus.REJECTED,
            rejectedCandidates = relevant.ifEmpty { methods }
        )
    }

    fun discoverCloud(
        classes: List<ClassDescriptor>
    ): CapabilityDiscovery<MethodSignature> = discoverCloud(DiscoveryIndex.from(classes))

    private fun discoverCloud(
        index: DiscoveryIndex
    ): CapabilityDiscovery<MethodSignature> {
        val relevantByClass = index.classes
            .filter { '.' !in it.className && '$' !in it.className }
            .mapNotNull { descriptor ->
                val relevant = descriptor.methods.filter(::isCloudRelevant)
                relevant.takeIf { it.isNotEmpty() }?.let {
                    Triple(descriptor.className, descriptor.methods, relevant)
                }
            }
        if (relevantByClass.isEmpty()) return CapabilityDiscovery(DiscoveryStatus.MISSING)

        val matches = relevantByClass.mapNotNull { (_, allMethods, _) ->
            val map = allMethods.filter {
                it.isExact(listOf(HookTargets.BOOLEAN), HookTargets.MAP)
            }
            val indexed = allMethods.filter {
                it.isExact(
                    listOf(HookTargets.MAP, HookTargets.INT),
                    HookTargets.INTEGER
                )
            }
            val accessor = allMethods.filter { it.isExact(emptyList(), HookTargets.INT) }
            val context = allMethods.filter {
                it.isExact(listOf(HookTargets.CONTEXT), HookTargets.BOOLEAN)
            }
            accessor.singleOrNull()?.takeIf {
                map.size == 1 && indexed.size == 1 && accessor.size == 1 && context.size == 1
            }
        }
        return when {
            matches.size == 1 -> CapabilityDiscovery(
                DiscoveryStatus.DISCOVERED,
                target = matches.single(),
                candidates = matches
            )
            matches.size > 1 -> CapabilityDiscovery(
                DiscoveryStatus.AMBIGUOUS,
                conflicts = matches
            )
            else -> CapabilityDiscovery(
                DiscoveryStatus.REJECTED,
                rejectedCandidates = relevantByClass.flatMap { it.third }.distinct()
            )
        }
    }

    private class DiscoveryIndex private constructor(
        val classes: List<IndexedClass>,
        private val methodsByClass: Map<String, List<MethodSignature>>
    ) {
        fun methodsFor(className: String): List<MethodSignature> =
            methodsByClass[className].orEmpty()

        companion object {
            fun from(classes: List<ClassDescriptor>): DiscoveryIndex {
                val methodsByClass = linkedMapOf<String, MutableSet<MethodSignature>>()
                classes.forEach { descriptor ->
                    methodsByClass.getOrPut(descriptor.className, ::linkedSetOf)
                        .addAll(descriptor.methods)
                }
                val normalized = methodsByClass.map { (className, methods) ->
                    IndexedClass(className, methods.toList())
                }
                return DiscoveryIndex(
                    classes = normalized,
                    methodsByClass = normalized.associate { descriptor ->
                        descriptor.className to descriptor.methods
                    }
                )
            }
        }
    }

    private data class IndexedClass(
        val className: String,
        val methods: List<MethodSignature>
    )

    private fun isWordLimitShape(method: MethodSignature): Boolean =
        method.className == HookTargets.WORD_LIMIT_CLASS &&
            method.isExact(emptyList(), HookTargets.INT)

    private fun isDisplayRelevant(method: MethodSignature): Boolean =
        method.parameterTypes == displayShortParameters ||
            method.parameterTypes == displayDetailedParameters ||
            method.parameterTypes.firstOrNull() == HookTargets.TEXT_VIEW

    private fun isPrivilegeRelevant(method: MethodSignature): Boolean =
        method.parameterTypes.firstOrNull() == HookTargets.PRIVILEGE_CODE

    private fun isCloudRelevant(method: MethodSignature): Boolean = when {
        method.parameterTypes == listOf(HookTargets.BOOLEAN) -> true
        method.parameterTypes.firstOrNull() == HookTargets.MAP -> true
        method.parameterTypes.isEmpty() &&
            method.returnType in setOf(HookTargets.INT, HookTargets.INTEGER) -> true
        method.parameterTypes == listOf(HookTargets.CONTEXT) -> true
        else -> false
    }

    private fun MethodSignature.isExact(
        parameters: List<String>,
        returnTypeName: String
    ): Boolean = isStatic && parameterTypes == parameters && returnType == returnTypeName
}
