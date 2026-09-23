package com.neop2p.data.local

import android.content.Context

/** Durable "user finished backup + verify" flag. Plain prefs — not secret. */
class OnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences("neop2p_onboarding", Context.MODE_PRIVATE)

    fun isComplete(): Boolean = prefs.getBoolean("complete", false)

    fun markComplete() {
        prefs.edit().putBoolean("complete", true).apply()
    }

    /** Highest accepted terms version (Phase 3). 0 = never accepted. */
    fun termsVersion(): Int = prefs.getInt(KEY_TERMS_VERSION, DEFAULT_TERMS_VERSION)

    fun acceptTerms(version: Int) {
        prefs.edit().putInt(KEY_TERMS_VERSION, version).apply()
    }

    companion object {
        const val KEY_TERMS_VERSION = "terms_version"
        const val DEFAULT_TERMS_VERSION = 0
    }
}
