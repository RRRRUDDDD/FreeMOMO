package com.rud.freemomo.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookNotificationStateTest {

    @Test
    fun `found notification is shown when no version has been notified`() {
        assertTrue(HookNotificationState.shouldShowFound(null, 898))
    }

    @Test
    fun `found notification is suppressed for the already notified version`() {
        assertFalse(HookNotificationState.shouldShowFound(898, 898))
    }

    @Test
    fun `found notification is shown after target version changes`() {
        assertTrue(HookNotificationState.shouldShowFound(893, 898))
    }
}
