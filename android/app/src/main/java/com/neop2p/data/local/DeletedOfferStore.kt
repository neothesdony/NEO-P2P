package com.neop2p.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray

/**
 * Persistent tombstone store for deleted offers.
 *
 * When an offer is deleted (by us or via a peer's NIP-09 event), the relay
 * still holds the original neop2p/offers announce event and replays it on every
 * subscription — so deleting the Room row alone lets the offer RESURRECT on
 * the next app open / reconnect. This store remembers "this offer/event is
 * deleted" so replay consumers can skip re-insertion.
 *
 * Keyed by BOTH the offerId and the Nostr event id (deletions reference the
 * event id; replayed events carry their own event id), so either form hits.
 *
 * NOTE: offer ids are timestamp-based and never reused, so a newly created
 * offer can never collide with a tombstone — no eviction is needed.
 */
@Singleton
class DeletedOfferStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DeletedOfferStore"
        private const val PREFS_NAME = "neop2p_deleted_offers"
        private const val KEY_DELETED = "deleted_offer_ids"
    }

    private val deleted = mutableSetOf<String>()

    init {
        load()
    }

    /** Remember that [offerId] and/or its Nostr [eventId] are deleted. */
    fun markDeleted(offerId: String, eventId: String? = null) {
        var changed = false
        if (offerId.isNotBlank() && deleted.add(offerId)) changed = true
        if (!eventId.isNullOrBlank() && deleted.add(eventId)) changed = true
        if (changed) persist()
    }

    /** True if the offer (or its event) carries a deletion tombstone. */
    fun isDeleted(offerIdOrEventId: String?): Boolean =
        !offerIdOrEventId.isNullOrBlank() && offerIdOrEventId in deleted

    fun clear() {
        deleted.clear()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_DELETED).apply()
    }

    private fun load() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DELETED, null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { deleted.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load deleted-offer tombstones: ${e.message}")
        }
    }

    private fun persist() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray()
            deleted.forEach { arr.put(it) }
            prefs.edit().putString(KEY_DELETED, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist deleted-offer tombstones: ${e.message}")
        }
    }
}
