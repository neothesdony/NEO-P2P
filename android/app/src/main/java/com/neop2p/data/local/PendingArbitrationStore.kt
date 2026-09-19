package com.neop2p.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent retry queue for ack-gated LXMF `evidence` deliveries
 * (Slice 3, 2026-09-01).
 *
 * Dispute delivery already has [PendingDisputeStore]; evidence had NO durable
 * retry — a kill between the local persist and the LXMF send (or a
 * permanently offline target) silently lost it. The sweep
 * (`P2POrchestrator.retryPendingArbitration`, 60s) re-sends until every
 * target acks, then removes the row. Idempotent on the receiving side:
 * evidence dedups by content (P2POrchestrator.applyEvidenceEvent).
 *
 * Storage: SharedPreferences JSON (mirrors [PendingDisputeStore]) — a tiny
 * list, no Room migration. Key: `pending_evidence_{escrowId}`.
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

        /** Serialize a [PendingEvidence] for persistence (pure, unit-testable). */
        fun toJson(p: PendingEvidence): String = JSONObject()
            .put("escrowId", p.escrowId)
            .put("submitter", p.submitter)
            .put("description", p.description)
            .put("mimeType", p.mimeType)
            .put("imageBase64", p.imageBase64)
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

    fun clear() {
        prefs().edit().clear().apply()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
