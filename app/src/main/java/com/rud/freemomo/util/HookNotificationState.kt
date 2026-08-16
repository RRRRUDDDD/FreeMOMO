package com.rud.freemomo.util

import android.content.Context

/** Persists one-time discovery notifications independently from the structural hook cache. */
object HookNotificationState {
    private const val PREFS_NAME = "momo_hook_notification_state"
    private const val KEY_FOUND_TARGET_VERSION = "found_target_version_code"

    @Synchronized
    fun claimFoundNotification(context: Context, targetVersionCode: Int): Boolean {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!shouldShowFound(preferences.getIntOrNull(KEY_FOUND_TARGET_VERSION), targetVersionCode)) {
            return false
        }
        preferences.edit().putInt(KEY_FOUND_TARGET_VERSION, targetVersionCode).apply()
        return true
    }

    internal fun shouldShowFound(lastNotifiedVersionCode: Int?, targetVersionCode: Int): Boolean =
        lastNotifiedVersionCode != targetVersionCode

    private fun android.content.SharedPreferences.getIntOrNull(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null
}
