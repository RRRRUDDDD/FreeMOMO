package com.rud.freemomo.hook

import com.rud.freemomo.util.DiscoverySnapshot
import com.rud.freemomo.util.HookCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiscoveryCoordinatorTest {
    private val known = requireNotNull(HookTargets.builtIn(898))
    private val first = requireNotNull(known.wordLimit)
    private val second = first.copy(methodName = "peer")

    @Test
    fun `scan prepared before observation cannot revert discovered word state`() {
        assertObservationWins(ambiguous = false)
    }

    @Test
    fun `scan prepared before observation cannot revert terminal ambiguity`() {
        assertObservationWins(ambiguous = true)
    }

    private fun assertObservationWins(ambiguous: Boolean) {
        val harness = Harness()
        harness.coordinator.start()
        val scanPrepared = CountDownLatch(1)
        val publishAllowed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val publication = executor.submit {
                val staleScan = scan()
                assertEquals(DiscoveryStatus.PENDING, harness.coordinator.state().snapshot.wordLimit?.status)
                scanPrepared.countDown()
                await(publishAllowed)
                harness.coordinator.publish(staleScan)
            }
            await(scanPrepared)
            assertEquals(HookDiscoveryPolicy.WORD_LIMIT_REPLACEMENT, harness.observe(first))
            assertTrue(harness.released.isEmpty())
            if (ambiguous) assertEquals(670, harness.observe(second))
            publishAllowed.countDown()
            publication.get(10, TimeUnit.SECONDS)

            val final = harness.coordinator.state()
            assertEquals(if (ambiguous) DiscoveryStatus.AMBIGUOUS else DiscoveryStatus.DISCOVERED,
                final.snapshot.wordLimit?.status)
            assertEquals(if (ambiguous) null else first, final.installed.wordLimit)
            assertEquals(known.display, final.installed.display)
            assertEquals(final.snapshot.targets, final.installed)
            assertEquals(final.snapshot, harness.persisted.last())
            assertEquals(final.installed, HookCache.decode(HookCache.encode(final.snapshot, 899), 899)?.targets)
            assertEquals(1, harness.installations.size)
            assertNull(harness.installations.single().wordLimit)
            if (ambiguous) {
                assertEquals(setOf(first, second), harness.released.toSet())
                assertEquals(670, harness.observe(first))
                assertEquals(final, harness.coordinator.state())
            }
        } finally {
            publishAllowed.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `observation waiting on installation commits after the scan and persists last`() {
        val installerEntered = CountDownLatch(1)
        val finishInstall = CountDownLatch(1)
        val observationStarted = CountDownLatch(1)
        val harness = Harness { requested ->
            installerEntered.countDown()
            await(finishInstall)
            requested
        }
        harness.coordinator.start()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publication = executor.submit { harness.coordinator.publish(scan()) }
            await(installerEntered)
            val observation = executor.submit<Int> {
                observationStarted.countDown()
                harness.observe(first)
            }
            await(observationStarted)
            finishInstall.countDown()
            publication.get(10, TimeUnit.SECONDS)
            assertEquals(HookDiscoveryPolicy.WORD_LIMIT_REPLACEMENT, observation.get(10, TimeUnit.SECONDS))
            val final = harness.coordinator.state()
            assertEquals(final.installed, final.snapshot.targets)
            assertEquals(first, final.installed.wordLimit)
            assertEquals(known.display, final.installed.display)
            assertEquals(final.snapshot, harness.persisted.last())
            assertEquals(1, harness.installations.size)
        } finally {
            finishInstall.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `nested installer callbacks merge current state and commit only the final transaction`() {
        listOf(false, true).forEach { ambiguous ->
            lateinit var harness: Harness
            harness = Harness { requested ->
                harness.observe(first)
                if (ambiguous) harness.observe(second)
                requested
            }
            harness.coordinator.start()
            assertEquals(1, harness.persisted.size)
            harness.coordinator.publish(scan())
            val final = harness.coordinator.state()
            assertEquals(2, harness.persisted.size)
            assertEquals(final.snapshot, harness.persisted.last())
            assertEquals(final.installed, final.snapshot.targets)
            assertEquals(if (ambiguous) DiscoveryStatus.AMBIGUOUS else DiscoveryStatus.DISCOVERED,
                final.snapshot.wordLimit?.status)
        }
    }

    @Test
    fun `reentrant persistence callbacks finish by writing the latest observed state`() {
        listOf(false, true).forEach { ambiguous ->
            val persisted = mutableListOf<DiscoverySnapshot>()
            val callbacks = mutableMapOf<MethodSignature, WordResultObserver>()
            var observedDuringPersistence = false
            var installs = 0
            val coordinator = DiscoveryCoordinator(
                initial(),
                installTargets = { installs++; it },
                installWordObservers = { pending, group ->
                    group.install(pending.candidates) { method, callback ->
                        callbacks[method] = callback
                        HookUnhook {}
                    }
                },
                persist = { snapshot ->
                    if (snapshot.display?.target != null && !observedDuringPersistence) {
                        observedDuringPersistence = true
                        callbacks.getValue(first)(670) {}
                        if (ambiguous) callbacks.getValue(second)(670) {}
                    }
                    persisted += snapshot
                }
            )
            coordinator.start()
            val final = coordinator.publish(scan())
            assertEquals(final.snapshot, persisted.last())
            assertEquals(final.installed, final.snapshot.targets)
            assertEquals(if (ambiguous) DiscoveryStatus.AMBIGUOUS else DiscoveryStatus.DISCOVERED,
                final.snapshot.wordLimit?.status)
            assertEquals(1, installs)
        }
    }

    @Test
    fun `failed installation persists unknown with retry metadata and does not claim installed`() {
        val harness = Harness { HookTargets() }
        harness.coordinator.start()
        val final = harness.coordinator.publish(scan())
        assertNull(final.snapshot.display)
        assertNull(final.installed.display)
        assertEquals(ScanRetryReason.INSTALLATION,
            final.snapshot.scanMetadata.getValue(HookCapability.DISPLAY).retryReason)
        assertEquals(final.snapshot, harness.persisted.last())
        assertEquals(1, harness.installations.size)
    }

    @Test
    fun `observer rollback makes word unknown without publishing any partial discovery`() {
        val persisted = mutableListOf<DiscoverySnapshot>()
        val released = mutableListOf<MethodSignature>()
        val callbacks = mutableListOf<WordResultObserver>()
        val coordinator = DiscoveryCoordinator(
            initial(),
            installTargets = { it },
            installWordObservers = { pending, group ->
                group.install(pending.candidates) { method, callback ->
                    callbacks += callback
                    callback(670) { error("Registration must not replace results") }
                    if (method == second) error("registration failure")
                    HookUnhook { released += method }
                }
            },
            persist = { persisted += it }
        )
        val final = coordinator.start()
        assertNull(final.snapshot.wordLimit)
        assertNull(final.installed.wordLimit)
        assertEquals(listOf(first), released)
        assertEquals(ScanRetryReason.INSTALLATION,
            final.snapshot.scanMetadata.getValue(HookCapability.WORD_LIMIT).retryReason)
        callbacks.forEach { callback -> callback(670) { error("Rolled-back callback is closed") } }
        assertEquals(listOf(final.snapshot), persisted)
    }

    @Test
    fun `terminal observation during completed registration is not mistaken for rollback`() {
        val persisted = mutableListOf<DiscoverySnapshot>()
        val released = mutableListOf<MethodSignature>()
        val coordinator = DiscoveryCoordinator(
            initial(),
            installTargets = { it },
            installWordObservers = { pending, group ->
                val callbacks = mutableListOf<WordResultObserver>()
                val success = group.install(pending.candidates) { method, callback ->
                    callbacks += callback
                    HookUnhook { released += method }
                }
                callbacks.forEach { it(670) {} }
                success
            },
            persist = { persisted += it }
        )
        val final = coordinator.start()
        assertEquals(DiscoveryStatus.AMBIGUOUS, final.snapshot.wordLimit?.status)
        assertNull(final.installed.wordLimit)
        assertEquals(listOf(second, first), released)
        assertEquals(listOf(final.snapshot), persisted)
    }

    @Test
    fun `out of range observations remain retryable and first discovery keeps other observers`() {
        val harness = Harness()
        harness.coordinator.start()
        assertEquals(42, harness.observe(first, 42))
        assertEquals(DiscoveryStatus.PENDING, harness.coordinator.state().snapshot.wordLimit?.status)
        assertEquals(HookDiscoveryPolicy.WORD_LIMIT_REPLACEMENT, harness.observe(first))
        assertTrue(harness.released.isEmpty())
        assertEquals(670, harness.observe(second))
        assertEquals(DiscoveryStatus.AMBIGUOUS, harness.coordinator.state().snapshot.wordLimit?.status)
        assertEquals(listOf(second, first), harness.released)
        assertEquals(670, harness.observe(first))
    }

    @Test
    fun `incomplete scan cannot be installed or persisted as a unique target`() {
        val harness = Harness()
        harness.coordinator.start()
        val incomplete = scan().copy(metadata = mapOf(HookCapability.DISPLAY to CapabilityScanMetadata(
            complete = false,
            failedClasses = setOf("notReadable"),
            inventoryFingerprint = "same",
            retryReason = ScanRetryReason.REFLECTION
        )))
        val final = harness.coordinator.publish(incomplete)
        assertNull(final.snapshot.display)
        assertTrue(harness.installations.isEmpty())
        assertFalse(final.snapshot.scanMetadata.getValue(HookCapability.DISPLAY).complete)
        assertEquals(final.snapshot, harness.persisted.last())
    }

    private inner class Harness(var install: (HookTargets) -> HookTargets = { it }) {
        val persisted = mutableListOf<DiscoverySnapshot>()
        val released = mutableListOf<MethodSignature>()
        val installations = mutableListOf<HookTargets>()
        val callbacks = mutableMapOf<MethodSignature, WordResultObserver>()
        val coordinator = DiscoveryCoordinator(
            initial = initial(),
            installTargets = { requested ->
                installations += requested
                install(requested)
            },
            installWordObservers = { pending, group ->
                group.install(pending.candidates) { method, callback ->
                    callbacks[method] = callback
                    HookUnhook { released += method }
                }
            },
            persist = { persisted += it }
        )

        fun observe(method: MethodSignature, value: Int = 670): Int {
            var result = value
            callbacks.getValue(method)(value) { result = it }
            return result
        }
    }

    private fun initial(): DiscoverySnapshot = DiscoverySnapshot(
        wordLimit = WordLimitDiscovery(DiscoveryStatus.PENDING, candidates = listOf(first, second)),
        privilege = CapabilityDiscovery(DiscoveryStatus.MISSING),
        cloud = CapabilityDiscovery(DiscoveryStatus.MISSING),
        scanMetadata = setOf(HookCapability.WORD_LIMIT, HookCapability.PRIVILEGE, HookCapability.CLOUD)
            .associateWith { CapabilityScanMetadata(complete = true, inventoryFingerprint = "same", attempts = 1) }
    )

    private fun scan(): DiscoveryScan = DiscoveryScan(
        StructuralDiscoveryResult(
            wordLimit = requireNotNull(initial().wordLimit),
            display = CapabilityDiscovery(DiscoveryStatus.DISCOVERED,
                target = requireNotNull(known.display), candidates = requireNotNull(known.display).methods),
            privilege = CapabilityDiscovery(DiscoveryStatus.MISSING),
            cloud = CapabilityDiscovery(DiscoveryStatus.MISSING)
        ),
        HookCapability.entries.associateWith { CapabilityScanMetadata(true, inventoryFingerprint = "same") }
    )

    private fun await(latch: CountDownLatch) {
        assertTrue("Timed out waiting for deterministic test interleaving", latch.await(10, TimeUnit.SECONDS))
    }
}
