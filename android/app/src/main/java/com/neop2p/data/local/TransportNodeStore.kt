package com.neop2p.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An extra RNS transport node endpoint. The default node is implicit and
 * never stored here; only user-added nodes live in the store.
 */
data class TransportNode(
    val host: String,
    val port: Int,
)

/**
 * Additional RNS transport nodes (Tier 3: infrastructure as optional
 * amplifiers). The default VPS node (NeoP2PConfig.RNS_TRANSPORT_NODE_HOST)
 * is ALWAYS connected — this store holds optional EXTRA nodes the user adds,
 * e.g. their own VPS or a friend's node. Every node is a packet ferry, not a
 * trust anchor: Reticulum encrypts everything end-to-end and signs announces,
 * so connecting more nodes only widens reach, never weakens security.
 *
 * Deliberately SharedPreferences + JSON, NOT Room: a tiny list, no migration.
 * Storage format: [{"host":"node.example.com","port":42420},...]
 *
 * Ports: 1..65535. Host is trimmed + lowercased (DNS is case-insensitive).
 * Duplicates (same host:port) are silently ignored.
 */
@Singleton
class TransportNodeStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    private val encryptedPrefs: EncryptedPrefsStore
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All configured extra nodes, in insertion order. */
    fun all(): List<TransportNode> {
        val raw = prefs.getString(KEY, "[]").orEmpty()
        val decrypted = encryptedPrefs.decrypt(raw) ?: return emptyList()
        return parse(decrypted)
    }

    /**
     * Add an extra node. Returns false when the input is invalid
     * (blank host / port out of range). Duplicate host:port is a no-op
     * returning true (idempotent add).
     */
    fun add(host: String, port: Int = DEFAULT_PORT): Boolean {
        val h = host.trim().lowercase()
        if (h.isBlank() || port !in 1..65535) return false
        val current = all()
        if (current.any { it.host == h && it.port == port }) return true
        prefs.edit().putString(KEY, encryptedPrefs.encrypt(toJson(current + TransportNode(h, port)))).apply()
        return true
    }

    /** Remove an extra node (case-insensitive host match). */
    fun remove(host: String, port: Int) {
        val h = host.trim().lowercase()
        val updated = all().filterNot { it.host == h && it.port == port }
        prefs.edit().putString(KEY, encryptedPrefs.encrypt(toJson(updated))).apply()
    }

    /** Forget all extra nodes (back to default-node-only). */
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS = "transport_nodes"
        private const val KEY = "nodes"
        const val DEFAULT_PORT: Int = 42420

        /**
         * Community transport-node presets (2026-09-10): a verified list of
         * public RNS transport nodes (from reticulum-android's
         * TcpCommunityServers) offered as one-tap Add in Settings. These are
         * OPT-IN — never auto-connected. They are pure packet ferries on an
         * open mesh (announces signed, traffic E2EE), so adding them only
         * widens reach.
         */
        fun communityPresets(): List<TransportNode> = COMMUNITY_PRESETS

        /** Verified public RNS transport nodes (reticulum-android
         *  TcpCommunityServers + NomadNode SEAsia). Opt-in only. */
        private val COMMUNITY_PRESETS = listOf(
            TransportNode("rns.beleth.net", 4242),
            TransportNode("rns.quad4.io", 4242),
            TransportNode("firezen.com", 4242),
            TransportNode("rns.jaykayenn.net", 4242),
            TransportNode("intr.cx", 4242),
            TransportNode("rns2.quad4.io", 4242),
            TransportNode("istanbul.reserve.network", 9034),
        )

        fun toJson(nodes: List<TransportNode>): String {
            val arr = JSONArray()
            nodes.forEach { n ->
                arr.put(JSONObject().put("host", n.host).put("port", n.port))
            }
            return arr.toString()
        }

        fun parse(json: String): List<TransportNode> {
            return try {
                val arr = JSONArray(json)
                val out = mutableListOf<TransportNode>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val host = o.optString("host").trim().lowercase()
                    val port = o.optInt("port", -1)
                    if (host.isBlank() || port !in 1..65535) continue
                    val node = TransportNode(host, port)
                    if (out.none { it.host == node.host && it.port == node.port }) {
                        out.add(node)
                    }
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
