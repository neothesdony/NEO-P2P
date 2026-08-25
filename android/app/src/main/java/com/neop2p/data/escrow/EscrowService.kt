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
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
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
    private val identityManager: IdentityManager
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
         * on-chain move is needed). 30 minutes covers wallet transfer + 1 block
         * confirmation without risking a false auto-cancel.
         */
        const val ESCROW_FUNDING_TIMEOUT_MS = 30 * 60 * 1000L

        /**
         * Timeout for a FUNDED escrow whose trade never proceeds. Once the
         * deposit is confirmed, give the trade a generous window to complete
         * before auto-refunding back to the seller/depositor (so a funded
         * trade isn't yanked back if the buyer is slow).
         */
        const val ESCROW_FUNDED_REFUND_TIMEOUT_MS = 6 * 60 * 60 * 1000L  // 6 hours
        private val NET_PARAMS: NetworkParameters by lazy {
            if (BuildConfig.NETWORK == "mainnet") {
                Log.w(TAG, "⚠️ MAINNET MODE — real funds at risk!")
                MainNetParams.get()
            } else {
                TestNet3Params.get()
            }
        }
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

    private val _escrowStates = MutableStateFlow<Map<String, EscrowState>>(emptyMap())
    // Hoisted once for the singleton lifetime; per-call scopes would leak.
    private val stateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    fun getEscrowState(escrowId: String): StateFlow<EscrowState> = _escrowStates
        .map { it[escrowId] ?: EscrowState() }
        .stateIn(stateScope, SharingStarted.Eagerly, EscrowState())

    /** Load a single escrow by ID (null if not found). */
    suspend fun getEscrow(escrowId: String): Escrow? =
        db.escrowDao().getEscrowSync(escrowId)?.toDomain()

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
                        EscrowStatus.SIGNED -> 0.6f
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
                        // Use the longer post-funding window so a funded trade
                        // isn't yanked back prematurely.
                        val fundedAt = entity.funded_at ?: entity.created_at
                        if (now - fundedAt > ESCROW_FUNDED_REFUND_TIMEOUT_MS) {
                            autoRefundEscrow(entity)
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
            val sellerAddress = identityManager.getBitcoinAddress()
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
        sellerPubKeyHex: String
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        // HARD ENFORCEMENT: refuse to create any escrow if the fee wallet
        // address fails signature verification. This prevents a forked build
        // from redirecting the 0.3% fee to an attacker-controlled address.
        if (!NeoP2PConfig.verifyFeeWalletIntegrity()) {
            return@withContext Result.failure(
                IllegalStateException("Fee wallet signature invalid — escrow disabled")
            )
        }
        try {
            val buyerKey = ECKey.fromPublicOnly(hexToBytes(buyerPubKeyHex))
            val sellerKey = ECKey.fromPublicOnly(hexToBytes(sellerPubKeyHex))
            val arbKey = ECKey.fromPublicOnly(xOnlyToCompressed(NeoP2PConfig.ARBITRATOR_PUBKEY))

            val redeemScript = ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey))
            // P2SH address = hash160 of the redeem script program.
            val fundingAddress = LegacyAddress.fromScriptHash(
                NET_PARAMS,
                Utils.sha256hash160(redeemScript.getProgram())
            )

            // Network (miner) fee the payout tx will pay on-chain. Estimated
            // from the fastest fee rate × the P2SH 2-of-3 payout vsize. The
            // seller must deposit enough to cover it (deposit = C + fee + net).
            val feeRatePerVb = chainMonitor.estimateFees().fastest
            val networkFeeSats = feeRatePerVb * PAYOUT_APPROX_VSIZE

            val escrow = Escrow(
                escrowId = "escrow_${offer.offerId}_${System.currentTimeMillis()}",
                offerId = offer.offerId,
                type = EscrowType.ON_CHAIN,
                fundingAddress = fundingAddress.toBase58(),
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
                status = EscrowStatus.FUNDING
            )

            db.escrowDao().upsert(escrow.toEntity())
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

                val updated = entity.copy(
                    funding_tx_id = fundingTxId,
                    status = EscrowStatus.FUNDED.name,
                    // Record when the funding was confirmed so the 15-minute
                    // auto-refund timeout measures from confirmation, not creation.
                    funded_at = System.currentTimeMillis()
                )
                db.escrowDao().upsert(updated)

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
        fundingOutputIndex: Int = 0,
        buyerAddressStr: String,
        feeAddressStr: String = NeoP2PConfig.FEE_WALLET_ADDRESS
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val escrow = entity.toDomain()
            requireNotNull(escrow.redeemScriptHex) { "Redeem script not stored" }

            val redeemScript = Script(hexToBytes(escrow.redeemScriptHex))

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
            payoutTx.addInput(Sha256Hash.wrap(fundingTxId), fundingOutputIndex.toLong(), ScriptBuilder.createEmpty())

            // Output 1: buyer receives the trade amount (full C, buyer fee = 0)
            val buyerAddress = LegacyAddress.fromBase58(NET_PARAMS, buyerAddressStr)
            payoutTx.addOutput(Coin.valueOf(escrow.tradeAmountSats), buyerAddress)

            // Output 2: fee wallet gets the full 0.3% platform fee
            val feeAddress = LegacyAddress.fromBase58(NET_PARAMS, feeAddressStr)
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
     * Sign the payout transaction with a key, using the real P2SH redeem script.
     * Returns the DER-encoded signature hex (with SIGHASH_ALL appended).
     */
    private fun signTransaction(entity: EscrowEntity, key: ECKey): String {
        val txHex = entity.psbt_unsigned?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("No unsigned tx found")
        val redeemScriptHex = entity.redeem_script_hex
            ?: throw IllegalStateException("No redeem script stored")
        val tx = Transaction(NET_PARAMS, hexToBytes(txHex))
        val redeemScript = Script(hexToBytes(redeemScriptHex))
        // Sign the input against the redeem script (not an empty script).
        val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
        val sig = key.sign(hash)
        // DER sig + SIGHASH_ALL
        return sig.encodeToDER().let { der ->
            (der + byteArrayOf(Transaction.SigHash.ALL.value.toByte())).joinToString("") { "%02x".format(it) }
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

            val txHex = entity.psbt_unsigned?.toString(Charsets.UTF_8)
                ?: return@withContext Result.failure(Exception("No unsigned tx found"))
            val redeemScriptHex = entity.redeem_script_hex
                ?: return@withContext Result.failure(Exception("No redeem script stored"))
            val redeemScript = Script(hexToBytes(redeemScriptHex))
            val tx = Transaction(NET_PARAMS, hexToBytes(txHex))

            // Roles → (pubkeyHex, signature).
            val candidates = linkedMapOf<String, Pair<String?, ByteArray?>>(
                "buyer" to (entity.buyer_pubkey_hex to entity.buyer_signature),
                "seller" to (entity.seller_pubkey_hex to entity.seller_signature),
                "arbitrator" to (NeoP2PConfig.ARBITRATOR_PUBKEY to entity.arbitrator_signature)
            ).filterValues { (pub, sig) -> pub != null && sig != null }

            if (candidates.size < 2) {
                return@withContext Result.failure(Exception("Need 2 of 3 distinct signatures to release"))
            }

            // Keep only signatures that actually verify against their role key.
            val valid = candidates.filter { (_, pair) ->
                verifySignature(tx, redeemScript, pair.first!!, pair.second!!)
            }
            if (valid.size < 2) {
                return@withContext Result.failure(Exception("Fewer than 2 valid signatures"))
            }

            // Take the first 2 distinct valid signatures.
            val sigs = valid.values.take(2).map { it.second!! }

            // P2SH multisig scriptSig: OP_0 <sig1> <sig2> <redeemScript>.
            val scriptSig = ScriptBuilder.createMultiSigInputScriptBytes(sigs, redeemScript.getProgram())
            tx.getInput(0).setScriptSig(scriptSig)

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
     */
    private fun verifySignature(
        tx: Transaction,
        redeemScript: Script,
        pubkeyHex: String,
        signatureWithSighash: ByteArray
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
            val hash = tx.hashForSignature(0, redeemScript, Transaction.SigHash.ALL, false)
            key.verify(hash, sig)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed: ${e.message}")
            false
        }
    }

    suspend fun disputeEscrow(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))
            val updated = entity.copy(status = EscrowStatus.DISPUTED.name)
            db.escrowDao().upsert(updated)
            val domain = updated.toDomain()
            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = domain, status = "disputed", progress = 0.5f,
                    error = "Dispute triggered — 7-day timelock started"))
            }
            _transitions.emit(EscrowTransition(escrowId, "disputed"))
            Result.success(domain)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun resolveDispute(
        escrowId: String,
        decision: ResolutionDecision,
        arbitratorPrivKeyHex: String,
        arbitratorNotes: String? = null
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
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
            val sig = signTransaction(entity, arbKey)

            val newStatus = when (decision) {
                ResolutionDecision.RELEASE_TO_SELLER -> EscrowStatus.RELEASED
                ResolutionDecision.REFUND_TO_BUYER -> EscrowStatus.REFUNDED
            }

            val updated = entity.copy(
                arbitrator_signature = sig.encodeToByteArray(),
                arbitrator_decision = decision.name,
                arbitrator_notes = arbitratorNotes,
                status = newStatus.name,
                released_at = if (newStatus == EscrowStatus.RELEASED) System.currentTimeMillis() else null
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
     * Estimated network fee for a refund, in sat/vB (fastest). Falls back to 50
     * if the fee API is unreachable.
     */
    suspend fun estimateRefundNetworkFee(): Long =
        chainMonitor.estimateFees().fastest

    /** A user-facing refund estimate (no transaction is built or signed). */
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
                val networkFeeSats = feeRate * REFUND_APPROX_VSIZE
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
            if (currentStatus != EscrowStatus.FUNDING && currentStatus != EscrowStatus.FUNDED) {
                return@withContext Result.failure(
                    Exception("Refund only allowed while the escrow is FUNDING or FUNDED")
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
            if (currentStatus != EscrowStatus.FUNDING && currentStatus != EscrowStatus.FUNDED) {
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
     * the user-initiated [cancelEscrowRefund] and the 15-minute auto-refund
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
            val buyerSig = signRaw(tx, redeemScript, key)
            val sellerSig = signRaw(tx, redeemScript, key)

            // Verify each signature against the role pubkey actually stored.
            val valid = listOf(
                buyerExpected to buyerSig,
                sellerExpected to sellerSig
            ).filter { (pub, sig) -> verifySignature(tx, redeemScript, pub, sig) }

            if (valid.size < 2) {
                return Result.failure(Exception("Fewer than 2 valid signatures for refund"))
            }

            val sigs = valid.take(2).map { it.second }
            val scriptSig = ScriptBuilder.createMultiSigInputScriptBytes(sigs, redeemScript.getProgram())
            tx.getInput(0).setScriptSig(scriptSig)

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

    /** Sign input 0 of [tx] against [redeemScript]; returns DER + SIGHASH_ALL. */
    private fun signRaw(tx: Transaction, redeemScript: Script, key: ECKey): ByteArray {
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
        val networkFeeSats = feeRate * REFUND_APPROX_VSIZE
        val refundAmount = escrow.depositAmountSats - networkFeeSats
        if (refundAmount <= 0) {
            throw IllegalStateException("Network fee exceeds deposit; cannot refund")
        }

        val tx = Transaction(NET_PARAMS)
        tx.addInput(Sha256Hash.wrap(fundingTxId), 0L, ScriptBuilder.createEmpty())
        val destination = Address.fromString(NET_PARAMS, destinationAddressStr)
        tx.addOutput(Coin.valueOf(refundAmount), destination)

        return RefundBuild(tx, refundAmount, networkFeeSats, feeRate)
    }

    fun getFeeSummary(tradeAmountSats: Long): FeeSummary {
        val feeSats = (tradeAmountSats * NeoP2PConfig.FEE_PERCENT).toLong()
        return FeeSummary(tradeAmountSats, NeoP2PConfig.FEE_PERCENT, feeSats, tradeAmountSats + feeSats, NeoP2PConfig.FEE_WALLET_ADDRESS)
    }

    data class FeeSummary(val tradeAmountSats: Long, val feePercent: Double, val feeSats: Long, val totalSats: Long, val feeAddress: String)

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
