package com.rud.freemomo.hook

/**
 * Remembers the host's real daily word limit observed by [WordLimitHook] before the
 * replacement takes effect. The sign-data sanitizer uses it to keep uploaded payloads
 * consistent with what the server already knows instead of leaking the replacement value.
 */
object WordLimitState {

    @Volatile private var stored: Int? = null

    val originalLimit: Int? get() = stored

    /** The first observed value wins; later reads observe the replacement, not the original. */
    fun recordOriginalLimit(value: Int) {
        if (value <= 0) return
        // Volatile early-exit: after the first observation the limit never changes,
        // and the getter is hot (every host daily-limit read lands here).
        if (stored != null) return
        synchronized(this) {
            if (stored == null) stored = value
        }
    }
}
