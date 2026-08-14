package com.rud.freemomo.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookDiscoveryPolicyTest {

    @Test
    fun `only 893 and 898 expose complete exact targets`() {
        assertEquals(setOf(893, 898), HookTargets.supportedVersionCodes())
        assertTrue(HookDiscoveryPolicy.isExactHookVersion(893))
        assertTrue(HookDiscoveryPolicy.isExactHookVersion(898))
        assertFalse(HookDiscoveryPolicy.isExactHookVersion(899))
        assertEquals(4, requireNotNull(HookDiscoveryPolicy.exactTargets(893)).presentCapabilityCount)
        assertEquals(4, requireNotNull(HookDiscoveryPolicy.exactTargets(898)).presentCapabilityCount)
        assertNull(HookDiscoveryPolicy.exactTargets(899))
    }

    @Test
    fun `word limit evidence keeps strict trusted bounds`() {
        assertFalse(HookDiscoveryPolicy.isWordLimitCandidate(600))
        assertTrue(HookDiscoveryPolicy.isWordLimitCandidate(601))
        assertTrue(HookDiscoveryPolicy.isWordLimitCandidate(19_999))
        assertFalse(HookDiscoveryPolicy.isWordLimitCandidate(20_000))
        assertEquals(99666, HookDiscoveryPolicy.wordLimitReplacement(670))
        assertNull(HookDiscoveryPolicy.wordLimitReplacement(37_515_930))
    }

    @Test
    fun `numeric level range is restricted to an already discovered display boundary`() {
        assertNull(HookDiscoveryPolicy.displayedLevelReplacement(0))
        assertEquals(21, HookDiscoveryPolicy.displayedLevelReplacement(1))
        assertEquals(21, HookDiscoveryPolicy.displayedLevelReplacement(20))
        assertNull(HookDiscoveryPolicy.displayedLevelReplacement(21))
    }

    @Test
    fun `known targets preserve all verified signatures`() {
        val target893 = requireNotNull(HookTargets.builtIn(893))
        val target898 = requireNotNull(HookTargets.builtIn(898))

        assertEquals("r", requireNotNull(target893.wordLimit).methodName)
        assertEquals("s", requireNotNull(target898.wordLimit).methodName)
        assertEquals("q28", requireNotNull(target893.display).twoArgumentMethod.className)
        assertEquals("n48", requireNotNull(target898.display).twoArgumentMethod.className)
        assertEquals(listOf("l", "n"), requireNotNull(target893.privilege)
            .singleArgumentMethods.map { it.methodName })
        assertEquals("m", target893.privilege.twoArgumentMethod.methodName)
        assertEquals(listOf("k", "m"), requireNotNull(target898.privilege)
            .singleArgumentMethods.map { it.methodName })
        assertEquals("l", target898.privilege.twoArgumentMethod.methodName)
        assertEquals("g85", requireNotNull(target893.cloud).className)
        assertEquals("y95", requireNotNull(target898.cloud).className)
    }
}
