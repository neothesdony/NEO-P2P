package com.neop2p.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the last release tag we posted an update notification for, so the
 * weekly check does not re-nag about the same release. Not secret (a public
 * GitHub tag), so plain SharedPreferences are fine.
 */
@Singleton
class UpdateCheckStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastNotifiedTag(): String? = prefs.getString(KEY_LAST_NOTIFIED, null)

    fun setLastNotifiedTag(tag: String) {
        prefs.edit().putString(KEY_LAST_NOTIFIED, tag).apply()
    }

    companion object {
        private const val PREFS = "neop2p_update_check"
        private const val KEY_LAST_NOTIFIED = "last_notified_tag"
    }
}
