package com.rud.freemomo.hook

import com.rud.freemomo.util.DiscoverySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryScannerTest {
    private val known = requireNotNull(HookTargets.builtIn(898))

    @Test
    fun `fixed capabilities reflect only their own declarations even when inventory is incomplete`() {
        val read = mutableListOf<String>()
        val scanner = DiscoveryScanner { className ->
            read += className
            when (className) {
                HookTargets.WORD_LIMIT_CLASS -> ClassDescriptor(className, listOf(requireNotNull(known.wordLimit)))
                HookTargets.PRIVILEGE_CLASS -> ClassDescriptor(className, requireNotNull(known.privilege).methods)
                else -> error("Unrequested reflection: $className")
            }
        }
        val scan = scanner.scan(
            setOf(HookCapability.WORD_LIMIT, HookCapability.PRIVILEGE),
            ClassInventory((1..1000).map { "Unrelated$it" }, "dex", complete = false)
        )
        assertEquals(setOf(HookTargets.WORD_LIMIT_CLASS, HookTargets.PRIVILEGE_CLASS), read.toSet())
        assertEquals(2, read.size)
        assertTrue(scan.metadata.values.all { it.complete })
        val snapshot = DiscoverySnapshot().fillUnknown(scan)
        assertEquals(DiscoveryStatus.PENDING, snapshot.wordLimit?.status)
        assertEquals(known.privilege, snapshot.targets.privilege)
        assertNull(snapshot.display)
        assertNull(snapshot.cloud)
    }

    @Test
    fun `missing fixed probes do not contaminate a complete global inventory`() {
        val display = requireNotNull(known.display)
        val cloud = requireNotNull(known.cloud)
        val classes = listOf(
            ClassDescriptor(display.twoArgumentMethod.className, display.methods),
            ClassDescriptor(cloud.className, listOf(
                cloud,
                MethodSignature(cloud.className, "map", listOf(HookTargets.BOOLEAN), HookTargets.MAP, true),
                MethodSignature(cloud.className, "index", listOf(HookTargets.MAP, HookTargets.INT), HookTargets.INTEGER, true),
                MethodSignature(cloud.className, "available", listOf(HookTargets.CONTEXT), HookTargets.BOOLEAN, true)
            ))
        ).associateBy { it.className }
        val scanner = DiscoveryScanner { name -> classes[name] ?: throw ClassNotFoundException(name) }
        val scan = scanner.scan(HookCapability.entries.toSet(), ClassInventory(classes.keys.toList(), "complete", true))
        val snapshot = DiscoverySnapshot().fillUnknown(scan)
        assertEquals(display, snapshot.targets.display)
        assertEquals(cloud, snapshot.targets.cloud)
        assertTrue(scan.metadata.getValue(HookCapability.DISPLAY).complete)
        assertTrue(scan.metadata.getValue(HookCapability.CLOUD).complete)
        assertTrue(scan.metadata.getValue(HookCapability.DISPLAY).failedClasses.isEmpty())
        assertTrue(scan.metadata.getValue(HookCapability.CLOUD).failedClasses.isEmpty())
        assertNull(snapshot.wordLimit)
        assertNull(snapshot.privilege)
        assertEquals(setOf(HookTargets.WORD_LIMIT_CLASS), scan.metadata.getValue(HookCapability.WORD_LIMIT).failedClasses)
        assertEquals(setOf(HookTargets.PRIVILEGE_CLASS), scan.metadata.getValue(HookCapability.PRIVILEGE).failedClasses)
    }

    @Test
    fun `an unreadable global class prevents uniqueness and can later expose a conflict`() {
        var recovered = false
        val first = requireNotNull(known.display)
        val peer = DisplayHookTargets(
            first.twoArgumentMethod.copy(className = "packaged.Peer"),
            first.detailedMethod.copy(className = "packaged.Peer")
        )
        val scanner = DiscoveryScanner { className ->
            if (className == peer.twoArgumentMethod.className && !recovered) {
                throw NoClassDefFoundError("temporary dependency")
            }
            ClassDescriptor(className, if (className == first.twoArgumentMethod.className) first.methods else peer.methods)
        }
        val inventory = ClassInventory(listOf(first.twoArgumentMethod.className, "packaged.Peer"), "same", true)
        val partial = scanner.scan(setOf(HookCapability.DISPLAY), inventory)
        assertNull(partial.result.targets.display)
        assertEquals(DiscoveryStatus.PENDING, partial.result.display.status)
        var snapshot = DiscoverySnapshot().fillUnknown(partial)
        assertNull(snapshot.display)
        assertEquals(setOf("packaged.Peer"), snapshot.scanMetadata.getValue(HookCapability.DISPLAY).failedClasses)
        assertTrue(scanner.probeFailed(snapshot).isEmpty())

        recovered = true
        assertEquals(setOf("packaged.Peer"), scanner.probeFailed(snapshot))
        snapshot = snapshot.fillUnknown(scanner.scan(setOf(HookCapability.DISPLAY), inventory))
        assertEquals(DiscoveryStatus.AMBIGUOUS, snapshot.display?.status)
        assertNull(snapshot.targets.display)
        assertTrue(snapshot.scanMetadata.getValue(HookCapability.DISPLAY).complete)
        assertEquals(2, snapshot.scanMetadata.getValue(HookCapability.DISPLAY).attempts)
    }

    @Test
    fun `temporary reflection failure recovers without a permanent fixed-class negative`() {
        var readable = false
        val scanner = DiscoveryScanner { className ->
            if (!readable) throw ClassNotFoundException(className)
            ClassDescriptor(className, requireNotNull(known.privilege).methods)
        }
        val inventory = ClassInventory(emptyList(), "same", true)
        var snapshot = DiscoverySnapshot().fillUnknown(
            scanner.scan(setOf(HookCapability.PRIVILEGE), inventory)
        )
        assertNull(snapshot.privilege)
        assertFalse(snapshot.scanMetadata.getValue(HookCapability.PRIVILEGE).complete)
        readable = true
        snapshot = snapshot.fillUnknown(scanner.scan(setOf(HookCapability.PRIVILEGE), inventory))
        assertEquals(known.privilege, snapshot.targets.privilege)
        assertTrue(snapshot.scanMetadata.getValue(HookCapability.PRIVILEGE).failedClasses.isEmpty())
        assertEquals(2, snapshot.scanMetadata.getValue(HookCapability.PRIVILEGE).attempts)
    }

    @Test
    fun `incomplete enumeration establishes neither a unique match nor a negative`() {
        val display = requireNotNull(known.display)
        val scanner = DiscoveryScanner { ClassDescriptor(it, display.methods) }
        val scan = scanner.scan(
            setOf(HookCapability.DISPLAY, HookCapability.CLOUD),
            ClassInventory(listOf(display.twoArgumentMethod.className), "partial", false)
        )
        val snapshot = DiscoverySnapshot().fillUnknown(scan)
        assertNull(scan.result.targets.display)
        assertEquals(DiscoveryStatus.PENDING, scan.result.cloud.status)
        assertNull(snapshot.display)
        assertNull(snapshot.cloud)
        assertTrue(scan.metadata.values.all { !it.complete && !it.enumerationComplete })
    }

    @Test
    fun `recovered unrelated classes allow complete global uniqueness`() {
        var recovered = false
        val display = requireNotNull(known.display)
        val scanner = DiscoveryScanner { name ->
            if (name == "unrelated" && !recovered) throw LinkageError("dependency")
            ClassDescriptor(name, if (name == "unrelated") emptyList() else display.methods)
        }
        val inventory = ClassInventory(listOf(display.twoArgumentMethod.className, "unrelated"), "same", true)
        var snapshot = DiscoverySnapshot().fillUnknown(scanner.scan(setOf(HookCapability.DISPLAY), inventory))
        assertNull(snapshot.display)
        recovered = true
        snapshot = snapshot.fillUnknown(scanner.scan(setOf(HookCapability.DISPLAY), inventory))
        assertEquals(display, snapshot.targets.display)
    }

    @Test
    fun `complete empty search retains a terminal negative`() {
        val scan = DiscoveryScanner { ClassDescriptor(it, emptyList()) }.scan(
            setOf(HookCapability.DISPLAY), ClassInventory(listOf("empty"), "same", true)
        )
        val snapshot = DiscoverySnapshot().fillUnknown(scan)
        assertEquals(DiscoveryStatus.MISSING, snapshot.display?.status)
        assertTrue(snapshot.scanMetadata.getValue(HookCapability.DISPLAY).complete)
        assertFalse(HookCapability.DISPLAY in snapshot.unknownCapabilities)
    }

    @Test
    fun `fatal reflection errors escape instead of becoming retry metadata`() {
        assertThrows(OutOfMemoryError::class.java) {
            DiscoveryScanner { throw OutOfMemoryError("fatal") }.scan(
                setOf(HookCapability.WORD_LIMIT), ClassInventory(emptyList(), "same", true)
            )
        }
    }
}
