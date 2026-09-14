package com.rud.freemomo.util

import com.rud.freemomo.hook.CapabilityDiscovery
import com.rud.freemomo.hook.CapabilityScanMetadata
import com.rud.freemomo.hook.DiscoveryScan
import com.rud.freemomo.hook.DiscoveryStatus
import com.rud.freemomo.hook.DisplayHookTargets
import com.rud.freemomo.hook.HookCapability
import com.rud.freemomo.hook.HookTargets
import com.rud.freemomo.hook.MethodSignature
import com.rud.freemomo.hook.PrivilegeHookTargets
import com.rud.freemomo.hook.ScanRetryReason
import com.rud.freemomo.hook.WordLimitDiscovery

/** Unknown capabilities retain retry evidence; only complete scans can publish terminal states. */
data class DiscoverySnapshot(
    val wordLimit: WordLimitDiscovery? = null,
    val display: CapabilityDiscovery<DisplayHookTargets>? = null,
    val privilege: CapabilityDiscovery<PrivilegeHookTargets>? = null,
    val cloud: CapabilityDiscovery<MethodSignature>? = null,
    val scanMetadata: Map<HookCapability, CapabilityScanMetadata> = emptyMap()
) {
    val targets: HookTargets = HookTargets(
        wordLimit = wordLimit?.target.takeIf { wordLimit?.status == DiscoveryStatus.DISCOVERED },
        display = display?.target.takeIf { display?.status == DiscoveryStatus.DISCOVERED },
        privilege = privilege?.target.takeIf { privilege?.status == DiscoveryStatus.DISCOVERED },
        cloud = cloud?.target.takeIf { cloud?.status == DiscoveryStatus.DISCOVERED }
    )

    val unknownCapabilities: Set<HookCapability>
        get() = HookCapability.entries.filterTo(linkedSetOf()) {
            when (it) {
                HookCapability.WORD_LIMIT -> wordLimit == null
                HookCapability.DISPLAY -> display == null
                HookCapability.PRIVILEGE -> privilege == null
                HookCapability.CLOUD -> cloud == null
            }
        }

    val requiresStructuralScan: Boolean get() = unknownCapabilities.isNotEmpty()

    val hasPendingWordObservation: Boolean
        get() = wordLimit?.status == DiscoveryStatus.PENDING && wordLimit.candidates.isNotEmpty()

    /** Called only while holding the coordinator monitor, against its latest snapshot. */
    fun fillUnknown(scan: DiscoveryScan): DiscoverySnapshot {
        val accepted = unknownCapabilities.intersect(scan.metadata.keys)
        val complete = accepted.filterTo(linkedSetOf()) { scan.metadata.getValue(it).isCompleteFor(it) }
        return copy(
            wordLimit = if (HookCapability.WORD_LIMIT in complete) scan.result.wordLimit else wordLimit,
            display = if (HookCapability.DISPLAY in complete) scan.result.display else display,
            privilege = if (HookCapability.PRIVILEGE in complete) scan.result.privilege else privilege,
            cloud = if (HookCapability.CLOUD in complete) scan.result.cloud else cloud,
            scanMetadata = scanMetadata + accepted.associateWith { capability ->
                scan.metadata.getValue(capability).copy(
                    attempts = ((scanMetadata[capability]?.attempts ?: 0).toLong() + 1)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            }
        )
    }

    fun clear(capabilities: Set<HookCapability>): DiscoverySnapshot = copy(
        wordLimit = wordLimit.takeUnless { HookCapability.WORD_LIMIT in capabilities },
        display = display.takeUnless { HookCapability.DISPLAY in capabilities },
        privilege = privilege.takeUnless { HookCapability.PRIVILEGE in capabilities },
        cloud = cloud.takeUnless { HookCapability.CLOUD in capabilities }
    )

    /** A changed Dex inventory invalidates previous global uniqueness and negative evidence. */
    fun forInventory(fingerprint: String?): DiscoverySnapshot {
        if (fingerprint == null) return this
        val changed = scanMetadata.filter { (_, metadata) ->
            metadata.inventoryFingerprint != null && metadata.inventoryFingerprint != fingerprint
        }.keys
        return clear(changed).copy(
            scanMetadata = scanMetadata + changed.associateWith { capability ->
                scanMetadata.getValue(capability).copy(
                    complete = false,
                    retryReason = ScanRetryReason.INVENTORY_CHANGED
                )
            }
        )
    }

    /** Requested targets stay cached only after exactly those hooks have installed. */
    fun reconcileInstall(requested: HookTargets, installed: HookTargets): DiscoverySnapshot {
        val failed = linkedMapOf<HookCapability, Set<String>>()
        if (requested.wordLimit != null && installed.wordLimit != requested.wordLimit) {
            failed[HookCapability.WORD_LIMIT] = setOf(requested.wordLimit.className)
        }
        if (requested.display != null && installed.display != requested.display) {
            failed[HookCapability.DISPLAY] = requested.display.methods.mapTo(linkedSetOf()) { it.className }
        }
        if (requested.privilege != null && installed.privilege != requested.privilege) {
            failed[HookCapability.PRIVILEGE] = requested.privilege.methods.mapTo(linkedSetOf()) { it.className }
        }
        if (requested.cloud != null && installed.cloud != requested.cloud) {
            failed[HookCapability.CLOUD] = setOf(requested.cloud.className)
        }
        return installationFailed(failed)
    }

    fun installationFailed(failed: Map<HookCapability, Set<String>>): DiscoverySnapshot =
        clear(failed.keys).copy(
            scanMetadata = scanMetadata + failed.mapValues { (capability, classes) ->
                (scanMetadata[capability] ?: CapabilityScanMetadata(complete = false)).copy(
                    complete = false,
                    failedClasses = classes,
                    retryReason = ScanRetryReason.INSTALLATION
                )
            }
        )

    companion object {
        fun fromTargets(targets: HookTargets): DiscoverySnapshot = DiscoverySnapshot(
            wordLimit = targets.wordLimit?.let {
                WordLimitDiscovery(DiscoveryStatus.DISCOVERED, target = it, candidates = listOf(it))
            },
            display = targets.display?.let {
                CapabilityDiscovery(DiscoveryStatus.DISCOVERED, target = it, candidates = it.methods)
            },
            privilege = targets.privilege?.let {
                CapabilityDiscovery(DiscoveryStatus.DISCOVERED, target = it, candidates = it.methods)
            },
            cloud = targets.cloud?.let {
                CapabilityDiscovery(DiscoveryStatus.DISCOVERED, target = it, candidates = listOf(it))
            },
            scanMetadata = buildMap {
                val complete = CapabilityScanMetadata(complete = true)
                if (targets.wordLimit != null) put(HookCapability.WORD_LIMIT, complete)
                if (targets.display != null) put(HookCapability.DISPLAY, complete)
                if (targets.privilege != null) put(HookCapability.PRIVILEGE, complete)
                if (targets.cloud != null) put(HookCapability.CLOUD, complete)
            }
        )
    }
}
