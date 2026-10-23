package com.rud.freemomo.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordLimitStateTest {

    @Test
    fun `first positive value wins and non-positive values are ignored`() {
        assertNull(WordLimitState.originalLimit)
        WordLimitState.recordOriginalLimit(0)
        WordLimitState.recordOriginalLimit(-5)
        assertNull(WordLimitState.originalLimit)
        WordLimitState.recordOriginalLimit(671)
        assertEquals(671, WordLimitState.originalLimit)
        // Later reads observe the replacement, never the original: first value sticks.
        WordLimitState.recordOriginalLimit(99666)
        assertEquals(671, WordLimitState.originalLimit)
    }
}
