package com.neop2p.data.wallet

import android.content.Context
import com.neop2p.data.local.EncryptedPrefsStore
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists [HdPointers] (P0.3) as an AES-256-GCM blob in SharedPreferences.
 *
 * Process-death safe: [save] commits synchronously, because P0.6 write-ahead
 * persists the advanced change index BEFORE broadcasting — an async `apply()`
 * lost to a crash would let a retry reuse an already-broadcast change address.
 *
 * A corrupt, absent, or unparseable blob degrades to [HdPointers] — the
 * data-class default (including its `reserved` set), never a partially
 * populated pointer.
 */
@Singleton
class WalletAddressStateStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    private val encryptedPrefs: EncryptedPrefsStore
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Read the persisted pointers for [identity], or the defaults when nothing
     * valid is stored. When the stored blob belongs to a DIFFERENT identity the
     * pointers reset — an identity switch/restore must not carry over another
     * wallet's indices (P0.7).
     */
    fun load(identity: String = ""): HdPointers {
        val raw = prefs.getString(KEY, null) ?: return HdPointers()
        val decrypted = encryptedPrefs.decrypt(raw) ?: return HdPointers()
        val storedIdentity = parseIdentity(decrypted)
        if (storedIdentity.isNotBlank() && storedIdentity != identity) return HdPointers()
        return parse(decrypted)
    }

    /** Persist [pointers] durably (synchronous commit) for [identity]. Returns commit success. */
    fun save(pointers: HdPointers, identity: String = ""): Boolean {
        val blob = encryptedPrefs.encrypt(toJson(pointers, identity))
        return prefs.edit().putString(KEY, blob).commit()
    }

    /** Forget the pointers (identity reset/restore). */
    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    companion object {
        const val PREFS = "wallet_address_state"
        const val KEY = "hd_pointers"

        fun toJson(p: HdPointers, identity: String = ""): String {
            val o = JSONObject()
            o.put("identity", identity)
            o.put("nextExternal", p.nextExternal)
            o.put("nextChange", p.nextChange)
            val arr = JSONArray()
            p.reserved.sorted().forEach { arr.put(it) }
            o.put("reserved", arr)
            return o.toString()
        }

        /** The identity the stored blob belongs to ("" for legacy/absent blobs). */
        fun parseIdentity(json: String?): String {
            if (json.isNullOrBlank()) return ""
            return try {
                JSONObject(json).optString("identity", "")
            } catch (_: Exception) {
                ""
            }
        }

        fun parse(json: String?): HdPointers {
            if (json.isNullOrBlank()) return HdPointers()
            return try {
                val o = JSONObject(json)
                val reserved = mutableSetOf<Int>()
                val arr = o.optJSONArray("reserved")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val v = arr.optInt(i, -1)
                        if (v >= 0) reserved.add(v)
                    }
                }
                HdPointers(
                    nextExternal = o.optInt("nextExternal", 0).coerceAtLeast(0),
                    nextChange = o.optInt("nextChange", 0).coerceAtLeast(0),
                    reserved = reserved
                )
            } catch (_: Exception) {
                HdPointers()
            }
        }
    }
}
