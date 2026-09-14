package com.rud.freemomo.hook

import com.rud.freemomo.util.HookCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookDiscoveryPolicyTest {

    @Test
    fun `only verified versions expose complete exact targets`() {
        assertEquals(setOf(893, 898, 900), HookTargets.supportedVersionCodes())
        listOf(893, 898, 900).forEach { versionCode ->
            assertTrue(HookDiscoveryPolicy.isExactHookVersion(versionCode))
            assertEquals(4, requireNotNull(
                HookDiscoveryPolicy.exactTargets(versionCode)
            ).presentCapabilityCount)
        }
        listOf(899, 901).forEach { versionCode ->
            assertFalse(HookDiscoveryPolicy.isExactHookVersion(versionCode))
            assertNull(HookDiscoveryPolicy.exactTargets(versionCode))
        }
    }

    @Test
    fun `900 exact targets resolve without admitting extra privilege helpers`() {
        // Declarations observed on Momo 5.6.00 (900), independent of the built-in map.
        val privilegeClass = "com.maimemo.android.momo.user.level.a"
        val privilegeCode = "com.maimemo.android.momo.user.level.PrivilegeCode"
        val observed = listOf(
            MethodSignature("com.maimemo.android.momo.a", "s", emptyList(), "int", true),
            MethodSignature("dgb", "g", listOf("android.widget.TextView", "int"), "void", true),
            MethodSignature("dgb", "h", listOf(
                "android.widget.TextView", "int", "boolean", "float"
            ), "void", true),
            MethodSignature(privilegeClass, "k", listOf(privilegeCode), "boolean", true),
            MethodSignature(privilegeClass, "m", listOf(privilegeCode), "boolean", true),
            MethodSignature(privilegeClass, "l", listOf(privilegeCode, "boolean"), "boolean", true),
            MethodSignature(privilegeClass, "f", listOf(privilegeCode),
                "com.maimemo.android.momo.user.level.LevelPrivilege", false),
            MethodSignature(privilegeClass, "p", listOf(privilegeCode), "void", true),
            MethodSignature("ej7", "c", emptyList(), "int", true)
        )
        val privilegeMethods = observed.filter { it.className == privilegeClass }
        val structural = StructuralHookDiscovery.discoverPrivilege(
            listOf(ClassDescriptor(privilegeClass, privilegeMethods))
        )
        assertEquals(DiscoveryStatus.REJECTED, structural.status)

        val targets = requireNotNull(HookDiscoveryPolicy.exactTargets(900))
        assertEquals(targets, HookCache.validate(targets) { it in observed })
        assertEquals(setOf("k", "l", "m"), requireNotNull(targets.privilege)
            .methods.map { it.methodName }.toSet())

        val missingGate = HookCache.validate(targets) { it in observed && it.methodName != "l" }
        assertNull(missingGate.privilege)
        assertEquals(3, missingGate.presentCapabilityCount)
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
