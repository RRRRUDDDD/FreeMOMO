package com.rud.freemomo.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHookTargetsTest {
    @Test fun updateSignaturesAreLimitedToTheVerifiedVersion() {
        assertNotNull(UpdateHookTargets.forVersion(900))
        listOf(0, 893, 898, 899, 901, Int.MAX_VALUE).forEach {
            assertNull(UpdateHookTargets.forVersion(it))
        }
        assertNotNull(HookDiscoveryPolicy.exactTargets(893))
        assertNotNull(HookDiscoveryPolicy.exactTargets(898))
        assertNotNull(HookDiscoveryPolicy.exactTargets(900))
    }

    @Test fun everyRequiredMemberMustValidateBeforeInstallation() {
        val profile = requireNotNull(UpdateHookTargets.forVersion(900))
        assertTrue(profile.validate({ true }, { true }, { true }))
        profile.methods.values.forEach { rejected ->
            assertFalse(rejected.displayName, profile.validate({ it != rejected }, { true }, { true }))
        }
        profile.constructors.values.forEach { rejected ->
            assertFalse(rejected.toString(), profile.validate({ true }, { it != rejected }, { true }))
        }
        profile.fields.values.forEach { rejected ->
            assertFalse(rejected.toString(), profile.validate({ true }, { true }, { it != rejected }))
        }
    }

    @Test fun dispatchAndDownloadBoundariesRetainFullSignatures() {
        val methods = requireNotNull(UpdateHookTargets.forVersion(900)).methods
        assertEquals(MethodSignature("wpd", "e", listOf("android.content.Context",
            "com.maimemo.android.momo.model.upgrade.AppUpgradeDialogInfo"), "void", true), methods["dispatch"])
        assertEquals("android.content.ServiceConnection", methods.getValue("helper.start").returnType)
        assertEquals(listOf("android.content.Context", "java.lang.String", "boolean", "e70"),
            methods.getValue("helper.start").parameterTypes)
        assertEquals(listOf("android.content.Context", "boolean"), methods.getValue("helper.retry").parameterTypes)
        assertEquals(listOf("java.lang.Object"), methods.getValue("result.about").parameterTypes)
        assertEquals(methods.size, methods.values.distinct().size)
    }

    @Test fun allAppMembersMatchIndependentRecoveredDexEvidence() {
        val evidence = requireNotNull(javaClass.getResourceAsStream("/momo900-update-signatures.tsv"))
            .bufferedReader().useLines { lines -> lines.filterNot { it.startsWith("#") }.toSet() }
        val profile = requireNotNull(UpdateHookTargets.forVersion(900))
        profile.methods.values.filterNot { it.className.startsWith("android.") }.forEach { method ->
            val key = listOf("M", method.className, method.methodName,
                method.parameterTypes.joinToString(","), method.returnType, method.isStatic.toString()).joinToString("\t")
            assertTrue("Missing DEX method evidence: $key", key in evidence)
        }
        profile.constructors.values.forEach { constructor ->
            val key = listOf("M", constructor.className, "<init>",
                constructor.parameterTypes.joinToString(","), "void", "false").joinToString("\t")
            assertTrue("Missing DEX constructor evidence: $key", key in evidence)
        }
        profile.fields.values.forEach { field ->
            val key = listOf("F", field.className, field.name, field.type, field.isStatic.toString()).joinToString("\t")
            assertTrue("Missing DEX field evidence: $key", key in evidence)
        }
    }
}
