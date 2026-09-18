package com.neop2p.data.p2p

/**
 * Watchdog for the "RS session RUNNING but every interface offline" dead state
 * (network change / profile swap). Pure; the caller owns the clock and restart.
 */
object TransportRecoveryPolicy {
    const val CHECK_INTERVAL_MS: Long = 6_000L
    /** Ignore the first [GRACE_MS] after RUNNING (cold start, path discovery). */
    const val GRACE_MS: Long = 90_000L
    /** After this long, stop trying (a later restart resets the window). */
    const val WINDOW_MS: Long = 180_000L
    const val COOLDOWN_MS: Long = 30_000L

    fun shouldRecover(
        running: Boolean,
        onlineInterfaces: Int,
        expectedInterfaces: Int,
        readySinceMs: Long,
        nowMs: Long,
        lastRecoveryMs: Long,
    ): Boolean {
        if (!running) return false
        if (expectedInterfaces <= 0) return false
        if (onlineInterfaces > 0) return false
        if (readySinceMs < 0L) return false
        val age = nowMs - readySinceMs
        if (age < GRACE_MS || age > WINDOW_MS) return false
        if (lastRecoveryMs > 0L && nowMs - lastRecoveryMs < COOLDOWN_MS) return false
        return true
    }
}
