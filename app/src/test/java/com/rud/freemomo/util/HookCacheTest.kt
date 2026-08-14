package com.rud.freemomo.util

import com.rud.freemomo.hook.HookTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookCacheTest {

    @Test
    fun `all known signatures round trip without losing declaration order`() {
        listOf(893, 898).forEach { versionCode ->
            val targets = requireNotNull(HookTargets.builtIn(versionCode))
            assertEquals(
                targets,
                HookCache.decode(HookCache.encode(targets, versionCode), versionCode)
            )
        }
    }

    @Test
    fun `legacy missing schema and changed version are rejected`() {
        assertNull(HookCache.decode(mapOf("version_code" to "899"), 899))

        val encoded = HookCache.encode(requireNotNull(HookTargets.builtIn(898)), 898)
        assertNull(HookCache.decode(encoded, 899))
        assertNull(HookCache.decode(encoded - "structural_schema", 898))
    }

    @Test
    fun `damaged no arg signature is not confused with a missing parameter field`() {
        val encoded = HookCache.encode(requireNotNull(HookTargets.builtIn(898)), 898)
        assertNull(HookCache.decode(encoded - "word.0.parameter_count", 898))
        assertNull(HookCache.decode(encoded + ("word.0.parameter_count" to "-1"), 898))
    }

    @Test
    fun `display and privilege groups fail atomically while other capabilities survive`() {
        val targets = requireNotNull(HookTargets.builtIn(898))
        val rejected = setOf(
            requireNotNull(targets.display).detailedMethod,
            requireNotNull(targets.privilege).twoArgumentMethod
        )

        val validated = HookCache.validate(targets) { it !in rejected }

        assertEquals(targets.wordLimit, validated.wordLimit)
        assertNull(validated.display)
        assertNull(validated.privilege)
        assertEquals(targets.cloud, validated.cloud)
    }

    @Test
    fun `partial capabilities round trip without inventing missing groups`() {
        val known = requireNotNull(HookTargets.builtIn(898))
        val partial = HookTargets(wordLimit = known.wordLimit, cloud = known.cloud)

        assertEquals(partial, HookCache.decode(HookCache.encode(partial, 899), 899))
    }

    @Test
    fun `return parameter and static mismatches are rejected per capability`() {
        val targets = requireNotNull(HookTargets.builtIn(898))
        val validSignatures = listOfNotNull(
            targets.wordLimit,
            targets.cloud
        ) + requireNotNull(targets.display).methods + requireNotNull(targets.privilege).methods
        val mutated = targets.copy(
            wordLimit = requireNotNull(targets.wordLimit).copy(returnType = "java.lang.Integer"),
            cloud = requireNotNull(targets.cloud).copy(isStatic = false)
        )

        val validated = HookCache.validate(mutated) { it in validSignatures }

        assertNull(validated.wordLimit)
        assertNull(validated.cloud)
        assertEquals(targets.display, validated.display)
        assertEquals(targets.privilege, validated.privilege)
    }

    @Test
    fun `cache rejects structurally valid methods outside each capability boundary`() {
        val targets = requireNotNull(HookTargets.builtIn(898))
        val corrupted = targets.copy(
            wordLimit = requireNotNull(targets.wordLimit).copy(className = "other.Class"),
            cloud = requireNotNull(targets.cloud).copy(className = "some.package.y95")
        )

        val validated = HookCache.validate(corrupted) { true }

        assertNull(validated.wordLimit)
        assertNull(validated.cloud)
        assertEquals(targets.display, validated.display)
        assertEquals(targets.privilege, validated.privilege)
    }

    @Test
    fun `word cache is reusable only when its declared structural candidate is unique`() {
        val target = requireNotNull(HookTargets.builtIn(898)?.wordLimit)
        val peer = target.copy(methodName = "other")

        assertTrue(HookCache.isReusableWordTarget(target, listOf(target, target)))
        assertFalse(HookCache.isReusableWordTarget(target, listOf(target, peer)))
        assertFalse(HookCache.isReusableWordTarget(target, emptyList()))
    }
}
