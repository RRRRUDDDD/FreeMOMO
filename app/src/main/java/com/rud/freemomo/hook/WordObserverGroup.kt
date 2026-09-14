package com.rud.freemomo.hook

import com.rud.freemomo.util.ThrowablePolicy

fun interface HookUnhook {
    fun unhook()
}

typealias WordResultObserver = (Int, (Int) -> Unit) -> Unit

/**
 * Shares the coordinator monitor. Registration is staged; neither a partial group nor callbacks
 * already in flight after close may change a result. A first match deliberately keeps peers live.
 */
class WordObserverGroup(
    private val monitor: Any,
    private val onResult: (MethodSignature, Int, (Int) -> Unit) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    private enum class Phase { NEW, REGISTERING, ACTIVE, CLOSED }

    private var phase = Phase.NEW
    private val handles = mutableListOf<HookUnhook>()

    val active: Boolean get() = synchronized(monitor) { phase == Phase.ACTIVE }

    fun install(
        candidates: List<MethodSignature>,
        register: (MethodSignature, WordResultObserver) -> HookUnhook
    ): Boolean = synchronized(monitor) {
        if (phase != Phase.NEW) return@synchronized false
        if (candidates.isEmpty() || candidates.distinct().size != candidates.size ||
            candidates.any { !HookSignatures.isWord(it) }
        ) {
            phase = Phase.CLOSED
            return@synchronized false
        }
        phase = Phase.REGISTERING
        try {
            for (candidate in candidates) {
                if (phase == Phase.CLOSED) return@synchronized false
                val handle = register(candidate) { value, replace ->
                    synchronized(monitor) {
                        if (phase == Phase.ACTIVE) onResult(candidate, value, replace)
                    }
                }
                if (phase == Phase.CLOSED) {
                    release(listOf(handle))
                    return@synchronized false
                }
                handles += handle
            }
            phase = Phase.ACTIVE
            true
        } catch (error: Throwable) {
            close()
            ThrowablePolicy.rethrowIfFatal(error)
            onError(error)
            false
        }
    }

    fun close() = synchronized(monitor) {
        if (phase == Phase.CLOSED) return@synchronized
        phase = Phase.CLOSED
        val pending = handles.asReversed().toList()
        handles.clear()
        release(pending)
    }

    private fun release(pending: List<HookUnhook>) {
        var fatal: Throwable? = null
        pending.forEach { handle ->
            try {
                handle.unhook()
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) {
                    if (fatal == null) fatal = error
                } else {
                    onError(error)
                }
            }
        }
        fatal?.let { throw it }
    }
}
