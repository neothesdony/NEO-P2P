package com.neop2p.data.local

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-app language override (system / id / en).
 *
 * Deliberately SharedPreferences: a single tiny value, no DB migration
 * (Room stays v21). Applied in MainActivity.attachBaseContext via a
 * Configuration locale override (the app uses FragmentActivity, so
 * AppCompatDelegate.setApplicationLocales is NOT available).
 */
@Singleton
class LocaleStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "system" (follow device), "id", or "en". */
    fun locale(): String = prefs.getString(KEY, "system").orEmpty()

    fun setLocale(code: String) {
        prefs.edit().putString(KEY, code).apply()
    }

    companion object {
        private const val PREFS = "locale_prefs"
        private const val KEY = "locale"
    }
}
