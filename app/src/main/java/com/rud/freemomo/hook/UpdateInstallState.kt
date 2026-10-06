package com.rud.freemomo.hook

import com.rud.freemomo.util.ThrowablePolicy

/** Core guards remain closed until the complete manual-operation adapter is active. */
class UpdateInstallState(private val guardIds: Set<String>, private val supported: Boolean) {
    enum class Status { UNSUPPORTED, UNAVAILABLE, PARTIAL, GUARDED, MANUAL_READY }

    private val guards = mutableSetOf<String>()
    private var nextTrigger = 0
    private var installing = false
    @Volatile var manualReady = false
        private set

    val status: Status
        @Synchronized get() = when {
            !supported -> Status.UNSUPPORTED
            manualReady -> Status.MANUAL_READY
            guards.isEmpty() -> Status.UNAVAILABLE
            guards.containsAll(guardIds) -> Status.GUARDED
            else -> Status.PARTIAL
        }

    val needsRetry: Boolean
        @Synchronized get() = supported && !manualReady && nextTrigger < DiscoveryTrigger.entries.size

    @Synchronized
    fun attempt(
        trigger: DiscoveryTrigger,
        installGuard: (String) -> Unit,
        installManual: () -> Unit,
        onFailure: (String, Throwable) -> Unit
    ): Status {
        if (!needsRetry || installing || trigger.ordinal != nextTrigger) return status
        installing = true
        try {
            nextTrigger++ // Claim before registration so reentrant calls cannot spend this opportunity twice.
            for (id in guardIds - guards) {
                try {
                    installGuard(id)
                    guards += id
                } catch (error: Throwable) {
                    ThrowablePolicy.rethrowIfFatal(error)
                    onFailure("guard:$id", error)
                }
            }
            try {
                installManual()
                manualReady = true
            } catch (error: Throwable) {
                ThrowablePolicy.rethrowIfFatal(error)
                onFailure("manual", error)
            }
        } finally {
            installing = false
        }
        return status
    }
}
