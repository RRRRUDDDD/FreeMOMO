package com.rud.freemomo.hook

import com.rud.freemomo.util.DiscoverySnapshot
import com.rud.freemomo.util.ThrowablePolicy

enum class HookCapability(val cacheKey: String, val fixedClass: String? = null) {
    WORD_LIMIT("word", HookTargets.WORD_LIMIT_CLASS),
    DISPLAY("display"),
    PRIVILEGE("privilege", HookTargets.PRIVILEGE_CLASS),
    CLOUD("cloud");

    val needsGlobalScan: Boolean get() = fixedClass == null
}

enum class ScanRetryReason { REFLECTION, ENUMERATION, INSTALLATION, INVENTORY_CHANGED }

/** Complete is evidence about the whole capability search space, not just its matches. */
data class CapabilityScanMetadata(
    val complete: Boolean,
    val failedClasses: Set<String> = emptySet(),
    val inventoryFingerprint: String? = null,
    val enumerationComplete: Boolean = true,
    val attempts: Int = 0,
    val retryReason: ScanRetryReason? = null
) {
    init {
        require(attempts >= 0)
        require(!complete || (failedClasses.isEmpty() && retryReason == null))
    }

    fun isCompleteFor(capability: HookCapability): Boolean =
        complete && (!capability.needsGlobalScan || enumerationComplete)
}

/** The fingerprint covers the entire exposed Dex inventory, including its class identities. */
data class ClassInventory(
    val classNames: List<String>,
    val fingerprint: String?,
    val complete: Boolean
)

data class DiscoveryScan(
    val result: StructuralDiscoveryResult,
    val metadata: Map<HookCapability, CapabilityScanMetadata>,
    val describedClassCount: Int = 0,
    val describedMethodCount: Int = 0
)

/** Reflection is injectable so temporary failures and their recovery can be exercised on a JVM. */
class DiscoveryScanner(private val describeClass: (String) -> ClassDescriptor) {
    fun probeFailed(snapshot: DiscoverySnapshot): Set<String> = snapshot.unknownCapabilities
        .flatMap { snapshot.scanMetadata[it]?.failedClasses.orEmpty() }
        .distinct()
        .filterTo(linkedSetOf()) { describeOrNull(it) != null }

    fun scan(capabilities: Set<HookCapability>, inventory: ClassInventory): DiscoveryScan {
        val global = capabilities.any { it.needsGlobalScan }
        val inventoryClasses = inventory.classNames.toSet()
        val requestedClasses = linkedSetOf<String>()
        if (global) requestedClasses.addAll(inventoryClasses)
        capabilities.mapNotNullTo(requestedClasses) { it.fixedClass }
        val failed = linkedSetOf<String>()
        val descriptors = requestedClasses.mapNotNull { className ->
            describeOrNull(className).also { if (it == null) failed += className }
        }
        val metadata = capabilities.associateWith { capability ->
            val failures = if (capability.needsGlobalScan) {
                failed.filterTo(linkedSetOf()) { it in inventoryClasses }
            } else {
                failed.filterTo(linkedSetOf()) { it == capability.fixedClass }
            }
            val enumerated = !capability.needsGlobalScan || inventory.complete
            CapabilityScanMetadata(
                complete = enumerated && failures.isEmpty(),
                failedClasses = failures,
                inventoryFingerprint = inventory.fingerprint,
                enumerationComplete = enumerated,
                retryReason = when {
                    !enumerated -> ScanRetryReason.ENUMERATION
                    failures.isNotEmpty() -> ScanRetryReason.REFLECTION
                    else -> null
                }
            )
        }
        val discovered = StructuralHookDiscovery.discover(descriptors, capabilities = capabilities)
        val safeResult = discovered.copy(
            wordLimit = if (metadata[HookCapability.WORD_LIMIT]?.complete == false) {
                WordLimitDiscovery(DiscoveryStatus.PENDING)
            } else discovered.wordLimit,
            display = if (metadata[HookCapability.DISPLAY]?.complete == false) {
                CapabilityDiscovery(DiscoveryStatus.PENDING)
            } else discovered.display,
            privilege = if (metadata[HookCapability.PRIVILEGE]?.complete == false) {
                CapabilityDiscovery(DiscoveryStatus.PENDING)
            } else discovered.privilege,
            cloud = if (metadata[HookCapability.CLOUD]?.complete == false) {
                CapabilityDiscovery(DiscoveryStatus.PENDING)
            } else discovered.cloud
        )
        return DiscoveryScan(
            safeResult,
            metadata,
            descriptors.size,
            descriptors.sumOf { it.uniqueMethods.size }
        )
    }

    private fun describeOrNull(className: String): ClassDescriptor? = try {
        describeClass(className)
    } catch (error: Throwable) {
        ThrowablePolicy.rethrowIfFatal(error)
        null
    }
}

enum class DiscoveryTrigger { ATTACH, FIRST_RESUME, AFTER_RESUME_DELAY }

/** One attach attempt and exactly two lifecycle opportunities; repeated resumes add no budget. */
class DiscoveryRetryPolicy {
    private var attachClaimed = false
    private var resumeClaimed = false
    private var delayClaimed = false
    private var scannedThisLaunch = false

    @Synchronized
    fun select(
        trigger: DiscoveryTrigger,
        snapshot: DiscoverySnapshot,
        readableFailures: Set<String>,
        inventory: ClassInventory
    ): Set<HookCapability> {
        if (!claim(trigger)) return emptySet()
        val unknown = snapshot.unknownCapabilities
        val selected = if (scannedThisLaunch) {
            unknown
        } else {
            unknown.filterTo(linkedSetOf()) { capability ->
                val prior = snapshot.scanMetadata[capability]
                prior == null || prior.complete ||
                    prior.failedClasses.any { it in readableFailures } ||
                    (inventory.fingerprint != null &&
                        inventory.fingerprint != prior.inventoryFingerprint) ||
                    (!prior.enumerationComplete && inventory.complete)
            }
        }
        if (selected.isNotEmpty()) scannedThisLaunch = true
        return selected
    }

    private fun claim(trigger: DiscoveryTrigger): Boolean = when (trigger) {
        DiscoveryTrigger.ATTACH -> (!attachClaimed).also { attachClaimed = true }
        DiscoveryTrigger.FIRST_RESUME -> (attachClaimed && !resumeClaimed).also {
            if (attachClaimed) resumeClaimed = true
        }
        DiscoveryTrigger.AFTER_RESUME_DELAY -> (resumeClaimed && !delayClaimed).also {
            if (resumeClaimed) delayClaimed = true
        }
    }
}
