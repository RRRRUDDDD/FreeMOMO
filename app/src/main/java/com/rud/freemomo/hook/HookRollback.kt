package com.rud.freemomo.hook

/**
 * Best-effort reversed rollback shared by every hook group: each handle still gets its unhook
 * attempt when an earlier one dies fatally. The fatal is rethrown only after the loop; ordinary
 * failures go to onError.
 */
fun unhookAllQuietly(handles: List<HookUnhook>, onError: (Throwable) -> Unit) {
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
    fatal?.let { throw it }
}
