package com.neop2p.data.p2p.routing

import android.util.Log
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.escrow.EscrowService
import com.neop2p.domain.model.EscrowStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single ingestion + routing point for inbound escrow lifecycle events
 * (LXMF "escrow_status") — the 2-party counterpart of [OfferRouter].
 *
 * The seller's device creates the escrow row; the buyer's device has NO row
 * at all. Every service transition publishes an escrow_status LXMF message
 * carrying the mutable escrow fields; this router upserts them on the
 * counterparty so both devices converge on one escrow (chat unlock, receipt
 * flow, status screen).
 *
 * Safety rules (mirror the service's own state machine):
 *   - Only apply events for escrows the local identity is a party to
 *     (buyer_peer_id / seller_peer_id == myPeerId).
 *   - Never downgrade: a terminal status (RELEASED/REFUNDED/CANCELLED/
 *     DISPUTED) is locked forever; earlier states never move backwards.
 *   - The remote event may only carry status transitions the happy path
 *     allows (FUNDING→FUNDED→PAYMENT_PENDING→RECEIPT_SENT→CONFIRMING, or
 *     →DISPUTED); everything else is ignored.
 *   - Local-only fields (psbt_unsigned, signatures, arbitrator_*) are never
 *     overwritten by remote events.
 *
 * This is the ONLY DB writer for remote escrow rows; the service's own
 * transitions are the local writer (and the publisher of these events).
 */
@Singleton
class EscrowRouter @Inject constructor(
    private val escrowDao: EscrowDao,
    private val escrowService: EscrowService,
    private val identityManager: IdentityManager
) {

    companion object {
        private const val TAG = "EscrowRouter"

        /** Statuses that are terminal — a remote event can never change them. */
        private val TERMINAL = setOf(
            EscrowStatus.RELEASED.name,
            EscrowStatus.REFUNDED.name,
            EscrowStatus.CANCELLED.name
            // DISPUTED is NOT terminal-locked: the arbitrator's outcome
            // (RELEASED/REFUNDED, published via LXMF escrow_status by the party that
            // applied the LXMF resolution message resolution) must close the counterparty's
            // DISPUTED row. Anything else landing on DISPUTED is still blocked
            // below (only arbitration outcomes may move it).
        )

        /**
         * Pure transition rule: return the effective new status for a remote
         * event, or null when the transition must be ignored. Mirrored by
         * EscrowRouterApplyTest.
         */
        fun applyRemoteStatus(localStatus: String?, remoteStatus: String): String? {
            if (remoteStatus !in ALLOWED_REMOTE) return null
            if (localStatus == null) {
                // No local row: accept FUNDING (escrow announcement) or
                // FUNDED (late join); later states without a local row are
                // unreconstructible — ignore.
                return if (remoteStatus == EscrowStatus.FUNDING.name ||
                    remoteStatus == EscrowStatus.FUNDED.name
                ) remoteStatus else null
            }
            if (localStatus in TERMINAL) return null
            if (localStatus == remoteStatus) return null
            // A remote DISPUTE may open from any non-terminal state EXCEPT
            // FUNDING (2026-09-05): the deposit is either not yet broadcast
            // (nothing to arbitrate) or in flight (unconfirmed — the
            // arbitrator's resolution would spend a nonexistent output).
            // Mirrors EscrowService.canDisputeFromStatus; a stale/forged
            // FUNDING dispute from an older build must not flip the mirrored row.
            if (remoteStatus == EscrowStatus.DISPUTED.name) {
                return if (localStatus == EscrowStatus.FUNDING.name) null else remoteStatus
            }
            // Arbitration outcomes: a DISPUTED row may only close via the
            // arbitrator's RELEASED/REFUNDED (LXMF resolution message resolution, re-synced
            // as LXMF escrow_status by the party that applied it). Anything else
            // landing on DISPUTED (CANCELLED, FUNDED, ...) is dropped.
            if (localStatus == EscrowStatus.DISPUTED.name) {
                return if (remoteStatus == EscrowStatus.RELEASED.name ||
                    remoteStatus == EscrowStatus.REFUNDED.name
                ) remoteStatus else null
            }
            // Terminal outcomes (auto-cancel / auto-refund / dispute
            // resolution) may land from any non-terminal state — the
            // seller's sweep is the authority and the buyer must converge
            // (previously the buyer's row stayed FUNDING forever with an
            // expired countdown while the seller had already cancelled).
            if (remoteStatus == EscrowStatus.CANCELLED.name ||
                remoteStatus == EscrowStatus.REFUNDED.name
            ) return remoteStatus
            // Otherwise only strictly-forward happy-path moves are allowed.
            val order = listOf(
                EscrowStatus.FUNDING.name,
                EscrowStatus.FUNDED.name,
                EscrowStatus.SIGNED.name,
                EscrowStatus.PAYMENT_PENDING.name,
                EscrowStatus.RECEIPT_SENT.name,
                EscrowStatus.CONFIRMING.name,
                EscrowStatus.RELEASED.name
            )
            val li = order.indexOf(localStatus)
            val ri = order.indexOf(remoteStatus)
            if (li == -1 || ri == -1 || ri <= li) return null
            return remoteStatus
        }

        private val ALLOWED_REMOTE = setOf(
            EscrowStatus.FUNDING.name,
            EscrowStatus.FUNDED.name,
            EscrowStatus.SIGNED.name,
            EscrowStatus.PAYMENT_PENDING.name,
            EscrowStatus.RECEIPT_SENT.name,
            EscrowStatus.CONFIRMING.name,
            EscrowStatus.RELEASED.name,
            EscrowStatus.DISPUTED.name,
            EscrowStatus.CANCELLED.name,
            EscrowStatus.REFUNDED.name
        )
    }

    /** Starts the router's collector. Call exactly once from the orchestrator. */
    fun startListening(scope: CoroutineScope) {
        if (started) return
        started = true
        // Phase 4: escrow status events arrive via the orchestrator's LXMF
        // routing (ingestEscrowStatus is called directly) — no collector here.
    }

    /** Ingest one escrow_status event (content JSON) into the local escrow row. */
    suspend fun ingestEscrowStatus(obj: kotlinx.serialization.json.JsonObject) {
        try {
            val escrowId = obj["escrow_id"]?.jsonPrimitive?.content ?: return
            val remoteStatus = obj["status"]?.jsonPrimitive?.content ?: return
            val buyerPeerId = obj["buyer_peer_id"]?.jsonPrimitive?.content ?: ""
            val sellerPeerId = obj["seller_peer_id"]?.jsonPrimitive?.content ?: ""
            val myPeerId = identityManager.myPeerId()

            // Party gate: only escrows involving the local identity.
            if (buyerPeerId != myPeerId && sellerPeerId != myPeerId) return

            val local = escrowDao.getEscrowSync(escrowId)
            val effective = applyRemoteStatus(local?.status, remoteStatus)
            // The seller's real creation time (carried since 2026-08-27) —
            // the buyer's mirrored row must use it, not its own ingest time,
            // or the funding countdown is wrong on the buyer side.
            val remoteCreatedAt = obj["created_at"]?.jsonPrimitive?.content?.toLongOrNull()

            if (local == null) {
                // FUNDING announcements (and late FUNDED joins) create the row.
                if (effective == null) return
                // No local row: build a minimal one from the event fields so
                // the buyer (who never creates the row) gets a status screen.
                val entity = EscrowEntity(
                    escrow_id = escrowId,
                    offer_id = obj["offer_id"]?.jsonPrimitive?.content ?: return,
                    type = "ON_CHAIN",
                    funding_address = obj["funding_address"]?.jsonPrimitive?.content,
                    funding_script_type = obj["funding_script_type"]?.jsonPrimitive?.content ?: "LEGACY",
                    deposit_amount_sats = obj["deposit_sats"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    trade_amount_sats = obj["trade_sats"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    fee_amount_sats = 0L,
                    fee_address = "",
                    buyer_peer_id = buyerPeerId,
                    seller_peer_id = sellerPeerId,
                    buyer_pubkey_hex = obj["buyer_pubkey_hex"]?.jsonPrimitive?.content,
                    seller_pubkey_hex = obj["seller_pubkey_hex"]?.jsonPrimitive?.content,
                    status = effective,
                    created_at = remoteCreatedAt ?: System.currentTimeMillis(),
                    buyer_btc_address = obj["buyer_btc_address"]?.jsonPrimitive?.content,
                    funded_at = obj["funded_at"]?.jsonPrimitive?.content?.toLongOrNull(),
                    paid_at = obj["paid_at"]?.jsonPrimitive?.content?.toLongOrNull(),
                    receipt_reference = obj["receipt_reference"]?.jsonPrimitive?.content,
                    receipt_sent_at = obj["receipt_sent_at"]?.jsonPrimitive?.content?.toLongOrNull(),
                    funding_tx_id = obj["funding_tx_id"]?.jsonPrimitive?.content,
                    funding_vout = obj["funding_vout"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    payout_tx_id = obj["payout_tx_id"]?.jsonPrimitive?.content,
                    refund_destination = obj["refund_destination"]?.jsonPrimitive?.content,
                    seller_refund_address = obj["seller_refund_address"]?.jsonPrimitive?.content,
                    redeem_script_hex = obj["redeem_script_hex"]?.jsonPrimitive?.content,
                    funded_amount_sats = obj["funded_amount_sats"]?.jsonPrimitive?.content?.toLongOrNull()
                )
                escrowDao.upsert(entity)
                Log.d(TAG, "Created remote escrow $escrowId status=$effective")
                return
            }

            // Existing row: advance status + refresh mutable fields (never
            // signatures / psbt / arbitrator fields). Same-status events
            // (e.g. FUNDING re-published WITH the funding txid) keep the
            // status but still refresh the mutable fields — otherwise the
            // buyer's row would never learn the txid and would stay
            // "Pending / waiting for deposit" forever.
            val updated = local.copy(
                status = effective ?: local.status,
                // Always adopt the remote creation time when present. The
                // seller publishes its own row's created_at, so for the
                // seller's row this is a no-op; for the buyer's mirrored row
                // it heals a wrong deadline (single-key model: the old
                // `seller_peer_id == myPeerId` guard matched on the buyer's
                // device too, so a stale ingest-time created_at was never
                // corrected and the funding countdown stayed wrong forever).
                created_at = remoteCreatedAt ?: local.created_at,
                funding_tx_id = obj["funding_tx_id"]?.jsonPrimitive?.content ?: local.funding_tx_id,
                funding_vout = obj["funding_vout"]?.jsonPrimitive?.content?.toLongOrNull() ?: local.funding_vout,
                payout_tx_id = obj["payout_tx_id"]?.jsonPrimitive?.content ?: local.payout_tx_id,
                funded_at = obj["funded_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: local.funded_at,
                paid_at = obj["paid_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: local.paid_at,
                receipt_reference = obj["receipt_reference"]?.jsonPrimitive?.content ?: local.receipt_reference,
                receipt_sent_at = obj["receipt_sent_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: local.receipt_sent_at,
                buyer_btc_address = obj["buyer_btc_address"]?.jsonPrimitive?.content ?: local.buyer_btc_address,
                refund_destination = obj["refund_destination"]?.jsonPrimitive?.content ?: local.refund_destination,
                seller_refund_address = obj["seller_refund_address"]?.jsonPrimitive?.content ?: local.seller_refund_address,
                // Never overwrite a local redeem script with a remote blank,
                // but adopt the remote one when the local row lacks it (the
                // buyer's mirror needs it to apply arbitration resolutions).
                redeem_script_hex = obj["redeem_script_hex"]?.jsonPrimitive?.content
                    ?: local.redeem_script_hex,
                funded_amount_sats = obj["funded_amount_sats"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: local.funded_amount_sats
            )
            escrowDao.upsert(updated)

            // Only a real transition fires the user-facing notification;
            // same-status refreshes (txid updates) are silent.
            if (effective != null) {
                escrowService.emitRemoteTransition(escrowId, effective)
            } else if (local.funding_tx_id != updated.funding_tx_id) {
                // Same-status event that carried a NEW funding txid (seller
                // bound the deposit but it is not confirmed yet): reload the
                // counterparty's open screen so it flips from "Waiting for
                // deposit" to "In progress / waiting for confirmation".
                // Status "funding_txid" is unmapped in the orchestrator, so
                // no notification is fired.
                escrowService.emitRemoteTransition(escrowId, "funding_txid")
            }

            Log.d(TAG, "Applied remote escrow $escrowId ${local.status}→${effective ?: local.status}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ingest escrow status: ${e.message}")
        }
    }

    @Volatile
    private var started = false
}
