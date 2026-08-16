package com.rud.freemomo.util

import org.junit.Assert.assertSame
import org.junit.Test

class ThrowablePolicyTest {

    @Test
    fun `virtual machine errors are rethrown unchanged`() {
        val fatal = object : VirtualMachineError("fatal") {}

        val thrown = try {
            ThrowablePolicy.rethrowIfFatal(fatal)
            null
        } catch (error: VirtualMachineError) {
            error
        }

        assertSame(fatal, thrown)
    }

    @Test(expected = ThreadDeath::class)
    fun `thread death is rethrown`() {
        ThrowablePolicy.rethrowIfFatal(ThreadDeath())
    }

    @Test
    fun `ordinary reflection failures remain recoverable`() {
        ThrowablePolicy.rethrowIfFatal(ReflectiveOperationException("recoverable"))
    }
}
