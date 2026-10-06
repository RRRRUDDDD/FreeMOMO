package com.rud.freemomo.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DexInventoryMemoTest {

    @Test
    fun `unchanged identities reuse the memoized complete inventory`() {
        val cached = ClassInventory(listOf("a"), "first", true)

        val (inventory, entry) = DexInventoryReader.resolveInventory(
            listOf("a"), probeComplete = true, cached = listOf("a") to cached
        ) {
            error("a complete memo hit must not re-enumerate")
        }

        assertSame(cached, inventory)
        assertEquals(listOf("a") to cached, entry)
    }

    @Test
    fun `changed identities force a full re-enumeration and replace the memo`() {
        val cached = ClassInventory(listOf("a"), "first", true)

        val (inventory, entry) = DexInventoryReader.resolveInventory(
            listOf("a", "b"), probeComplete = true, cached = listOf("a") to cached
        ) {
            ClassInventory(listOf("a", "b"), "second", true)
        }

        assertEquals("second", inventory.fingerprint)
        assertEquals(listOf("a", "b") to inventory, entry)
    }

    @Test
    fun `an incomplete prior enumeration is never reused`() {
        val cached = ClassInventory(listOf("a"), "first", false)

        val (inventory, entry) = DexInventoryReader.resolveInventory(
            listOf("a"), probeComplete = true, cached = listOf("a") to cached
        ) {
            ClassInventory(listOf("a"), "second", true)
        }

        assertEquals("second", inventory.fingerprint)
        assertEquals(listOf("a") to inventory, entry)
    }

    @Test
    fun `a failed cheap probe forces a full re-enumeration`() {
        val cached = ClassInventory(listOf("a"), "first", true)

        val (inventory, entry) = DexInventoryReader.resolveInventory(
            listOf("a"), probeComplete = false, cached = listOf("a") to cached
        ) {
            ClassInventory(listOf("a"), "second", false)
        }

        assertEquals("second", inventory.fingerprint)
        assertEquals(listOf("a") to inventory, entry)
    }
}
