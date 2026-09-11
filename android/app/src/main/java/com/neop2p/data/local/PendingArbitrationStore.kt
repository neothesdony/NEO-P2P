package com.neop2p.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent retry queue for ack-gated LXMF `evidence` / `resolution`
 * deliveries (Slice 3, 2026-09-01).
 *
 * Dispute delivery already has [PendingDisputeStore]; evidence and resolution
 * had NO durable retry — a kill between the local persist and the LXMF send
 * (or a permanently offline target) silently lost them. The sweep
 * (`P2POrchestrator.retryPendingArbitration`, 60s) re-sends until every
 * target acks, then removes the row. Idempotent on the receiving side:
 * evidence dedups by content (P2POrchestrator.applyEvidenceEvent) and the
 * resolution resolved-gate skips already-applied disputes.
 *
 * Storage: SharedPreferences JSON (mirrors [PendingDisputeStore]) — a tiny
 * list, no Room migration. Keys: `pending_evidence_{escrowId}` /
 * `pending_resolution_{escrowId}`.
 */
@Singleton
class PendingArbitrationStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptedPrefs: EncryptedPrefsStore
) {
    companion object {
        private const val TAG = "PendingArbitrationStore"
        private const val PREFS_NAME = "neop2p_pending_arbitration"
        private const val KEY_EVIDENCE = "pending_evidence_"
        private const val KEY_RESOLUTION = "pending_resolution_"

        /** Serialize a [PendingEvidence] for persistence (pure, unit-testable). */
        fun toJson(p: PendingEvidence): String = JSONObject()
            .put("escrowId", p.escrowId)
            .put("submitter", p.submitter)
            .put("description", p.description)
            .put("mimeType", p.mimeType)
            .put("imageBase64", p.imageBase64)
            .put("targets", JSONArray().also { a -> p.targets.forEach { a.put(it) } })
            .toString()

        /** Serialize a [PendingResolution] for persistence (pure, unit-testable). */
        fun toJson(p: PendingResolution): String = JSONObject()
            .put("escrowId", p.escrowId)
            .put("decision", p.decision)
            .put("arbitratorSigHex", p.arbitratorSigHex)
            .apply { p.notes?.let { put("notes", it) } }
            .apply { p.sellerRefundAddress?.let { put("sellerRefundAddress", it) } }
            .apply { p.signedTxHex?.let { put("signedTxHex", it) } }
            .put("targets", JSONArray().also { a -> p.targets.forEach { a.put(it) } })
            .toString()

        /** Parse a persisted evidence row, or null when malformed. */
        fun parseEvidence(escrowId: String, json: String): PendingEvidence? = try {
            val o = JSONObject(json)
            PendingEvidence(
                escrowId = escrowId,
                submitter = o.optString("submitter", ""),
                description = o.optString("description", ""),
                mimeType = o.optString("mimeType", "image/jpeg"),
                imageBase64 = o.optString("imageBase64", ""),
                targets = stringArray(o.optJSONArray("targets"))
            )
        } catch (_: Exception) {
            null
        }

        /** Parse a persisted resolution row, or null when malformed. */
        fun parseResolution(escrowId: String, json: String): PendingResolution? = try {
            val o = JSONObject(json)
            PendingResolution(
                escrowId = escrowId,
                decision = o.optString("decision", ""),
                arbitratorSigHex = o.optString("arbitratorSigHex", ""),
                notes = o.optString("notes").takeIf { it.isNotBlank() },
                sellerRefundAddress = o.optString("sellerRefundAddress").takeIf { it.isNotBlank() },
                signedTxHex = o.optString("signedTxHex").takeIf { it.isNotBlank() },
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

    data class PendingEvidence(
        val escrowId: String,
        val submitter: String,
        val description: String,
        val mimeType: String,
        val imageBase64: String,
        val targets: List<String>
    )

    data class PendingResolution(
        val escrowId: String,
        val decision: String,
        val arbitratorSigHex: String,
        val notes: String?,
        val sellerRefundAddress: String?,
        val signedTxHex: String?,
        val targets: List<String>
    )

    fun saveEvidence(p: PendingEvidence) {
        try {
            prefs().edit().putString(KEY_EVIDENCE + p.escrowId, encryptedPrefs.encrypt(toJson(p))).apply()
            Log.d(TAG, "Saved pending evidence ${p.escrowId}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save pending evidence: ${e.message}")
        }
    }

    fun loadEvidence(escrowId: String): PendingEvidence? {
        val raw = prefs().getString(KEY_EVIDENCE + escrowId, null) ?: return null
        val decrypted = encryptedPrefs.decrypt(raw) ?: return null
        return parseEvidence(escrowId, decrypted)
    }

    fun allEvidence(): List<PendingEvidence> = try {
        prefs().all.entries.mapNotNull { (k, v) ->
            if (k.startsWith(KEY_EVIDENCE)) {
                val raw = v?.toString().orEmpty()
                val decrypted = encryptedPrefs.decrypt(raw) ?: return@mapNotNull null
                parseEvidence(k.removePrefix(KEY_EVIDENCE), decrypted)
            } else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to list pending evidence: ${e.message}")
        emptyList()
    }

    fun removeEvidence(escrowId: String) {
        try {
            prefs().edit().remove(KEY_EVIDENCE + escrowId).apply()
            Log.d(TAG, "Cleared pending evidence $escrowId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear pending evidence: ${e.message}")
        }
    }

    fun saveResolution(p: PendingResolution) {
        try {
            prefs().edit().putString(KEY_RESOLUTION + p.escrowId, encryptedPrefs.encrypt(toJson(p))).apply()
            Log.d(TAG, "Saved pending resolution ${p.escrowId}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save pending resolution: ${e.message}")
        }
    }

    fun loadResolution(escrowId: String): PendingResolution? {
        val raw = prefs().getString(KEY_RESOLUTION + escrowId, null) ?: return null
        val decrypted = encryptedPrefs.decrypt(raw) ?: return null
        return parseResolution(escrowId, decrypted)
    }

    fun allResolutions(): List<PendingResolution> = try {
        prefs().all.entries.mapNotNull { (k, v) ->
            if (k.startsWith(KEY_RESOLUTION)) {
                val raw = v?.toString().orEmpty()
                val decrypted = encryptedPrefs.decrypt(raw) ?: return@mapNotNull null
                parseResolution(k.removePrefix(KEY_RESOLUTION), decrypted)
            } else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to list pending resolutions: ${e.message}")
        emptyList()
    }

    fun removeResolution(escrowId: String) {
        try {
            prefs().edit().remove(KEY_RESOLUTION + escrowId).apply()
            Log.d(TAG, "Cleared pending resolution $escrowId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear pending resolution: ${e.message}")
        }
    }

    fun clear() {
        prefs().edit().clear().apply()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
