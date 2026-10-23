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
        val pending = handles.toList()
        handles.clear()
        unhookAllQuietly(pending, onError)
    }
}
