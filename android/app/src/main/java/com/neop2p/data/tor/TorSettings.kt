package com.neop2p.data.tor

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

interface TorSettings {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

@Singleton
class TorSettingsStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) : TorSettings {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFS = "tor_settings"
        const val KEY_ENABLED = "enabled"
    }
}
