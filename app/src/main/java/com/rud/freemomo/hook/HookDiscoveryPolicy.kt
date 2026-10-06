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

    /** The 1..20 range is valid only at an already-discovered TextView formatting boundary. */
    fun displayedLevelReplacement(originalLevel: Int): Int? =
        USER_LEVEL_REPLACEMENT.takeIf { originalLevel in 1 until USER_LEVEL_REPLACEMENT }
}
