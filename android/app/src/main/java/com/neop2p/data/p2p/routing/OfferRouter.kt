package com.neop2p.data.p2p.routing

import android.util.Log
import com.neop2p.BuildConfig
import com.neop2p.NeoP2PConfig
import com.neop2p.data.local.DeletedOfferStore
import com.neop2p.data.local.BlockedPeerStore
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toDomain
import com.neop2p.data.local.toEntity
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.p2p.RnsOfferDigest
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.domain.model.OfferStatus
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
import com.neop2p.service.NotificationDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single ingestion + routing point for every offer-related inbound event.
 *
 * This is the ONLY place that writes remote offer events into [OfferDao]:
 *   - [ingestRnsOffer] is the RNS entry path (offer_request → offer over
 *     LXMF) — it runs the same persistence pipeline as the removed Nostr
 *     collector (never downgrade a locked status, never resurrect a deleted
 *     offer, preserve the locally-applied matched_peer_id).
 *   - [receiveOffer] is the AppMessage.Offer entry path (legacy libp2p
 *     envelope, kept for the pre-key/chat envelope dispatch).
 *   - [applyOfferStatus] applies remote status updates (MATCHED/ESCROWED/
 *     PAUSED/OPEN) with the full no-downgrade / lost-claim / multiaddr rules.
 *
 * The orchestrator starts it with the process-wide scope, so ingestion is no
 * longer tied to the Home screen's ViewModel lifetime.
 */
@Singleton
class OfferRouter @Inject constructor(
    private val offerDao: OfferDao,
    private val deletedOfferStore: DeletedOfferStore,
    private val peerDao: com.neop2p.data.local.dao.PeerDao,
    private val identityManager: IdentityManager,
    private val blockedPeerStore: BlockedPeerStore,
    private val peerRegistry: com.neop2p.data.p2p.store.PeerRegistry,
    private val rnsTransport: com.neop2p.data.p2p.RnsTransport,
    private val notificationDispatcher: NotificationDispatcher
) {

    companion object {
        private const val TAG = "OfferRouter"

        /**
         * C5/D8 (2026-09-01): field-level ingest gate. The LXMF byte caps (I5)
         * bound the container, but the offer-JSON fields themselves must be
         * bounded too — a hostile peer could otherwise inject an absurd
         * fiat_amount / crypto_amount_sats into the feed, and unclamped Long
         * money math is the only overflow surface left after the integer-money
         * rule (G.M.01). Drops the whole offer on any violation (same pattern
         * as the deleted-offer skip).
         */
        fun isValidOfferPayload(
            offerJson: JsonObject,
            localNetwork: String = BuildConfig.NETWORK,
        ): Boolean {
            // Chain discriminator (2026-09-12): drop a cross-network offer even
            // if its announce aspect was spoofed or bridged. Legacy payloads
            // without the field are treated as testnet — the only network
            // deployed before the scoping change (mainnet keeps `neop2p.offers`
            // and always carries the field).
            val network = offerJson["network"]?.jsonPrimitive?.contentOrNull ?: "testnet"
            if (network != localNetwork) return false

            val sats = offerJson["crypto_amount_sats"]?.jsonPrimitive?.longOrNull
            if (sats == null || sats < NeoP2PConfig.MIN_OFFER_SATS || sats > NeoP2PConfig.MAX_OFFER_SATS) return false

            val fiat = offerJson["fiat_amount"]?.jsonPrimitive?.longOrNull
            if (fiat == null || fiat < NeoP2PConfig.MIN_OFFER_FIAT_IDR || fiat > NeoP2PConfig.MAX_OFFER_FIAT_IDR) return false

            val price = offerJson["price_per_unit"]?.jsonPrimitive?.doubleOrNull
            if (price == null || !price.isFinite() || price <= 0.0 || price > NeoP2PConfig.MAX_OFFER_PRICE) return false

            val methods = offerJson["fiat_methods"]?.let {
                runCatching { Json.decodeFromJsonElement<List<String>>(it) }.getOrNull()
            } ?: emptyList()
            if (methods.size > NeoP2PConfig.MAX_OFFER_FIAT_METHODS) return false
            for (m in methods) {
                if (m.length > NeoP2PConfig.MAX_OFFER_FIAT_METHOD_LENGTH) return false
            }
            return true
        }
    }

    /** Offer ids already notified as matched this process run (replay dedup). */
    private val notifiedOfferMatches = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Starts the router's collectors. Call exactly once from the orchestrator
     * (idempotent per process: collectors are owned by [scope] and guarded by
     * [started] so repeated calls do not stack duplicate collectors).
     *
     * Phase 4: the Nostr collectors were removed — offers arrive via
     * [ingestRnsOffer] and statuses via [applyOfferStatus], both called by the
     * orchestrator's LXMF routing. This only re-hydrates the in-memory peer
     * registry from the durable peer table.
     */
    fun startListening(scope: CoroutineScope) {
        if (started) return
        started = true
        // Re-hydrate the in-memory registry from the durable peer table so
        // direct dialing works right after a cold start (the registry itself
        // is not persistent; the DAO is). Best-effort: a failure here must
        // not block the collectors.
        scope.launch {
            runCatching {
                peerDao.getAllPeers().first().forEach { peer ->
                    if (peer.multiaddrs.isNotBlank() && peer.multiaddrs != "[]") {
                        try {
                            val addrs = Json.decodeFromJsonElement<List<String>>(
                                Json.parseToJsonElement(peer.multiaddrs).jsonArray
                            )
                            if (addrs.isNotEmpty()) {
                                peerRegistry.recordPeerSeen(peer.peer_id, multiaddrs = addrs)
                            }
                        } catch (_: Exception) {
                            Log.w(TAG, "Skipping malformed cached multiaddrs for ${peer.peer_id}")
                        }
                    }
                }
                Log.d(TAG, "Hydrated peer registry from DB")
            }.onFailure { Log.w(TAG, "Peer registry hydration failed: ${it.message}") }
        }
    }

    /**
     * Apply a remote offer status update (MATCHED/ESCROWED/PAUSED/OPEN) to the
     * local DB with the full no-downgrade / lost-claim / multiaddr-adoption
     * rules. Shared by the Nostr collector (LXMF offer_status) and the RNS LXMF
     * path (Phase 3) so both transports converge on one code path.
     */
    suspend fun applyOfferStatus(
        offerId: String,
        status: String,
        matchedPeerId: String?,
        buyerBtcAddress: String? = null,
        buyerPubKeyHex: String? = null,
        buyerAddressAttestation: String? = null,
        authorPeerId: String? = null,
        multiaddrs: List<String> = emptyList()
    ) {
        try {
            val existing = offerDao.getOfferSync(offerId)
            val myPeerId = try {
                identityManager.getOrCreateIdentity().peerId
            } catch (e: Exception) {
                // Identity locked behind device auth — fall back to a
                // conservative no-adoption path (status still applies
                // via effectiveStatus, but lost-claim convergence is
                // skipped until the next unlocked event).
                Log.w(TAG, "Identity locked; skipping lost-claim adoption: ${e.message}")
                ""
            }
            // No-downgrade guard (mirrors the raw-offer ingest path):
            // the relay replays ALL LXMF offer_status events on every
            // reconnect, and the older MATCHED event would otherwise
            // downgrade ESCROWED back to MATCHED — resurrecting the
            // "Create escrow & deposit" button on the seller's screen
            // for an escrow that already exists. Also keeps the offer
            // from unlocking except by the creator (U4).
            val effective = OfferClaimGate.effectiveStatus(
                localStatus = existing?.status,
                localMatched = existing?.matched_peer_id,
                remoteStatus = status,
                remoteMatched = matchedPeerId,
                authorPeerId = authorPeerId,
                creatorPeerId = existing?.creator_peer_id
            )
            // Two-taker convergence: adopt the relay's winner while
            // contested (OPEN/MATCHED). A losing taker's self-claim is
            // replaced by the winner's id so their UI shows "taken"
            // instead of routing into a lost trade's chat.
            val adoptedMatched = if (myPeerId.isNotBlank()) {
                OfferClaimGate.adoptMatchedPeer(
                    localStatus = existing?.status,
                    localMatched = existing?.matched_peer_id,
                    remoteMatched = matchedPeerId,
                    myPeerId = myPeerId
                )
            } else {
                null
            }
            val clearsMatch = OfferClaimGate.clearsMatch(effective)
            if (effective != null && effective != existing?.status) {
                if (clearsMatch) {
                    // U4 unlock: the creator declined the match (or re-activated
                    // a paused offer) — the offer is claimable again. Persist
                    // OPEN with matched_peer_id + locked_at cleared; a stale
                    // match would otherwise block the former taker's re-accept
                    // (claimOffer's CAS requires matched_peer_id IS NULL).
                    offerDao.updateStatusWithMatchedPeerAndLockedAt(
                        offerId, effective, "", null
                    )
                } else if (!adoptedMatched.isNullOrBlank() || !matchedPeerId.isNullOrBlank()) {
                    // Stamp locked_at when the offer becomes MATCHED so the
                    // orchestrator sweep can auto-expire a lock whose escrow
                    // is never created. NULL (any non-MATCHED transition)
                    // clears it.
                    offerDao.updateStatusWithMatchedPeerAndLockedAt(
                        offerId,
                        effective,
                        adoptedMatched ?: matchedPeerId.orEmpty(),
                        if (effective == "MATCHED") System.currentTimeMillis() else null
                    )
                } else {
                    offerDao.updateStatus(offerId, effective)
                }
            } else if (clearsMatch) {
                // Unlock re-delivery / self-heal: the event is OPEN but the row
                // is already OPEN with a stale match (legacy or a missed clear).
                // The status need not change, but the match must — the former
                // taker could otherwise never re-accept.
                offerDao.updateStatusWithMatchedPeerAndLockedAt(
                    offerId, effective ?: existing!!.status, "", null
                )
            } else if (!adoptedMatched.isNullOrBlank()) {
                // Same status, but the match converged on the winner
                // (lost-claim adoption) — persist the matched peer.
                offerDao.updateStatusWithMatchedPeer(
                    offerId, effective ?: existing!!.status, adoptedMatched
                )
            } else if (!matchedPeerId.isNullOrBlank() && existing?.matched_peer_id.isNullOrBlank()) {
                // Stale MATCHED replay after ESCROWED: keep the status
                // but still learn who matched (createSellerEscrow needs
                // it to build the escrow). Stamp locked_at only when the
                // row is actually MATCHED — an ESCROWED row must not get
                // a lock timestamp (its escrow lifecycle owns it).
                val lockNow = if (effective == "MATCHED") System.currentTimeMillis() else null
                offerDao.updateStatusWithMatchedPeerAndLockedAt(
                    offerId, effective ?: existing!!.status, matchedPeerId, lockNow
                )
            }
            // U1: persist the buyer's BTC payout address on the offer
            // row so the seller's createSellerEscrow can use it.
            buyerBtcAddress?.takeIf { it.isNotBlank() }?.let { addr ->
                offerDao.getOfferSync(offerId)?.let { e ->
                    offerDao.upsert(e.copy(btc_receive_address = addr))
                }
            }
            // C1: persist the matched buyer's secp256k1 pubkey so the seller's
            // createSellerEscrow can build a REAL 2-of-3 (buyer key != seller
            // key). Same pattern as the U1 address persist. Guarded: only lock
            // transitions (MATCHED/ESCROWED) set it, and a cleared match (U4
            // unlock) NULLS it — a stale key from a declined match must never
            // leak into a future escrow.
            if (clearsMatch) {
                offerDao.getOfferSync(offerId)?.let { e ->
                    if (e.buyer_pubkey_hex != null) offerDao.upsert(e.copy(buyer_pubkey_hex = null))
                }
            } else if (effective == "MATCHED" || effective == "ESCROWED") {
                buyerPubKeyHex?.takeIf { it.isNotBlank() }?.let { key ->
                    offerDao.getOfferSync(offerId)?.let { e ->
                        offerDao.upsert(e.copy(buyer_pubkey_hex = key))
                    }
                }
            }
            // F2: persist the buyer's role-signed payout address attestation so
            // the seller's createSellerEscrow can verify the payout destination.
            // Same lifecycle rules as buyer_pubkey_hex (C1): only lock
            // transitions set it; a cleared match NULLs it so a stale
            // attestation from a declined match can never leak into, or spoil,
            // a future escrow.
            if (clearsMatch) {
                offerDao.getOfferSync(offerId)?.let { e ->
                    if (e.buyer_address_attestation != null) {
                        offerDao.upsert(e.copy(buyer_address_attestation = null))
                    }
                }
            } else if (effective == "MATCHED" || effective == "ESCROWED") {
                buyerAddressAttestation?.takeIf { it.isNotBlank() }?.let { att ->
                    offerDao.getOfferSync(offerId)?.let { e ->
                        offerDao.upsert(e.copy(buyer_address_attestation = att))
                    }
                }
            }
            // Phase 2: adopt the acceptor's multiaddrs so the seller can
            // dial them directly (the offer event only carries the
            // seller's own). Durable in the DAO + live in the registry
            // so the seller's dial path works even after a cold start.
            if (multiaddrs.isNotEmpty()) {
                val acceptorId = if (!matchedPeerId.isNullOrBlank()) matchedPeerId
                else authorPeerId
                if (!acceptorId.isNullOrBlank()) {
                    peerRegistry.recordPeerSeen(acceptorId, multiaddrs = multiaddrs)
                    peerDao.getPeerSync(acceptorId)?.let { existingPeer ->
                        val merged = Json.decodeFromJsonElement<List<String>>(
                            Json.parseToJsonElement(existingPeer.multiaddrs.ifBlank { "[]" }).jsonArray
                        ).toMutableList()
                        merged.addAll(multiaddrs)
                        peerDao.upsert(existingPeer.copy(multiaddrs = Json.encodeToString<List<String>>(merged.distinct())))
                    } ?: peerDao.upsert(
                        com.neop2p.data.local.entity.PeerEntity(
                            peer_id = acceptorId,
                            nickname = "",
                            nostr_pubkey = "",
                            ln_node_id = "",
                            created_at = System.currentTimeMillis(),
                            reputation_score = 0f,
                            total_trades = 0,
                            last_seen = System.currentTimeMillis(),
                            relay_hints = "[]",
                            multiaddrs = Json.encodeToString<List<String>>(multiaddrs)
                        )
                    )
                    Log.d(TAG, "Adopted acceptor multiaddrs addrs=${multiaddrs.size} for $acceptorId")
                }
            }
            Log.d(TAG, "Applied status update offer=$offerId status=$effective matched=$matchedPeerId")
            // Notify the seller when a foreign peer matched their offer. The
            // old Nostr collector did this (offerStatusUpdates.collect); the
            // LXMF path (Phase 4) must too. Deduped per offer id per process
            // run so relay/LXMF replays don't re-notify.
            if (effective == OfferStatus.MATCHED.name &&
                shouldNotifyMatched(
                    creatorPeerId = existing?.creator_peer_id,
                    matchedPeerId = matchedPeerId,
                    myPeerId = myPeerId
                )
            ) {
                if (notifiedOfferMatches.add(offerId)) {
                    notificationDispatcher.notifyOfferMatched(offerId, matchedPeerId!!)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply offer status: ${e.message}")
        }
    }

    /**
     * Apply a remote offer deletion (tombstone propagation). The creator's
     * device broadcasts `offer_delete` over LXMF when it deletes an offer;
     * peers that had already ingested the offer remove the row and
     * tombstone it so a later re-announce of the original offer cannot
     * resurrect it. Only the offer's creator may delete it — a stranger's
     * spoofed delete must not kill someone else's offer.
     */
    suspend fun applyOfferDelete(offerId: String, fromPeerId: String) {
        if (offerId.isBlank()) return
        try {
            val existing = offerDao.getOfferSync(offerId) ?: return
            if (existing.creator_peer_id != fromPeerId) {
                Log.w(TAG, "Ignoring offer_delete for $offerId from non-creator $fromPeerId")
                return
            }
            offerDao.delete(existing)
            deletedOfferStore.markDeleted(offerId, existing.nostr_event_id)
            Log.d(TAG, "Applied remote offer_delete for $offerId (creator $fromPeerId)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply offer_delete: ${e.message}")
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

            // C5/D8: reject offers whose field magnitudes are out of range —
            // the LXMF byte caps bound the container, not the money fields.
            if (!isValidOfferPayload(offerJson)) {
                Log.w(TAG, "Rejecting offer with out-of-range fields")
                return
            }

            val offerId = offerJson["offer_id"]?.jsonPrimitive?.content
                ?: eventJson["id"]?.jsonPrimitive?.content ?: return

            // Local blocklist: offers from a blocked peer never enter the
            // feed (the block is local-only — never gossiped).
            val creatorId = offerJson["creator_peer_id"]?.jsonPrimitive?.content.orEmpty()
            if (creatorId.isNotBlank() && blockedPeerStore.isBlocked(creatorId)) {
                return
            }

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
            // Status comes ONLY from LXMF offer_status status events. The raw offer
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
                if (existingStatus == "OPEN") {
                    parsedStatus.name
                } else if (existingStatus == "CANCELLED" || existingStatus == "COMPLETED") {
                    // Terminal: the escrow was refunded or released. A raw
                    // offer re-announce must NEVER resurrect it — the escrow
                    // lifecycle is the authority (EscrowService marks the
                    // offer terminal and syncs it via LXMF offer_status).
                    existingStatus
                } else if (existingStatus == "MATCHED" || existingStatus == "ESCROWED") {
                    // U4: a locked offer can ONLY go back to OPEN when the
                    // author of the status event is the offer creator (the
                    // seller declining the match). No other peer may unlock a
                    // locked offer, and the raw offer event never does.
                    if (parsedStatus == OfferStatus.OPEN &&
                        offerJson["author_peer_id"]?.jsonPrimitive?.content == offerJson["creator_peer_id"]?.jsonPrimitive?.content
                    ) {
                        parsedStatus.name
                    } else {
                        existingStatus
                    }
                } else {
                    existingStatus
                }
            } ?: parsedStatus.name

            val offer = TradeOffer(
                offerId = offerId,
                creatorPeerId = offerJson["creator_peer_id"]?.jsonPrimitive?.content ?: "",
                creatorPubKeyHex = offerJson["creator_pubkey_hex"]?.jsonPrimitive?.content.orEmpty(),
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
                matchedPeerId = existing?.matched_peer_id,
                buyerPubKeyHex = existing?.buyer_pubkey_hex,
                // P0-1: payment details + the BTC receive address are LOCAL-ONLY
                // and deliberately never published to the relay. A raw offer
                // re-announce (relay replay on reconnect/refresh) must preserve
                // them — otherwise the seller's stored bank account is wiped on
                // every re-announce and the buyer never receives it.
                paymentDetails = existing?.toDomain()?.paymentDetails.orEmpty(),
                btcReceiveAddress = existing?.toDomain()?.btcReceiveAddress.orEmpty(),
                // F2/A3: the buyer's role-signed payout attestation is set
                // LOCALLY at accept time and never published in a raw offer —
                // preserve it across re-announces (REPLACE upsert would wipe
                // it), exactly like btcReceiveAddress/buyerPubKeyHex above.
                buyerAddressAttestation = existing?.buyer_address_attestation,
                // Offer lifetime: the relay carries the creator's TTL so both
                // sides converge on the same deadline. NULL = never expires.
                expiresAt = offerJson["expires_at"]?.jsonPrimitive?.long
                    ?: existing?.toDomain()?.expiresAt,
                // Local-only lifecycle metadata — never published. Preserve
                // across raw re-announces (REPLACE upsert would wipe it).
                lockedAt = existing?.toDomain()?.lockedAt
            )

            offerDao.upsert(offer.toEntity())

            // Upsert the creator's peer row so the home feed can show their
            // nickname AND so the taker holds the dial-able libp2p multiaddrs
            // for Phase-2 direct dialing. The nickname travels in the offer
            // event (never before: Peer rows were only created post-trade by
            // the reputation system, so every offer card fell back to
            // "Anonymous"). Never overwrite a richer existing row; preserve
            // stored multiaddrs when the event carries none (a re-announce
            // must not wipe addrs — same local-only preservation pattern as
            // paymentDetails/matchedPeerId).
            runCatching {
                val creatorId = offer.creatorPeerId
                if (creatorId.isNotBlank()) {
                    val existingPeer = peerDao.getPeerSync(creatorId)
                    // C10/I6: clamp + strip control chars on inbound nicknames
                    // (the identity manager sanitizes at write; the wire must
                    // be sanitized at ingest too — a hostile peer's nickname is
                    // otherwise planted verbatim into the peer row).
                    val nickname = IdentityManager.sanitizeNickname(
                        offerJson["nickname"]?.jsonPrimitive?.content.orEmpty()
                    )
                    val parsedMultiaddrs = if (offerJson["multiaddrs"] != null) {
                        try {
                            Json.decodeFromJsonElement<List<String>>(offerJson["multiaddrs"]!!)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                    val newMultiaddrs = if (parsedMultiaddrs.isNotEmpty()) {
                        Json.encodeToString<List<String>>(parsedMultiaddrs)
                    } else {
                        existingPeer?.multiaddrs ?: "[]"
                    }
                    val nicknameNeedsUpdate = existingPeer == null || existingPeer.nickname.isBlank()
                    val multiaddrsChanged = parsedMultiaddrs.isNotEmpty() &&
                        newMultiaddrs != (existingPeer?.multiaddrs ?: "[]")
                    if (nicknameNeedsUpdate || multiaddrsChanged) {
                        peerDao.upsert(
                            com.neop2p.data.local.entity.PeerEntity(
                                peer_id = creatorId,
                                nickname = if (nicknameNeedsUpdate) nickname else (existingPeer?.nickname ?: ""),
                                nostr_pubkey = eventJson["pubkey"]?.jsonPrimitive?.content
                                    ?: existingPeer?.nostr_pubkey ?: "",
                                ln_node_id = existingPeer?.ln_node_id ?: "",
                                created_at = existingPeer?.created_at ?: System.currentTimeMillis(),
                                reputation_score = existingPeer?.reputation_score ?: 0f,
                                total_trades = existingPeer?.total_trades ?: 0,
                                last_seen = System.currentTimeMillis(),
                                relay_hints = existingPeer?.relay_hints ?: "[]",
                                multiaddrs = newMultiaddrs
                            )
                        )
                    }
                    // Keep the in-memory registry in sync: Phase-2 dialing reads
                    // multiaddrsOf() from here (the DAO is durable; the registry
                    // is the live fast-path). Only refresh when the event
                    // actually carries addrs — never wipe cached ones.
                    if (parsedMultiaddrs.isNotEmpty()) {
                        peerRegistry.recordPeerSeen(creatorId, multiaddrs = parsedMultiaddrs)
                    }
                }
            }.onFailure { Log.w(TAG, "Failed to upsert creator peer: ${it.message}") }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist Nostr offer: ${e.message}")
        }
    }

    /**
     * RNS entry path (Phase 3): ingest a full offer JSON that arrived over
     * LXMF (offer_request → offer). Runs the same persistence pipeline as
     * [ingestOfferEvent] — the JSON schema is identical to the Nostr offer
     * content, so the two transports converge on one code path.
     */
    suspend fun ingestRnsOffer(offerJson: String) {
        try {
            val event = buildJsonObject {
                put("id", "rns_${offerJson.hashCode()}")
                put("content", offerJson)
            }
            ingestOfferEvent(event)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ingest RNS offer: ${e.message}")
        }
    }

    /**
     * Re-publish local MATCHED claims that never reached the counterparty
     * (kill before send). Delivered over LXMF to the offer CREATOR — the
     * matched peer's row is the local one; sending to matched_peer_id
     * re-sends the claim to OURSELVES (fixed 2026-09-07). The gate also
     * filters rows where the local identity is the creator (single-key
     * demo / same-seed devices), which would otherwise self-send.
     */
    suspend fun republishLostClaims(myPeerId: String) {
        if (myPeerId.isBlank()) return
        try {
            val lost = offerDao.getAllOffersSync().filter {
                OfferFeedGate.lostMatchTarget(
                    status = it.status,
                    matchedPeerId = it.matched_peer_id,
                    creatorPeerId = it.creator_peer_id,
                    myPeerId = myPeerId
                ) != null
            }
            for (offer in lost) {
                val target = OfferFeedGate.lostMatchTarget(
                    status = offer.status,
                    matchedPeerId = offer.matched_peer_id,
                    creatorPeerId = offer.creator_peer_id,
                    myPeerId = myPeerId
                ) ?: continue
                val result = rnsTransport.sendOfferStatus(
                    toPeerId = target,
                    offerId = offer.offer_id,
                    status = OfferStatus.MATCHED.name,
                    matchedPeerId = myPeerId,
                    buyerBtcAddress = OfferFeedGate.lostClaimBuyerAddress(
                        offer.btc_receive_address,
                        NeoP2PConfig.FEE_WALLET_ADDRESS
                    ),
                    buyerPubKeyHex = OfferFeedGate.lostClaimBuyerPubKey(
                        offer.buyer_pubkey_hex
                    ),
                    // F2: the buyer persisted its payout-address attestation on
                    // its local offer row at accept time; re-send it so the
                    // recovered MATCHED is still enough for the seller's
                    // createSellerEscrow to verify (otherwise it fails closed
                    // and the match is wedged until the 1h auto-cancel).
                    buyerAddressAttestation = offer.buyer_address_attestation,
                    authorPeerId = myPeerId
                )
                if (result.isSuccess) {
                    Log.d(TAG, "Re-published lost MATCHED ${offer.offer_id} to $target")
                } else {
                    // Result is the truth: sendOfferStatus returns Result, it
                    // does not throw — the old runCatching logged "success"
                    // even when delivery failed at send time.
                    Log.w(TAG, "Lost MATCHED re-publish to $target failed: ${result.exceptionOrNull()?.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "republishLostClaims failed: ${e.message}")
        }
    }

    /**
     * The digest commitment hash the stored row would announce — computed
     * with the CREATOR's nickname from the peer table (the same nickname the
     * creator embeds in canonicalJson when encoding), so a receiver-side hash
     * comparison against the incoming digest is exact. A missing peer row
     * falls back to the blank nickname (a spurious mismatch is self-healing:
     * the refetch re-ingests the offer and refreshes the peer row).
     *
     * 2026-09-02 (3rd-device convergence): the orchestrator's feed consumer
     * uses this to detect status/field changes on held offers.
     */
    suspend fun storedDigestHash(offer: com.neop2p.data.local.entity.TradeOfferEntity): String? = try {
        val creatorNickname = peerDao.getPeerSync(offer.creator_peer_id)?.nickname.orEmpty()
        val canonical = RnsOfferDigest.canonicalJson(offer.toDomain(), creatorNickname)
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        sha.digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "storedDigestHash failed: ${e.message}")
        null
    }

    @Volatile
    private var started = false
}

/**
 * Matched-offer notification entitlement (2026-09-06): only the offer
 * creator (seller) and the matched peer (buyer) may be notified of a
 * MATCHED event — a third-party observer that ingested the offer must
 * not get a notification whose tap opens the locked offer's details.
 *
 * The foreign-matcher guard (matcher != creator) is preserved from the
 * original inline check. A blank myPeerId (identity locked behind
 * device auth) conservatively suppresses the notification: the state
 * still converges (feed shows the locked offer), and a notification we
 * cannot authorize must not be the vector that leaks the details.
 */
internal fun shouldNotifyMatched(
    creatorPeerId: String?,
    matchedPeerId: String?,
    myPeerId: String
): Boolean {
    if (creatorPeerId == null) return false
    val matched = matchedPeerId ?: return false
    if (matched.isBlank() || matched.equals(creatorPeerId, ignoreCase = true)) return false
    if (myPeerId.isBlank()) return false
    return creatorPeerId.equals(myPeerId, ignoreCase = true) ||
        matched.equals(myPeerId, ignoreCase = true)
}
