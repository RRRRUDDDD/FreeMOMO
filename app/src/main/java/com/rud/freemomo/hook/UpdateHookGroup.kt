package com.rud.freemomo.hook

/** Staged registration: callbacks stay inert until activation, including after failed unhooks. */
class UpdateHookGroup(private val onError: (Throwable) -> Unit) {
    private val handles = mutableListOf<HookUnhook>()
    @Volatile var active = false
        private set

    fun add(handle: HookUnhook) {
        handles += handle
    }

    fun activate() {
        active = true
    }

    fun rollback() {
        active = false
        var fatal: Throwable? = null
        handles.asReversed().forEach { handle ->
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
        handles.clear()
        fatal?.let { throw it }
    }
}
