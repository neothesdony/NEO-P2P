package com.neop2p.data.local

import android.content.Context
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-peer chat E2EE binding state (Option 1, 2026-09-20).
 *
 *  - [expect] pins the RNS identity hash an invite link carried
 *    (`neop2p://peer/<id>#<hash>`), so the first verified handshake must match.
 *  - [recordWarning]/[warningFor] carry the last binding failure
 *    (invite mismatch / identity change) to the chat banner.
 *
 * SharedPreferences + JSON, encrypted via [EncryptedPrefsStore], mirroring
 * [TransportNodeStore]. Cleared by Settings → destroy local data.
 */
@Singleton
class PeerBindingStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    private val encryptedPrefs: EncryptedPrefsStore
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Entry(val expectedHash: String?, val warning: String?)

    fun expect(peerId: String, identityHashHex: String) {
        if (peerId.isBlank() || identityHashHex.isBlank()) return
        val map = read()
        map[peerId] = Entry(identityHashHex.lowercase(), map[peerId]?.warning)
        write(map)
    }

    fun expectedFor(peerId: String): String? = read()[peerId]?.expectedHash

    fun recordWarning(peerId: String, warning: String) {
        if (peerId.isBlank() || warning.isBlank()) return
        val map = read()
        val prev = map[peerId] ?: Entry(null, null)
        map[peerId] = prev.copy(warning = warning)
        write(map)
    }

    fun warningFor(peerId: String): String? = read()[peerId]?.warning

    fun clearWarning(peerId: String) {
        val map = read()
        val prev = map[peerId] ?: return
        if (prev.warning == null) return
        map[peerId] = prev.copy(warning = null)
        write(map)
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun read(): MutableMap<String, Entry> {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        val plain = encryptedPrefs.decrypt(raw) ?: return mutableMapOf()
        return parse(plain).toMutableMap()
    }

    private fun write(map: Map<String, Entry>) {
        prefs.edit().putString(KEY, encryptedPrefs.encrypt(encode(map))).apply()
    }

    companion object {
        private const val PREFS = "peer_bindings"
        private const val KEY = "bindings"
        const val WARNING_INVITE_MISMATCH = "INVITE_MISMATCH"
        const val WARNING_IDENTITY_CHANGED = "IDENTITY_CHANGED"

        fun encode(map: Map<String, Entry>): String {
            val root = JSONObject()
            for ((peerId, e) in map) {
                val o = JSONObject()
                e.expectedHash?.let { o.put("expected", it) }
                e.warning?.let { o.put("warning", it) }
                root.put(peerId, o)
            }
            return root.toString()
        }

        fun parse(json: String): Map<String, Entry> {
            val out = LinkedHashMap<String, Entry>()
            try {
                val root = JSONObject(json)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val peerId = keys.next()
                    if (peerId.isBlank()) continue
                    val o = root.optJSONObject(peerId) ?: continue
                    val expected = o.optString("expected").trim().ifBlank { null }
                    val warning = o.optString("warning").trim().ifBlank { null }
                    if (expected == null && warning == null) continue
                    out[peerId] = Entry(expected, warning)
                }
            } catch (_: Exception) {
            }
            return out
        }
    }
}
