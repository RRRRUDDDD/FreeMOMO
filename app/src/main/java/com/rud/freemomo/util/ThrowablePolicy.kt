package com.rud.freemomo.util

/** Fatal VM conditions must never be converted into an ordinary Hook rejection. */
object ThrowablePolicy {
    fun rethrowIfFatal(error: Throwable) {
        when (error) {
            is VirtualMachineError -> throw error
            is ThreadDeath -> throw error
        }
    }
}
