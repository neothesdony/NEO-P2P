package com.neop2p.data.local

import android.content.Context

/** Durable "user finished backup + verify" flag. Plain prefs — not secret. */
class OnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences("neop2p_onboarding", Context.MODE_PRIVATE)
    fun isComplete(): Boolean = prefs.getBoolean("complete", false)
    fun markComplete() {
        prefs.edit().putBoolean("complete", true).apply()
    }
}
