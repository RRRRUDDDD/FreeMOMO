package com.rud.freemomo.hook

import com.rud.freemomo.util.DiscoverySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryRetryPolicyTest {
    private val inventory = ClassInventory(listOf("failed"), "same", true)

    @Test
    fun `each process has only attach first-resume and one delayed opportunity`() {
        val policy = DiscoveryRetryPolicy()
        val unknown = DiscoverySnapshot()
        val all = HookCapability.entries.toSet()
        fun select(trigger: DiscoveryTrigger) = policy.select(trigger, unknown, emptySet(), inventory)
        assertTrue(select(DiscoveryTrigger.AFTER_RESUME_DELAY).isEmpty())
        assertTrue(select(DiscoveryTrigger.FIRST_RESUME).isEmpty())
        assertEquals(all, select(DiscoveryTrigger.ATTACH))
        assertTrue(select(DiscoveryTrigger.ATTACH).isEmpty())
        assertEquals(all, select(DiscoveryTrigger.FIRST_RESUME))
        assertTrue(select(DiscoveryTrigger.FIRST_RESUME).isEmpty())
        assertEquals(all, select(DiscoveryTrigger.AFTER_RESUME_DELAY))
        assertTrue(select(DiscoveryTrigger.AFTER_RESUME_DELAY).isEmpty())
    }

    @Test
    fun `later launches probe unchanged failures without unconditionally rescanning`() {
        val pending = pendingSnapshot()
        val policy = DiscoveryRetryPolicy()
        DiscoveryTrigger.entries.forEach { trigger ->
            assertTrue(policy.select(trigger, pending, emptySet(), inventory).isEmpty())
        }
        assertEquals(3, pending.scanMetadata.getValue(HookCapability.DISPLAY).attempts)
    }

    @Test
    fun `recovery after attach can trigger the first resume scan`() {
        val pending = pendingSnapshot()
        val policy = DiscoveryRetryPolicy()
        assertTrue(policy.select(DiscoveryTrigger.ATTACH, pending, emptySet(), inventory).isEmpty())
        assertEquals(
            setOf(HookCapability.DISPLAY),
            policy.select(DiscoveryTrigger.FIRST_RESUME, pending, setOf("failed"), inventory)
        )
        assertEquals(
            setOf(HookCapability.DISPLAY),
            policy.select(DiscoveryTrigger.AFTER_RESUME_DELAY, pending, emptySet(), inventory)
        )
    }

    @Test
    fun `changed inventory retries an incomplete scan even if a prior class still fails`() {
        assertEquals(
            setOf(HookCapability.DISPLAY),
            DiscoveryRetryPolicy().select(
                DiscoveryTrigger.ATTACH, pendingSnapshot(), emptySet(), inventory.copy(fingerprint = "new")
            )
        )
    }

    @Test
    fun `cached installation failures still require readability or inventory recovery`() {
        val pending = pendingSnapshot().let { snapshot ->
            snapshot.copy(scanMetadata = mapOf(HookCapability.DISPLAY to
                snapshot.scanMetadata.getValue(HookCapability.DISPLAY).copy(retryReason = ScanRetryReason.INSTALLATION)))
        }
        assertTrue(DiscoveryRetryPolicy().select(
            DiscoveryTrigger.ATTACH, pending, emptySet(), inventory
        ).isEmpty())
        assertEquals(setOf(HookCapability.DISPLAY), DiscoveryRetryPolicy().select(
            DiscoveryTrigger.ATTACH, pending, setOf("failed"), inventory
        ))
    }

    @Test
    fun `complete negatives and installed capabilities are not selected`() {
        val known = DiscoverySnapshot.fromTargets(requireNotNull(HookTargets.builtIn(898)))
            .copy(display = CapabilityDiscovery(DiscoveryStatus.MISSING))
        assertTrue(DiscoveryRetryPolicy().select(DiscoveryTrigger.ATTACH, known, emptySet(), inventory).isEmpty())
    }

    @Test
    fun `inventory change invalidates cached uniqueness before installation`() {
        val known = DiscoverySnapshot.fromTargets(requireNotNull(HookTargets.builtIn(898)))
            .copy(scanMetadata = mapOf(HookCapability.DISPLAY to CapabilityScanMetadata(true, inventoryFingerprint = "same")))
        assertFalse(known.forInventory("same").requiresStructuralScan)
        val changed = known.forInventory("new")
        assertEquals(setOf(HookCapability.DISPLAY), changed.unknownCapabilities)
        assertEquals(ScanRetryReason.INVENTORY_CHANGED, changed.scanMetadata.getValue(HookCapability.DISPLAY).retryReason)
        assertEquals(setOf(HookCapability.DISPLAY), DiscoveryRetryPolicy().select(
            DiscoveryTrigger.ATTACH, changed, emptySet(), inventory.copy(fingerprint = "new")
        ))
    }

    private fun pendingSnapshot(): DiscoverySnapshot =
        DiscoverySnapshot.fromTargets(requireNotNull(HookTargets.builtIn(898))).copy(
            display = null,
            scanMetadata = mapOf(HookCapability.DISPLAY to CapabilityScanMetadata(
                complete = false,
                failedClasses = setOf("failed"),
                inventoryFingerprint = "same",
                attempts = 3,
                retryReason = ScanRetryReason.REFLECTION
            ))
        )
}
