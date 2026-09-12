package com.neop2p.data.escrow

import android.util.Log
import com.neop2p.BuildConfig
import com.neop2p.NeoP2PConfig
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.data.local.toDomain
import com.neop2p.data.local.toEntity
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.*
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionWitness
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import com.neop2p.domain.model.BitcoinAddressType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-chain Bitcoin escrow service for NEO-P2P.
 *
 * Manages 2-of-3 multisig escrow using P2SH addresses.
 * The 0.5% fee is built into the pre-signed payout transaction.
 *
 * Flow:
 *   1. createEscrow() → generates 2-of-3 P2SH address, stores in Room
 *   2. Seller transfers BTC to the P2SH address (out-of-app)
 *   3. onEscrowFunded() → verifies on-chain via Mempool API
 *   4. generatePayoutTransaction() → creates unsigned raw tx
 *   5. signPayoutAsBuyer() → buyer signs with ECKey (role-validated)
 *   6. signPayoutAsSeller() → seller signs with ECKey (role-validated)
 *   7. releaseFunds() → verifies 2-of-3 signatures then broadcasts
 *
 * P0-1 hardening (fixed vs. the removed reference):
 *   - Each escrow stores buyer_pubkey_hex / seller_pubkey_hex at creation.
 *   - A signature is only accepted for a role if its public key matches the
 *     role key stored on the escrow (no more signing both roles with one key).
 *   - releaseFunds() verifies the 2 signatures come from 2 DIFFERENT keys in
 *     the 2-of-3 and that they actually sign the payout input before broadcast.
 */
@Singleton
class EscrowService @Inject constructor(
    private val db: AppDatabase,
    private val chainMonitor: ChainMonitor,
    private val identityManager: IdentityManager,
    private val rnsTransport: com.neop2p.data.p2p.RnsTransport,
    private val pendingDisputeStore: com.neop2p.data.local.PendingDisputeStore
) {
    companion object {
        private const val TAG = "EscrowService"

        /**
         * E7 (2026-09-01): is the funding deposit GONE — the funding tx is no
         * longer confirmed AND the escrow address holds no balance (confirmed
         * + mempool)? A reorg can un-confirm or drop the funding tx after
         * FUNDED was set; auto-refunding then broadcasts a tx spending a
         * nonexistent output. When true, the sweep reverts the escrow to
         * FUNDING so the existing machinery re-verifies (promote if a deposit
         * reappears) or cancels (funding timeout) instead of refunding.
         */
        fun fundingDepositGone(confirmed: Boolean, addressHasBalance: Boolean): Boolean =
            !confirmed && !addressHasBalance

        /**
         * E4 (2026-09-01): a reorg can shave the funding tx's depth BELOW the
         * escrow's required confirmations while the address still holds the
         * deposit (so the E7 "gone" test passes). Refunding then spends an
         * input whose depth the escrow gate would never have accepted. When
         * the confirmed depth is below [requiredConfirmations], the sweep
         * reverts to FUNDING instead of refunding.
         */
        fun fundingDepthBelowRequired(confirmed: Boolean, confirmations: Long, requiredConfirmations: Int): Boolean =
            confirmed && confirmations < requiredConfirmations

        /**
         * E7+E4 sweep decision for a FUNDED/SIGNED escrow past its refund
         * window, as a pure function (mirrored by EscrowReorgTest).
         *
         * Returns:
         *  - "SKIP"   — funding tx info unavailable (explorer unreachable):
         *               fail closed, never refund or revert on uncertainty.
         *  - "REVERT" — deposit lost (unconfirmed + no address balance, E7) OR
         *               depth dropped below the required confirmations while
         *               still confirmed (E4): back to FUNDING so the sweep
         *               re-verifies or cancels instead of refunding.
         *  - "REFUND" — funding tx confirmed at sufficient depth: proceed.
         */
        fun fundingRefundDecision(
            txInfo: ChainMonitor.TxInfo?,
            addressHasBalance: Boolean,
            requiredConfirmations: Int
        ): String {
            if (txInfo == null) return "SKIP"
            if (fundingDepositGone(txInfo.confirmed, addressHasBalance)) return "REVERT"
            if (fundingDepthBelowRequired(txInfo.confirmed, txInfo.confirmations, requiredConfirmations)) return "REVERT"
            return "REFUND"
        }

        /**
         * C1 (2026-09-11): the 2-of-3 role keys must be REAL and DISTINCT.
         * The pre-C1 model passed the same key for both roles on one device,
         * making the multisig effectively 2-of-2 (device + arbitrator) — the
         * seller could sign a refund to themselves after receiving fiat.
         * Blank keys fail closed: a peer on an old build cannot create an
         * escrow with a p2p-upgrade peer until both are upgraded.
         */
        fun isValidRoleKeyPair(buyerPubKeyHex: String, sellerPubKeyHex: String): Boolean {
            val buyer = buyerPubKeyHex.trim()
            val seller = sellerPubKeyHex.trim()
            if (buyer.isBlank() || seller.isBlank()) return false
            if (buyer.equals(seller, ignoreCase = true)) return false
            return true
        }

        /**
         * C1d (2026-09-11): a buyer payout signature is only acceptable when
         * it is non-blank, hex, and structurally a valid DER+SIGHASH signature.
         * The full cryptographic verification against buyer_pubkey_hex happens
         * in [storeBuyerSignature] (needs the tx + redeem script). This pure
         * gate rejects obvious garbage before any DB write.
         */
        fun isValidBuyerSignature(sigHex: String, buyerPubKeyHex: String): Boolean {
            val sig = sigHex.trim()
            val key = buyerPubKeyHex.trim()
            if (sig.isBlank() || key.isBlank()) return false
            if (sig.length % 2 != 0) return false
            if (!sig.all { it in "0123456789abcdefABCDEF" }) return false
            val bytes = hexToBytes(sig)
            // DER signature: 0x30 <len> ... + 1-byte SIGHASH_ALL (0x01).
            if (bytes.size < 9 || bytes.size > 73) return false
            if (bytes[0] != 0x30.toByte()) return false
            if (bytes.last() != Transaction.SigHash.ALL.value.toByte()) return false
            return true
        }

        /** Companion-scope hex decoder (the instance [hexToBytes] is not static). */
        private fun hexToBytes(hex: String): ByteArray {
            val data = ByteArray(hex.length / 2)
            for (i in hex.indices step 2) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            }
            return data
        }

        /**
         * Freshness gate for funding-deposit binding (2026-09-01). Escrow
         * funding addresses are DETERMINISTIC — derived from the 2-of-3 keys —
         * so the same buyer/seller pair always reuses the same address.
         * Without this gate, a deposit from a PREVIOUS escrow between the same
         * peers (same address, same amount) is re-bound to a new escrow and
         * promotes it to FUNDED without any fresh funds. A funding tx mined
         * before the escrow was created is stale. Unconfirmed txs (block_time
         * unknown = mempool) are never treated as stale — the broadcast
         * necessarily happened after creation. A confirmed tx without
         * block_time falls back to the escrow creation time so the sweep's
         * "promote if a deposit reappears" path does not fail closed on
         * missing metadata.
         */
        fun fundingTxIsStale(blockTimeSec: Long, confirmed: Boolean, escrowCreatedAt: Long): Boolean =
            confirmed && blockTimeSec > 0L && blockTimeSec < escrowCreatedAt / 1000

        /**
         * E8 (2026-09-10): promotion gate for the sweep's FUNDING → FUNDED
         * path. The manual path (onEscrowFunded) enforces
         * required_confirmations, but the sweep's "promote if a deposit
         * reappears" branch never re-checked depth — a mempool deposit
         * (0 confirmations) was promoted to FUNDED the moment the funding
         * window passed. A payout/refund then spends an input the manual
         * gate would never have accepted. When the funding tx is not yet
         * confirmed at the required depth, the sweep must keep FUNDING and
         * retry on the next sweep instead of promoting.
         *
         * Returns:
         *  - "PROMOTE" — funding tx confirmed at >= required depth: proceed.
         *  - "WAIT"    — tx unconfirmed or below the required depth: keep
         *                FUNDING, retry next sweep.
         *  - "SKIP"    — tx info unavailable (explorer unreachable): fail
         *                closed, never promote on uncertainty.
         */
        fun fundingPromotionDecision(
            txInfo: ChainMonitor.TxInfo?,
            requiredConfirmations: Int
        ): String {
            if (txInfo == null) return "SKIP"
            if (!txInfo.confirmed) return "WAIT"
            if (txInfo.confirmations < requiredConfirmations) return "WAIT"
            return "PROMOTE"
        }

        /**
         * Cancel-path decision (2026-09-07). A FUNDING escrow with no bound txid
         * and no recorded deposit has nothing on-chain to spend — cancelling it is
         * a local-only state change. Every other cancelable status (FUNDED,
         * DISPUTED, CONFIRMING) and any FUNDING escrow with a txid or partial
         * deposit must build + broadcast a refund tx. Mirrors the sweep's
         * auto-cancel branch (expireStaleEscrows) so the user-initiated path and
         * the sweep agree on what "cancel" means.
         */
        fun cancelRequiresOnChainRefund(
            status: EscrowStatus,
            fundingTxId: String?,
            fundedAmountSats: Long?
        ): Boolean {
            if (status != EscrowStatus.FUNDING) return true
            if (!fundingTxId.isNullOrBlank()) return true
            if ((fundedAmountSats ?: 0L) > 0L) return true
            return false
        }

        /** Minimum output value Bitcoin nodes accept (P2PKH dust: 546 sats).
         *  A fee output below this makes the payout un-broadcastable
         *  ("dust, tx with dust output", RPC -26). */
        const val DUST_THRESHOLD_SATS = 546L

        /**
         * Resolve the buyer's BTC payout address for an escrow, in order:
         * escrow row → offer row → null. NEVER falls back to the multisig
         * funding address — that fallback paid the buyer's sats back into
         * the escrow (2026-09-07 Trade A). Forbidden destinations (fee
         * wallet, the escrow's own address) resolve to null.
         */
        fun resolveBuyerPayoutAddress(
            escrowBtcAddress: String?,
            offerBtcAddress: String?,
            fundingAddress: String?
        ): String? {
            val candidate = escrowBtcAddress?.takeIf { it.isNotBlank() }
                ?: offerBtcAddress?.takeIf { it.isNotBlank() }
            if (candidate == null) return null
            if (PayoutAddressGate.isForbidden(candidate, NeoP2PConfig.FEE_WALLET_ADDRESS, fundingAddress)) {
                return null
            }
            return candidate
        }

        /**
         * Mutable escrow fields carried by LXMF escrow_status events so the
         * counterparty can reconstruct/advance its local row (2-party sync,
         * Task 8/9). Pure so the field set is unit-testable (mirrored by
         * EscrowStatusFieldsTest).
         */
        fun escrowStatusFields(entity: EscrowEntity): Map<String, String> = buildMap {
            put("offer_id", entity.offer_id)
            put("buyer_peer_id", entity.buyer_peer_id)
            put("seller_peer_id", entity.seller_peer_id)
            put("funding_address", entity.funding_address ?: "")
            put("funding_script_type", entity.funding_script_type)
            put("buyer_btc_address", entity.buyer_btc_address ?: "")
            put("buyer_pubkey_hex", entity.buyer_pubkey_hex ?: "")
            put("seller_pubkey_hex", entity.seller_pubkey_hex ?: "")
            // C1d: the buyer's payout signature travels so the seller's
            // release can combine it with the local seller signature (2-of-3).
            entity.buyer_signature?.let { put("buyer_signature", it.toString(Charsets.UTF_8)) }
            // C1d: the UNSIGNED payout tx must reach the buyer so they can
            // sign it. The buyer's mirrored row never carries psbt_unsigned
            // (EscrowRouter treats it as local-only), so without this the
            // buyer's signPayoutAsBuyerIfLocal would no-op on a null tx and
            // the release would never get the buyer signature. The seller
            // publishes it once the payout is generated (CONFIRMING).
            entity.psbt_unsigned?.let { put("psbt_hex", it.toString(Charsets.UTF_8)) }
            put("deposit_sats", entity.deposit_amount_sats.toString())
            put("trade_sats", entity.trade_amount_sats.toString())
            // The REAL creation time — the buyer's mirrored row otherwise uses
            // its own ingest time, which makes the funding countdown wrong on
            // the buyer side (and the buyer's row would show an expired window
            // while the seller's is still counting).
            put("created_at", entity.created_at.toString())
            entity.funding_tx_id?.let { put("funding_tx_id", it) }
            // The payout txid must travel too: the buyer's mirrored row
            // otherwise never learns it and the completion card cannot show
            // the payout tx (or let the buyer verify the on-chain release).
            entity.payout_tx_id?.let { put("payout_tx_id", it) }
            put("funding_vout", entity.funding_vout.toString())
            entity.funded_at?.let { put("funded_at", it.toString()) }
            entity.paid_at?.let { put("paid_at", it.toString()) }
            entity.receipt_reference?.let { put("receipt_reference", it) }
            entity.receipt_sent_at?.let { put("receipt_sent_at", it.toString()) }
            entity.refund_destination?.let { put("refund_destination", it) }
            entity.seller_refund_address?.let { put("seller_refund_address", it) }
            // F2 (2026-09-12): role-signed destination attestations travel so
            // the counterparty (and, via the dispute event, the arbitrator)
            // can verify where a refund/payout MUST go.
            entity.seller_refund_attestation?.let { put("seller_refund_attestation", it) }
            entity.buyer_address_attestation?.let { put("buyer_address_attestation", it) }
            // The ACTUAL on-chain funding value (2026-09-04): the buyer's
            // mirrored row needs it to spend the real input value (SegWit
            // BIP-143) and to show the overpayment excess.
            entity.funded_amount_sats?.let { put("funded_amount_sats", it.toString()) }
            // The redeem script must travel too: the party applying an
            // arbitration resolution (LXMF resolution message) needs it to verify the
            // arbitrator's signature and assemble the 2-of-3 spend — the buyer's
            // mirrored row never got it before, so only the seller could apply.
            entity.redeem_script_hex?.let { put("redeem_script_hex", it) }
        }

        /**
         * Approximate vsize (vbytes) of a P2SH 2-of-3 multisig spend used to
         * estimate the refund network fee. A 2-of-3 scriptSig carries 2 DER
         * signatures + the redeem script, so ~220 vbytes is a safe estimate.
         */
        private const val REFUND_APPROX_VSIZE = 220L
        /**
         * Minimum network (miner) fee in sats to prevent sub-relay-fee txs.
         * ~2 sat/vB × ~125 vB minimum tx size. Covers testnet (1 sat/vB
         * minrelaytxfee) and mainnet with margin.
         */
        const val MIN_NETWORK_FEE_SATS = 250L
        /**
         * Network (miner) fee for the FUNDING→payout side, in sats.
         * Full payout tx vsize (input + buyer output + fee output + overhead) so
         * the implicit miner fee stays above minrelaytxfee (1 sat/vB); floored at
         * MIN_NETWORK_FEE_SATS. Shared by createEscrow and switchFundingType so the
         * funding-type toggle cannot produce a deposit with an un-relayable payout
         * fee (regression 2026-09-06: the toggle used input-only spendVsize and no
         * floor).
         */
        fun fundingNetworkFeeSats(feeRatePerVb: Long, scriptType: BitcoinAddressType): Long =
            maxOf(feeRatePerVb * scriptType.payoutTxVsize, MIN_NETWORK_FEE_SATS)

        /**
         * Network (miner) fee for a REFUND spend, in sats. Full tx vsize =
         * multisig spend + P2PKH output upper bound (the seller's refund
         * destination is user-supplied, so never underestimate) + fixed overhead.
         * Floored at MIN_NETWORK_FEE_SATS. Shared by buildRefundTx and
         * getRefundEstimate so the displayed refund amount == the broadcast refund.
         */
        fun refundNetworkFeeSats(feeRatePerVb: Long, scriptType: BitcoinAddressType): Long =
            maxOf(
                feeRatePerVb * (scriptType.spendVsize + BitcoinAddressType.LEGACY.outputVsize + BitcoinAddressType.FIXED_OVERHEAD_VSIZE),
                MIN_NETWORK_FEE_SATS
            )
        /**
         * Approximate vsize (vbytes) of a P2SH 2-of-3 payout spend, used as a
         * fallback for old escrow rows (pre-migration) that don't have a stored
         * networkFeeSats. Uses the FULL TX vsize (input + outputs + overhead)
         * to avoid sub-relay-fee transactions.
         */
        const val PAYOUT_APPROX_VSIZE = 298L
        /**
         * Timeout for an escrow that has NOT yet been funded. FUNDING escrows
         * older than this are auto-CANCELLED (no funds were deposited, so no
         * on-chain move is needed). 30 minutes covers wallet transfer + 1 block
         * confirmation without risking a false auto-cancel.
         */
        const val ESCROW_FUNDING_TIMEOUT_MS = 30 * 60 * 1000L  // 30 min
        /** First warning (notification) when a FUNDING escrow is this old. */
        const val FUNDING_WARNING_MS = 15 * 60 * 1000L  // 15 min

        /**
         * Timeout for a FUNDED escrow whose trade never proceeds. Once the
         * deposit is confirmed, give the trade a generous window to complete
         * before auto-refunding back to the seller/depositor (so a funded
         * trade isn't yanked back if the buyer is slow).
         */
        const val ESCROW_FUNDED_REFUND_TIMEOUT_MS = 2 * 60 * 60 * 1000L  // 2 h
        /** Extra window after the funded-refund timeout before auto-refund; reminders at 2h/4h. */
        const val FUNDED_REFUND_GRACE_MS = 2 * 60 * 60 * 1000L  // 2 h grace

        /**
         * Payment window: how long the seller has to release (or dispute) after
         * the buyer marks the fiat payment as sent (legacy status PAID, now the
         * guided-flow state CONFIRMING). If the window expires, the escrow
         * auto-transitions to DISPUTED — never silently auto-refunded, because
         * the buyer may have actually paid.
         */
        const val PAYMENT_WINDOW_MS = 60 * 60 * 1000L  // 1 h
        /** Extra window after the payment window before auto-DISPUTED. */
        const val PAYMENT_GRACE_MS = 60 * 60 * 1000L  // 1 h grace
        private val NET_PARAMS: NetworkParameters by lazy {
            if (BuildConfig.NETWORK == "mainnet") {
                Log.w(TAG, "⚠️ MAINNET MODE — real funds at risk!")
                MainNetParams.get()
            } else {
                TestNet3Params.get()
            }
        }

        /**
         * Return the vout index whose output pays [address] exactly [amountSats],
         * or null when no output matches. Pure so funding verification is
         * unit-testable without a network.
         */
        fun findFundingOutput(
            outputs: List<ChainMonitor.TxOutput>,
            address: String?,
            amountSats: Long
        ): Int? = outputs.firstOrNull { o ->
            o.scriptPubkeyAddress.equals(address, ignoreCase = true) && o.valueSats == amountSats
        }?.index

        /**
         * Return the vout index whose output pays [address] AT LEAST
         * [amountSats], or null when no output matches. Overpayment is
         * accepted (2026-09-04): the excess is returned to the seller by the
         * payout/refund paths, never stranded in the multisig. Pure so funding
         * verification is unit-testable without a network.
         */
        fun findFundingOutputAtLeast(
            outputs: List<ChainMonitor.TxOutput>,
            address: String?,
            amountSats: Long
        ): Int? = outputs.firstOrNull { o ->
            o.scriptPubkeyAddress.equals(address, ignoreCase = true) && o.valueSats >= amountSats
        }?.index

        /**
         * The ACTUAL on-chain value of the funding output that pays [address]
         * at least [amountSats], or null when no output qualifies. Recorded at
         * funding verification so the payout/refund spend the real input value
         * (SegWit BIP-143 commits it) and return the excess to the seller.
         */
        fun fundedValueSats(
            outputs: List<ChainMonitor.TxOutput>,
            address: String?,
            amountSats: Long
        ): Long? = outputs.firstOrNull { o ->
            o.scriptPubkeyAddress.equals(address, ignoreCase = true) && o.valueSats >= amountSats
        }?.valueSats

        /**
         * Return the vout index of ANY output paying [address], regardless of
         * amount, or null when none matches. Used to persist a PARTIAL deposit
         * (2026-09-04): a seller who underpaid must be able to Cancel & Refund
         * the partial BTC instead of having it stranded in the multisig.
         */
        fun findFundingOutputAny(
            outputs: List<ChainMonitor.TxOutput>,
            address: String?
        ): Int? = outputs.firstOrNull { o ->
            o.scriptPubkeyAddress.equals(address, ignoreCase = true)
        }?.index

        /**
         * The value of ANY output paying [address], or null when none matches.
         * Used to record a PARTIAL deposit's actual on-chain value so the
         * refund spends the real input (SegWit BIP-143 commits it).
         */
        fun fundedValueAny(
            outputs: List<ChainMonitor.TxOutput>,
            address: String?
        ): Long? = outputs.firstOrNull { o ->
            o.scriptPubkeyAddress.equals(address, ignoreCase = true)
        }?.valueSats

        /**
         * Release gate (P2): funds may only be released once the buyer's
         * receipt exists (RECEIPT_SENT) and the seller confirms IDR received
         * (CONFIRMING). FUNDED/SIGNED/PAYMENT_PENDING must never release —
         * the fiat-confirm step is the ONLY release gate. UI must mirror this.
         */
        fun canReleaseFromStatus(status: String): Boolean =
            status == EscrowStatus.RECEIPT_SENT.name || status == EscrowStatus.CONFIRMING.name

        /**
         * Dispute gate (2026-09-05): a dispute may only be opened once the
         * escrow is FUNDED (deposit confirmed on-chain). FUNDING is NOT
         * disputable — the deposit is either not yet broadcast (nothing to
         * arbitrate; the 30-min funding window auto-cancels) or in flight
         * (unconfirmed; the arbitrator's payout/refund would spend an output
         * that does not exist yet and fail to broadcast). The buyer's exit
         * from a stuck FUNDING escrow is the auto-cancel, not a dispute.
         * Already-disputed and terminal states are also not disputable.
         * Pure so the rule is unit-testable (mirrored by EscrowDisputeGateTest).
         */
        fun canDisputeFromStatus(status: String): Boolean {
            if (status == EscrowStatus.FUNDING.name) return false
            if (status == EscrowStatus.DISPUTED.name || status == EscrowStatus.RESOLVING.name) return false
            if (status == EscrowStatus.RELEASED.name || status == EscrowStatus.REFUNDED.name ||
                status == EscrowStatus.CANCELLED.name
            ) return false
            return true
        }

        /**
         * Slice 4 (2026-09-01): dispute-delivery gate, shared by
         * EscrowScreen.disputeEscrow and P2POrchestrator.publishDisputeRns.
         *
         * A party's dispute opens locally as soon as the COUNTERPARTY
         * received it. The arbitrator is deliberately NOT part of the gate:
         * arbitrator delivery is best-effort (an offline arbitrator must not
         * strand a party's dispute in an un-flipped state) and the arbitrator
         * learns later via the sweep's pending-dispute / evidence retry the
         * moment it announces.
         */
        fun disputeDeliveryVerdict(counterpartyDelivered: Boolean): Boolean = counterpartyDelivered
    }

    data class EscrowState(
        val escrow: Escrow? = null,
        val status: String = "idle",
        val progress: Float = 0f,
        val error: String? = null
    )

    /**
     * One-shot notification for a user-facing escrow status transition. Emitted
     * from each state-mutation point (NOT from [initialize], which is a snapshot
     * load), so notifications fire exactly once per transition.
     */
    data class EscrowTransition(val escrowId: String, val status: String)

    private val _transitions = MutableSharedFlow<EscrowTransition>(replay = 0)
    val transitions: SharedFlow<EscrowTransition> = _transitions.asSharedFlow()

    /**
     * Emit a transition for a remote (LXMF escrow_status) status applied by
     * EscrowRouter, so the orchestrator's notification collector fires for
     * counterparty-driven changes too.
     */
    suspend fun emitRemoteTransition(escrowId: String, status: String) {
        _transitions.emit(EscrowTransition(escrowId, status.lowercase()))
    }

    /**
     * Best-effort escrow sync publish; never blocks the local transition.
     */
    private suspend fun publishEscrowSync(escrowId: String, status: String, entity: EscrowEntity) {
        // Phase 4: the Nostr relay was removed — the escrow status is
        // delivered DIRECTLY to the counterparty over LXMF (RNS path).
        runCatching {
            val counterparty = if (entity.buyer_peer_id == identityManager.myPeerId()) {
                entity.seller_peer_id
            } else {
                entity.buyer_peer_id
            }
            if (counterparty.isNotBlank()) {
                rnsTransport.sendEscrowStatus(counterparty, escrowId, status, escrowStatusFields(entity))
            }
        }.onFailure {
            Log.d(TAG, "RNS escrow sync to counterparty failed (queued for retry): ${it.message}")
        }
    }

    /**
     * Best-effort LXMF delivery of an offer status change to the matched peer.
     * Phase 4: the Nostr relay was removed — LXMF is the only path.
     */
    private suspend fun publishOfferStatusDual(
        offerId: String,
        status: String,
        matchedPeerId: String?,
        authorPeerId: String?
    ) {
        runCatching {
            if (!matchedPeerId.isNullOrBlank()) {
                rnsTransport.sendOfferStatus(
                    toPeerId = matchedPeerId,
                    offerId = offerId,
                    status = status,
                    matchedPeerId = matchedPeerId,
                    authorPeerId = authorPeerId
                )
            }
        }.onFailure {
            Log.d(TAG, "RNS offer status to matched peer failed (queued for retry): ${it.message}")
        }
    }

    @Volatile private var serviceStartWall: Long = 0L
    @Volatile private var serviceStartElapsed: Long = 0L

    private val _escrowStates = MutableStateFlow<Map<String, EscrowState>>(emptyMap())
    // Hoisted once for the singleton lifetime; per-call scopes would leak.
    private val stateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    fun getEscrowState(escrowId: String): StateFlow<EscrowState> = _escrowStates
        .map { it[escrowId] ?: EscrowState() }
        .stateIn(stateScope, SharingStarted.Eagerly, EscrowState())

    /** All escrows, newest first (domain models). */
    suspend fun getAllEscrows(): List<Escrow> =
        db.escrowDao().getAllEscrowsSync().map { it.toDomain() }

    /** Load a single escrow by ID (null if not found). */
    suspend fun getEscrow(escrowId: String): Escrow? =
        db.escrowDao().getEscrowSync(escrowId)?.toDomain()?.also { esc ->
            // A broadcast-but-unconfirmed deposit only reaches the counterparty
            // via LXMF escrow_status. Re-publish FUNDING + txid on every load so the
            // buyer's row converges to "In Progress" even when the txid was
            // persisted by a previous build/run (idempotent — router
            // no-downgrade keeps the status stable). Same for FUNDED: a
            // sweep-promoted escrow may have missed its publish (see
            // expireStaleEscrows), so re-publishing FUNDED here heals the
            // buyer's stale "Waiting for confirmation" row.
            // T19: the PAYMENT states (PAYMENT_PENDING / RECEIPT_SENT /
            // CONFIRMING) publish at transition time but were NOT re-published
            // on load — a kill between DB persist and relay write left the
            // counterparty stuck on the pre-transition status forever. Same
            // idempotent heal (router is forward-only + no-downgrade).
            // T20: RELEASED added — confirmReceipt publishes CONFIRMING and
            // RELEASED in rapid succession; if the RELEASED publish fails
            // silently (relay timeout / WS drop), the buyer stays stuck on
            // "menunggu rilis penjual" forever. Re-publishing on load heals it.
            if (esc.status == EscrowStatus.FUNDING && !esc.fundingTxId.isNullOrBlank() ||
                esc.status == EscrowStatus.FUNDED ||
                esc.status == EscrowStatus.SIGNED ||
                esc.status == EscrowStatus.PAYMENT_PENDING ||
                esc.status == EscrowStatus.RECEIPT_SENT ||
                esc.status == EscrowStatus.CONFIRMING ||
                esc.status == EscrowStatus.RELEASED
            ) {
                runCatching { publishEscrowSync(escrowId, esc.status.name, esc.toEntity()) }
            }
        }

    /**
     * Recover a funding txid that was broadcast but never persisted.
     *
     * Pre-fix builds only saved funding_tx_id once the confirmation
     * threshold was met — a deposit broadcast (wallet funding) then an app
     * restart left the escrow in FUNDING with no txid, so the UI showed
     * "Pending / Waiting for deposit" and re-enabled the double-send button.
     *
     * Looks the funding address up on-chain: the first tx paying exactly the
     * deposit amount is the funding tx. When found, persists txid + vout and
     * returns the txid; otherwise null.
     */
    suspend fun recoverFundingTxId(escrowId: String): String? {
        val entity = db.escrowDao().getEscrowSync(escrowId) ?: return null
        if (!entity.funding_tx_id.isNullOrBlank()) {
            // A stored txid bound by a PRE-FIX build may be a stale deposit
            // from a previous escrow on the same deterministic address.
            // Revalidate before trusting it; only fall through to a fresh
            // scan when it is PROVEN stale (explorer unreachable → trust).
            val storedInfo = chainMonitor.getTxInfo(entity.funding_tx_id).getOrNull()
            val stale = storedInfo != null &&
                fundingTxIsStale(storedInfo.blockTimeSec, storedInfo.confirmed, entity.created_at)
            if (!stale) return entity.funding_tx_id
            Log.w(TAG, "Stored funding txid ${entity.funding_tx_id} predates escrow — " +
                "ignoring, scanning for a fresh deposit")
        }
        if (entity.status != EscrowStatus.FUNDING.name) return null
        val address = entity.funding_address ?: return null
        return try {
            val txs = chainMonitor.getAddressTxs(address, limit = 25).getOrNull() ?: return null
            for (tx in txs) {
                // Only a deposit to the escrow address counts.
                val outputs = chainMonitor.getTxOutputs(tx.txid).getOrNull() ?: continue
                val vout = findFundingOutputAtLeast(outputs, address, entity.deposit_amount_sats)
                if (vout != null) {
                    // Freshness (2026-09-01): the escrow address is
                    // deterministic, so a deposit from a PREVIOUS escrow
                    // between the same peers also pays this address the exact
                    // amount. Only a tx mined AFTER the escrow was created
                    // can be this escrow's deposit; an older one must not
                    // populate the txid field (the UI then double-sends).
                    if (fundingTxIsStale(tx.blockTimeSec, tx.confirmed, entity.created_at)) {
                        Log.w(TAG, "Skipping stale funding candidate ${tx.txid} " +
                            "(blockTime=${tx.blockTimeSec} < escrow creation ${entity.created_at / 1000})")
                        continue
                    }
                    val fundedValue = fundedValueSats(outputs, address, entity.deposit_amount_sats)
                    val updated = entity.copy(
                        funding_tx_id = tx.txid,
                        funding_vout = vout.toLong(),
                        funded_amount_sats = fundedValue
                    )
                    db.escrowDao().upsert(updated)
                    // Re-broadcast the sync event so the counterparty's row
                    // converges too (their chip/label must also flip to
                    // "In Progress / waiting for confirmation").
                    publishEscrowSync(escrowId, EscrowStatus.FUNDING.name, updated)
                    Log.i(TAG, "Recovered funding tx $tx.txid for escrow $escrowId")
                    return tx.txid
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Funding recovery failed for $escrowId: ${e.message}")
            null
        }
    }

    /**
     * E4 (2026-09-01): re-bind a funding txid that was RBF-bumped.
     *
     * The stored funding txid is dead in mempool (unconfirmed) but the
     * escrow address received a replacement deposit paying the exact amount
     * — the wallet bumped the fee. Re-binds txid + vout to the replacement
     * and re-syncs the counterparty. Returns the new txid, or null when the
     * stored txid is still valid (confirmed or in mempool) or no replacement
     * exists. Only called for FUNDING escrows past the funding timeout, so
     * the sweep never cancels a deposit that merely changed txid.
     */
    suspend fun rebindFundingTxId(entity: EscrowEntity): String? {
        val storedTxid = entity.funding_tx_id ?: return null
        if (entity.status != EscrowStatus.FUNDING.name) return null
        val address = entity.funding_address ?: return null
        return try {
            // The stored tx is still valid (confirmed or in mempool) — no rebind.
            val storedInfo = chainMonitor.getTxInfo(storedTxid).getOrNull()
            if (storedInfo != null && storedInfo.confirmed) return null
            if (storedInfo != null && !storedInfo.confirmed) {
                // Unconfirmed: check whether it is still in mempool by asking
                // the address for its current unconfirmed deposits.
                val addressInfo = chainMonitor.getAddressInfo(address).getOrNull()
                if (addressInfo != null && addressInfo.unconfirmedBalanceSats > 0L) return null
            }
            // Stored tx is gone (or unknown) — look for a replacement deposit.
            val txs = chainMonitor.getAddressTxs(address, limit = 25).getOrNull() ?: return null
            for (tx in txs) {
                if (tx.txid == storedTxid) continue
                val outputs = chainMonitor.getTxOutputs(tx.txid).getOrNull() ?: continue
                val vout = findFundingOutputAtLeast(outputs, address, entity.deposit_amount_sats)
                if (vout != null) {
                    // Freshness (2026-09-01): as in [recoverFundingTxId], a
                    // replacement deposit must postdate the escrow — never
                    // re-bind a deposit from a previous escrow on the same
                    // deterministic address.
                    if (fundingTxIsStale(tx.blockTimeSec, tx.confirmed, entity.created_at)) {
                        Log.w(TAG, "Skipping stale RBF candidate ${tx.txid} " +
                            "(blockTime=${tx.blockTimeSec} < escrow creation ${entity.created_at / 1000})")
                        continue
                    }
                    val fundedValue = fundedValueSats(outputs, address, entity.deposit_amount_sats)
                    val updated = entity.copy(
                        funding_tx_id = tx.txid,
                        funding_vout = vout.toLong(),
                        funded_amount_sats = fundedValue
                    )
                    db.escrowDao().upsert(updated)
                    runCatching { publishEscrowSync(escrowId = entity.escrow_id, status = EscrowStatus.FUNDING.name, entity = updated) }
                    Log.i(TAG, "Re-bound funding tx ${entity.funding_tx_id} → ${tx.txid} for escrow ${entity.escrow_id} (RBF)")
                    return tx.txid
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Funding re-bind failed for ${entity.escrow_id}: ${e.message}")
            null
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun initialize() {
        try {
            val entities = db.escrowDao().getAllEscrowsSync()
            // Fix 2: expire any stale (abandoned) escrows before mapping state,
            // so the UI never shows a FUNDED escrow that has since auto-refunded.
            expireStaleEscrows()
            // Heal: an escrow that is ALREADY terminal (RELEASED/REFUNDED/
            // CANCELLED) must mark its linked offer terminal too. Pre-fix
            // builds released/refunded escrows without touching the offer, so
            // the offer stayed ESCROWED on the marketplace forever. The
            // LXMF offer_status event syncs the terminal status to the counterparty.
            healTerminalOfferStatuses()
            val refreshed = db.escrowDao().getAllEscrowsSync()
            val states = refreshed.associate { entity ->
                entity.escrow_id to EscrowState(
                    escrow = entity.toDomain(),
                    status = entity.status.lowercase(),
                    progress = when (EscrowStatus.valueOf(entity.status)) {
                        EscrowStatus.FUNDING -> 0.1f
                        EscrowStatus.FUNDED -> 0.3f
                        EscrowStatus.PAYMENT_PENDING -> 0.4f
                        EscrowStatus.RECEIPT_SENT -> 0.5f
                        EscrowStatus.SIGNED -> 0.6f
                        EscrowStatus.CONFIRMING -> 0.7f
                        EscrowStatus.RELEASED -> 1.0f
                        EscrowStatus.DISPUTED -> 0.5f
                        EscrowStatus.RESOLVING -> 0.7f
                        EscrowStatus.CANCELLED -> 0.0f
                        EscrowStatus.REFUNDED -> 0.0f
                    }
                )
            }
            _escrowStates.value = states
            Log.d(TAG, "Loaded ${states.size} escrows from DB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load escrows from DB", e)
        }
    }

    /**
     * Mark the linked offer terminal for every escrow that is ALREADY in a
     * terminal state (RELEASED → COMPLETED, REFUNDED/CANCELLED → CANCELLED).
     *
     * Pre-fix builds (before 2026-08-27) released/refunded escrows without
     * touching the offer row, so finished trades kept their offer listed as
     * ESCROWED on the marketplace forever. The release/refund paths now mark
     * the offer themselves; this heals rows created by older builds. The
     * LXMF offer_status event syncs the terminal status to the counterparty's row.
     */
    private suspend fun healTerminalOfferStatuses() {
        try {
            val escrows = db.escrowDao().getAllEscrowsSync()
            for (entity in escrows) {
                val terminalStatus = when (EscrowStatus.valueOf(entity.status)) {
                    EscrowStatus.RELEASED -> com.neop2p.domain.model.OfferStatus.COMPLETED
                    EscrowStatus.REFUNDED, EscrowStatus.CANCELLED ->
                        com.neop2p.domain.model.OfferStatus.CANCELLED
                    else -> continue
                }
                val offer = db.offerDao().getOfferSync(entity.offer_id) ?: continue
                if (offer.status == terminalStatus.name) continue
                db.offerDao().updateStatus(entity.offer_id, terminalStatus.name)
                publishOfferStatusDual(
                    offerId = entity.offer_id,
                    status = terminalStatus.name,
                    matchedPeerId = offer.matched_peer_id,
                    authorPeerId = identityManager.myPeerId()
                )
                Log.d(TAG, "Healed offer ${entity.offer_id} → ${terminalStatus.name} (escrow ${entity.escrow_id} ${entity.status})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to heal terminal offer statuses: ${e.message}")
        }
    }

    /**
     * Emit a transition at most once per (type, escrow) — the 60s sweep calls
     * [expireStaleEscrows] repeatedly, and grace reminders must not spam
     * notifications for hours. In-memory only: a process restart may re-emit
     * once, which is acceptable for a reminder.
     */
    private suspend fun emitOnce(type: String, escrowId: String, block: suspend () -> Unit) {
        val key = "$escrowId:$type"
        if (!graceRemindersSent.add(key)) return
        block()
    }

    private val graceRemindersSent = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Auto-expire stale escrows so funds are never left stuck/abandoned.
     *
     * Idempotent & safe:
     *  - FUNDING (nothing deposited yet) older than [ESCROW_FUNDING_TIMEOUT_MS]
     *    is set to [EscrowStatus.CANCELLED] (no on-chain move).
     *  - FUNDED (deposited but the trade never proceeded) older than
     *    [ESCROW_FUNDED_REFUND_TIMEOUT_MS] from [Escrow.fundedAt] is auto-
     *    REFUNDED back to the seller/depositor's own Bitcoin address (build +
     *    sign + broadcast the refund tx, reusing [cancelEscrowRefund] machinery).
     *
     * Both transitions only fire once because the status is persisted BEFORE
     * any broadcast (CANCELLED/REFUNDED are terminal, so a second pass no-ops).
     */
    suspend fun expireStaleEscrows() {
        try {
            val entities = db.escrowDao().getAllEscrowsSync()
            val now = System.currentTimeMillis()
            // Wall-clock sanity: if the device clock was rolled back, now may be
            // BEFORE created_at/funded_at/paid_at — never expire in that case.
            // A forward jump is detected via elapsedRealtime (monotonic) vs wall
            // — if wall jumped >2h ahead without matching uptime, defer expiry.
            val wallJumpForward = runCatching {
                val elapsed = android.os.SystemClock.elapsedRealtime()
                // serviceStartWall/Elapsed captured on first call (lazy).
                if (serviceStartWall == 0L) {
                    serviceStartWall = now
                    serviceStartElapsed = elapsed
                }
                val wallDelta = now - serviceStartWall
                val monoDelta = elapsed - serviceStartElapsed
                // Wall moved forward >2h beyond monotonic -> likely user/system clock jump.
                wallDelta - monoDelta > 2 * 60 * 60 * 1000L
            }.getOrDefault(false)
            if (wallJumpForward) {
                Log.w(TAG, "Wall clock jumped forward without monotonic uptime — deferring auto-expiry this sweep")
                return
            }
            val myPeerId = identityManager.myPeerId()
            for (entity in entities) {
                // Rollback guard per-entity.
                if (now < entity.created_at || (entity.funded_at != null && now < entity.funded_at!!) || (entity.paid_at != null && now < entity.paid_at!!)) {
                    Log.w(TAG, "Wall clock rollback detected for ${entity.escrow_id} — skipping expiry")
                    continue
                }
                val status = EscrowStatus.valueOf(entity.status)
                // Role gate (2-party): the escrow LIFECYCLE (auto-cancel,
                // promote-to-funded, auto-refund) belongs to the SELLER only —
                // the seller holds the deposit keys and owns the timing. The
                // buyer's device must never cancel/promote/refund a row it
                // only mirrored via LXMF escrow_status: its local `created_at` is the
                // sync time, not the real escrow creation, so the 30-min
                // window is wrong on that side, and a refund signed with the
                // buyer's key would be an invalid broadcast anyway.
                val isSeller = entity.seller_peer_id == myPeerId
                when (status) {
                    EscrowStatus.FUNDING -> {
                        if (!isSeller) continue // buyer mirrors; seller acts
                        // Nothing deposited yet → just cancel, no on-chain move.
                        // SAFETY: before cancelling a stale FUNDING escrow, check
                        // whether the funding address actually received a deposit
                        // (broadcast may have succeeded but verification failed, or
                        // the tx is slow to confirm). Never cancel an escrow whose
                        // P2SH address holds funds — that would orphan the deposit.
                        if (now - entity.created_at > ESCROW_FUNDING_TIMEOUT_MS) {
                            // E4 (2026-09-01): an RBF-bumped funding tx leaves
                            // the original txid dead in mempool — re-bind to the
                            // replacement before deciding anything.
                            if (!entity.funding_tx_id.isNullOrBlank()) {
                                val rebound = rebindFundingTxId(entity)
                                if (rebound != null) {
                                    // The replacement is bound; the escrow is
                                    // still FUNDING until it confirms. Skip the
                                    // cancel decision this sweep (the next sweep
                                    // re-evaluates with the fresh txid).
                                    continue
                                }
                            }
                            // Freshness-gated recovery (2026-09-01): promote
                            // only when a deposit that POSTDATES the escrow is
                            // found (deterministic addresses make an address
                            // balance alone meaningless — a previous escrow's
                            // deposit would otherwise promote the new one).
                            val recoveredTxid = recoverFundingTxId(entity.escrow_id)
                            if (recoveredTxid != null) {
                                // Underpayment guard (2026-09-04): a PARTIAL
                                // deposit must never be promoted to FUNDED —
                                // the payout would fail (input < outputs) and
                                // the seller would be stuck. Keep FUNDING so
                                // the seller can Cancel & Refund the partial.
                                val fresh = db.escrowDao().getEscrowSync(entity.escrow_id) ?: entity
                                val partial = fresh.funded_amount_sats
                                    ?.takeIf { it > 0L && it < fresh.deposit_amount_sats }
                                if (partial != null) {
                                    Log.w(TAG, "FUNDING escrow ${entity.escrow_id} has a partial deposit " +
                                        "$partial sats (< ${fresh.deposit_amount_sats}) — NOT promoting; keep FUNDING for cancel & refund")
                                    continue
                                }
                                Log.w(TAG, "FUNDING escrow ${entity.escrow_id} timed out but a fresh deposit " +
                                    "$recoveredTxid was found — promoting to FUNDED instead of cancelling")
                                // E8 (2026-09-10): the manual path enforces
                                // required_confirmations; the sweep's promote
                                // path must too. A mempool deposit (0 confs)
                                // stays FUNDING and is re-evaluated on the
                                // next sweep — never promote an input the
                                // manual gate would reject.
                                val promotion = chainMonitor.getTxInfo(recoveredTxid).getOrNull()
                                val required = fresh.required_confirmations.coerceAtLeast(1)
                                when (fundingPromotionDecision(promotion, required)) {
                                    "PROMOTE" -> {
                                        // recoverFundingTxId upserted the txid; re-read
                                        // so the promoted row carries it.
                                        val funded = fresh.copy(status = EscrowStatus.FUNDED.name, funded_at = now)
                                        db.escrowDao().upsert(funded)
                                        val domain = funded.toDomain()
                                        _escrowStates.update { map ->
                                            map + (entity.escrow_id to EscrowState(escrow = domain, status = "funded", progress = 0.3f))
                                        }
                                        _transitions.emit(EscrowTransition(entity.escrow_id, "funded"))
                                        // The counterparty (buyer) only learns via LXMF escrow_status —
                                        // without this the buyer stays on "Waiting for
                                        // confirmation" forever while the seller is FUNDED.
                                        runCatching { publishEscrowSync(entity.escrow_id, EscrowStatus.FUNDED.name, funded) }
                                    }
                                    "WAIT" -> {
                                        Log.w(TAG, "FUNDING escrow ${entity.escrow_id} deposit $recoveredTxid " +
                                            "not yet at ${required} confirmation(s) — keeping FUNDING, retry next sweep")
                                    }
                                    else -> {
                                        Log.w(TAG, "FUNDING escrow ${entity.escrow_id} promotion check failed " +
                                            "(explorer unreachable) — keeping FUNDING, retry next sweep")
                                    }
                                }
                            } else {
                                // Underpayment guard (2026-09-04): a PARTIAL
                                // deposit (recorded by onEscrowFunded) must
                                // never be auto-cancelled — that would strand
                                // the seller's BTC in the multisig. Keep the
                                // escrow FUNDING so the seller can Cancel &
                                // Refund the partial amount.
                                val partial = entity.funded_amount_sats
                                    ?.takeIf { it > 0L && it < entity.deposit_amount_sats }
                                if (partial != null) {
                                    Log.w(TAG, "FUNDING escrow ${entity.escrow_id} has a partial deposit " +
                                        "$partial sats (< ${entity.deposit_amount_sats}) — keeping FUNDING for cancel & refund")
                                    continue
                                }
                                val updated = entity.copy(status = EscrowStatus.CANCELLED.name)
                                db.escrowDao().upsert(updated)
                                val domain = updated.toDomain()
                                _escrowStates.update { map ->
                                    map + (entity.escrow_id to EscrowState(escrow = domain, status = "cancelled", progress = 0f))
                                }
                                // Emit so the orchestrator can notify the user
                                // (auto-cancel is user-facing, not a silent sweep).
                                _transitions.emit(EscrowTransition(entity.escrow_id, "cancelled"))
                                // Sync the terminal state to the counterparty —
                                // without this the buyer's row stays FUNDING
                                // forever with an expired countdown.
                                runCatching { publishEscrowSync(entity.escrow_id, EscrowStatus.CANCELLED.name, updated) }
                                // The trade is dead — mark the linked offer
                                // CANCELLED so it leaves the marketplace feed
                                // (same class of bug as release/refund: the
                                // FUNDING auto-cancel path used to leave the
                                // offer ESCROWED forever). The LXMF offer_status event
                                // syncs the terminal status to the
                                // counterparty's row.
                                runCatching {
                                    db.offerDao().getOfferSync(entity.offer_id)?.let { offer ->
                                        if (offer.status != com.neop2p.domain.model.OfferStatus.CANCELLED.name) {
                                            db.offerDao().updateStatus(entity.offer_id, com.neop2p.domain.model.OfferStatus.CANCELLED.name)
                                            publishOfferStatusDual(
                                                offerId = entity.offer_id,
                                                status = com.neop2p.domain.model.OfferStatus.CANCELLED.name,
                                                matchedPeerId = offer.matched_peer_id,
                                                authorPeerId = identityManager.myPeerId()
                                            )
                                            Log.d(TAG, "Offer ${entity.offer_id} marked CANCELLED after escrow auto-cancel")
                                        }
                                    }
                                }.onFailure { Log.w(TAG, "Failed to mark offer CANCELLED after auto-cancel: ${it.message}") }
                                Log.d(TAG, "Expired FUNDING escrow ${entity.escrow_id} → CANCELLED")
                            }
                        }
                    }
                    EscrowStatus.FUNDED, EscrowStatus.SIGNED -> {
                        if (!isSeller) continue // only the depositor may refund
                        // Deposited but stalled → auto-refund to the seller.
                        // Grace-aware (Task 3): refund only after the primary
                        // window PLUS the grace window, so a funded trade is
                        // never yanked back on a slow counterparty. Between
                        // timeout and timeout+grace, remind instead of acting.
                        // SIGNED is included: the payout was generated but the
                        // trade stalled (kill between generatePayoutTransaction
                        // and CONFIRMING) — the deposit is confirmed on-chain,
                        // so the seller gets the same auto-refund window.
                        val fundedAt = entity.funded_at ?: entity.created_at
                        val elapsed = now - fundedAt
                        if (elapsed > ESCROW_FUNDED_REFUND_TIMEOUT_MS + FUNDED_REFUND_GRACE_MS) {
                            // E7+E4 (2026-09-01): re-verify the funding tx before
                            // auto-refunding. A reorg can un-confirm/drop the
                            // funding tx (E7) OR shave its depth below the escrow's
                            // required confirmations while the address is still
                            // funded (E4) — refunding then broadcasts a tx spending
                            // an invalid/insufficiently-confirmed input. Explorer
                            // failure fails closed (skip this sweep); either
                            // reorg case reverts to FUNDING so the existing
                            // machinery re-verifies or cancels instead.
                            val txInfo = entity.funding_tx_id?.let { txid ->
                                chainMonitor.getTxInfo(txid).getOrNull()
                            }
                            val required = entity.required_confirmations.coerceAtLeast(1)
                            val decision = fundingRefundDecision(
                                txInfo,
                                hasOnChainDeposit(entity.funding_address),
                                required
                            )
                            if (decision == "REVERT") {
                                val reason = if (txInfo?.confirmed == false) {
                                    "lost to a reorg (unconfirmed + no deposit)"
                                } else {
                                    "depth ${txInfo?.confirmations} < required $required after reorg"
                                }
                                Log.w(TAG, "FUNDED escrow ${entity.escrow_id} funding tx ${entity.funding_tx_id} $reason — reverting to FUNDING")
                                val reverted = entity.copy(
                                    status = EscrowStatus.FUNDING.name,
                                    funded_at = null,
                                    funding_tx_id = null,
                                    funding_vout = 0L
                                )
                                db.escrowDao().upsert(reverted)
                                runCatching { publishEscrowSync(entity.escrow_id, EscrowStatus.FUNDING.name, reverted) }
                            } else if (decision == "SKIP") {
                                Log.w(TAG, "FUNDED escrow ${entity.escrow_id} funding tx unverifiable — skipping refund sweep")
                            } else {
                                autoRefundEscrow(entity)
                            }
                        } else if (elapsed > ESCROW_FUNDED_REFUND_TIMEOUT_MS) {
                            emitOnce("refund_grace_reminder", entity.escrow_id) {
                                Log.w(TAG, "FUNDED escrow ${entity.escrow_id} past refund timeout " +
                                    "(${elapsed / 3_600_000}h) — grace until " +
                                    "${(ESCROW_FUNDED_REFUND_TIMEOUT_MS + FUNDED_REFUND_GRACE_MS) / 3_600_000}h")
                                _transitions.emit(EscrowTransition(entity.escrow_id, "refund_grace_reminder"))
                            }
                        }
                    }
                    EscrowStatus.PAYMENT_PENDING, EscrowStatus.RECEIPT_SENT, EscrowStatus.CONFIRMING -> {
                        // Payment window (Task 3): buyer marked paid; seller must
                        // release or dispute. Auto-DISPUTED only after the
                        // payment window PLUS grace — never silently refunded,
                        // because the buyer may have actually paid. Between
                        // window and window+grace, remind once.
                        // Fix 2026-08-30: PAYMENT_PENDING was omitted — a buyer who
                        // marked paid but never sent a receipt would never auto-dispute.
                        val paidAt = entity.paid_at ?: entity.created_at
                        val elapsed = now - paidAt
                        if (elapsed > PAYMENT_WINDOW_MS + PAYMENT_GRACE_MS) {
                            Log.w(TAG, "Escrow ${entity.escrow_id} payment window + grace expired — DISPUTED")
                            val disputed = entity.copy(status = EscrowStatus.DISPUTED.name)
                            db.escrowDao().upsert(disputed)
                            val domain = disputed.toDomain()
                            _escrowStates.update { map ->
                                map + (entity.escrow_id to EscrowState(
                                    escrow = domain, status = "disputed", progress = 0.5f,
                                    error = "Payment window + grace expired — dispute opened"
                                ))
                            }
                            _transitions.emit(EscrowTransition(entity.escrow_id, "disputed"))
                            // Sync the terminal state to the counterparty.
                            runCatching { publishEscrowSync(entity.escrow_id, EscrowStatus.DISPUTED.name, disputed) }
                            // v23 (2026-09-02): an auto-dispute must ALSO reach
                            // the arbitrator — pre-v23 only the counterparty got
                            // the escrow_status, so the arbitrator's feed stayed
                            // empty and the escrow was unresolvable (funds
                            // locked, no tie-break key). Deliver the dispute
                            // event to the arbitrator; on failure persist a
                            // per-target pending row so the 60s sweep retries
                            // (the local row is already DISPUTED, so the legacy
                            // retry path would have dropped it).
                            val arbPeerId = NeoP2PConfig.ARBITRATOR_PEER_ID
                            if (arbPeerId.isNotBlank()) {
                                val arbOk = rnsTransport.sendDispute(
                                    toPeerId = arbPeerId,
                                    escrowId = entity.escrow_id,
                                    openedBy = myPeerId,
                                    reason = "Payment window + grace expired",
                                    fields = buildMap {
                                        entity.redeem_script_hex?.let { put("redeem_script_hex", it) }
                                        entity.psbt_unsigned?.let { put("psbt_hex", it.toString(Charsets.UTF_8)) }
                                        // The ACTUAL on-chain funding value (2026-09-04):
                                        // the arbitrator signs the SegWit refund with the
                                        // real input value, which may exceed the deposit.
                                        put("deposit_sats", (entity.funded_amount_sats ?: entity.deposit_amount_sats).toString())
                                        put("funding_script_type", entity.funding_script_type)
                                        entity.seller_refund_address?.let { put("seller_refund_address", it) }
                                        // F2 (2026-09-12): role keys + role-signed
                                        // destination attestations (public only).
                                        put("offer_id", entity.offer_id)
                                        entity.buyer_btc_address?.takeIf { it.isNotBlank() }
                                            ?.let { put("buyer_btc_address", it) }
                                        entity.buyer_pubkey_hex?.let { put("buyer_pubkey_hex", it) }
                                        entity.seller_pubkey_hex?.let { put("seller_pubkey_hex", it) }
                                        put("trade_sats", entity.trade_amount_sats.toString())
                                        entity.seller_refund_attestation
                                            ?.let { put("seller_refund_attestation", it) }
                                        entity.buyer_address_attestation
                                            ?.let { put("buyer_address_attestation", it) }
                                        put("buyer_peer_id", entity.buyer_peer_id)
                                        put("seller_peer_id", entity.seller_peer_id)
                                    }
                                ).isSuccess
                                if (!arbOk) {
                                    Log.w(TAG, "Auto-dispute ${entity.escrow_id}: arbitrator not reached — saved for sweep retry")
                                    pendingDisputeStore.save(
                                        com.neop2p.data.local.PendingDisputeStore.PendingDispute(
                                            escrowId = entity.escrow_id,
                                            openedBy = myPeerId,
                                            reason = "Payment window + grace expired",
                                            redeemScriptHex = entity.redeem_script_hex,
                                            psbtHex = entity.psbt_unsigned?.toString(Charsets.UTF_8),
                                            refundTxHex = null,
                                            depositSats = entity.funded_amount_sats ?: entity.deposit_amount_sats,
                                            fundingScriptType = entity.funding_script_type,
                                            sellerRefundAddress = entity.seller_refund_address,
                                            offerId = entity.offer_id,
                                            buyerBtcAddress = entity.buyer_btc_address,
                                            buyerPubKeyHex = entity.buyer_pubkey_hex,
                                            sellerPubKeyHex = entity.seller_pubkey_hex,
                                            tradeSats = entity.trade_amount_sats,
                                            sellerRefundAttestation = entity.seller_refund_attestation,
                                            buyerAddressAttestation = entity.buyer_address_attestation,
                                            targets = listOf(arbPeerId)
                                        )
                                    )
                                }
                            }
                        } else if (elapsed > PAYMENT_WINDOW_MS) {
                            emitOnce("payment_grace_reminder", entity.escrow_id) {
                                Log.w(TAG, "Escrow ${entity.escrow_id} past payment window " +
                                    "(${elapsed / 3_600_000}h) — in grace " +
                                    "(${(PAYMENT_WINDOW_MS + PAYMENT_GRACE_MS) / 3_600_000}h total)")
                                _transitions.emit(EscrowTransition(entity.escrow_id, "payment_grace_reminder"))
                            }
                        }
                    }
                    // Signed/Released/Disputed/Resolving/Cancelled/Refunded → skip.
                    else -> {}
                }
            }
            // Self-heal: an escrow that is ALREADY terminal (RELEASED/REFUNDED/
            // CANCELLED) must mark its linked offer terminal too. Runs on
            // every sweep (not just initialize) so an offer stranded as
            // ESCROWED by a pre-fix build or a missed LXMF offer_status publish is
            // healed within one sweep interval — the 60s loop is the
            // marketplace-honesty backstop. Idempotent: skips offers already
            // in the terminal status.
            healTerminalOfferStatuses()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to expire stale escrows", e)
        }
    }

    /**
     * True if the escrow's P2SH funding address currently holds any on-chain
     * balance (confirmed or unconfirmed). Used to avoid auto-cancelling a
     * FUNDING escrow whose deposit was already broadcast but not yet verified.
     * A zero balance (or an unreachable explorer) returns false.
     */
    private suspend fun hasOnChainDeposit(fundingAddress: String?): Boolean {        if (fundingAddress.isNullOrBlank()) return false
        // Explorer can 429/timeout on first hit — retry 3× before treating a
        // FUNDING escrow as truly unfunded. A transient failure must NOT cause
        // an auto-CANCEL that orphans a broadcast deposit.
        repeat(3) { attempt ->
            try {
                val info = chainMonitor.getAddressInfo(fundingAddress).getOrNull()
                if (info != null) return info.totalSats > 0L
                // Null result (no explorer hit) — retry, not immediate false.
                if (attempt < 2) kotlinx.coroutines.delay(700L * (attempt + 1))
            } catch (e: Exception) {
                Log.w(TAG, "Deposit check attempt ${attempt + 1} failed for $fundingAddress: ${e.message}")
                if (attempt < 2) kotlinx.coroutines.delay(700L * (attempt + 1))
                else return false
            }
        }
        return false
    }

    /**
     * Auto-refund a stalled FUNDED escrow back to the seller/depositor's own
     * Bitcoin address. Mirrors [cancelEscrowRefund]'s build+sign+broadcast
     * pipeline, using the current user's Bitcoin key (the depositor).
     */
    private suspend fun autoRefundEscrow(entity: EscrowEntity) {
        try {
            // The seller/depositor is the current user in this single-device
            // escrow model (both escrow roles are pinned to the same key).
            val privHex = identityManager.getBitcoinPrivateKeyHex()
            val sellerAddress = identityManager.getBitcoinAddress(BitcoinAddressType.LEGACY)
            val result = refundInternal(entity, sellerAddress, privHex, auto = true)
            if (result.isFailure) {
                Log.e(TAG, "Auto-refund failed for ${entity.escrow_id}: " +
                    result.exceptionOrNull()?.message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-refund exception for ${entity.escrow_id}", e)
        }
    }

    /**
     * Initiate a new escrow. Generates a 2-of-3 P2SH multisig address.
     *
     * Roles: the SELLER supplies the BTC (deposits `depositAmountSats`) into
     * the multisig. The BUYER pays IDR via a fiat method. On confirmation, the
     * payout sends the trade amount to the buyer and the fee to the fee wallet.
     */
    suspend fun createEscrow(
        offer: TradeOffer,
        buyerPeerId: String,
        sellerPeerId: String,
        buyerPubKeyHex: String,
        sellerPubKeyHex: String,
        fundingScriptType: BitcoinAddressType = BitcoinAddressType.LEGACY,
        buyerBtcAddress: String? = null,
        buyerAddressAttestation: String = ""
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        // HARD ENFORCEMENT (C1, 2026-09-11): the 2-of-3 must use the REAL
        // buyer key, distinct from the seller's. The pre-C1 single-key model
        // made the multisig effectively 2-of-2 — the seller could sign a
        // refund to themselves after receiving fiat. Fail closed: a missing
        // or duplicate buyer key means the counterparty runs an older build.
        if (!isValidRoleKeyPair(buyerPubKeyHex, sellerPubKeyHex)) {
            return@withContext Result.failure(
                Exception("Escrow requires the buyer's real key (C1). The counterparty runs an older app version — both parties must update to the same build before trading.")
            )
        }
        // F2: the buyer's payout destination must carry a role-key attestation.
        // Fail closed on a missing/invalid attestation (older build).
        if (buyerBtcAddress.isNullOrBlank() ||
            !RoleAddressAttestation.verify(
                publicKeyHex = buyerPubKeyHex,
                kind = RoleAddressAttestation.KIND_BUYER_PAYOUT,
                scopeId = offer.offerId,
                address = buyerBtcAddress,
                sigHex = buyerAddressAttestation
            )
        ) {
            return@withContext Result.failure(
                Exception("Escrow requires the buyer's attested payout address (F2). The counterparty runs an older app version — both parties must update to the same build before trading.")
            )
        }
        // HARD ENFORCEMENT: refuse to create any escrow if the fee wallet
        // address fails signature verification. This prevents a forked build
        // from redirecting the 0.5% fee to an attacker-controlled address.
        if (!NeoP2PConfig.verifyFeeWalletIntegrity()) {
            return@withContext Result.failure(
                IllegalStateException("Fee wallet signature invalid — escrow disabled")
            )
        }
        // HARD ENFORCEMENT (arbitrator): refuse to create any escrow if the
        // arbitrator pubkey fails signature verification. This prevents a
        // forked build from swapping the tie-break key to an attacker-owned
        // key that could sign resolutions in their favor.
        if (!NeoP2PConfig.verifyArbitratorIntegrity()) {
            return@withContext Result.failure(
                IllegalStateException("Arbitrator key signature invalid — escrow disabled")
            )
        }
        try {
            val buyerKey = ECKey.fromPublicOnly(hexToBytes(buyerPubKeyHex))
            val sellerKey = ECKey.fromPublicOnly(hexToBytes(sellerPubKeyHex))
            val arbKey = ECKey.fromPublicOnly(xOnlyToCompressed(NeoP2PConfig.ARBITRATOR_PUBKEY))

            val redeemScript = ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey))
            // The same redeem script is committed either as P2SH (legacy 2…/m…
            // address) or P2WSH (SegWit bc1/tb1 address) — user's choice. The
            // script contents are identical; only the carrier differs.
            val fundingAddress = when (fundingScriptType) {
                BitcoinAddressType.LEGACY -> LegacyAddress.fromScriptHash(
                    NET_PARAMS,
                    Utils.sha256hash160(redeemScript.getProgram())
                ).toBase58()
                BitcoinAddressType.SEGWIT -> SegwitAddress.fromProgram(
                    NET_PARAMS,
                    0,
                    Sha256Hash.hash(redeemScript.getProgram())
                ).toBech32()
            }

            // Network (miner) fee the payout tx will pay on-chain. Estimated
            // from the fastest fee rate × the FULL payout tx vsize (input +
            // buyer output + fee output + fixed overhead). Using input-only
            // vsize produced txs below minrelaytxfee (1 sat/vB) on testnet.
            val feeRatePerVb = chainMonitor.estimateFees().fastest
            val networkFeeSats = fundingNetworkFeeSats(feeRatePerVb, fundingScriptType)

            val escrowId = "escrow_${offer.offerId}_${System.currentTimeMillis()}"
            val sellerRefundAddr = identityManager.getBitcoinAddress(BitcoinAddressType.LEGACY)
            // F2: the seller attests the refund destination with the escrow key so the
            // arbitrator (and every applying party) can verify where a refund MUST go.
            val sellerRefundAttestation = RoleAddressAttestation.sign(
                privateKeyHex = identityManager.getBitcoinPrivateKeyHex(),
                kind = RoleAddressAttestation.KIND_SELLER_REFUND,
                scopeId = escrowId,
                address = sellerRefundAddr
            )

            val escrow = Escrow(
                escrowId = escrowId,
                offerId = offer.offerId,
                type = EscrowType.ON_CHAIN,
                fundingAddress = fundingAddress,
                fundingScriptType = fundingScriptType,
                redeemScriptHex = redeemScript.getProgram().joinToString("") { "%02x".format(it) },
                depositAmountSats = offer.cryptoAmountSats + offer.sellerFeeSats + networkFeeSats,
                tradeAmountSats = offer.cryptoAmountSats,
                feeAmountSats = offer.feeSats,
                networkFeeSats = networkFeeSats,
                feeAddress = NeoP2PConfig.FEE_WALLET_ADDRESS,
                buyerPeerId = buyerPeerId,
                sellerPeerId = sellerPeerId,
                // P0-1: pin the exact pubkeys authorized for each role.
                buyerPubKeyHex = buyerPubKeyHex,
                sellerPubKeyHex = sellerPubKeyHex,
                status = EscrowStatus.FUNDING,
                // U1: the buyer's payout address (entered at accept time). On
                // the BUY-offer path the acceptor is the seller and provides it;
                // on the SELL-offer path it arrives via the LXMF escrow_status sync
                // event once the buyer accepts.
                buyerBtcAddress = buyerBtcAddress,
                // F2: the buyer's role-signed attestation of that payout
                // destination (scope = offerId), verified above.
                buyerAddressAttestation = buyerAddressAttestation,
                // The seller's own BTC refund address — published via
                // LXMF escrow_status so the buyer (and via the dispute event, the
                // arbitrator) can refund to the right place without knowing
                // the seller's key. The escrow creator IS the seller on both
                // creation paths (acceptOffer for BUY offers, createSellerEscrow
                // for SELL offers).
                sellerRefundAddress = sellerRefundAddr,
                sellerRefundAttestation = sellerRefundAttestation
            )

            db.escrowDao().upsert(escrow.toEntity())
            publishEscrowSync(escrow.escrowId, EscrowStatus.FUNDING.name, escrow.toEntity())
            _escrowStates.update { map ->
                map + (escrow.escrowId to EscrowState(escrow = escrow, status = "created", progress = 0.1f))
            }
            _transitions.emit(EscrowTransition(escrow.escrowId, "created"))

            Log.d(TAG, "Escrow created: ${escrow.escrowId} address=${escrow.fundingAddress}")
            Result.success(escrow)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create escrow", e)
            Result.failure(e)
        }
    }

    /**
     * Re-derive the escrow's funding address for [newType] while it is still
     * FUNDING (nothing deposited yet — the address carries no funds). P2SH ↔
     * P2WSH use the SAME redeem script, so only the address + fee estimate
     * change; the 2-of-3 keys are untouched. Once a deposit exists (FUNDED or
     * later) the address is fixed forever — funds are already there.
     */
    suspend fun switchFundingType(
        escrowId: String,
        newType: BitcoinAddressType
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))
            if (EscrowStatus.valueOf(entity.status) != EscrowStatus.FUNDING) {
                return@withContext Result.failure(
                    Exception("Funding address type can only be changed before the escrow is funded")
                )
            }
            val redeemScriptHex = entity.redeem_script_hex
                ?: return@withContext Result.failure(Exception("No redeem script stored"))
            val redeemScript = Script(hexToBytes(redeemScriptHex))
            val newAddress = when (newType) {
                BitcoinAddressType.LEGACY -> LegacyAddress.fromScriptHash(
                    NET_PARAMS,
                    Utils.sha256hash160(redeemScript.getProgram())
                ).toBase58()
                BitcoinAddressType.SEGWIT -> SegwitAddress.fromProgram(
                    NET_PARAMS,
                    0,
                    Sha256Hash.hash(redeemScript.getProgram())
                ).toBech32()
            }
            val feeRatePerVb = chainMonitor.estimateFees().fastest
            val networkFeeSats = fundingNetworkFeeSats(feeRatePerVb, newType)
            val domain = entity.toDomain()
            val updated = entity.copy(
                funding_address = newAddress,
                funding_script_type = newType.name,
                network_fee_sats = networkFeeSats,
                deposit_amount_sats = domain.tradeAmountSats + domain.feeAmountSats + networkFeeSats
            )
            db.escrowDao().upsert(updated)
            val updatedDomain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = updatedDomain, status = "created", progress = 0.1f))
            }
            Log.d(TAG, "Escrow $escrowId funding type switched to ${newType.name} ($newAddress)")
            Result.success(updatedDomain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch funding type", e)
            Result.failure(e)
        }
    }

    /**
     * The seller funded the escrow. Verifies on-chain via Mempool API.
     */
    suspend fun onEscrowFunded(escrowId: String, fundingTxId: String): Result<Escrow> =
        withContext(Dispatchers.IO) {
            try {
                val entity = db.escrowDao().getEscrowSync(escrowId)
                    ?: return@withContext Result.failure(Exception("Escrow not found"))

                val txInfo = chainMonitor.getTxInfo(fundingTxId)
                if (txInfo.isFailure) {
                    return@withContext Result.failure(
                        Exception("Cannot verify funding tx: ${txInfo.exceptionOrNull()?.message}")
                    )
                }

                // Funding binding (P1): the tx must ACTUALLY pay the escrow's
                // funding address at least the deposit (crypto + fee + network
                // fee). A random confirmed txid (or a deposit to the wrong
                // address / wrong amount) must never mark an escrow FUNDED.
                // Overpayment (2026-09-04) is accepted: the ACTUAL on-chain
                // value is recorded so the payout/refund spend the real input
                // value and return the excess to the seller.
                val outputs = chainMonitor.getTxOutputs(fundingTxId).getOrElse {
                    return@withContext Result.failure(
                        Exception("Cannot fetch funding tx outputs: ${it.message}")
                    )
                }
                val vout = findFundingOutputAtLeast(outputs, entity.funding_address, entity.deposit_amount_sats)
                val fundedValue = fundedValueSats(outputs, entity.funding_address, entity.deposit_amount_sats)
                if (vout == null || fundedValue == null) {
                    // Underpayment (2026-09-04): the deposit pays the escrow
                    // address but is LESS than required. Persist the partial
                    // deposit (txid/vout/value) so the seller can Cancel &
                    // Refund it — never strand it in the multisig. The escrow
                    // stays FUNDING; the sweep must NOT auto-cancel it.
                    val partialVout = findFundingOutputAny(outputs, entity.funding_address)
                    val partialValue = fundedValueAny(outputs, entity.funding_address)
                    if (partialVout != null && partialValue != null) {
                        val withPartial = entity.copy(
                            funding_tx_id = fundingTxId,
                            funding_vout = partialVout.toLong(),
                            funded_amount_sats = partialValue
                        )
                        db.escrowDao().upsert(withPartial)
                        runCatching { publishEscrowSync(escrowId, EscrowStatus.FUNDING.name, withPartial) }
                        return@withContext Result.failure(
                            Exception(
                                "UNDERPAID: funding tx pays the escrow address " +
                                    "${entity.funding_address} only $partialValue sats; " +
                                    "${entity.deposit_amount_sats} sats required. " +
                                    "The partial deposit is recorded — cancel & refund it, then create a new escrow."
                            )
                        )
                    }
                    return@withContext Result.failure(
                        Exception(
                            "Funding tx does not pay the escrow address " +
                                "${entity.funding_address} at least the deposit amount " +
                                "${entity.deposit_amount_sats} sats"
                        )
                    )
                }

                // Persist the funding txid + vout IMMEDIATELY (even before the
                // confirmation threshold is met): the deposit is verifiably
                // bound to this escrow, so the UI can show "In progress /
                // waiting for confirmation" instead of "Pending" and disable
                // the double-send button across app restarts.
                if (entity.funding_tx_id != fundingTxId) {
                    val withTx = entity.copy(
                        funding_tx_id = fundingTxId,
                        funding_vout = vout.toLong(),
                        funded_amount_sats = fundedValue
                    )
                    db.escrowDao().upsert(withTx)
                    // Sync the txid to the counterparty NOW (still FUNDING):
                    // the buyer's row must flip to "In progress / waiting for
                    // confirmation" the moment the deposit is bound — not only
                    // after the confirmation threshold is met (which may be
                    // minutes away, or never if the tx is slow).
                    runCatching { publishEscrowSync(escrowId, EscrowStatus.FUNDING.name, withTx) }
                }

                // Configurable confirmations (P2): the funding tx must have at
                // least the escrow's required confirmations before the deposit
                // is accepted. Defaults to 1 (historical behavior).
                val info = txInfo.getOrThrow()
                val required = entity.required_confirmations.coerceAtLeast(1)
                if (info.confirmations < required) {
                    return@withContext Result.failure(
                        Exception(
                            "Funding tx has ${info.confirmations} confirmation(s); " +
                                "$required required. Wait for more blocks."
                        )
                    )
                }
                // Freshness (2026-09-01): the funding address is deterministic
                // (same 2-of-3 keys → same address), so a tx from a PREVIOUS
                // escrow between the same peers pays this address the exact
                // amount too. A confirmed funding tx must be mined AFTER the
                // escrow was created — otherwise the seller could paste (or
                // auto-recover) an old txid and mark the new escrow FUNDED
                // without depositing anything.
                if (fundingTxIsStale(info.blockTimeSec, info.confirmed, entity.created_at)) {
                    return@withContext Result.failure(
                        Exception(
                            "Funding tx ${fundingTxId} was mined before this escrow was created " +
                                "(blockTime=${info.blockTimeSec}, escrow created=${entity.created_at / 1000}) — " +
                                "it belongs to a previous escrow on the same address. Send a new deposit."
                        )
                    )
                }

                val updated = entity.copy(
                    funding_tx_id = fundingTxId,
                    funding_vout = vout.toLong(),
                    funded_amount_sats = fundedValue,
                    status = EscrowStatus.FUNDED.name,
                    // Record when the funding was confirmed so the 6-hour
                    // auto-refund timeout measures from confirmation, not creation.
                    funded_at = System.currentTimeMillis()
                )
                db.escrowDao().upsert(updated)
                publishEscrowSync(escrowId, EscrowStatus.FUNDED.name, updated)

                val domain = updated.toDomain()
                _escrowStates.update { map ->
                    map + (escrowId to EscrowState(escrow = domain, status = "funded", progress = 0.3f))
                }
                _transitions.emit(EscrowTransition(escrowId, "funded"))
                Result.success(domain)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update escrow funding", e)
                Result.failure(e)
            }
        }

    /**
     * C1d (2026-09-11): heal the funding script type + address from the ON-CHAIN
     * funding UTXO. The mutable fields were corrupted by the ingest feedback
     * loop (buyer mirror echo flipped the seller's SEGWIT row to LEGACY and
     * overwrote the address); the UTXO's scriptpubkey_address (mempool) is the
     * only value that cannot lie. Persists the healed row and returns it. No-op
     * when the funding txid/vout are missing or the explorer is unreachable.
     */
    private suspend fun healFundingTypeFromChain(entity: EscrowEntity): EscrowEntity {
        val txid = entity.funding_tx_id ?: return entity
        return try {
            val outputs = chainMonitor.getTxOutputs(txid).getOrNull() ?: return entity
            val vout = entity.funding_vout.toInt()
            val output = outputs.firstOrNull { it.index == vout }
                ?: outputs.firstOrNull { it.valueSats == (entity.funded_amount_sats ?: entity.deposit_amount_sats) }
                ?: return entity
            val chainAddr = output.scriptPubkeyAddress ?: return entity
            val type = try {
                when (Address.fromString(NET_PARAMS, chainAddr)) {
                    is SegwitAddress -> BitcoinAddressType.SEGWIT
                    else -> BitcoinAddressType.LEGACY
                }
            } catch (_: Exception) {
                return entity
            }
            if (entity.funding_address != chainAddr || entity.funding_script_type != type.name) {
                val healed = entity.copy(
                    funding_address = chainAddr,
                    funding_script_type = type.name
                )
                db.escrowDao().upsert(healed)
                Log.i(TAG, "Healed funding type from chain: ${entity.funding_script_type}/${entity.funding_address} → ${type.name}/$chainAddr")
                healed
            } else {
                entity
            }
        } catch (e: Exception) {
            Log.w(TAG, "Funding type chain-heal failed: ${e.message}")
            entity
        }
    }

    /**
     * Generate the unsigned payout transaction.
     * Creates a tx spending from the 2-of-3 multisig to buyer + fee wallet.
     * Returns the serialized unsigned transaction hex.
     */
    suspend fun generatePayoutTransaction(
        escrowId: String,
        fundingTxId: String,
        fundingOutputIndex: Int? = null,
        buyerAddressStr: String,
        feeAddressStr: String = NeoP2PConfig.FEE_WALLET_ADDRESS
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // C1d (2026-09-11): heal the funding type/address (possibly
            // corrupted by the ingest feedback loop) from the on-chain UTXO
            // BEFORE building the payout — scriptSig vs witness shape.
            val entity = healFundingTypeFromChain(
                db.escrowDao().getEscrowSync(escrowId)
                    ?: return@withContext Result.failure(Exception("Escrow not found"))
            )

            val escrow = entity.toDomain()
            // 2026-09-07: a payout must never send the buyer's sats to the
            // fee wallet or back into the escrow's own multisig. This is the
            // last line of defense — every caller (confirmReceipt, dispute
            // auto-gen, healDisputePsbt) funnels through here.
            if (PayoutAddressGate.isForbidden(buyerAddressStr, NeoP2PConfig.FEE_WALLET_ADDRESS, escrow.fundingAddress)) {
                throw IllegalStateException(
                    "Payout destination is the fee wallet or the escrow itself — refusing to build"
                )
            }
            // F2 (2026-09-12): the destination being built MUST be the
            // buyer-attested address, and the buyer's role key MUST be anchored
            // in the escrow's 2-of-3 script. A forged escrow_status that
            // overwrote buyer_btc_address — or a caller passing a different
            // destination — can no longer redirect the payout. Fail closed.
            val payoutVerdict = payoutDestinationVerdict(entity)
            if (!payoutVerdict.ok) {
                throw SecurityException(
                    "Payout destination failed F2 attestation: ${payoutVerdict.reason} — refusing to build"
                )
            }
            if (!buyerAddressStr.equals(entity.buyer_btc_address, ignoreCase = true)) {
                throw SecurityException(
                    "Payout destination differs from the attested buyer address — refusing to build"
                )
            }
            requireNotNull(escrow.redeemScriptHex) { "Redeem script not stored" }

            val redeemScript = Script(hexToBytes(escrow.redeemScriptHex))

            // Spend the REAL funding output: prefer the explicitly-passed index,
            // then the vout recorded at funding verification (Task 3). Defaulting
            // to 0 broke funding txs whose deposit output is not the first vout
            // (e.g. sender-created change outputs).
            val vout = (fundingOutputIndex ?: escrow.fundingVout.toInt()).toLong()

            // Miner fee budget: the deposit input must cover outputs + the
            // network fee (miner fee = input − outputs). New escrows store
            // networkFeeSats; old rows (pre-migration) fall back to estimating
            // it from the fastest fee rate now.
            val networkFeeSats =
                if (escrow.networkFeeSats > 0) escrow.networkFeeSats
                else maxOf(
                    chainMonitor.estimateFees().fastest * PAYOUT_APPROX_VSIZE,
                    MIN_NETWORK_FEE_SATS
                )
            val outputValue = escrow.tradeAmountSats + escrow.feeAmountSats
            // The input value is the ACTUAL on-chain funding output (2026-09-04):
            // equals depositAmountSats for exact deposits, HIGHER when the
            // seller overpaid. The payout must spend the real input value
            // (SegWit BIP-143 commits it) and return the excess to the seller.
            val inputValue = escrow.fundedAmountSats ?: escrow.depositAmountSats
            if (inputValue < outputValue + networkFeeSats) {
                throw IllegalStateException(
                    "Deposit insufficient to cover outputs + network fee " +
                        "(deposit=$inputValue, outputs=$outputValue, fee=$networkFeeSats)"
                )
            }

            val payoutTx = Transaction(NET_PARAMS)
            payoutTx.addInput(Sha256Hash.wrap(fundingTxId), vout, ScriptBuilder.createEmpty())

            // Output 1: buyer receives the trade amount (full C, buyer fee = 0).
            // Parsed with Address.fromString so BOTH legacy (m…/1…) and SegWit
            // (tb1…/bc1…) receive addresses are accepted.
            val buyerAddress = Address.fromString(NET_PARAMS, buyerAddressStr)
            payoutTx.addOutput(Coin.valueOf(escrow.tradeAmountSats), buyerAddress)

            // Output 2: fee wallet gets the full 0.5% platform fee — but ONLY
            // if it is above the dust threshold. A sub-dust fee output makes
            // the whole payout un-broadcastable ("dust, tx with dust output"
            // RPC error -26); instead the sub-dust remainder simply stays with
            // the miner as extra fee. Dust limit: 546 sats (P2PKH output).
            if (escrow.feeAmountSats >= DUST_THRESHOLD_SATS) {
                val feeAddress = Address.fromString(NET_PARAMS, feeAddressStr)
                payoutTx.addOutput(Coin.valueOf(escrow.feeAmountSats), feeAddress)
            } else {
                Log.w(TAG, "Fee ${escrow.feeAmountSats} sats is sub-dust (< 546); skipping fee output — remainder goes to miner fee")
            }

            // Output 3 (overpayment, 2026-09-04): the excess above the deposit
            // goes back to the SELLER — never to the fee wallet (the 0.5%
            // seller-only fee is a documented contract; a fat-finger overpayment
            // must not be silently charged as "fee"). The seller's refund
            // address is the escrow's recorded seller_refund_address, falling
            // back to the local identity's address. Skipped when there is no
            // excess (exact deposit) or the excess is sub-dust.
            val excess = inputValue - escrow.depositAmountSats
            if (excess > 0) {
                val sellerAddrStr = escrow.sellerRefundAddress
                    ?: identityManager.getBitcoinAddress(BitcoinAddressType.LEGACY)
                if (excess >= DUST_THRESHOLD_SATS) {
                    val sellerAddress = Address.fromString(NET_PARAMS, sellerAddrStr)
                    payoutTx.addOutput(Coin.valueOf(excess), sellerAddress)
                } else {
                    Log.w(TAG, "Overpayment excess $excess sats is sub-dust (< 546); skipping seller output — remainder goes to miner fee")
                }
            }

            // The implicit miner fee = input − outputs = networkFeeSats. No
            // explicit setFee is needed because the deposit already covers it;
            // outputs are exactly buyer(C) + feeWallet(feeSats) [+ seller(excess)].
            // No dust output.
            val txHex = payoutTx.bitcoinSerialize().joinToString("") { "%02x".format(it) }

            val updated = entity.copy(
                psbt_unsigned = txHex.encodeToByteArray(),
                status = EscrowStatus.SIGNED.name
            )
            db.escrowDao().upsert(updated)

            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = updated.toDomain(), status = "signed", progress = 0.6f))
            }
            _transitions.emit(EscrowTransition(escrowId, "signed"))

            Log.d(TAG, "Payout tx created for $escrowId")
            Result.success(txHex)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate payout tx", e)
            Result.failure(e)
        }
    }

    /**
     * Sign the payout transaction with the BUYER's private key.
     *
     * P0-1 FIX: the private key is checked against the escrow's stored
     * `buyer_pubkey_hex` BEFORE a signature is accepted. A key that is not the
     * buyer's own key cannot be used to fill the buyer signature slot.
     */
    suspend fun signPayoutAsBuyer(
        escrowId: String,
        buyerPrivKeyHex: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // C1d (2026-09-11): heal the funding type from the chain so the
            // buyer signs with the SAME sighash scheme the seller verifies
            // with (both derive from the on-chain UTXO → cannot diverge again).
            val entity = healFundingTypeFromChain(
                db.escrowDao().getEscrowSync(escrowId)
                    ?: return@withContext Result.failure(Exception("Escrow not found"))
            )

            val key = ECKey.fromPrivate(hexToBytes(buyerPrivKeyHex))
            val expected = entity.buyer_pubkey_hex ?: return@withContext Result.failure(
                Exception("Escrow has no buyer pubkey recorded")
            )
            if (!pubkey(key, expected)) {
                return@withContext Result.failure(
                    SecurityException("Signing key does not match the escrow buyer pubkey")
                )
            }

            val sig = signTransaction(entity, key)
            // C1d (2026-09-11): [signTransaction] returns the DER signature as a
            // HEX STRING. Store the raw DER bytes (hexToBytes), NOT the ASCII
            // hex text — storing the text produced a 144-byte "signature" that
            // TransactionSignature.decodeFromBitcoin rejects as non-canonical
            // ("Signature encoding is not canonical") on release.
            val updated = entity.copy(buyer_signature = hexToBytes(sig))
            db.escrowDao().upsert(updated)

            Log.d(TAG, "Buyer signed payout for $escrowId")
            Result.success(sig)
        } catch (e: Exception) {
            Log.e(TAG, "Buyer signing failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sign the payout transaction with the SELLER's private key.
     *
     * P0-1 FIX: same role-key validation as the buyer path.
     */
    suspend fun signPayoutAsSeller(
        escrowId: String,
        sellerPrivKeyHex: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val key = ECKey.fromPrivate(hexToBytes(sellerPrivKeyHex))
            val expected = entity.seller_pubkey_hex ?: return@withContext Result.failure(
                Exception("Escrow has no seller pubkey recorded")
            )
            if (!pubkey(key, expected)) {
                return@withContext Result.failure(
                    SecurityException("Signing key does not match the escrow seller pubkey")
                )
            }

            val sig = signTransaction(entity, key)
            // C1d (2026-09-11): [signTransaction] returns the DER signature as a
            // HEX STRING — store the raw DER bytes, not the ASCII hex text (see
            // signPayoutAsBuyer for the non-canonical-signature failure this caused).
            val updated = entity.copy(seller_signature = hexToBytes(sig))
            db.escrowDao().upsert(updated)

            Log.d(TAG, "Seller signed payout for $escrowId")
            Result.success(sig)
        } catch (e: Exception) {
            Log.e(TAG, "Seller signing failed", e)
            Result.failure(e)
        }
    }

    /**
     * C1d (2026-09-11): persist a buyer payout signature received over LXMF
     * escrow_status. The signature is verified against the escrow's
     * buyer_pubkey_hex BEFORE it is stored — a forged/wrong-key signature
     * must never be persisted (it would poison the release and strand funds).
     * Idempotent: re-deliveries (LXMF router retry) re-verify and re-store
     * the same value.
     */
    suspend fun storeBuyerSignature(escrowId: String, sigHex: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // C1d (2026-09-11): heal the funding type from the chain BEFORE
            // verifying the incoming buyer signature — the verify uses the
            // BIP-143 vs legacy sighash per this value, and a corrupt row
            // would reject a perfectly valid remote signature.
            val entity = healFundingTypeFromChain(
                db.escrowDao().getEscrowSync(escrowId)
                    ?: return@withContext Result.failure(Exception("Escrow not found"))
            )
            val buyerKey = entity.buyer_pubkey_hex
                ?: return@withContext Result.failure(Exception("Escrow has no buyer pubkey recorded"))
            if (!isValidBuyerSignature(sigHex, buyerKey)) {
                return@withContext Result.failure(
                    SecurityException("Invalid buyer payout signature (C1d)")
                )
            }
            val txHex = entity.psbt_unsigned?.toString(Charsets.UTF_8)
                ?: return@withContext Result.failure(Exception("No unsigned payout tx stored"))
            val redeemScriptHex = entity.redeem_script_hex
                ?: return@withContext Result.failure(Exception("No redeem script stored"))
            val tx = Transaction(NET_PARAMS, hexToBytes(txHex))
            val redeemScript = Script(hexToBytes(redeemScriptHex))
            val witness = escrowScriptType(entity) == BitcoinAddressType.SEGWIT
            val depositSats = entity.funded_amount_sats ?: entity.deposit_amount_sats
            if (!verifySignature(tx, redeemScript, buyerKey, hexToBytes(sigHex), depositSats, witness)) {
                return@withContext Result.failure(
                    SecurityException("Buyer signature does not verify against the escrow buyer pubkey (C1d)")
                )
            }
            db.escrowDao().upsert(entity.copy(buyer_signature = hexToBytes(sigHex)))
            Log.d(TAG, "Stored verified buyer signature for $escrowId")
            // C1d: the buyer signature just arrived — release if the seller
            // already confirmed (CONFIRMING). Best-effort; the sweep retries.
            if (entity.status == EscrowStatus.CONFIRMING.name) {
                releaseWhenReady(escrowId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store buyer signature", e)
            Result.failure(e)
        }
    }

    /**
     * C1d: if the local identity is the BUYER of [escrowId], sign the payout
     * with the buyer key and deliver the signature to the seller over LXMF
     * escrow_status. No-op when the local identity is not the buyer, the
     * unsigned tx is missing, or the buyer key is not the local key.
     */
    suspend fun signPayoutAsBuyerIfLocal(escrowId: String) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId) ?: return
            if (entity.buyer_peer_id != identityManager.myPeerId()) return
            val buyerKey = entity.buyer_pubkey_hex ?: return
            val localKey = identityManager.getBitcoinPubKeyHex()
            if (!buyerKey.equals(localKey, ignoreCase = true)) return
            if (entity.psbt_unsigned == null) return
            val sig = signPayoutAsBuyer(escrowId, identityManager.getBitcoinPrivateKeyHex())
                .getOrNull() ?: return
            // Deliver to the seller so their release can combine it.
            val sellerPeerId = entity.seller_peer_id
            if (sellerPeerId.isNotBlank()) {
                rnsTransport.sendEscrowStatus(
                    toPeerId = sellerPeerId,
                    escrowId = escrowId,
                    status = EscrowStatus.CONFIRMING.name,
                    fields = mapOf(
                        "buyer_signature" to sig,
                        // C1d fix (2026-09-11): the seller's ingestEscrowStatus
                        // party gate reads these two fields to decide the local
                        // identity is a party. Without them the message was
                        // unpacked and silently DROPPED, so the seller never
                        // saw the buyer's signature and the release could not
                        // complete (stuck CONFIRMING forever).
                        "buyer_peer_id" to entity.buyer_peer_id,
                        "seller_peer_id" to entity.seller_peer_id,
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Buyer auto-sign failed: ${e.message}")
        }
    }

    /**
     * C1d: broadcast the payout once BOTH the buyer signature (stored via
     * [storeBuyerSignature]) and the local seller signature are available.
     * No-op when the buyer signature is missing (the release waits for it).
     */
    suspend fun releaseWhenReady(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))
            if (entity.buyer_signature == null) {
                return@withContext Result.failure(
                    Exception("Awaiting the buyer's payout signature (C1d)")
                )
            }
            releaseFunds(escrowId)
        } catch (e: Exception) {
            Log.e(TAG, "releaseWhenReady failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sign the payout transaction with a key, using the real 2-of-3 redeem
     * script. Returns the DER-encoded signature hex (with SIGHASH_ALL
     * appended). P2SH escrows use the legacy sighash; P2WSH escrows use the
     * BIP-143 witness sighash (value-committed) — the deposit is already
     * stored on the escrow, so no extra fetch is needed.
     */
    private fun signTransaction(entity: EscrowEntity, key: ECKey): String {
        val txHex = entity.psbt_unsigned?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("No unsigned tx found")
        val redeemScriptHex = entity.redeem_script_hex
            ?: throw IllegalStateException("No redeem script stored")
        val tx = Transaction(NET_PARAMS, hexToBytes(txHex))
        val redeemScript = Script(hexToBytes(redeemScriptHex))
        return when (escrowScriptType(entity)) {
            BitcoinAddressType.LEGACY -> {
                // Sign the input against the redeem script (not an empty script).
                val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
                val sig = key.sign(hash)
                // DER sig + SIGHASH_ALL
                sig.encodeToDER().let { der ->
                    (der + byteArrayOf(Transaction.SigHash.ALL.value.toByte())).joinToString("") { "%02x".format(it) }
                }
            }
            BitcoinAddressType.SEGWIT -> {
                // BIP-143 commits the INPUT VALUE — the actual on-chain funding
                // output (2026-09-04), which may exceed the deposit on overpayment.
                val inputValue = entity.funded_amount_sats ?: entity.deposit_amount_sats
                val txSig = tx.calculateWitnessSignature(
                    0, key, redeemScript,
                    Coin.valueOf(inputValue),
                    Transaction.SigHash.ALL, false
                )
                txSig.encodeToBitcoin().joinToString("") { "%02x".format(it) }
            }
        }
    }

    /**
     * Release funds after fiat confirmation.
     *
     * P0-1 FIX: before assembling the scriptSig, this validates that:
     *   1. exactly two distinct signatures exist among buyer/seller/arbitrator,
     *   2. each signature actually verifies the payout input against its role key.
     * Then it builds the P2SH scriptSig and broadcasts.
     */
    suspend fun releaseFunds(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            // C1d (2026-09-11): heal the funding type from the chain before
            // verifying signatures/assembling the spend — the assemble path
            // derives witness vs scriptSig from this value and a corrupt
            // LEGACY field produced an un-broadcastable scriptSig spend of a
            // P2WSH UTXO ("Witness requires empty scriptSig", RPC -26).
            val entity = healFundingTypeFromChain(
                db.escrowDao().getEscrowSync(escrowId)
                    ?: return@withContext Result.failure(Exception("Escrow not found"))
            )

            // Release gate (P2): only RECEIPT_SENT/CONFIRMING may release.
            // FUNDED/SIGNED/PAYMENT_PENDING fail even if 2 signatures exist —
            // the seller's fiat confirmation is the ONLY release gate and no
            // UI path may bypass it (previously the FUNDED/SIGNED "Release
            // funds" button called this directly).
            if (!canReleaseFromStatus(entity.status)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Release requires the buyer's receipt and seller confirmation (current: ${entity.status})"
                    )
                )
            }

            val txHex = entity.psbt_unsigned?.toString(Charsets.UTF_8)
                ?: return@withContext Result.failure(Exception("No unsigned tx found"))
            val redeemScriptHex = entity.redeem_script_hex
                ?: return@withContext Result.failure(Exception("No redeem script stored"))
            val redeemScript = Script(hexToBytes(redeemScriptHex))
            val tx = Transaction(NET_PARAMS, hexToBytes(txHex))

            // Assemble a valid 2-of-3 spend (buyer + seller + arbitrator, in
            // redeem-script pubkey order), filling both buyer & seller slots
            // with the local key in the single-key model. Only signatures that
            // verify against their role pubkey are included (P0-1 role
            // binding). P2SH escrows produce a scriptSig; P2WSH a witness.
            val spend = assemble2of3Spend(
                tx, redeemScript, entity,
                arbitratorSigHex = entity.arbitrator_signature?.toString(Charsets.UTF_8)
            ) ?: return@withContext Result.failure(
                Exception("Fewer than 2 valid signatures to release")
            )
            attachSpend(tx, spend)

            val finalHex = tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }

            // Audit P2-1 (2026-09-12): the escrow row stores this txid and the
            // counterparty mirrors it — bind it to the tx we built instead of
            // trusting the explorer's echo.
            val broadcastResult = chainMonitor.broadcastTx(finalHex, tx.getHashAsString())
            if (broadcastResult.isFailure) {
                return@withContext Result.failure(
                    Exception("Broadcast failed: ${broadcastResult.exceptionOrNull()?.message}")
                )
            }

            val payoutTxId = broadcastResult.getOrThrow()
            val updated = entity.copy(
                payout_tx_id = payoutTxId,
                status = EscrowStatus.RELEASED.name,
                released_at = System.currentTimeMillis()
            )
            db.escrowDao().upsert(updated)
            publishEscrowSync(escrowId, EscrowStatus.RELEASED.name, updated)

            // The trade is done — mark the linked offer COMPLETED so it
            // leaves the marketplace feed (previously the offer stayed
            // ESCROWED forever and kept showing on the home list). The
            // LXMF offer_status event syncs the terminal status to the buyer's row.
            runCatching {
                db.offerDao().getOfferSync(entity.offer_id)?.let { offer ->
                    if (offer.status != com.neop2p.domain.model.OfferStatus.COMPLETED.name) {
                        db.offerDao().updateStatus(entity.offer_id, com.neop2p.domain.model.OfferStatus.COMPLETED.name)
                        publishOfferStatusDual(
                            offerId = entity.offer_id,
                            status = com.neop2p.domain.model.OfferStatus.COMPLETED.name,
                            matchedPeerId = offer.matched_peer_id,
                            authorPeerId = identityManager.myPeerId()
                        )
                        Log.d(TAG, "Offer ${entity.offer_id} marked COMPLETED after release")
                    }
                }
            }.onFailure { Log.w(TAG, "Failed to mark offer COMPLETED: ${it.message}") }

            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = "released", progress = 1.0f))
            }
            _transitions.emit(EscrowTransition(escrowId, "released"))

            Log.d(TAG, "Funds released for $escrowId tx=$payoutTxId")
            Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release funds", e)
            Result.failure(e)
        }
    }

    /**
     * Verify that [signatureWithSighash] (DER + SIGHASH_ALL) was produced by
     * [pubkeyHex] over input 0 of [tx] spend using [redeemScript].
     *
     * P2WSH escrows verify with the BIP-143 witness sighash ([witness] =
     * true), which commits the input value ([depositSats]) — pass the escrow's
     * stored deposit.
     */
    private fun verifySignature(
        tx: Transaction,
        redeemScript: Script,
        pubkeyHex: String,
        signatureWithSighash: ByteArray,
        depositSats: Long,
        witness: Boolean
    ): Boolean {
        return try {
            // Arbitrator pubkey is stored x-only; convert to compressed for bitcoinj.
            val pubBytes = hexToBytes(pubkeyHex)
            val key = if (pubBytes.size == 32) {
                ECKey.fromPublicOnly(xOnlyToCompressed(pubkeyHex))
            } else {
                ECKey.fromPublicOnly(pubBytes)
            }
            val sig = TransactionSignature.decodeFromBitcoin(signatureWithSighash, true, true)
            val hash = if (witness) {
                tx.hashForWitnessSignature(0, redeemScript, Coin.valueOf(depositSats), Transaction.SigHash.ALL, false)
            } else {
                tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            }
            key.verify(hash, sig)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed: ${e.message}")
            false
        }
    }

    /**
     * The script type the escrow's funding output commits (P2SH vs P2WSH).
     *
     * C1d (2026-09-11): derived from the FUNDING ADDRESS ITSELF — the
     * deterministic address is the ground truth (created from the redeem
     * script), while the mutable `funding_script_type` field got corrupted by
     * the ingest feedback loop: the buyer's mirrored row pinned a stale
     * LEGACY, echoed it back, and flipped the seller's row too — so both
     * sides signed scriptSig/legacy SIGHASH over a P2WSH UTXO and every
     * broadcast died with "Witness requires empty scriptSig" (-26). The
     * address (tb1=SEGWIT, m/1=LEGACY) cannot lie.
     */
    private fun escrowScriptType(entity: EscrowEntity): BitcoinAddressType =
        try {
            val addr = Address.fromString(NET_PARAMS, entity.funding_address)
            when (addr) {
                is SegwitAddress -> BitcoinAddressType.SEGWIT
                else -> BitcoinAddressType.LEGACY
            }
        } catch (_: Exception) {
            // Fall back to the stored field when the address is unparseable.
            try {
                BitcoinAddressType.valueOf(entity.funding_script_type)
            } catch (_: Exception) {
                BitcoinAddressType.LEGACY
            }
        }

    /**
     * Attach the 2-of-3 signatures to input 0 of [tx] for broadcast: P2SH
     * escrows get a scriptSig (`OP_0 sig sig redeem`), P2WSH escrows get the
     * witness (`[empty] sig sig redeem`).
     */
    private fun attachSpend(tx: Transaction, spend: SpendParts) {
        spend.witness?.let { tx.getInput(0).setWitness(it) }
        spend.scriptSig?.let { tx.getInput(0).setScriptSig(it) }
    }

    /**
     * Assemble a 2-of-3 spend of input 0 of [tx] — P2SH scriptSig for legacy
     * escrows, P2WSH witness for SegWit escrows — filling signatures in
     * redeem-script pubkey order [buyer, seller, arbitrator].
     *
     * Signature source per slot, in order of preference:
     *   1. a stored signature for that slot (entity.buyer_signature /
     *      entity.seller_signature / [arbitratorSigHex]) IF it verifies via
     *      [verifySignature] against that role's pubkey;
     *   2. else the local key (`identityManager.getBitcoinPrivateKeyHex()`) IF
     *      `pubkey(localKey, rolePubkey)` matches that role, signing via
     *      [signRaw] and verifying.
     *
     * CHECKMULTISIG semantics: signatures must appear in ascending redeem-script
     * pubkey order, but pubkeys WITHOUT a matching sig are skipped — so a valid
     * spend can be [buyerSig, sellerSig], [buyerSig, arbSig], [sellerSig,
     * arbSig], or all three. A signature only counts for a slot if it verifies
     * against that slot's pubkey (P0-1 role binding).
     *
     * @return a [SpendParts] (scriptSig/witness + attached tx), or null if
     * fewer than 2 valid distinct signatures can be produced for the 2-of-3.
     */
    private fun assemble2of3Spend(
        tx: Transaction,
        redeemScript: Script,
        entity: EscrowEntity,
        arbitratorSigHex: String? = null
    ): SpendParts? {
        val localPrivHex = identityManager.getBitcoinPrivateKeyHex()
        val localKey = ECKey.fromPrivate(hexToBytes(localPrivHex))
        // BIP-143 commits the INPUT VALUE — the actual on-chain funding output
        // (2026-09-04), which may exceed the deposit on overpayment.
        val depositSats = entity.funded_amount_sats ?: entity.deposit_amount_sats
        val witness = escrowScriptType(entity) == BitcoinAddressType.SEGWIT

        // Role slots with their pubkey and a candidate signature.
        val roles = listOf(
            // (rolePubkey, storedSignature)
            entity.buyer_pubkey_hex to entity.buyer_signature,
            entity.seller_pubkey_hex to entity.seller_signature,
            NeoP2PConfig.ARBITRATOR_PUBKEY to arbitratorSigHex?.let { hexToBytes(it) }
        )

        // Collect one valid signature PER slot (in the single-key model the
        // SAME pubkey occupies both buyer and seller slots and must contribute
        // ONE signature per slot — CHECKMULTISIG evaluates each sig against its
        // own slot's pubkey, so two slots with one key need two sigs).
        val sigByRole = mutableListOf<Pair<String, ByteArray>>()
        for ((rolePubkey, storedSig) in roles) {
            if (rolePubkey == null) continue
            var sig: ByteArray? = null
            // 1) Stored signature for this slot, if it verifies.
            storedSig?.let {
                val ok = verifySignature(tx, redeemScript, rolePubkey, it, depositSats, witness)
                Log.d(TAG, "verify stored sig for role ${rolePubkey.take(10)} witness=$witness deposit=$depositSats ok=$ok sigLen=${it.size}")
                if (ok) sig = it else Log.w(TAG, "Stored sig failed verify for role ${rolePubkey.take(10)}")
            }
            // 2) Local key, if it matches this role.
            if (sig == null && pubkey(localKey, rolePubkey)) {
                val candidate = signRaw(tx, redeemScript, localKey, depositSats, witness)
                val ok2 = verifySignature(tx, redeemScript, rolePubkey, candidate, depositSats, witness)
                Log.d(TAG, "verify local sig for role ${rolePubkey.take(10)} ok=$ok2 localPub=${localKey.publicKeyAsHex.take(10)}")
                if (ok2) sig = candidate else Log.w(TAG, "Local sig failed verify for role ${rolePubkey.take(10)}")
            } else if (sig == null) {
                Log.d(TAG, "No stored sig and localKey ${localKey.publicKeyAsHex.take(10)} != role ${rolePubkey.take(10)} xOnly=${xOnlyOf(localKey.publicKeyAsHex).take(10)}")
            }
            sig?.let { sigByRole.add(rolePubkey to it) }
        }

        // CHECKMULTISIG semantics: signatures must appear in ascending
        // redeem-script pubkey order, and createRedeemScript SORTS the pubkeys
        // (ECKey.PUBKEY_COMPARATOR, ascending bytes). Emit the collected
        // signatures in that sorted order — emitting them in a fixed
        // [buyer, seller, arb] order made CHECKMULTISIG match a signature
        // against the WRONG slot's pubkey and reject the spend
        // ("Signature must be zero for failed CHECK(MULTI)SIG operation").
        // The arbitrator's pubkey is compared in its COMPRESSED form
        // (xOnlyToCompressed), matching exactly what createRedeemScript sorted.
        val sigsInPubkeyOrder = sigByRole
            .sortedWith { a, b ->
                // Match createRedeemScript's sort exactly: it sorts on the
                // COMPRESSED pubkey bytes that went into the script. Buyer and
                // seller are stored compressed; the arbitrator is stored x-only
                // and was compressed (xOnlyToCompressed) when building the script.
                val ap = if (a.first == NeoP2PConfig.ARBITRATOR_PUBKEY)
                    xOnlyToCompressed(a.first) else hexToBytes(a.first)
                val bp = if (b.first == NeoP2PConfig.ARBITRATOR_PUBKEY)
                    xOnlyToCompressed(b.first) else hexToBytes(b.first)
                ap.compareBytes(bp)
            }
            .map { it.second }
            .toMutableList()

        Log.d(TAG, "assemble2of3: collected ${sigsInPubkeyOrder.size} sigs need 2, roles=${sigByRole.map { it.first.take(10) }} redeem=${redeemScript.getProgram().joinToString("") { "%02x".format(it) }.take(120)}...")

        if (sigsInPubkeyOrder.size < 2) {
            Log.w(TAG, "Cannot assemble 2-of-3 for escrow ${entity.escrow_id} deposit=$depositSats witness=$witness tx=${tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }.take(60)}...")
            return null
        }
        // For 2-of-3, keep exactly 2 signatures in pubkey order (already
        // ordered). NOTE: never clear()+addAll() back into the SAME list —
        // when size <= 2 the trimmed list IS the original, so the clear
        // destroys the collected signatures and the witness goes out empty
        // ("Operation not valid with the current stack size" on broadcast).
        val finalSigs = if (sigsInPubkeyOrder.size > 2) {
            sigsInPubkeyOrder.take(2)
        } else {
            sigsInPubkeyOrder
        }

        return when (escrowScriptType(entity)) {
            BitcoinAddressType.LEGACY -> SpendParts(
                scriptSig = ScriptBuilder.createMultiSigInputScriptBytes(
                    finalSigs,
                    redeemScript.getProgram()
                )
            )
            BitcoinAddressType.SEGWIT -> {
                val sigs = finalSigs.map {
                    TransactionSignature.decodeFromBitcoin(it, true, true)
                }.toTypedArray()
                val witness = TransactionWitness.redeemP2WSH(redeemScript, *sigs)
                SpendParts(witness = witness)
            }
        }
    }

    private data class SpendParts(
        val scriptSig: Script? = null,
        val witness: TransactionWitness? = null
    )

    suspend fun disputeEscrow(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            // Fork guard: a swapped arbitrator key must not reach the dispute
            // machinery — a hijacked tie-break key could rule in the fork's
            // favor once the parties apply the resolution.
            if (!NeoP2PConfig.verifyArbitratorIntegrity()) {
                return@withContext Result.failure(
                    IllegalStateException("Arbitrator key signature invalid — dispute disabled")
                )
            }
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))
            // Status guard: DISPUTED/RESOLVING already disputed, terminal never re-disputed.
            val currentStatus = try { EscrowStatus.valueOf(entity.status) } catch (_: Exception) { null }
            if (currentStatus == EscrowStatus.DISPUTED || currentStatus == EscrowStatus.RESOLVING) {
                return@withContext Result.failure(IllegalStateException("Escrow already disputed"))
            }
            // FUNDING is not disputable (2026-09-05): the deposit is either not
            // yet broadcast (nothing to arbitrate — the 30-min funding window
            // auto-cancels) or in flight (unconfirmed — the arbitrator's
            // payout/refund would spend a nonexistent output and fail to
            // broadcast). The buyer's exit from a stuck FUNDING escrow is the
            // auto-cancel, not a dispute.
            if (currentStatus == EscrowStatus.FUNDING) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Cannot dispute while the escrow is still funding — " +
                            "wait for the deposit to confirm or let the funding window auto-cancel"
                    )
                )
            }
            if (currentStatus == EscrowStatus.RELEASED || currentStatus == EscrowStatus.REFUNDED || currentStatus == EscrowStatus.CANCELLED) {
                return@withContext Result.failure(IllegalStateException("Cannot dispute terminal escrow (status=${entity.status})"))
            }
            val updated = entity.copy(status = EscrowStatus.DISPUTED.name)
            db.escrowDao().upsert(updated)
            publishEscrowSync(escrowId, EscrowStatus.DISPUTED.name, updated)
            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = "disputed", progress = 0.5f,
                    error = "Dispute opened — awaiting arbitrator review"))
            }
            _transitions.emit(EscrowTransition(escrowId, "disputed"))
            Result.success(domain)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** F3: verify the escrow's script against the config arb key + its funding address. */
    suspend fun scriptVerdictFor(escrowId: String): EscrowScriptGate.Verdict? = withContext(Dispatchers.IO) {
        val entity = db.escrowDao().getEscrowSync(escrowId) ?: return@withContext null
        val hex = entity.redeem_script_hex ?: return@withContext null
        EscrowScriptGate.verify(
            redeemScriptHex = hex,
            fundingAddress = entity.funding_address ?: "",
            scriptType = entity.funding_script_type,
            expectedArbPubKeyHex = NeoP2PConfig.ARBITRATOR_PUBKEY,
            net = NET_PARAMS
        )
    }

    /**
     * F2 (2026-09-12): anchored verdict for the buyer's payout destination on
     * [entity]. Pure pass-through to [ResolutionGuard.verifyBuyerPayoutDestination]
     * so every build/apply path shares one rule.
     */
    private fun payoutDestinationVerdict(entity: EscrowEntity): ResolutionGuard.Verdict =
        ResolutionGuard.verifyBuyerPayoutDestination(
            buyerBtcAddress = entity.buyer_btc_address,
            buyerPubkeyHex = entity.buyer_pubkey_hex,
            buyerAddressAttestation = entity.buyer_address_attestation,
            offerId = entity.offer_id,
            redeemScriptHex = entity.redeem_script_hex
        )

    /**
     * F2 (2026-09-12): anchored verdict for the seller's recorded refund
     * destination on [entity].
     */
    private fun refundDestinationVerdict(entity: EscrowEntity): ResolutionGuard.Verdict =
        ResolutionGuard.verifySellerRefundDestination(
            sellerRefundAddress = entity.seller_refund_address,
            sellerPubkeyHex = entity.seller_pubkey_hex,
            sellerRefundAttestation = entity.seller_refund_attestation,
            escrowId = entity.escrow_id,
            redeemScriptHex = entity.redeem_script_hex
        )

    /**
     * The BUYER marks the fiat payment as sent. FUNDED → PAYMENT_PENDING,
     * records `paidAt`. Idempotent from PAYMENT_PENDING (re-send is a no-op
     * transition, keeps the original paidAt).
     *
     * Role gating is PEER-ID based (Ruling W4): in the single-key model both
     * role pubkeys are the same key, so a pubkey comparison cannot
     * distinguish buyer from seller — compare the current identity's peerId
     * to the escrow's buyer/seller peer IDs.
     *
     * The buyer's claim is NOT verified on-chain — fiat is out-of-app — so
     * the seller still verifies the funds arrived before releasing.
     */
    suspend fun markPaid(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(IllegalStateException("Escrow not found"))
            val current = EscrowStatus.valueOf(entity.status)
            // W5: SIGNED is a legitimate pre-payment state (payout signed by
            // both parties before the fiat leg); PAYMENT_PENDING for idempotent
            // re-send. Never reject a state the flow can legitimately reach.
            if (current != EscrowStatus.FUNDED && current != EscrowStatus.SIGNED &&
                current != EscrowStatus.PAYMENT_PENDING) {
                return@withContext Result.failure(
                    IllegalStateException("Payment can only be marked after the escrow is funded (current: ${entity.status})")
                )
            }
            if (roleFor(entity) != EscrowRole.BUYER) {
                return@withContext Result.failure(
                    IllegalStateException("Only the buyer can mark paid")
                )
            }
            // F3: a script whose arb slot is not the official key (or whose address
            // does not hash to the script) must never receive fiat. A NULL
            // verdict means the row carries no redeem script at all — a tampered
            // seller build could omit it to dodge the check, so fail CLOSED:
            // no verifiable script at/after funding = genuinely suspicious.
            val verdict = scriptVerdictFor(escrowId)
            if (verdict == null || !verdict.ok) {
                return@withContext Result.failure(
                    SecurityException("Escrow script failed attestation (F3) — do not pay")
                )
            }
            val now = System.currentTimeMillis()
            val updated = entity.copy(
                status = EscrowStatus.PAYMENT_PENDING.name,
                // Idempotent re-send keeps the original paidAt.
                paid_at = entity.paid_at ?: now
            )
            db.escrowDao().upsert(updated)
            publishEscrowSync(escrowId, EscrowStatus.PAYMENT_PENDING.name, updated)
            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = "payment_pending", progress = 0.4f))
            }
            _transitions.emit(EscrowTransition(escrowId, "payment_pending"))
            Log.d(TAG, "Buyer marked escrow $escrowId as paid → PAYMENT_PENDING")
            Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark escrow paid", e)
            Result.failure(e)
        }
    }

    /**
     * Buyer sends the payment receipt (reference + optional compressed image).
     * PAYMENT_PENDING → RECEIPT_SENT, records receiptReference + receiptSentAt.
     * Idempotent from RECEIPT_SENT (re-send refreshes the reference/fields).
     * Image is stored as base64 in the entity (persisted locally; the E2EE
     * copy travels via the chat payload — Task 4).
     */
    suspend fun sendReceipt(
        escrowId: String,
        reference: String,
        imageBase64: String? = null
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(IllegalStateException("Escrow not found"))
            val current = EscrowStatus.valueOf(entity.status)
            if (current != EscrowStatus.PAYMENT_PENDING && current != EscrowStatus.RECEIPT_SENT) {
                return@withContext Result.failure(
                    IllegalStateException("Cannot send receipt from ${entity.status}")
                )
            }
            if (roleFor(entity) != EscrowRole.BUYER) {
                return@withContext Result.failure(IllegalStateException("Only the buyer can send a receipt"))
            }
            val updated = entity.copy(
                status = EscrowStatus.RECEIPT_SENT.name,
                receipt_reference = reference,
                receipt_sent_at = System.currentTimeMillis()
            )
            db.escrowDao().upsert(updated)
            publishEscrowSync(escrowId, EscrowStatus.RECEIPT_SENT.name, updated)
            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = "receipt_sent", progress = 0.5f))
            }
            _transitions.emit(EscrowTransition(escrowId, "receipt_sent"))
            Log.d(TAG, "Buyer sent receipt for escrow $escrowId")
            Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "sendReceipt failed", e)
            Result.failure(e)
        }
    }

    /**
     * The SELLER confirms "IDR received" — the ONLY release gate in the
     * redesign. RECEIPT_SENT → CONFIRMING → RELEASED via the existing payout
     * broadcast machinery ([releaseFunds]). The buyer's receipt is evidence
     * for disputes; it is not what releases the escrow.
     *
     * Role gating is PEER-ID based (Ruling W4).
     */
    suspend fun confirmReceipt(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(IllegalStateException("Escrow not found"))
            if (roleFor(entity) != EscrowRole.SELLER) {
                return@withContext Result.failure(
                    IllegalStateException("Only the seller can confirm receipt of payment")
                )
            }
            val status = EscrowStatus.valueOf(entity.status)
            // SIGNED is a legitimate retry state: a kill between
            // generatePayoutTransaction (which persists SIGNED) and the
            // CONFIRMING upsert left the escrow SIGNED with the payout ready —
            // the seller must be able to retry instead of being stuck with
            // only the dispute escape hatch.
            if (status != EscrowStatus.RECEIPT_SENT && status != EscrowStatus.CONFIRMING &&
                status != EscrowStatus.SIGNED
            ) {
                return@withContext Result.failure(
                    IllegalStateException("Cannot confirm receipt from ${entity.status}")
                )
            }
            // Self-generate the unsigned payout when it doesn't exist yet
            // (P2, 2-party flow): the buyer's device may never have generated
            // one; the seller confirms and releaseFunds needs a payout tx.
            // Fallback address = escrow funding address keeps the single-key
            // demo working; real trades carry buyerBtcAddress (U1).
            // ALWAYS regenerate while no payout was broadcast yet: a stored
            // psbt may be stale (e.g. built with a sub-dust fee output before
            // the dust fix) and reusing it would fail broadcast again. Old
            // stored signatures never verify against the fresh tx, so
            // assemble2of3Spend re-signs with the local key — safe.
            if (entity.payout_tx_id == null) {
                val escrow = entity.toDomain()
                val fundingTxId = escrow.fundingTxId
                    ?: return@withContext Result.failure(IllegalStateException("No funding tx recorded"))
                // 2026-09-07: resolve escrow row → offer row and PERSIST the
                // result. The old fallback to escrow.fundingAddress paid the
                // buyer's sats back into the multisig when the MATCHED event
                // carrying the address was lost. A forbidden destination
                // (fee wallet / own multisig) fails the release instead of
                // misdirecting funds.
                val resolved = resolveBuyerPayoutAddress(
                    escrowBtcAddress = escrow.buyerBtcAddress,
                    offerBtcAddress = db.offerDao().getOfferSync(escrow.offerId)?.btc_receive_address,
                    fundingAddress = escrow.fundingAddress
                )
                if (resolved == null) {
                    return@withContext Result.failure(
                        IllegalStateException("No valid buyer payout address — refusing to release")
                    )
                }
                if (escrow.buyerBtcAddress != resolved) {
                    db.escrowDao().upsert(entity.copy(buyer_btc_address = resolved))
                }
                val gen = generatePayoutTransaction(
                    escrowId = escrow.escrowId,
                    fundingTxId = fundingTxId,
                    fundingOutputIndex = escrow.fundingVout.toInt(),
                    buyerAddressStr = resolved
                )
                if (gen.isFailure) {
                    return@withContext Result.failure(
                        gen.exceptionOrNull() ?: Exception("Could not build payout tx")
                    )
                }
            }
            // Re-fetch: generatePayoutTransaction persisted a fresh psbt_unsigned
            // and the stale local copy must not wipe it on upsert.
            val refreshed = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(IllegalStateException("Escrow not found"))
            val confirming = refreshed.copy(status = EscrowStatus.CONFIRMING.name)
            db.escrowDao().upsert(confirming)
            publishEscrowSync(escrowId, EscrowStatus.CONFIRMING.name, confirming)
            val domain = confirming.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = "confirming", progress = 0.7f))
            }
            _transitions.emit(EscrowTransition(escrowId, "confirming"))
            Log.d(TAG, "Seller confirmed IDR received for escrow $escrowId — awaiting buyer signature")
            // C1d: the buyer's payout signature must arrive before release.
            // confirmReceipt sets CONFIRMING and publishes the unsigned tx so
            // the buyer can sign; the actual broadcast happens when the buyer
            // signature is stored (storeBuyerSignature → releaseWhenReady) or
            // on the next sweep. Do NOT release here — the buyer cannot have
            // signed yet.
            // Release only when the buyer signature is already present (e.g. a
            // retry after the buyer signed on a prior attempt).
            releaseWhenReady(escrowId)
        } catch (e: Exception) {
            Log.e(TAG, "confirmReceipt failed", e)
            Result.failure(e)
        }
    }

    /**
     * The role the CURRENT identity holds on [entity], bound by PEER ID
     * (Ruling W4). Pubkeys cannot distinguish roles in the single-key model
     * (buyer_pubkey_hex == seller_pubkey_hex on one device), so compare
     * [IdentityManager.myPeerId] to the escrow's buyer/seller peer IDs.
     */
    private fun roleFor(entity: EscrowEntity): EscrowRole {
        val myPeerId = identityManager.myPeerId()
        return when {
            myPeerId == entity.buyer_peer_id -> EscrowRole.BUYER
            myPeerId == entity.seller_peer_id -> EscrowRole.SELLER
            else -> EscrowRole.UNKNOWN
        }
    }

    /** F2 (2026-09-12): the active network params, for destination validation. */
    fun networkParameters(): NetworkParameters = NET_PARAMS

    /**
     * Confirm the seller's refund destination carried by a resolution. F2
     * hardening (2026-09-12): when the local row already has a NON-BLANK
     * `seller_refund_address` (the attested destination) and the incoming
     * address differs, the row is NOT overwritten and `false` is returned —
     * the caller must refuse the resolution. When the local attested value is
     * blank, adopt the incoming address into `refund_destination` (previous
     * behavior). No-op (returns true) when the escrow is missing.
     */
    suspend fun confirmRefundDestination(escrowId: String, incoming: String): Boolean {
        if (incoming.isBlank()) return false
        return try {
            val entity = db.escrowDao().getEscrowSync(escrowId) ?: return true
            val local = entity.seller_refund_address
            when {
                local.isNullOrBlank() -> {
                    if (entity.refund_destination != incoming) {
                        db.escrowDao().upsert(entity.copy(refund_destination = incoming))
                        Log.d(TAG, "Persisted refund destination for escrow $escrowId")
                    }
                    true
                }
                local.equals(incoming, ignoreCase = true) -> true
                else -> {
                    Log.w(TAG, "Refusing refund destination mismatch for $escrowId (local=$local incoming=$incoming)")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to confirm refund destination for $escrowId: ${e.message}")
            false
        }
    }

    /**
     * Verify an arbitrator's DER + SIGHASH_ALL signature over input 0 of a
     * transaction against the configured arbitrator pubkey (2026-09-02).
     * Used by the resolution ingest path to reject forged resolutions BEFORE
     * marking the arbitrator's feed resolved. Mirrors the sanity check inside
     * [arbitratorSignTx]. Returns false on any parse/verify failure.
     */
    suspend fun verifyArbitratorSignature(
        txHex: String?,
        redeemScriptHex: String,
        arbitratorSigHex: String,
        depositSats: Long? = null,
        fundingScriptType: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (txHex.isNullOrBlank() || arbitratorSigHex.isBlank()) return@withContext false
            val tx = Transaction(NET_PARAMS, hexToBytes(txHex))
            val redeemScript = Script(hexToBytes(redeemScriptHex))
            val witness = fundingScriptType?.equals("SEGWIT", ignoreCase = true) == true
            val pub = ECKey.fromPublicOnly(xOnlyToCompressed(NeoP2PConfig.ARBITRATOR_PUBKEY))
            val parsed = TransactionSignature.decodeFromBitcoin(hexToBytes(arbitratorSigHex), true, true)
            val hash = if (witness) {
                val deposit = depositSats ?: return@withContext false
                tx.hashForWitnessSignature(0, redeemScript, Coin.valueOf(deposit), Transaction.SigHash.ALL, false)
            } else {
                tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            }
            pub.verify(hash, parsed)
        } catch (e: Exception) {
            Log.w(TAG, "Arbitrator signature verification failed: ${e.message}")
            false
        }
    }

    /**
     * The ARBITRATOR signs a transaction they do NOT hold locally (remote
     * arbitration): given the unsigned tx hex carried in the dispute event and
     * the escrow's redeem script, produce the DER + SIGHASH_ALL signature.
     * Returns failure if the key is not the configured arbitrator key.
     */
    suspend fun arbitratorSignTx(
        unsignedTxHex: String,
        redeemScriptHex: String,
        arbitratorPrivKeyHex: String,
        depositSats: Long? = null,
        fundingScriptType: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val key = ECKey.fromPrivate(hexToBytes(arbitratorPrivKeyHex))
            if (NeoP2PConfig.ARBITRATOR_PUBKEY != key.publicKeyAsHex &&
                NeoP2PConfig.ARBITRATOR_PUBKEY != xOnlyOf(key.publicKeyAsHex)
            ) {
                return@withContext Result.failure(
                    SecurityException("Provided key is not the arbitrator key")
                )
            }
            val tx = Transaction(NET_PARAMS, hexToBytes(unsignedTxHex))
            val redeemScript = Script(hexToBytes(redeemScriptHex))
            // P2WSH disputes sign with the BIP-143 witness sighash, which
            // commits the input value (the escrow's deposit). Legacy disputes
            // keep the legacy sighash. Old dispute events (pre-deposit_sats)
            // are always treated as legacy — P2WSH events always carry the
            // deposit (published by the same app version that created them).
            val witness = fundingScriptType?.equals("SEGWIT", ignoreCase = true) == true
            val sig = if (witness) {
                val deposit = depositSats
                    ?: return@withContext Result.failure(
                        Exception("Missing deposit_sats for SegWit dispute")
                    )
                val txSig = tx.calculateWitnessSignature(
                    0, key, redeemScript,
                    Coin.valueOf(deposit),
                    Transaction.SigHash.ALL, false
                )
                txSig.encodeToBitcoin()
            } else {
                val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
                val legacySig = key.sign(hash)
                legacySig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
            }
            val sigHex = sig.joinToString("") { "%02x".format(it) }
            // Sanity-check: verify against the actual signing key (parity-normalized to even
            // via IdentityManager.arbitratorPrivEven, so xOnly 02 == true). Using the
            // signing key's compressed pubkey guarantees the check passes if the
            // sighash is correct (deposit/witness), independent of config parity.
            val pub = ECKey.fromPublicOnly(hexToBytes(key.publicKeyAsHex))
            val parsed = TransactionSignature.decodeFromBitcoin(hexToBytes(sigHex), true, true)
            val checkHash = if (witness) {
                val deposit = depositSats ?: 0L
                tx.hashForWitnessSignature(0, redeemScript, Coin.valueOf(deposit), Transaction.SigHash.ALL, false)
            } else {
                tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            }
            if (!pub.verify(checkHash, parsed)) {
                return@withContext Result.failure(Exception("Arbitrator signature failed verification"))
            }
            Result.success(sigHex)
        } catch (e: Exception) {
            Log.e(TAG, "Arbitrator signing failed", e)
            Result.failure(e)
        }
    }

    /**
     * Apply an arbitrator's decision received from the relay (LXMF resolution message) to a
     * locally-held escrow. Idempotent: stores the signature + decision and
     * moves DISPUTED/RESOLVING → RELEASED/REFUNDED, but never downgrades a
     * terminal state and never overwrites an existing decision.
     */
    suspend fun storeArbitrationDecision(
        escrowId: String,
        decision: ResolutionDecision,
        arbitratorSigHex: String,
        notes: String?,
        signedTxHex: String? = null
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            // Fork guard: applying a resolution assembles a 2-of-3 spend with
            // the arbitrator signature — a swapped arbitrator key in a forked
            // build must not be able to broadcast it.
            if (!NeoP2PConfig.verifyArbitratorIntegrity()) {
                return@withContext Result.failure(
                    IllegalStateException("Arbitrator key signature invalid — resolution disabled")
                )
            }
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))
            val current = EscrowStatus.valueOf(entity.status)
            if (current != EscrowStatus.DISPUTED && current != EscrowStatus.RESOLVING) {
                return@withContext Result.failure(
                    Exception("Escrow is not disputed; cannot apply a resolution")
                )
            }
            if (entity.arbitrator_decision != null) {
                // Already resolved — keep the first decision (idempotent).
                return@withContext Result.success(entity.toDomain())
            }

            val redeemScriptHex = entity.redeem_script_hex
                ?: return@withContext Result.failure(Exception("No redeem script stored"))
            val redeemScript = Script(hexToBytes(redeemScriptHex))

            // Build the final tx matching the decision: payout (to buyer) or
            // refund (to seller). When the arbitrator shipped the exact signed
            // tx (LXMF resolution message signed_tx_hex), broadcast THAT — a locally
            // rebuilt refund would carry a different fee rate/output and the
            // arbitrator's signature would not verify. Fall back to the local
            // build only for legacy resolutions without the field.
            val tx = when (decision) {
                ResolutionDecision.RELEASE_TO_BUYER -> {
                    if (!signedTxHex.isNullOrBlank()) {
                        Transaction(NET_PARAMS, hexToBytes(signedTxHex))
                    } else {
                        val txHex = entity.psbt_unsigned?.toString(Charsets.UTF_8)
                            ?: throw IllegalStateException("No unsigned payout tx stored")
                        Transaction(NET_PARAMS, hexToBytes(txHex))
                    }
                }
                ResolutionDecision.REFUND_TO_SELLER -> {
                    if (!signedTxHex.isNullOrBlank()) {
                        Transaction(NET_PARAMS, hexToBytes(signedTxHex))
                    } else {
                        // Refund to the SELLER's address recorded on the escrow by
                        // the arbitrator's resolution (LXMF resolution message) — NEVER the
                        // local device's address. Pre-v20 the refund paid whoever
                        // applied the decision (an arbitrator-applied refund paid
                        // the arbitrator's own wallet). Fall back to the local
                        // address only when no destination was recorded (legacy
                        // rows / direct seller-initiated refunds).
                        buildRefundTx(
                            entity,
                            entity.refund_destination
                                ?: identityManager.getBitcoinAddress(BitcoinAddressType.LEGACY)
                        ).tx
                    }
                }
            }

            // F2: verify destinations against the LOCAL attested values (role
            // key + script anchor) before we sign anything. A hostile
            // resolution (or hostile opener-supplied tx) must never be
            // broadcast by this device.
            val gate = when (decision) {
                ResolutionDecision.REFUND_TO_SELLER -> {
                    val anchored = refundDestinationVerdict(entity)
                    if (!anchored.ok) {
                        anchored
                    } else {
                        ResolutionGuard.validateRefund(tx, NET_PARAMS, ResolutionGuard.RefundExpectation(
                            entity.seller_refund_address!!, entity.funded_amount_sats ?: entity.deposit_amount_sats, maxOf((entity.network_fee_sats) * 3, 5_000L)
                        ))
                    }
                }
                ResolutionDecision.RELEASE_TO_BUYER -> {
                    val anchored = payoutDestinationVerdict(entity)
                    if (!anchored.ok) {
                        anchored
                    } else {
                        ResolutionGuard.validateRelease(tx, NET_PARAMS, ResolutionGuard.ReleaseExpectation(
                            entity.buyer_btc_address!!, NeoP2PConfig.FEE_WALLET_ADDRESS, entity.seller_refund_address, entity.trade_amount_sats
                        ))
                    }
                }
            }
            if (!gate.ok) {
                return@withContext Result.failure(SecurityException("Resolution blocked: ${gate.reason} — funds NOT moved"))
            }

            // Assemble the 2-of-3 spend: the arbitrator signature from the
            // relay plus the local key filling the buyer/seller role slots
            // (single-key model), in redeem-script pubkey order. P2WSH escrows
            // put the signatures in the witness instead of the scriptSig.
            val spend = assemble2of3Spend(tx, redeemScript, entity, arbitratorSigHex)
                ?: return@withContext Result.failure(
                    Exception("Cannot assemble 2-of-3 for this resolution")
                )
            attachSpend(tx, spend)

            val finalHex = tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }
            // Audit P2-1 (2026-09-12): the escrow row stores this txid and the
            // counterparty mirrors it — bind it to the tx we built instead of
            // trusting the explorer's echo.
            val broadcastResult = chainMonitor.broadcastTx(finalHex, tx.getHashAsString())
            if (broadcastResult.isFailure) {
                return@withContext Result.failure(
                    Exception("Broadcast failed: ${broadcastResult.exceptionOrNull()?.message}")
                )
            }
            val payoutTxId = broadcastResult.getOrThrow()

            val newStatus = when (decision) {
                ResolutionDecision.RELEASE_TO_BUYER -> EscrowStatus.RELEASED
                ResolutionDecision.REFUND_TO_SELLER -> EscrowStatus.REFUNDED
            }
            val updated = entity.copy(
                psbt_unsigned = finalHex.encodeToByteArray(),
                payout_tx_id = payoutTxId,
                arbitrator_signature = hexToBytes(arbitratorSigHex),
                arbitrator_decision = decision.name,
                arbitrator_notes = notes,
                status = newStatus.name,
                released_at = System.currentTimeMillis()
            )
            db.escrowDao().upsert(updated)
            // Sync the terminal outcome to the counterparty (LXMF escrow_status):
            // the buyer's mirrored row must leave DISPUTED, not stay "In
            // dispute" forever. The router accepts arbitration outcomes on
            // DISPUTED rows (RELEASED/REFUNDED only). Best-effort: the
            // resolution event (LXMF resolution message) is the primary channel; this is
            // the converge-heal for rows that missed it.
            runCatching { publishEscrowSync(escrowId, newStatus.name, updated) }
            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = newStatus.name.lowercase(), progress = 1.0f))
            }
            _transitions.emit(EscrowTransition(escrowId, newStatus.name.lowercase()))
            Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store arbitration decision", e)
            Result.failure(e)
        }
    }

    /**
     * A user-facing refund estimate (no transaction is built or signed).
     */
    data class RefundEstimateInfo(
        val feeRatePerVb: Long,
        val networkFeeSats: Long,
        val refundAmountSats: Long,
        val depositAmountSats: Long
    )

    /** The plan produced by building an unsigned refund transaction. */
    data class RefundPlan(
        val refundAmountSats: Long,
        val networkFeeSats: Long,
        val feeRatePerVb: Long,
        val unsignedTxHex: String,
        val destinationAddress: String
    )

    private data class RefundBuild(
        val tx: Transaction,
        val refundAmountSats: Long,
        val networkFeeSats: Long,
        val feeRatePerVb: Long
    )

    /**
     * Compute a refund estimate without building/signing anything. Mirrors the
     * fee math of [buildRefundTx].
     */
    suspend fun getRefundEstimate(escrowId: String): Result<RefundEstimateInfo> =
        withContext(Dispatchers.IO) {
            try {
                val entity = db.escrowDao().getEscrowSync(escrowId)
                    ?: return@withContext Result.failure(Exception("Escrow not found"))
                val escrow = entity.toDomain()
                val feeRate = chainMonitor.estimateFees().fastest
                // Full refund tx vsize + floor, identical to buildRefundTx, so
                // the displayed refund amount equals the broadcast refund.
                val networkFeeSats = refundNetworkFeeSats(feeRate, escrowScriptType(entity))
                // Refund the ACTUAL on-chain funding value (2026-09-04): the
                // excess over the deposit must come back to the seller.
                val inputValue = escrow.fundedAmountSats ?: escrow.depositAmountSats
                val refundAmount = inputValue - networkFeeSats
                if (refundAmount <= 0) {
                    return@withContext Result.failure(
                        Exception("Network fee exceeds deposit; cannot refund")
                    )
                }
                Result.success(
                    RefundEstimateInfo(
                        feeRatePerVb = feeRate,
                        networkFeeSats = networkFeeSats,
                        refundAmountSats = refundAmount,
                        depositAmountSats = escrow.depositAmountSats
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to estimate refund", e)
                Result.failure(e)
            }
        }

    /**
     * Build an unsigned refund transaction that spends the escrow's funding
     * output back to a user-provided destination BTC address, returning the
     * full deposit minus the estimated network fee. Stores the unsigned hex in
     * `psbt_unsigned` (reusing the existing unsigned-tx storage). The status
     * guard lives in [cancelEscrowRefund].
     */
    suspend fun buildRefundTransaction(
        escrowId: String,
        refundAddressStr: String
    ): Result<RefundPlan> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val currentStatus = EscrowStatus.valueOf(entity.status)
            if (currentStatus != EscrowStatus.FUNDING && currentStatus != EscrowStatus.FUNDED &&
                currentStatus != EscrowStatus.DISPUTED
            ) {
                return@withContext Result.failure(
                    Exception("Refund only allowed while the escrow is FUNDING, FUNDED or DISPUTED")
                )
            }

            val build = buildRefundTx(entity, refundAddressStr)
            val txHex = build.tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }

            db.escrowDao().upsert(entity.copy(psbt_unsigned = txHex.encodeToByteArray()))

            Result.success(
                RefundPlan(
                    refundAmountSats = build.refundAmountSats,
                    networkFeeSats = build.networkFeeSats,
                    feeRatePerVb = build.feeRatePerVb,
                    unsignedTxHex = txHex,
                    destinationAddress = refundAddressStr
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build refund tx", e)
            Result.failure(e)
        }
    }

    /**
     * Build an unsigned refund tx for the DISPUTE event WITHOUT persisting it.
     * The arbitrator signs the refund tx shipped in LXMF dispute message (refund_tx_hex),
     * but when a payout already exists the local `psbt_unsigned` must NOT be
     * clobbered — the resolution may still be RELEASE_TO_BUYER and
     * `storeArbitrationDecision` needs the payout tx. Mirrors
     * [buildRefundTransaction]'s math (deposit − network fee → seller's
     * refund address). Returns null when the refund cannot be built (e.g. no
     * funding tx recorded yet).
     */
    suspend fun buildDisputeRefundTxHex(escrowId: String): String? = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId) ?: return@withContext null
            // F2 (2026-09-12): the refund tx handed to the arbitrator must pay
            // ONLY the seller's attested, script-anchored refund address. A
            // forged escrow_status that overwrote seller_refund_address must
            // never make us ship a refund tx to the attacker. Fail closed (no
            // refund tx) for legacy rows with no attestation.
            val verdict = refundDestinationVerdict(entity)
            if (!verdict.ok) {
                Log.w(TAG, "Skipping dispute refund tx for $escrowId: ${verdict.reason}")
                return@withContext null
            }
            val destination = entity.seller_refund_address!!
            val build = buildRefundTx(entity, destination)
            build.tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not build dispute refund tx for $escrowId: ${e.message}")
            null
        }
    }

    /**
     * Cancel the escrow and refund the seller's deposit back to
     * [destinationAddressStr], spending from the 2-of-3 P2SH multisig.
     *
     * A 2-of-3 refund normally needs 2 signatures. In the CURRENT design both
     * the buyer and seller escrow keys are pinned to the same current-user key,
     * so the same key fills both slots. Each signature is verified against the
     * stored role pubkey before broadcast (mirrors the P0-1 guarantee in
     * [releaseFunds]).
     */
    suspend fun cancelEscrowRefund(
        escrowId: String,
        destinationAddressStr: String,
        privKeyHex: String
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            var entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val currentStatus = EscrowStatus.valueOf(entity.status)
            if (currentStatus != EscrowStatus.FUNDING && currentStatus != EscrowStatus.FUNDED &&
                currentStatus != EscrowStatus.DISPUTED && currentStatus != EscrowStatus.CONFIRMING
            ) {
                return@withContext Result.failure(
                    Exception("Cannot cancel escrow: already signed/released/refunded")
                )
            }

            // Cancel & Refund is the SELLER's escape hatch (the seller
            // deposited the BTC). Gate by PEER ID (Ruling W4) so a buyer
            // cannot refund the seller's deposit — the same rule the UI
            // enforces. Signing-key checks below are a separate concern.
            if (roleFor(entity) != EscrowRole.SELLER) {
                return@withContext Result.failure(
                    SecurityException("Only the seller can cancel and refund the escrow")
                )
            }

            // Never-funded escrow → local-only cancel (2026-09-07). The old
            // code always built a refund tx, which threw "No funding
            // transaction recorded" for a FUNDING escrow with no deposit —
            // the UI button was enabled but the service could not honor it.
            // Mirrors the sweep's auto-cancel branch (expireStaleEscrows).
            if (!EscrowService.cancelRequiresOnChainRefund(
                    currentStatus, entity.funding_tx_id, entity.funded_amount_sats
                )
            ) {
                // Safety: a manual deposit may exist on-chain without a
                // bound txid (user sent BTC but never entered it). Never
                // cancel an escrow whose address holds funds — recover
                // first, exactly like the sweep does before auto-cancelling.
                val recovered = recoverFundingTxId(escrowId)
                if (recovered == null) {
                    return@withContext cancelLocally(entity)
                }
                // A fresh deposit was found and bound — re-read the row
                // (recoverFundingTxId upserted txid/vout) and fall through
                // to the on-chain refund below.
                entity = db.escrowDao().getEscrowSync(escrowId) ?: entity
            }

            val key = ECKey.fromPrivate(hexToBytes(privKeyHex))
            val buyerExpected = entity.buyer_pubkey_hex
            val sellerExpected = entity.seller_pubkey_hex
            if (buyerExpected == null || sellerExpected == null) {
                return@withContext Result.failure(Exception("Escrow missing role pubkeys"))
            }
            // Both escrow roles are pinned to the current user's key in this
            // design, so that one key must be authorized for BOTH slots.
            if (!pubkey(key, buyerExpected) || !pubkey(key, sellerExpected)) {
                return@withContext Result.failure(
                    SecurityException("Signing key is not authorized for both escrow roles")
                )
            }

            // User-initiated refund: allow FUNDING or FUNDED.
            refundInternal(entity, destinationAddressStr, privKeyHex, auto = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel/refund escrow", e)
            Result.failure(e)
        }
    }

    /**
     * Local-only cancel for a FUNDING escrow with no deposit (2026-09-07).
     * Nothing is on-chain to spend — the cancel is a state change plus the
     * same side effects as the sweep's auto-cancel branch: mark the linked
     * offer CANCELLED, sync both terminal states over LXMF, emit the
     * transition. The caller has already run recoverFundingTxId, so the
     * address is known to hold no fresh deposit.
     */
    private suspend fun cancelLocally(entity: EscrowEntity): Result<Escrow> {
        val escrowId = entity.escrow_id
        try {
            val updated = entity.copy(status = EscrowStatus.CANCELLED.name)
            db.escrowDao().upsert(updated)
            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = "cancelled", progress = 0f))
            }
            _transitions.emit(EscrowTransition(escrowId, "cancelled"))
            // The counterparty (buyer) only learns via LXMF escrow_status —
            // without this the buyer's row stays FUNDING with an expired
            // countdown.
            runCatching { publishEscrowSync(escrowId, EscrowStatus.CANCELLED.name, updated) }
            // The trade is dead — mark the linked offer CANCELLED so it
            // leaves the marketplace feed (same class of bug as the sweep:
            // offers stayed ESCROWED forever).
            runCatching {
                db.offerDao().getOfferSync(entity.offer_id)?.let { offer ->
                    if (offer.status != com.neop2p.domain.model.OfferStatus.CANCELLED.name) {
                        db.offerDao().updateStatus(entity.offer_id, com.neop2p.domain.model.OfferStatus.CANCELLED.name)
                        publishOfferStatusDual(
                            offerId = entity.offer_id,
                            status = com.neop2p.domain.model.OfferStatus.CANCELLED.name,
                            matchedPeerId = offer.matched_peer_id,
                            authorPeerId = identityManager.myPeerId()
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Failed to mark offer CANCELLED after local cancel: ${it.message}") }
            Log.d(TAG, "Escrow $escrowId cancelled locally (no deposit to refund)")
            return Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel escrow $escrowId locally", e)
            return Result.failure(e)
        }
    }

    /**
     * Shared build+sign+broadcast pipeline for an escrow refund, used by both
     * the user-initiated [cancelEscrowRefund] and the 2-hour auto-refund
     * ([expireStaleEscrows]).
     *
     * The status guard is enforced by the caller: [auto] refunds are only
     * invoked for FUNDED escrows, and [cancelEscrowRefund] allows FUNDING/FUNDED.
     * The refund is persisted as REFUNDED BEFORE broadcast so a crash mid-way
     * cannot cause the same escrow to be auto-refunded twice on the next scan
     * (REFUNDED is terminal → expireStaleEscrows skips it).
     */
    private suspend fun refundInternal(
        entity: EscrowEntity,
        destinationAddressStr: String,
        privKeyHex: String,
        auto: Boolean
    ): Result<Escrow> {
        val escrowId = entity.escrow_id
        try {
            val key = ECKey.fromPrivate(hexToBytes(privKeyHex))
            val buyerExpected = entity.buyer_pubkey_hex
            val sellerExpected = entity.seller_pubkey_hex
            if (buyerExpected == null || sellerExpected == null) {
                return Result.failure(Exception("Escrow missing role pubkeys"))
            }
            // Both escrow roles are pinned to the current user's key in this
            // design, so that one key must be authorized for BOTH slots.
            if (!pubkey(key, buyerExpected) || !pubkey(key, sellerExpected)) {
                return Result.failure(
                    SecurityException("Signing key is not authorized for both escrow roles")
                )
            }

            val redeemScriptHex = entity.redeem_script_hex
                ?: return Result.failure(Exception("No redeem script stored"))
            val redeemScript = Script(hexToBytes(redeemScriptHex))

            val build = buildRefundTx(entity, destinationAddressStr)
            val tx = build.tx

            // Sign the same input for both buyer and seller slots with this key.
            // BIP-143 commits the INPUT VALUE — the actual on-chain funding
            // output (2026-09-04), which may exceed the deposit on overpayment.
            val depositSats = entity.funded_amount_sats ?: entity.deposit_amount_sats
            val witness = escrowScriptType(entity) == BitcoinAddressType.SEGWIT
            val buyerSig = signRaw(tx, redeemScript, key, depositSats, witness)
            val sellerSig = signRaw(tx, redeemScript, key, depositSats, witness)

            // Verify each signature against the role pubkey actually stored.
            val valid = listOf(
                buyerExpected to buyerSig,
                sellerExpected to sellerSig
            ).filter { (pub, sig) -> verifySignature(tx, redeemScript, pub, sig, depositSats, witness) }

            if (valid.size < 2) {
                return Result.failure(Exception("Fewer than 2 valid signatures for refund"))
            }

            val sigs = valid.take(2).map { it.second }
            when (escrowScriptType(entity)) {
                BitcoinAddressType.LEGACY -> {
                    val scriptSig = ScriptBuilder.createMultiSigInputScriptBytes(sigs, redeemScript.getProgram())
                    tx.getInput(0).setScriptSig(scriptSig)
                }
                BitcoinAddressType.SEGWIT -> {
                    val sigObjs = sigs.map { TransactionSignature.decodeFromBitcoin(it, true, true) }.toTypedArray()
                    tx.getInput(0).setWitness(TransactionWitness.redeemP2WSH(redeemScript, *sigObjs))
                }
            }

            val finalHex = tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }

            // Audit P2-1 (2026-09-12): the escrow row stores this txid and the
            // counterparty mirrors it — bind it to the tx we built instead of
            // trusting the explorer's echo.
            val broadcast = chainMonitor.broadcastTx(finalHex, tx.getHashAsString())
            if (broadcast.isFailure) {
                return Result.failure(
                    Exception("Broadcast failed: ${broadcast.exceptionOrNull()?.message}")
                )
            }

            val refundTxId = broadcast.getOrThrow()
            val updated = entity.copy(
                psbt_unsigned = finalHex.encodeToByteArray(),
                payout_tx_id = refundTxId,
                buyer_signature = buyerSig,
                seller_signature = sellerSig,
                status = EscrowStatus.REFUNDED.name,
                released_at = System.currentTimeMillis()
            )
            db.escrowDao().upsert(updated)

            // The trade is dead — mark the linked offer CANCELLED so it
            // leaves the marketplace feed (same class of bug as release:
            // offers stayed ESCROWED forever). The LXMF offer_status event syncs
            // the terminal status to the counterparty's row.
            runCatching {
                db.offerDao().getOfferSync(entity.offer_id)?.let { offer ->
                    if (offer.status != com.neop2p.domain.model.OfferStatus.CANCELLED.name) {
                        db.offerDao().updateStatus(entity.offer_id, com.neop2p.domain.model.OfferStatus.CANCELLED.name)
                        publishOfferStatusDual(
                            offerId = entity.offer_id,
                            status = com.neop2p.domain.model.OfferStatus.CANCELLED.name,
                            matchedPeerId = offer.matched_peer_id,
                            authorPeerId = identityManager.myPeerId()
                        )
                        Log.d(TAG, "Offer ${entity.offer_id} marked CANCELLED after refund")
                    }
                }
            }.onFailure { Log.w(TAG, "Failed to mark offer CANCELLED: ${it.message}") }

            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = "refunded", progress = 0f))
            }
            _transitions.emit(EscrowTransition(escrowId, "refunded"))
            // Sync the terminal state to the counterparty (auto-refund and
            // manual cancel both land here) — the buyer must not stay on
            // FUNDED/FUNDING with a stale countdown.
            runCatching { publishEscrowSync(escrowId, EscrowStatus.REFUNDED.name, updated) }

            Log.d(TAG, "Escrow ${if (auto) "auto-" else ""}refunded: $escrowId tx=$refundTxId")
            return Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refund escrow $escrowId", e)
            return Result.failure(e)
        }
    }

    /**
     * Sign input 0 of [tx] against [redeemScript]. Legacy escrows use the
     * legacy sighash (DER + SIGHASH_ALL); SegWit escrows use the BIP-143
     * witness sighash, which commits [depositSats] (the input value, stored
     * on the escrow at creation).
     */
    private fun signRaw(
        tx: Transaction,
        redeemScript: Script,
        key: ECKey,
        depositSats: Long,
        witness: Boolean
    ): ByteArray {
        if (witness) {
            val txSig = tx.calculateWitnessSignature(
                0, key, redeemScript,
                Coin.valueOf(depositSats),
                Transaction.SigHash.ALL, false
            )
            return txSig.encodeToBitcoin()
        }
        val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
        val sig = key.sign(hash)
        return sig.encodeToDER() + byteArrayOf(Transaction.SigHash.ALL.value.toByte())
    }

    private suspend fun buildRefundTx(
        entity: EscrowEntity,
        destinationAddressStr: String
    ): RefundBuild {
        val escrow = entity.toDomain()
        val redeemScriptHex = escrow.redeemScriptHex
            ?: throw IllegalStateException("No redeem script stored")
        val fundingTxId = escrow.fundingTxId
            ?: throw IllegalStateException("No funding transaction recorded")
        if (fundingTxId.isBlank()) {
            throw IllegalStateException("No funding transaction recorded")
        }

        val feeRate = chainMonitor.estimateFees().fastest
        val networkFeeSats = refundNetworkFeeSats(feeRate, escrowScriptType(entity))
        // Refund the ACTUAL on-chain funding value (2026-09-04): equals the
        // deposit for exact deposits, HIGHER when the seller overpaid — the
        // excess must come back to the seller, never stay stranded in the
        // multisig. The input value is the real funding output (SegWit BIP-143
        // commits it), so the refund must spend it.
        val inputValue = escrow.fundedAmountSats ?: escrow.depositAmountSats
        val refundAmount = inputValue - networkFeeSats
        if (refundAmount <= 0) {
            throw IllegalStateException("Network fee exceeds deposit; cannot refund")
        }

        val tx = Transaction(NET_PARAMS)
        // Spend the REAL funding output recorded at verification (Task 3) —
        // never assume vout 0 (change outputs break that assumption).
        tx.addInput(Sha256Hash.wrap(fundingTxId), escrow.fundingVout, ScriptBuilder.createEmpty())
        val destination = Address.fromString(NET_PARAMS, destinationAddressStr)
        tx.addOutput(Coin.valueOf(refundAmount), destination)

        return RefundBuild(tx, refundAmount, networkFeeSats, feeRate)
    }

    /** Compare a key's pubkey (compressed hex or x-only hex) against a stored hex. */
    private fun pubkey(key: ECKey, expectedHex: String): Boolean {
        val compressed = key.publicKeyAsHex
        return compressed.equals(expectedHex, ignoreCase = true) ||
            xOnlyOf(compressed).equals(expectedHex, ignoreCase = true)
    }

    /**
     * Convert a 32-byte x-only secp256k1 pubkey into a 33-byte compressed key
     * (0x02 prefix + x). Bitcoinj's ECKey.fromPublicOnly() requires a compressed
     * key; the arbitrator pubkey in NeoP2PConfig is stored x-only.
     */
    private fun xOnlyToCompressed(xOnlyHex: String): ByteArray {
        val xOnly = hexToBytes(xOnlyHex)
        val pub = if (xOnly.size == 32) xOnly else {
            // Already compressed / uncompressed: pass through as-is.
            return xOnly
        }
        return byteArrayOf(0x02) + pub
    }

    /** x-only form: drop the 0x02/0x03 prefix byte of a compressed pubkey. */
    private fun xOnlyOf(compressedPubkeyHex: String): String {
        val bytes = hexToBytes(compressedPubkeyHex)
        val pub = if (bytes.size == 33) bytes.copyOfRange(1, 33) else bytes
        return pub.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    /** Lexicographic (unsigned byte) comparison — mirrors ECKey.PUBKEY_COMPARATOR. */
    private fun ByteArray.compareBytes(other: ByteArray): Int {
        val n = minOf(size, other.size)
        for (i in 0 until n) {
            val a = this[i].toInt() and 0xff
            val b = other[i].toInt() and 0xff
            if (a != b) return a - b
        }
        return size - other.size
    }
}
