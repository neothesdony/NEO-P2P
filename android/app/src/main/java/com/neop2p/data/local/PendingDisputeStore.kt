package com.neop2p.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
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

        /** Serialize a [PendingDispute] for persistence (pure, unit-testable). */
        fun toJson(p: PendingDispute): String = JSONObject()
            .put("openedBy", p.openedBy)
            .put("reason", p.reason)
            .apply { p.redeemScriptHex?.let { put("redeem", it) } }
            .apply { p.psbtHex?.let { put("psbt", it) } }
            .apply { p.refundTxHex?.let { put("refund", it) } }
            .apply { p.depositSats?.let { put("deposit", it) } }
            .apply { p.fundingScriptType?.let { put("fscript", it) } }
            .apply { p.sellerRefundAddress?.let { put("refundAddr", it) } }
            .apply { p.offerId?.let { put("offerId", it) } }
            .apply { p.buyerBtcAddress?.let { put("buyerBtcAddress", it) } }
            .apply { p.buyerPubKeyHex?.let { put("buyerPubKeyHex", it) } }
            .apply { p.sellerPubKeyHex?.let { put("sellerPubKeyHex", it) } }
            .apply { p.tradeSats?.let { put("tradeSats", it) } }
            .apply { p.sellerRefundAttestation?.let { put("sellerRefundAttestation", it) } }
            .apply { p.buyerAddressAttestation?.let { put("buyerAddressAttestation", it) } }
            .put("targets", JSONArray().also { a -> p.targets.forEach { a.put(it) } })
            .put("ts", System.currentTimeMillis())
            .toString()

        /** Parse a persisted dispute row, or null when malformed. */
        fun parse(escrowId: String, json: String): PendingDispute? = try {
            val o = JSONObject(json)
            PendingDispute(
                escrowId = escrowId,
                openedBy = o.optString("openedBy", ""),
                reason = o.optString("reason", ""),
                redeemScriptHex = o.optString("redeem").takeIf { it.isNotBlank() },
                psbtHex = o.optString("psbt").takeIf { it.isNotBlank() },
                refundTxHex = o.optString("refund").takeIf { it.isNotBlank() },
                depositSats = if (o.has("deposit")) o.optLong("deposit") else null,
                fundingScriptType = o.optString("fscript").takeIf { it.isNotBlank() },
                sellerRefundAddress = o.optString("refundAddr").takeIf { it.isNotBlank() },
                offerId = o.optString("offerId").takeIf { it.isNotBlank() },
                buyerBtcAddress = o.optString("buyerBtcAddress").takeIf { it.isNotBlank() },
                buyerPubKeyHex = o.optString("buyerPubKeyHex").takeIf { it.isNotBlank() },
                sellerPubKeyHex = o.optString("sellerPubKeyHex").takeIf { it.isNotBlank() },
                tradeSats = if (o.has("tradeSats")) o.optLong("tradeSats") else null,
                sellerRefundAttestation = o.optString("sellerRefundAttestation").takeIf { it.isNotBlank() },
                buyerAddressAttestation = o.optString("buyerAddressAttestation").takeIf { it.isNotBlank() },
                targets = stringArray(o.optJSONArray("targets"))
            )
        } catch (_: Exception) {
            null
        }

        private fun stringArray(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i)
                if (v.isNotBlank()) out.add(v)
            }
            return out
        }
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
        // F2 (2026-09-12): the escrow's role keys + role-signed destination
        // attestations so the arbitrator (no local escrow row) can verify
        // where a payout/refund MUST go. Persisted so the 60s retry
        // re-publishes the SAME enriched payload, not a stripped one.
        val offerId: String? = null,
        val buyerBtcAddress: String? = null,
        val buyerPubKeyHex: String? = null,
        val sellerPubKeyHex: String? = null,
        val tradeSats: Long? = null,
        val sellerRefundAttestation: String? = null,
        val buyerAddressAttestation: String? = null,
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
            prefs.edit().putString(key(pending.escrowId), encryptedPrefs.encrypt(toJson(pending))).apply()
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
            parse(escrowId, decrypted)
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
