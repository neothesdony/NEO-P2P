package com.neop2p.data.local

import android.content.Context
import com.neop2p.data.p2p.SweepThrottle
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last-emitted timestamp per sweep key (P7.3), so a process
 * restart does not re-fire a reminder the in-memory `emitOnce` set would have
 * suppressed. AES-256-GCM blob in SharedPreferences (same cipher path as the
 * other small stores).
 */
@Singleton
class SweepThrottleStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    private val encryptedPrefs: EncryptedPrefsStore
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Last emit time for [key], or null when never emitted. */
    fun lastEmittedAt(key: String): Long? {
        val raw = prefs.getString(KEY, null) ?: return null
        val decrypted = encryptedPrefs.decrypt(raw) ?: return null
        return parse(decrypted)[key]
    }

    /** Record that [key] emitted at [nowMs]. Synchronous commit. */
    fun markEmitted(key: String, nowMs: Long) {
        val raw = prefs.getString(KEY, null)
        val decrypted = raw?.let { encryptedPrefs.decrypt(it) }
        val current = decrypted?.let { parse(it) }.orEmpty()
        val updated = current + (key to nowMs)
        prefs.edit().putString(KEY, encryptedPrefs.encrypt(toJson(updated))).commit()
    }

    /** True when [key] may emit now. */
    fun shouldEmit(key: String, nowMs: Long, windowMs: Long = SweepThrottle.DEFAULT_WINDOW_MS): Boolean =
        SweepThrottle.shouldEmit(lastEmittedAt(key), nowMs, windowMs)

    /** Forget every throttle timestamp (identity reset). */
    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    companion object {
        const val PREFS = "sweep_throttle"
        const val KEY = "timestamps"

        fun toJson(map: Map<String, Long>): String {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, v) }
            return o.toString()
        }

        fun parse(json: String?): Map<String, Long> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val o = JSONObject(json)
                buildMap {
                    o.keys().forEach { k ->
                        o.optLong(k, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { put(k, it) }
                    }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
