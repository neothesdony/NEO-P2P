package com.neop2p.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Persistent retry queue for ack-gated `LXMF dispute message` dispute publishes.
 *
 * `EscrowScreen.disputeEscrow()` now does publish-then-commit: if `publishDispute`
 * returns `confirmed.isEmpty()` the local row must NOT flip to DISPUTED (arbitrator
 * would never see it). We persist the payload here and `P2POrchestrator.sweepStaleEscrows`
 * (60s) retries `publishDispute` until a relay acks, then flips locally via `disputeEscrow`.
 * Idempotent: a second publish after success is skipped because the escrow is already DISPUTED.
 */
@Singleton
class PendingDisputeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedPrefs: EncryptedPrefsStore
) {
    companion object {
        private const val TAG = "PendingDisputeStore"
        private const val PREFS_NAME = "neop2p_pending_disputes"
    }

    data class PendingDispute(
        val escrowId: String,
        val openedBy: String,
        val reason: String,
        val redeemScriptHex: String?,
        val psbtHex: String?,
        val refundTxHex: String?,
        val depositSats: Long?,
        val fundingScriptType: String?,
        val sellerRefundAddress: String?,
        // Undelivered LXMF targets (2026-09-02). Empty = legacy row: the
        // sweep retry derives the default targets (counterparty + arbitrator).
        // Per-target tracking lets an auto-dispute (already DISPUTED locally)
        // keep retrying the arbitrator without being dropped by the
        // "already DISPUTED" skip.
        val targets: List<String> = emptyList()
    )

    fun save(pending: PendingDispute) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val obj = JSONObject().apply {
                put("openedBy", pending.openedBy)
                put("reason", pending.reason)
                pending.redeemScriptHex?.let { put("redeem", it) }
                pending.psbtHex?.let { put("psbt", it) }
                pending.refundTxHex?.let { put("refund", it) }
                pending.depositSats?.let { put("deposit", it) }
                pending.fundingScriptType?.let { put("fscript", it) }
                pending.sellerRefundAddress?.let { put("refundAddr", it) }
                put("targets", org.json.JSONArray().also { a -> pending.targets.forEach { a.put(it) } })
                put("ts", System.currentTimeMillis())
            }
            prefs.edit().putString(key(pending.escrowId), encryptedPrefs.encrypt(obj.toString())).apply()
            Log.d(TAG, "Saved pending dispute ${pending.escrowId} targets=${pending.targets}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save pending dispute: ${e.message}")
        }
    }

    fun load(escrowId: String): PendingDispute? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(key(escrowId), null) ?: return null
            val decrypted = encryptedPrefs.decrypt(raw) ?: return null
            val obj = JSONObject(decrypted)
            PendingDispute(
                escrowId = escrowId,
                openedBy = obj.optString("openedBy", ""),
                reason = obj.optString("reason", ""),
                redeemScriptHex = if (obj.has("redeem")) obj.optString("redeem").takeIf { it.isNotBlank() } else null,
                psbtHex = if (obj.has("psbt")) obj.optString("psbt").takeIf { it.isNotBlank() } else null,
                refundTxHex = if (obj.has("refund")) obj.optString("refund").takeIf { it.isNotBlank() } else null,
                depositSats = if (obj.has("deposit")) obj.optLong("deposit") else null,
                fundingScriptType = if (obj.has("fscript")) obj.optString("fscript").takeIf { it.isNotBlank() } else null,
                sellerRefundAddress = if (obj.has("refundAddr")) obj.optString("refundAddr").takeIf { it.isNotBlank() } else null,
                targets = obj.optJSONArray("targets")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                } ?: emptyList()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load pending dispute $escrowId: ${e.message}")
            null
        }
    }

    fun allEscrowIds(): Set<String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.all.keys.mapNotNull { k ->
                if (k.startsWith("pending_")) k.removePrefix("pending_") else null
            }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    fun remove(escrowId: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(key(escrowId)).apply()
            Log.d(TAG, "Cleared pending dispute $escrowId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear pending dispute: ${e.message}")
        }
    }

    fun clear() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun key(escrowId: String) = "pending_$escrowId"
}
