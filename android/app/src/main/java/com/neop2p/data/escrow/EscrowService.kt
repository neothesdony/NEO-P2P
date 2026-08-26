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
 * The 0.3% fee is built into the pre-signed payout transaction.
 *
 * Flow:
 *   1. createEscrow() → generates 2-of-3 P2SH address, stores in Room
 *   2. Seller transfers BTC to the P2SH address (out-of-app)
 *   3. onEscrowFunded() → verifies on-chain via Mempool API
 *   4. generatePayoutTransaction() → creates unsigned raw tx
 *   5. signPayoutAsBuyer() → buyer signs with ECKey (role-validated)
 *   6. signPayoutAsSeller() → seller signs with ECKey (role-validated)
 *   7. releaseFunds() → verifies 2-of-3 signatures then broadcasts
 *   8. disputeEscrow() / resolveDispute() → arbitrator path
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
    private val nostrClient: com.neop2p.data.p2p.NostrClient
) {
    companion object {
        private const val TAG = "EscrowService"
        /**
         * Approximate vsize (vbytes) of a P2SH 2-of-3 multisig spend used to
         * estimate the refund network fee. A 2-of-3 scriptSig carries 2 DER
         * signatures + the redeem script, so ~220 vbytes is a safe estimate.
         */
        private const val REFUND_APPROX_VSIZE = 220L
        /**
         * Approximate vsize (vbytes) of a P2SH 2-of-3 payout spend, used to
         * estimate the network (miner) fee at escrow creation and to validate
         * the payout miner-fee budget. Mirrors [REFUND_APPROX_VSIZE] (~220 vB).
         */
        const val PAYOUT_APPROX_VSIZE = 220L
        /**
         * Timeout for an escrow that has NOT yet been funded. FUNDING escrows
         * older than this are auto-CANCELLED (no funds were deposited, so no
         * on-chain move is needed). 45 minutes covers wallet transfer + 1 block
         * confirmation without risking a false auto-cancel.
         */
        const val ESCROW_FUNDING_TIMEOUT_MS = 45 * 60 * 1000L  // 45 min (was 30)
        /** First warning (notification) when a FUNDING escrow is this old. */
        const val FUNDING_WARNING_MS = 30 * 60 * 1000L

        /**
         * Timeout for a FUNDED escrow whose trade never proceeds. Once the
         * deposit is confirmed, give the trade a generous window to complete
         * before auto-refunding back to the seller/depositor (so a funded
         * trade isn't yanked back if the buyer is slow).
         */
        const val ESCROW_FUNDED_REFUND_TIMEOUT_MS = 12 * 60 * 60 * 1000L  // 12 h (was 6)
        /** Extra window after the funded-refund timeout before auto-refund; reminders at 24h/48h. */
        const val FUNDED_REFUND_GRACE_MS = 48 * 60 * 60 * 1000L  // 48 h total grace

        /**
         * Payment window: how long the seller has to release (or dispute) after
         * the buyer marks the fiat payment as sent (legacy status PAID, now the
         * guided-flow state CONFIRMING). If the window expires, the escrow
         * auto-transitions to DISPUTED — never silently auto-refunded, because
         * the buyer may have actually paid.
         */
        const val PAYMENT_WINDOW_MS = 24 * 60 * 60 * 1000L  // 24 h (was 2 h)
        /** Extra window after the payment window before auto-DISPUTED. */
        const val PAYMENT_GRACE_MS = 12 * 60 * 60 * 1000L  // 12 h grace
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
         * Release gate (P2): funds may only be released once the buyer's
         * receipt exists (RECEIPT_SENT) and the seller confirms IDR received
         * (CONFIRMING). FUNDED/SIGNED/PAYMENT_PENDING must never release —
         * the fiat-confirm step is the ONLY release gate. UI must mirror this.
         */
        fun canReleaseFromStatus(status: String): Boolean =
            status == EscrowStatus.RECEIPT_SENT.name || status == EscrowStatus.CONFIRMING.name
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
     * Emit a transition for a remote (kind:33337) status applied by
     * EscrowRouter, so the orchestrator's notification collector fires for
     * counterparty-driven changes too.
     */
    suspend fun emitRemoteTransition(escrowId: String, status: String) {
        _transitions.emit(EscrowTransition(escrowId, status.lowercase()))
    }

    /**
     * Mutable escrow fields carried by kind:33337 events so the counterparty
     * can reconstruct/advance its local row (2-party sync, Task 8/9).
     */
    private fun escrowStatusFields(entity: EscrowEntity): Map<String, String> = buildMap {
        put("offer_id", entity.offer_id)
        put("buyer_peer_id", entity.buyer_peer_id)
        put("seller_peer_id", entity.seller_peer_id)
        put("funding_address", entity.funding_address ?: "")
        put("funding_script_type", entity.funding_script_type)
        put("buyer_btc_address", entity.buyer_btc_address ?: "")
        put("buyer_pubkey_hex", entity.buyer_pubkey_hex ?: "")
        put("seller_pubkey_hex", entity.seller_pubkey_hex ?: "")
        put("deposit_sats", entity.deposit_amount_sats.toString())
        put("trade_sats", entity.trade_amount_sats.toString())
        entity.funding_tx_id?.let { put("funding_tx_id", it) }
        put("funding_vout", entity.funding_vout.toString())
        entity.funded_at?.let { put("funded_at", it.toString()) }
        entity.paid_at?.let { put("paid_at", it.toString()) }
        entity.receipt_reference?.let { put("receipt_reference", it) }
        entity.receipt_sent_at?.let { put("receipt_sent_at", it.toString()) }
    }

    /** Best-effort kind:33337 publish; never blocks the local transition. */
    private suspend fun publishEscrowSync(escrowId: String, status: String, entity: EscrowEntity) {
        runCatching {
            nostrClient.publishEscrowStatus(escrowId, status, escrowStatusFields(entity))
        }.onFailure {
            Log.w(TAG, "Failed to publish escrow sync event: ${it.message}")
        }
    }

    private val _escrowStates = MutableStateFlow<Map<String, EscrowState>>(emptyMap())
    // Hoisted once for the singleton lifetime; per-call scopes would leak.
    private val stateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    fun getEscrowState(escrowId: String): StateFlow<EscrowState> = _escrowStates
        .map { it[escrowId] ?: EscrowState() }
        .stateIn(stateScope, SharingStarted.Eagerly, EscrowState())

    /** Load a single escrow by ID (null if not found). */
    suspend fun getEscrow(escrowId: String): Escrow? =
        db.escrowDao().getEscrowSync(escrowId)?.toDomain()

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
        if (!entity.funding_tx_id.isNullOrBlank()) return entity.funding_tx_id
        if (entity.status != EscrowStatus.FUNDING.name) return null
        val address = entity.funding_address ?: return null
        return try {
            val txs = chainMonitor.getAddressTxs(address, limit = 25).getOrNull() ?: return null
            for (tx in txs) {
                // Only a deposit to the escrow address counts.
                val outputs = chainMonitor.getTxOutputs(tx.txid).getOrNull() ?: continue
                val vout = findFundingOutput(outputs, address, entity.deposit_amount_sats)
                if (vout != null) {
                    val updated = entity.copy(funding_tx_id = tx.txid, funding_vout = vout.toLong())
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun initialize() {
        try {
            val entities = db.escrowDao().getAllEscrowsSync()
            // Fix 2: expire any stale (abandoned) escrows before mapping state,
            // so the UI never shows a FUNDED escrow that has since auto-refunded.
            expireStaleEscrows()
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
            for (entity in entities) {
                val status = EscrowStatus.valueOf(entity.status)
                when (status) {
                    EscrowStatus.FUNDING -> {
                        // Nothing deposited yet → just cancel, no on-chain move.
                        // SAFETY: before cancelling a stale FUNDING escrow, check
                        // whether the funding address actually received a deposit
                        // (broadcast may have succeeded but verification failed, or
                        // the tx is slow to confirm). Never cancel an escrow whose
                        // P2SH address holds funds — that would orphan the deposit.
                        if (now - entity.created_at > ESCROW_FUNDING_TIMEOUT_MS) {
                            val hasDeposit = hasOnChainDeposit(entity.funding_address)
                            if (hasDeposit) {
                                Log.w(TAG, "FUNDING escrow ${entity.escrow_id} timed out but address " +
                                    "${entity.funding_address} has a deposit — promoting to FUNDED " +
                                    "instead of cancelling")
                                val funded = entity.copy(status = EscrowStatus.FUNDED.name, funded_at = now)
                                db.escrowDao().upsert(funded)
                                val domain = funded.toDomain()
                                _escrowStates.update { map ->
                                    map + (entity.escrow_id to EscrowState(escrow = domain, status = "funded", progress = 0.3f))
                                }
                                _transitions.emit(EscrowTransition(entity.escrow_id, "funded"))
                            } else {
                                val updated = entity.copy(status = EscrowStatus.CANCELLED.name)
                                db.escrowDao().upsert(updated)
                                val domain = updated.toDomain()
                                _escrowStates.update { map ->
                                    map + (entity.escrow_id to EscrowState(escrow = domain, status = "cancelled", progress = 0f))
                                }
                                // Emit so the orchestrator can notify the user
                                // (auto-cancel is user-facing, not a silent sweep).
                                _transitions.emit(EscrowTransition(entity.escrow_id, "cancelled"))
                                Log.d(TAG, "Expired FUNDING escrow ${entity.escrow_id} → CANCELLED")
                            }
                        }
                    }
                    EscrowStatus.FUNDED -> {
                        // Deposited but stalled → auto-refund to the seller.
                        // Grace-aware (Task 3): refund only after the primary
                        // window PLUS the grace window, so a funded trade is
                        // never yanked back on a slow counterparty. Between
                        // timeout and timeout+grace, remind instead of acting.
                        val fundedAt = entity.funded_at ?: entity.created_at
                        val elapsed = now - fundedAt
                        if (elapsed > ESCROW_FUNDED_REFUND_TIMEOUT_MS + FUNDED_REFUND_GRACE_MS) {
                            autoRefundEscrow(entity)
                        } else if (elapsed > ESCROW_FUNDED_REFUND_TIMEOUT_MS) {
                            emitOnce("refund_grace_reminder", entity.escrow_id) {
                                Log.w(TAG, "FUNDED escrow ${entity.escrow_id} past refund timeout " +
                                    "(${elapsed / 3_600_000}h) — grace until " +
                                    "${(ESCROW_FUNDED_REFUND_TIMEOUT_MS + FUNDED_REFUND_GRACE_MS) / 3_600_000}h")
                                _transitions.emit(EscrowTransition(entity.escrow_id, "refund_grace_reminder"))
                            }
                        }
                    }
                    EscrowStatus.RECEIPT_SENT, EscrowStatus.CONFIRMING -> {
                        // Payment window (Task 3): buyer marked paid; seller must
                        // release or dispute. Auto-DISPUTED only after the
                        // payment window PLUS grace — never silently refunded,
                        // because the buyer may have actually paid. Between
                        // window and window+grace, remind once.
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
    private suspend fun hasOnChainDeposit(fundingAddress: String?): Boolean {
        if (fundingAddress.isNullOrBlank()) return false
        return try {
            val info = chainMonitor.getAddressInfo(fundingAddress).getOrNull() ?: return false
            info.totalSats > 0L
        } catch (e: Exception) {
            Log.w(TAG, "Deposit check failed for $fundingAddress: ${e.message}")
            false
        }
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
        buyerBtcAddress: String? = null
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        // HARD ENFORCEMENT: refuse to create any escrow if the fee wallet
        // address fails signature verification. This prevents a forked build
        // from redirecting the 0.3% fee to an attacker-controlled address.
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
            // from the fastest fee rate × the payout vsize for the chosen
            // script type (P2WSH witness spends are ~half the vbytes of P2SH).
            val feeRatePerVb = chainMonitor.estimateFees().fastest
            val networkFeeSats = feeRatePerVb * fundingScriptType.spendVsize

            val escrow = Escrow(
                escrowId = "escrow_${offer.offerId}_${System.currentTimeMillis()}",
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
                // on the SELL-offer path it arrives via the kind:33337 sync
                // event once the buyer accepts.
                buyerBtcAddress = buyerBtcAddress
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
            val networkFeeSats = feeRatePerVb * newType.spendVsize
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
                // funding address the exact deposit (crypto + fee + network fee).
                // A random confirmed txid (or a deposit to the wrong address /
                // wrong amount) must never mark an escrow FUNDED.
                val outputs = chainMonitor.getTxOutputs(fundingTxId).getOrElse {
                    return@withContext Result.failure(
                        Exception("Cannot fetch funding tx outputs: ${it.message}")
                    )
                }
                val vout = findFundingOutput(outputs, entity.funding_address, entity.deposit_amount_sats)
                    ?: return@withContext Result.failure(
                        Exception(
                            "Funding tx does not pay the escrow address " +
                                "${entity.funding_address} the deposit amount " +
                                "${entity.deposit_amount_sats} sats"
                        )
                    )

                // Persist the funding txid + vout IMMEDIATELY (even before the
                // confirmation threshold is met): the deposit is verifiably
                // bound to this escrow, so the UI can show "In progress /
                // waiting for confirmation" instead of "Pending" and disable
                // the double-send button across app restarts.
                if (entity.funding_tx_id != fundingTxId) {
                    db.escrowDao().upsert(entity.copy(funding_tx_id = fundingTxId, funding_vout = vout.toLong()))
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

                val updated = entity.copy(
                    funding_tx_id = fundingTxId,
                    funding_vout = vout.toLong(),
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
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val escrow = entity.toDomain()
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
                else chainMonitor.estimateFees().fastest * PAYOUT_APPROX_VSIZE
            val outputValue = escrow.tradeAmountSats + escrow.feeAmountSats
            if (escrow.depositAmountSats < outputValue + networkFeeSats) {
                throw IllegalStateException(
                    "Deposit insufficient to cover outputs + network fee " +
                        "(deposit=${escrow.depositAmountSats}, outputs=$outputValue, fee=$networkFeeSats)"
                )
            }

            val payoutTx = Transaction(NET_PARAMS)
            payoutTx.addInput(Sha256Hash.wrap(fundingTxId), vout, ScriptBuilder.createEmpty())

            // Output 1: buyer receives the trade amount (full C, buyer fee = 0).
            // Parsed with Address.fromString so BOTH legacy (m…/1…) and SegWit
            // (tb1…/bc1…) receive addresses are accepted.
            val buyerAddress = Address.fromString(NET_PARAMS, buyerAddressStr)
            payoutTx.addOutput(Coin.valueOf(escrow.tradeAmountSats), buyerAddress)

            // Output 2: fee wallet gets the full 0.3% platform fee
            val feeAddress = Address.fromString(NET_PARAMS, feeAddressStr)
            payoutTx.addOutput(Coin.valueOf(escrow.feeAmountSats), feeAddress)

            // The implicit miner fee = input − outputs = networkFeeSats. No
            // explicit setFee is needed because the deposit already covers it;
            // outputs are exactly buyer(C) + feeWallet(feeSats). No dust output.
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
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

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
            val updated = entity.copy(buyer_signature = sig.encodeToByteArray())
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
            val updated = entity.copy(seller_signature = sig.encodeToByteArray())
            db.escrowDao().upsert(updated)

            Log.d(TAG, "Seller signed payout for $escrowId")
            Result.success(sig)
        } catch (e: Exception) {
            Log.e(TAG, "Seller signing failed", e)
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
                val txSig = tx.calculateWitnessSignature(
                    0, key, redeemScript,
                    Coin.valueOf(entity.deposit_amount_sats),
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
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

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

            val broadcastResult = chainMonitor.broadcastTx(finalHex)
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

    /** The script type the escrow's funding output commits (P2SH vs P2WSH). */
    private fun escrowScriptType(entity: EscrowEntity): BitcoinAddressType =
        try {
            BitcoinAddressType.valueOf(entity.funding_script_type)
        } catch (_: Exception) {
            BitcoinAddressType.LEGACY
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
        val depositSats = entity.deposit_amount_sats
        val witness = escrowScriptType(entity) == BitcoinAddressType.SEGWIT

        // Role slots in redeem-script pubkey order.
        val roles = listOf(
            // (rolePubkey, storedSignature)
            entity.buyer_pubkey_hex to entity.buyer_signature,
            entity.seller_pubkey_hex to entity.seller_signature,
            NeoP2PConfig.ARBITRATOR_PUBKEY to arbitratorSigHex?.let { hexToBytes(it) }
        )

        val sigsInPubkeyOrder = mutableListOf<ByteArray>()
        for ((rolePubkey, storedSig) in roles) {
            if (rolePubkey == null) continue
            var sig: ByteArray? = null
            // 1) Stored signature for this slot, if it verifies.
            storedSig?.let {
                if (verifySignature(tx, redeemScript, rolePubkey, it, depositSats, witness)) {
                    sig = it
                }
            }
            // 2) Local key, if it matches this role.
            if (sig == null && pubkey(localKey, rolePubkey)) {
                val candidate = signRaw(tx, redeemScript, localKey, depositSats, witness)
                if (verifySignature(tx, redeemScript, rolePubkey, candidate, depositSats, witness)) {
                    sig = candidate
                }
            }
            sig?.let { sigsInPubkeyOrder.add(it) }
        }

        if (sigsInPubkeyOrder.size < 2) return null

        return when (escrowScriptType(entity)) {
            BitcoinAddressType.LEGACY -> SpendParts(
                scriptSig = ScriptBuilder.createMultiSigInputScriptBytes(
                    sigsInPubkeyOrder,
                    redeemScript.getProgram()
                )
            )
            BitcoinAddressType.SEGWIT -> {
                val sigs = sigsInPubkeyOrder.map {
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
            if (status != EscrowStatus.RECEIPT_SENT && status != EscrowStatus.CONFIRMING) {
                return@withContext Result.failure(
                    IllegalStateException("Cannot confirm receipt from ${entity.status}")
                )
            }
            // Self-generate the unsigned payout when it doesn't exist yet
            // (P2, 2-party flow): the buyer's device may never have generated
            // one; the seller confirms and releaseFunds needs a payout tx.
            // Fallback address = escrow funding address keeps the single-key
            // demo working; real trades carry buyerBtcAddress (U1).
            if (entity.psbt_unsigned == null) {
                val escrow = entity.toDomain()
                val fundingTxId = escrow.fundingTxId
                    ?: return@withContext Result.failure(IllegalStateException("No funding tx recorded"))
                val buyerAddr = escrow.buyerBtcAddress?.takeIf { it.isNotBlank() }
                    ?: escrow.fundingAddress
                    ?: return@withContext Result.failure(IllegalStateException("No buyer payout address"))
                val gen = generatePayoutTransaction(
                    escrowId = escrow.escrowId,
                    fundingTxId = fundingTxId,
                    fundingOutputIndex = escrow.fundingVout.toInt(),
                    buyerAddressStr = buyerAddr
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
            Log.d(TAG, "Seller confirmed IDR received for escrow $escrowId — releasing")
            // Release path: assemble the 2-of-3 spend (single-key model fills
            // both role slots) and broadcast the payout; RELEASED is terminal.
            releaseFunds(escrowId)
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

    suspend fun resolveDispute(
        escrowId: String,
        decision: ResolutionDecision,
        arbitratorPrivKeyHex: String,
        arbitratorNotes: String? = null
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            // Fork guard: only an unmodified build may resolve disputes —
            // the arbitrator key itself is pinned by the owner's signature.
            if (!NeoP2PConfig.verifyArbitratorIntegrity()) {
                return@withContext Result.failure(
                    IllegalStateException("Arbitrator key signature invalid — resolution disabled")
                )
            }
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val currentStatus = EscrowStatus.valueOf(entity.status)
            if (currentStatus != EscrowStatus.DISPUTED) {
                return@withContext Result.failure(Exception("Escrow $escrowId is not disputed"))
            }

            // P0-1: only the real arbitrator key may sign a resolution.
            val arbKey = ECKey.fromPrivate(hexToBytes(arbitratorPrivKeyHex))
            if (NeoP2PConfig.ARBITRATOR_PUBKEY != arbKey.publicKeyAsHex &&
                NeoP2PConfig.ARBITRATOR_PUBKEY != xOnlyOf(arbKey.publicKeyAsHex)
            ) {
                return@withContext Result.failure(
                    SecurityException("Provided key is not the arbitrator key")
                )
            }

            val redeemScriptHex = entity.redeem_script_hex
                ?: return@withContext Result.failure(Exception("No redeem script stored"))
            val redeemScript = Script(hexToBytes(redeemScriptHex))

            // Build the final tx matching the decision: payout (to buyer) or refund (to seller).
            val tx = when (decision) {
                ResolutionDecision.RELEASE_TO_BUYER -> {
                    val txHex = entity.psbt_unsigned?.toString(Charsets.UTF_8)
                        ?: return@withContext Result.failure(Exception("No unsigned payout tx stored"))
                    Transaction(NET_PARAMS, hexToBytes(txHex))
                }
                ResolutionDecision.REFUND_TO_SELLER ->
                    buildRefundTx(entity, identityManager.getBitcoinAddress(BitcoinAddressType.LEGACY)).tx
            }

            // The arbitrator signs the actual final tx (payout or refund).
            val arbSig = signRaw(tx, redeemScript, arbKey, entity.deposit_amount_sats,
                escrowScriptType(entity) == BitcoinAddressType.SEGWIT)
                .joinToString("") { "%02x".format(it) }
            val spend = assemble2of3Spend(tx, redeemScript, entity, arbitratorSigHex = arbSig)
                ?: return@withContext Result.failure(
                    Exception("Arbitrator cannot broadcast alone; publish a resolution for the parties to apply")
                )
            attachSpend(tx, spend)

            val finalHex = tx.bitcoinSerialize().joinToString("") { "%02x".format(it) }
            val broadcastResult = chainMonitor.broadcastTx(finalHex)
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
                arbitrator_signature = hexToBytes(arbSig),
                arbitrator_decision = decision.name,
                arbitrator_notes = arbitratorNotes,
                status = newStatus.name,
                released_at = System.currentTimeMillis()
            )
            db.escrowDao().upsert(updated)

            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = decision.name.lowercase(), progress = 1.0f))
            }
            _transitions.emit(EscrowTransition(escrowId, decision.name.lowercase()))
            Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve dispute", e)
            Result.failure(e)
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
            // Sanity-check the produced signature verifies against the arbitrator key.
            val pub = ECKey.fromPublicOnly(xOnlyToCompressed(NeoP2PConfig.ARBITRATOR_PUBKEY))
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
     * Apply an arbitrator's decision received from the relay (kind:33388) to a
     * locally-held escrow. Idempotent: stores the signature + decision and
     * moves DISPUTED/RESOLVING → RELEASED/REFUNDED, but never downgrades a
     * terminal state and never overwrites an existing decision.
     */
    suspend fun storeArbitrationDecision(
        escrowId: String,
        decision: ResolutionDecision,
        arbitratorSigHex: String,
        notes: String?
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

            // Build the final tx matching the decision: payout (to buyer) or refund (to seller).
            val tx = when (decision) {
                ResolutionDecision.RELEASE_TO_BUYER -> {
                    val txHex = entity.psbt_unsigned?.toString(Charsets.UTF_8)
                        ?: throw IllegalStateException("No unsigned payout tx stored")
                    Transaction(NET_PARAMS, hexToBytes(txHex))
                }
                ResolutionDecision.REFUND_TO_SELLER ->
                    buildRefundTx(entity, identityManager.getBitcoinAddress(BitcoinAddressType.LEGACY)).tx
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
            val broadcastResult = chainMonitor.broadcastTx(finalHex)
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
                // P2WSH spends are ~half the vbytes of P2SH (witness discount).
                val networkFeeSats = feeRate * escrowScriptType(entity).spendVsize
                val refundAmount = escrow.depositAmountSats - networkFeeSats
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
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val currentStatus = EscrowStatus.valueOf(entity.status)
            if (currentStatus != EscrowStatus.FUNDING && currentStatus != EscrowStatus.FUNDED &&
                currentStatus != EscrowStatus.DISPUTED
            ) {
                return@withContext Result.failure(
                    Exception("Cannot cancel escrow: already signed/released/refunded")
                )
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
     * Shared build+sign+broadcast pipeline for an escrow refund, used by both
     * the user-initiated [cancelEscrowRefund] and the 6-hour auto-refund
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
            val depositSats = entity.deposit_amount_sats
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

            val broadcast = chainMonitor.broadcastTx(finalHex)
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

            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = "refunded", progress = 0f))
            }
            _transitions.emit(EscrowTransition(escrowId, "refunded"))

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
        val networkFeeSats = feeRate * escrowScriptType(entity).spendVsize
        val refundAmount = escrow.depositAmountSats - networkFeeSats
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
}
