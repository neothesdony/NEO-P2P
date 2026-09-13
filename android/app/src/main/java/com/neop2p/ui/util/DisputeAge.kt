package com.neop2p.ui.util

/**
 * Dispute age bucketing (2026-09-13). Disputes have no deadline, so the UI must at least
 * say how long one has been waiting. Returns a bucket + count; the screen maps them to
 * localized strings (EN/ID parity).
 */
object DisputeAge {
    enum class Bucket { TODAY, DAYS, WEEKS }
    data class Age(val bucket: Bucket, val count: Int)

    fun of(disputedAt: Long?, now: Long): Age? {
        if (disputedAt == null) return null
        val elapsed = now - disputedAt
        if (elapsed < 0) return null
        val days = elapsed / 86_400_000L
        return when {
            days < 1 -> Age(Bucket.TODAY, 0)
            days < 7 -> Age(Bucket.DAYS, days.toInt())
            else -> Age(Bucket.WEEKS, (days / 7).toInt())
        }
    }
}
