package com.rud.freemomo.hook

import org.junit.Assert.*
import org.junit.Test

class UpdateHookGroupTest {
    @Test fun registrationFailureRollsBackInReverseOrderAndLeavesCallbacksInert() {
        val released = mutableListOf<Int>()
        val group = UpdateHookGroup { fail("unexpected unhook error") }
        try {
            group.add(HookUnhook { released += 1 })
            group.add(HookUnhook { released += 2 })
            assertFalse(group.active)
            error("third registration failed")
        } catch (_: IllegalStateException) {
            group.rollback()
        }
        assertEquals(listOf(2, 1), released)
        assertFalse(group.active)
        group.rollback()
        assertEquals(listOf(2, 1), released)
    }

    @Test fun activationOnlyOccursAfterAllHandlesExistAndRollbackRevokesItFirst() {
        val group = UpdateHookGroup { fail("unexpected unhook error") }
        group.add(HookUnhook { assertFalse(group.active) })
        assertFalse(group.active)
        group.activate()
        assertTrue(group.active)
        group.rollback()
        assertFalse(group.active)
    }

    @Test fun failedUnhookDoesNotPreventRemainingCleanupOrReactivateOldGroup() {
        val failures = mutableListOf<Throwable>()
        val released = mutableListOf<Int>()
        val old = UpdateHookGroup { failures += it }
        old.add(HookUnhook { released += 1 })
        old.add(HookUnhook { released += 2; error("cannot unhook") })
        old.activate()
        old.rollback()
        val retry = UpdateHookGroup { fail("unexpected error") }
        retry.add(HookUnhook {})
        retry.activate()
        assertTrue(retry.active)
        assertFalse(old.active)
        assertEquals(listOf(2, 1), released)
        assertEquals(1, failures.size)
    }

    @Test fun fatalUnhookStillReleasesRemainingHandlesAndPropagates() {
        var remainingReleased = false
        val group = UpdateHookGroup { fail("fatal must propagate") }
        group.add(HookUnhook { remainingReleased = true })
        group.add(HookUnhook { throw OutOfMemoryError() })
        group.activate()
        assertThrows(OutOfMemoryError::class.java) { group.rollback() }
        assertTrue(remainingReleased)
        assertFalse(group.active)
        group.rollback()
    }
}
