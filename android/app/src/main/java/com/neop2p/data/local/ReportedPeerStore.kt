package com.neop2p.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only store of reported peers.
 *
 * A report is a local record: peerId + reason + timestamp. It NEVER travels
 * to the relay or any server (a gossip report would let peers retaliate /
 * learn who reported them, and there is no admin backend to receive it —
 * this app is zero-backend). The report is a persistent trace the user can
 * review in Settings and keep as evidence alongside blocked peers. It does
 * NOT change trade state and does NOT auto-release funds.
 */
@Singleton
class ReportedPeerStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("neop2p_reported_peers", Context.MODE_PRIVATE)

    data class Report(
        val peerId: String,
        val reason: String,
        val reportedAt: Long
    )

    fun reports(): List<Report> =
        prefs.all
            .mapNotNull { (peerId, value) ->
                val raw = value as? String ?: return@mapNotNull null
                val parts = raw.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                Report(
                    peerId = peerId,
                    reason = parts[0],
                    reportedAt = parts[1].toLongOrNull() ?: 0L
                )
            }
            .sortedByDescending { it.reportedAt }

    fun isReported(peerId: String): Boolean =
        peerId.isNotBlank() && prefs.contains(peerId)

    fun report(peerId: String, reason: String) {
        if (peerId.isBlank()) return
        val safeReason = reason.ifBlank { "unspecified" }.replace("|", " ")
        prefs.edit().putString(peerId, "$safeReason|${System.currentTimeMillis()}").apply()
    }

    fun remove(peerId: String) {
        prefs.edit().remove(peerId).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
