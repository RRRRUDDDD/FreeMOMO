package com.rud.freemomo.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecNeoHookPolicyTest {

    @Test
    fun `is zero follows the normal application initialization path`() {
        assertTrue(SecNeoHookPolicy.shouldForceNormalPath("is", 0))
    }

    @Test
    fun `other native shell checks keep their original behavior`() {
        assertFalse(SecNeoHookPolicy.shouldForceNormalPath("is", 1))
        assertFalse(SecNeoHookPolicy.shouldForceNormalPath("cis", 0))
    }

    @Test
    fun `only the newly added protection wrappers are suppressed`() {
        assertTrue(SecNeoHookPolicy.shouldSuppressProtectionWrapper("callSW"))
        assertTrue(SecNeoHookPolicy.shouldSuppressProtectionWrapper("callBS"))
        assertFalse(SecNeoHookPolicy.shouldSuppressProtectionWrapper("pn"))
        assertFalse(SecNeoHookPolicy.shouldSuppressProtectionWrapper("hn"))
    }
}
