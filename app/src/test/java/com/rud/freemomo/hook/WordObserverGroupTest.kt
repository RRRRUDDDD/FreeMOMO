package com.rud.freemomo.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WordObserverGroupTest {
    private val first = requireNotNull(HookTargets.builtIn(898)?.wordLimit)
    private val second = first.copy(methodName = "peer")

    @Test
    fun `registration stages all handles before any callback can mutate a result`() {
        var changes = 0
        val callbacks = mutableListOf<WordResultObserver>()
        val group = WordObserverGroup(Any(), { _, _, replace -> changes++; replace(999) })
        assertTrue(group.install(listOf(first, second)) { _, callback ->
            callbacks += callback
            callback(670) { error("Partial group must not replace a result") }
            HookUnhook {}
        })
        assertTrue(group.active)
        assertEquals(0, changes)
        var result = 670
        callbacks.first()(670) { result = it }
        assertEquals(999, result)
        assertEquals(1, changes)
    }

    @Test
    fun `registration failure rolls back every handle in reverse order and closes callbacks`() {
        val released = mutableListOf<MethodSignature>()
        val callbacks = mutableListOf<WordResultObserver>()
        val group = WordObserverGroup(Any(), { _, _, _ -> error("Rolled-back group") })
        val third = first.copy(methodName = "third")
        assertFalse(group.install(listOf(first, second, third)) { method, callback ->
            callbacks += callback
            if (method == third) error("Failed third registration")
            HookUnhook { released += method }
        })
        assertEquals(listOf(second, first), released)
        assertFalse(group.active)
        callbacks.forEach { it(670) { error("Closed callback") } }
        group.close()
        assertEquals(listOf(second, first), released)
    }

    @Test
    fun `handle returned after reentrant close is immediately unhooked`() {
        var registrations = 0
        var releases = 0
        val group = WordObserverGroup(Any(), { _, _, _ -> error("Closed group") })
        assertFalse(group.install(listOf(first, second)) { _, _ ->
            registrations++
            group.close()
            HookUnhook { releases++ }
        })
        assertEquals(1, registrations)
        assertEquals(1, releases)
        assertFalse(group.active)
    }

    @Test
    fun `callback already waiting on the monitor becomes inert when group closes`() {
        val monitor = Any()
        val group = WordObserverGroup(monitor, { _, _, _ -> error("In-flight closed callback") })
        lateinit var callback: WordResultObserver
        group.install(listOf(first)) { _, observer -> callback = observer; HookUnhook {} }
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = synchronized(monitor) {
                val submitted = executor.submit {
                    started.countDown()
                    callback(670) { error("Result modified after close") }
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))
                group.close()
                submitted
            }
            future.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `one unhook failure cannot prevent other handles being released`() {
        val released = mutableListOf<MethodSignature>()
        val errors = mutableListOf<Throwable>()
        val group = WordObserverGroup(Any(), { _, _, _ -> }, onError = { errors += it })
        group.install(listOf(first, second)) { method, _ ->
            HookUnhook {
                released += method
                if (method == second) error("unhook failure")
            }
        }
        group.close()
        assertEquals(listOf(second, first), released)
        assertEquals(1, errors.size)
        assertFalse(group.active)
    }

    @Test
    fun `duplicate or wrong-shape candidates cannot create an observation group`() {
        listOf(listOf(first, first), listOf(first.copy(returnType = "java.lang.Integer"))).forEach { candidates ->
            val group = WordObserverGroup(Any(), { _, _, _ -> })
            assertFalse(group.install(candidates) { _, _ -> error("Invalid registration") })
            assertFalse(group.active)
        }
    }
}
