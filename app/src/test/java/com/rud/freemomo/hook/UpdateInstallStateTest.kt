package com.rud.freemomo.hook

import org.junit.Assert.*
import org.junit.Test

class UpdateInstallStateTest {
    private val ids = setOf("dispatch", "download", "install")
    private val ignoreFailure: (String, Throwable) -> Unit = { _, _ -> }
    private val unavailable: () -> Unit = { throw ClassNotFoundException("not ready") }

    @Test fun failedManualProfileKeepsIndependentCoreGuardsClosed() {
        val state = UpdateInstallState(ids, true)
        val guards = mutableSetOf<String>()
        assertEquals(UpdateInstallState.Status.GUARDED,
            state.attempt(DiscoveryTrigger.ATTACH, { guards += it }, unavailable, ignoreFailure))
        assertEquals(ids, guards)
        assertFalse(state.manualReady)
        assertTrue(state.needsRetry)
    }

    @Test fun lateClassAvailabilityRetriesOnlyMissingGuardsAndEnablesManualAdapter() {
        val state = UpdateInstallState(ids, true)
        val calls = mutableListOf<String>()
        val failed = mutableListOf<String>()
        state.attempt(DiscoveryTrigger.ATTACH, {
            calls += it
            if (it == "download") throw ClassNotFoundException()
        }, unavailable) { stage, _ -> failed += stage }
        assertEquals(UpdateInstallState.Status.PARTIAL, state.status)
        assertEquals(listOf("guard:download", "manual"), failed)
        assertEquals(UpdateInstallState.Status.MANUAL_READY,
            state.attempt(DiscoveryTrigger.FIRST_RESUME, { calls += it }, {}, ignoreFailure))
        assertEquals(listOf("dispatch", "download", "install", "download"), calls)
        assertTrue(state.manualReady)
        assertFalse(state.needsRetry)
        state.attempt(DiscoveryTrigger.AFTER_RESUME_DELAY, { error("duplicate guard") },
            { error("duplicate adapter") }, ignoreFailure)
    }

    @Test fun retryBudgetIsBoundedAndOutOfOrderOrRepeatedEventsDoNotSpendIt() {
        val state = UpdateInstallState(ids, true)
        var attempts = 0
        val fail: () -> Unit = { attempts++; unavailable() }
        state.attempt(DiscoveryTrigger.FIRST_RESUME, {}, fail, ignoreFailure)
        state.attempt(DiscoveryTrigger.AFTER_RESUME_DELAY, {}, fail, ignoreFailure)
        assertEquals(0, attempts)
        DiscoveryTrigger.values().forEach { trigger ->
            repeat(3) { state.attempt(trigger, {}, fail, ignoreFailure) }
        }
        assertEquals(3, attempts)
        assertFalse(state.needsRetry)
        assertEquals(UpdateInstallState.Status.GUARDED, state.status)
    }

    @Test fun unsupportedVersionsNeverInstallAnyGuardOrRetry() {
        val state = UpdateInstallState(ids, false)
        DiscoveryTrigger.values().forEach { trigger ->
            assertEquals(UpdateInstallState.Status.UNSUPPORTED,
                state.attempt(trigger, { error("unverified version") }, { error("unverified version") }, ignoreFailure))
        }
        assertFalse(state.needsRetry)
    }

    @Test fun zeroWorkingGuardsMustNotClaimProtection() {
        val state = UpdateInstallState(ids, true)
        assertEquals(UpdateInstallState.Status.UNAVAILABLE,
            state.attempt(DiscoveryTrigger.ATTACH, { throw NoSuchMethodException() }, unavailable, ignoreFailure))
    }

    @Test fun completeManualAdapterWorksEvenIfRedundantGuardRegistrationFailed() {
        val state = UpdateInstallState(ids, true)
        assertEquals(UpdateInstallState.Status.MANUAL_READY,
            state.attempt(DiscoveryTrigger.ATTACH, { throw IllegalStateException() }, {}, ignoreFailure))
        assertTrue(state.manualReady)
    }

    @Test fun reentrantLifecycleCannotDuplicateAnInProgressRegistration() {
        val state = UpdateInstallState(ids, true)
        var registrations = 0
        var adapters = 0
        state.attempt(DiscoveryTrigger.ATTACH, {
            registrations++
            state.attempt(DiscoveryTrigger.FIRST_RESUME, { error("reentrant guard") },
                { error("reentrant adapter") }, ignoreFailure)
        }, { adapters++; unavailable() }, ignoreFailure)
        assertEquals(3, registrations)
        assertEquals(1, adapters)
        state.attempt(DiscoveryTrigger.FIRST_RESUME, { error("already installed") }, { adapters++ }, ignoreFailure)
        assertEquals(2, adapters)
    }

    @Test fun fatalRegistrationErrorsAreNotReportedAsRecoverable() {
        val state = UpdateInstallState(ids, true)
        assertThrows(OutOfMemoryError::class.java) {
            state.attempt(DiscoveryTrigger.ATTACH, { throw OutOfMemoryError() },
                { error("must not install") }, { _, _ -> fail("fatal swallowed") })
        }
        assertFalse(state.manualReady)
    }

    @Test fun guardsStayClosedDuringManualRegistrationAndAfterRegistrationFailure() {
        val state = UpdateInstallState(ids, true)
        state.attempt(DiscoveryTrigger.ATTACH, {}, {
            assertFalse(state.manualReady)
            throw IllegalStateException("hook registration failed")
        }, ignoreFailure)
        assertFalse(state.manualReady)
        state.attempt(DiscoveryTrigger.FIRST_RESUME, {}, {
            assertFalse(state.manualReady)
        }, ignoreFailure)
        assertTrue(state.manualReady)
    }
}
