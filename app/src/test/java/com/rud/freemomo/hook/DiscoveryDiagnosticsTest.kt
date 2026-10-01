package com.rud.freemomo.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryDiagnosticsTest {
    private val display = requireNotNull(HookTargets.builtIn(898)?.display)
    private val owner = display.twoArgumentMethod.className
    private val inventory = ClassInventory(listOf(owner, "unreadable"), "same", true)

    @Test
    fun `only debug 904 may enumerate and other calls do not consume its one shot`() {
        val diagnostics = DiscoveryDiagnostics()
        var reads = 0
        val logs = mutableListOf<String>()
        val enumerate = { reads++; inventory }
        val describe = { name: String -> ClassDescriptor(name, emptyList()) }
        diagnostics.run(false, 904, enumerate, describe, logs::add)
        diagnostics.run(true, 900, enumerate, describe, logs::add)
        assertEquals(0, reads)
        assertTrue(logs.isEmpty())
        diagnostics.run(true, 904, enumerate, describe, logs::add)
        diagnostics.run(true, 904, enumerate, describe, logs::add)
        assertEquals(1, reads)
        assertEquals(1, logs.count { "begin evidence-only" in it })
        assertEquals(1, logs.count { "end inventory=" in it })
    }

    @Test
    fun `partial evidence identifies candidates while retaining incomplete scan status`() {
        val logs = mutableListOf<String>()
        DiscoveryDiagnostics().run(true, 904, { inventory }, { name ->
            if (name == "unreadable") throw NoClassDefFoundError("private exception detail")
            ClassDescriptor(name, if (name == owner) display.methods else emptyList())
        }, logs::add)
        assertTrue(logs.any { "display evidence=DISCOVERED" in it })
        assertTrue(logs.any { display.twoArgumentMethod.displayName in it })
        assertTrue(logs.any { display.detailedMethod.displayName in it })
        assertTrue(logs.any { "display complete=false retry=REFLECTION" in it })
        assertTrue(logs.any { "class=unreadable type=java.lang.NoClassDefFoundError" in it })
        assertFalse(logs.any { "private exception detail" in it })
    }

    @Test
    fun `ambiguous display candidates are reported as conflicts not accepted targets`() {
        val peer = display.methods.map { it.copy(className = "Peer") }
        val logs = mutableListOf<String>()
        DiscoveryDiagnostics().run(true, 904, {
            ClassInventory(listOf(owner, "Peer"), "same", true)
        }, { name ->
            ClassDescriptor(name, when (name) {
                owner -> display.methods
                "Peer" -> peer
                else -> emptyList()
            })
        }, logs::add)
        assertTrue(logs.any { "display evidence=AMBIGUOUS candidates=0 conflicts=4" in it })
        assertEquals(4, logs.count { "display declaration" in it })
    }

    @Test
    fun `privilege helper rejection retains complete signatures and static flags`() {
        val target = requireNotNull(HookTargets.builtIn(900)?.privilege)
        val helper = MethodSignature(HookTargets.PRIVILEGE_CLASS, "f",
            listOf(HookTargets.PRIVILEGE_CODE), "LevelPrivilege", false)
        val logs = mutableListOf<String>()
        DiscoveryDiagnostics().run(true, 904, { ClassInventory(emptyList(), "same", true) }, {
            ClassDescriptor(it, if (it == HookTargets.PRIVILEGE_CLASS) target.methods + helper else emptyList())
        }, logs::add)
        assertTrue(logs.any { "privilege evidence=REJECTED" in it })
        assertTrue(logs.any { "static=false ${helper.displayName}" in it })
        assertEquals(4, logs.count { "privilege declaration" in it })
    }

    @Test
    fun `failure records and rejected declarations are bounded with explicit omitted counts`() {
        val logs = mutableListOf<String>()
        DiscoveryDiagnostics().run(true, 904, {
            ClassInventory((1..100).map { "unreadable$it" } + owner, "same", true)
        }, { name ->
            if (name.startsWith("unreadable")) throw LinkageError("private detail")
            ClassDescriptor(name, if (name == owner) (1..100).map {
                display.twoArgumentMethod.copy(methodName = "candidate$it")
            } else emptyList())
        }, logs::add)
        assertEquals(16, logs.count { "reflection failed" in it })
        assertEquals(24, logs.count { "display declaration" in it })
        assertTrue(logs.any { "omittedFailures=84" in it })
        assertTrue(logs.any { "rejected=100 omittedMethods=76" in it })
        assertTrue(logs.any { "cloud evidence=" in it })
        assertTrue(logs.last().contains("end inventory="))
    }

    @Test
    fun `foreign names cannot inject multiline or oversized records`() {
        val logs = mutableListOf<String>()
        val foreign = "name\n\r\t" + "x".repeat(4000)
        DiscoveryDiagnostics().run(true, 904, {
            ClassInventory(listOf(foreign), "same", true)
        }, { name ->
            if (name == foreign) throw LinkageError("secret")
            ClassDescriptor(name, emptyList())
        }, logs::add)
        assertTrue(logs.all { it.length <= "FreeMOMO: diagnostic:904 ".length + 1600 })
        assertFalse(logs.any { line -> line.any { it.isISOControl() } })
        assertFalse(logs.any { "secret" in it })
    }

    @Test
    fun `enumeration failure is isolated and cannot cause repeated attempts`() {
        val diagnostics = DiscoveryDiagnostics()
        val logs = mutableListOf<String>()
        var attempts = 0
        val enumerate = { attempts++; throw IllegalStateException("sensitive message") }
        repeat(2) {
            diagnostics.run(true, 904, enumerate, { error("must not describe") }, logs::add)
        }
        assertEquals(1, attempts)
        assertTrue(logs.last().endsWith("aborted type=java.lang.IllegalStateException"))
        assertFalse(logs.any { "sensitive message" in it })
    }

    @Test
    fun `fatal reflection errors are never swallowed by the diagnostic boundary`() {
        assertThrows(OutOfMemoryError::class.java) {
            DiscoveryDiagnostics().run(true, 904, { inventory }, {
                throw OutOfMemoryError("fatal")
            }, {})
        }
    }
}
