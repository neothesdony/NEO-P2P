package com.neop2p.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only blocklist of peers. Blocking hides a peer's offers from the
 * market feed and stops their chat from surfacing. The list is stored in
 * SharedPreferences (plain ids, no secrets) — it NEVER travels to the relay
 * (a gossip blocklist would let peers retaliate / learn who blocked them).
 */
@Singleton
class BlockedPeerStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("neop2p_blocked_peers", Context.MODE_PRIVATE)

    fun isBlocked(peerId: String): Boolean =
        peerId.isNotBlank() && prefs.getBoolean(peerId, false)

    fun block(peerId: String) {
        if (peerId.isBlank()) return
        prefs.edit().putBoolean(peerId, true).apply()
    }

    fun unblock(peerId: String) {
        prefs.edit().remove(peerId).apply()
    }

    fun blockedPeerIds(): List<String> =
        prefs.all.filterValues { it == true }.keys.toList()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
