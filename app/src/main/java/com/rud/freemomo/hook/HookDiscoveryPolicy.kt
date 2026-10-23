package com.rud.freemomo.hook

object HookDiscoveryPolicy {
    const val WORD_LIMIT_REPLACEMENT = 99666
    const val USER_LEVEL_REPLACEMENT = 21
    const val CLOUD_LIBRARY_LIMIT_REPLACEMENT = 60

    /** Single source for discovery and replacement so the accepted range cannot drift apart. */
    val WORD_LIMIT_RANGE: LongRange = 601L..19_999L

    fun exactTargets(versionCode: Int): HookTargets? = HookTargets.builtIn(versionCode)

    fun isWordLimitCandidate(result: Long): Boolean = result in WORD_LIMIT_RANGE

    fun wordLimitReplacement(result: Long): Int? =
        WORD_LIMIT_REPLACEMENT.takeIf { isWordLimitCandidate(result) }

    /**
     * The replacement path's range gate: only an in-range original (601..19999) produces the
     * replacement, and [record] must land before [write] hands 99666 to the host, so the
     * sign-data sanitizer can never meet 99666 in an upload without the real limit already
     * recorded in [WordLimitState.originalLimit]. Out-of-range originals stay out of both the
     * write and the record: the next in-range value becomes the recorded original.
     */
    fun gatedWordLimitReplacement(original: Int, record: (Int) -> Unit, write: (Int) -> Unit) {
        val replacement = wordLimitReplacement(original.toLong()) ?: return
        record(original)
        write(replacement)
    }

    /** The 1..20 range is valid only at an already-discovered TextView formatting boundary. */
    fun displayedLevelReplacement(originalLevel: Int): Int? =
        USER_LEVEL_REPLACEMENT.takeIf { originalLevel in 1 until USER_LEVEL_REPLACEMENT }
}
