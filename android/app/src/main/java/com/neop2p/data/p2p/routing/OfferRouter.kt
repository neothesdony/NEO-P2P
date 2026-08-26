package com.neop2p.data.p2p.routing

import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.data.local.DeletedOfferStore
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toEntity
import com.neop2p.data.p2p.NostrClient
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single ingestion + routing point for every offer-related inbound event.
 *
 * This is the ONLY place that writes relay events into [OfferDao]:
 *   - [startListening] consumes raw Nostr offer events (kind:33333) and
 *     persists them with the status/tombstone protections that used to live
 *     in HomeViewModel (never downgrade a locked status, never resurrect a
 *     deleted offer, preserve the locally-applied matched_peer_id).
 *   - [receiveOffer] is the libp2p AppMessage.Offer entry path — it feeds the
 *     same ingestion logic so both transports converge on one code path.
 *
 * The orchestrator starts it with the process-wide scope, so ingestion is no
 * longer tied to the Home screen's ViewModel lifetime.
 */
@Singleton
class OfferRouter @Inject constructor(
    private val nostrClient: NostrClient,
    private val offerDao: OfferDao,
    private val deletedOfferStore: DeletedOfferStore
) {

    companion object {
        private const val TAG = "OfferRouter"
    }

    /**
     * Starts the router's collectors. Call exactly once from the orchestrator
     * (idempotent per process: collectors are owned by [scope] and guarded by
     * [started] so repeated calls do not stack duplicate collectors).
     */
    fun startListening(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            // `collect` (not collectLatest): a new offer emission must NOT
            // cancel an in-flight ingest. During the relay replay flood
            // (4 relays × 50 events on connect) collectLatest cancels the
            // previous ingest mid-write, dropping offers ("Child of the
            // scoped flow was cancelled"). Sequential processing is fast
            // (DB upserts) and lossless.
            nostrClient.offers.collect { eventJson -> ingestOfferEvent(eventJson) }
        }
        // Apply kind:33336 status events (accept → MATCHED/ESCROWED) to the DB.
        // This is the ONLY DB writer for status updates; the orchestrator's
        // collectOfferStatuses only emits notifications.
        scope.launch {
            nostrClient.offerStatusUpdates.collect { update ->
                try {
                    val offerId = update.offerId
                    val status = update.status
                    val matchedPeerId = update.matchedPeerId
                    if (!matchedPeerId.isNullOrBlank()) {
                        offerDao.updateStatusWithMatchedPeer(offerId, status, matchedPeerId)
                    } else {
                        offerDao.updateStatus(offerId, status)
                    }
                    // U1: persist the buyer's BTC payout address on the offer
                    // row so the seller's createSellerEscrow can use it.
                    update.buyerBtcAddress?.takeIf { it.isNotBlank() }?.let { addr ->
                        offerDao.getOfferSync(offerId)?.let { existing ->
                            offerDao.upsert(existing.copy(btc_receive_address = addr))
                        }
                    }
                    Log.d(TAG, "Applied status update offer=$offerId status=$status matched=$matchedPeerId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply offer status: ${e.message}")
                }
            }
        }
    }

    /**
     * libp2p entry path: wrap the inline offer JSON in a Nostr-shaped envelope
     * and run it through the same ingest pipeline as relay offers.
     */
    suspend fun receiveOffer(msg: AppMessage.Offer): Result<Unit> = try {
        val offerJson = Json.parseToJsonElement(msg.offerJson).jsonObject
        val event = buildJsonObject {
            put(
                "id",
                offerJson["offer_id"]?.jsonPrimitive?.content
                    ?: offerJson["id"]?.jsonPrimitive?.content
                    ?: msg.offerJson.hashCode().toString()
            )
            put("content", msg.offerJson)
        }
        ingestOfferEvent(event)
        Log.d(TAG, "Ingested libp2p offer from ${msg.from}")
        Result.success(Unit)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to ingest libp2p offer: ${e.message}")
        Result.failure(e)
    }

    /**
     * Single ingest pipeline for a Nostr offer event. Shared by the relay
     * collector and the libp2p entry path.
     */
    suspend fun ingestOfferEvent(eventJson: kotlinx.serialization.json.JsonObject) {
        try {
            val content = eventJson["content"]?.jsonPrimitive?.content ?: return
            val offerJson = Json.parseToJsonElement(content).jsonObject

            val offerId = offerJson["offer_id"]?.jsonPrimitive?.content
                ?: eventJson["id"]?.jsonPrimitive?.content ?: return

            // Deleted offers: the relay replays the original event on every
            // subscription, so a tombstone check is the ONLY thing keeping a
            // deleted offer from resurrecting on the next open/update. Skip
            // re-insertion entirely.
            if (deletedOfferStore.isDeleted(offerId) ||
                deletedOfferStore.isDeleted(eventJson["id"]?.jsonPrimitive?.content)
            ) {
                return
            }

            // Preserve locally-applied matched_peer_id (from the status event)
            // — the raw offer event never carries it, and REPLACE upsert would
            // otherwise wipe it on every re-announce.
            val existing = offerDao.getOfferSync(offerId)

            // Status comes ONLY from kind:33336 status events. The raw offer
            // event carries the creation-time status (OPEN) and would wipe
            // MATCHED/ESCROWED on every re-announce — never downgrade a locked
            // status from a raw offer event.
            val parsedStatus = try {
                OfferStatus.valueOf(
                    offerJson["status"]?.jsonPrimitive?.content ?: "OPEN"
                )
            } catch (_: Exception) {
                OfferStatus.OPEN
            }
            val effectiveStatus = existing?.status?.let { existingStatus ->
                if (existingStatus == "OPEN" || existingStatus == "CANCELLED") {
                    parsedStatus.name
                } else {
                    existingStatus
                }
            } ?: parsedStatus.name

            val offer = TradeOffer(
                offerId = offerId,
                creatorPeerId = offerJson["creator_peer_id"]?.jsonPrimitive?.content ?: "",
                type = OfferType.valueOf(
                    offerJson["type"]?.jsonPrimitive?.content ?: "SELL"
                ),
                fiatAmount = offerJson["fiat_amount"]?.jsonPrimitive?.long ?: 0L,
                cryptoAmountSats = offerJson["crypto_amount_sats"]?.jsonPrimitive?.long ?: 0L,
                pricePerUnit = offerJson["price_per_unit"]?.jsonPrimitive?.double ?: 0.0,
                feePercent = offerJson["fee_percent"]?.jsonPrimitive?.double
                    ?: NeoP2PConfig.FEE_PERCENT,
                fiatMethods = if (offerJson["fiat_methods"] != null) {
                    Json.decodeFromJsonElement<List<String>>(offerJson["fiat_methods"]!!)
                } else {
                    emptyList()
                },
                status = OfferStatus.valueOf(effectiveStatus),
                createdAt = offerJson["created_at"]?.jsonPrimitive?.long
                    ?: System.currentTimeMillis(),
                nostrEventId = eventJson["id"]?.jsonPrimitive?.content,
                matchedPeerId = existing?.matched_peer_id
            )

            offerDao.upsert(offer.toEntity())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist Nostr offer: ${e.message}")
        }
    }

    @Volatile
    private var started = false
}
