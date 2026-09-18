package com.neop2p.data.wallet

import android.content.Context
import androidx.core.content.edit
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.local.EncryptedPrefsStore
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last successful wallet snapshot (balance + history) as an
 * AES-256-GCM blob in SharedPreferences, scoped to the current identity.
 *
 * The HD scan window is ~60 addresses; over a high-RTT link that is tens of
 * seconds of sequential explorer round-trips (measured ~35 s on Wi-Fi with
 * ~300 ms RTT, 2026-09-17). The snapshot lets a cold wallet screen render the
 * last known balance immediately and refresh in the background instead of
 * blocking on the scan. It is a display cache only — never a source of truth
 * for spending (UTXOs are always fetched live).
 *
 * Scoped by identity: a snapshot written by a different identity (or by an
 * older, unscoped build) is discarded, mirroring [WalletAddressStateStore].
 */
@Singleton
class WalletSnapshotStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    private val encryptedPrefs: EncryptedPrefsStore
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val confirmedSats: Long,
        val unconfirmedSats: Long,
        val txs: List<ChainMonitor.AddressTx>
    ) {
        val totalSats: Long get() = confirmedSats + unconfirmedSats
    }

    fun save(snapshot: Snapshot, identity: String = "") {
        val blob = encryptedPrefs.encrypt(toJson(snapshot, identity))
        prefs.edit { putString(KEY, blob) }
    }

    fun load(identity: String = ""): Snapshot? {
        val raw = prefs.getString(KEY, null) ?: return null
        val decrypted = encryptedPrefs.decrypt(raw) ?: return null
        return parse(decrypted, identity)
    }

    fun clear() {
        prefs.edit { remove(KEY) }
    }

    companion object {
        const val PREFS = "wallet_snapshot"
        const val KEY = "snapshot"

        fun toJson(s: Snapshot, identity: String = ""): String {
            val o = JSONObject()
            o.put("identity", identity)
            o.put("confirmedSats", s.confirmedSats)
            o.put("unconfirmedSats", s.unconfirmedSats)
            val arr = JSONArray()
            s.txs.forEach { tx ->
                arr.put(
                    JSONObject().apply {
                        put("txid", tx.txid)
                        put("confirmed", tx.confirmed)
                        put("blockTimeSec", tx.blockTimeSec)
                        put("feeSats", tx.feeSats)
                        put("receivedSats", tx.receivedSats)
                        put("spentSats", tx.spentSats)
                        put("netSats", tx.netSats)
                        put("direction", tx.direction.name)
                    }
                )
            }
            o.put("txs", arr)
            return o.toString()
        }

        /** Parse [json] only when it belongs to [identity]; anything else is null. */
        fun parse(json: String?, identity: String = ""): Snapshot? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                if (o.optString("identity", "") != identity) return null
                val txs = mutableListOf<ChainMonitor.AddressTx>()
                val arr = o.optJSONArray("txs")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        val direction = runCatching {
                            ChainMonitor.TxDirection.valueOf(t.optString("direction"))
                        }.getOrNull() ?: continue
                        txs.add(
                            ChainMonitor.AddressTx(
                                txid = t.optString("txid"),
                                confirmed = t.optBoolean("confirmed"),
                                blockTimeSec = t.optLong("blockTimeSec"),
                                feeSats = t.optLong("feeSats"),
                                receivedSats = t.optLong("receivedSats"),
                                spentSats = t.optLong("spentSats"),
                                netSats = t.optLong("netSats"),
                                direction = direction
                            )
                        )
                    }
                }
                Snapshot(
                    confirmedSats = o.optLong("confirmedSats"),
                    unconfirmedSats = o.optLong("unconfirmedSats"),
                    txs = txs
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
