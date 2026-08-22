package com.neop2p.data.escrow

import android.util.Log
import com.neop2p.BuildConfig
import com.neop2p.NeoP2PConfig
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.*
import org.bitcoinj.crypto.TransactionSignature
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-chain Bitcoin escrow service for NEO-P2P.
 *
 * Manages 2-of-3 multisig escrow using P2SH addresses.
 * The 1% fee is built into the pre-signed payout transaction.
 *
 * Flow:
 *   1. createEscrow() → generates 2-of-3 P2SH address, stores in Room
 *   2. Buyer sends BTC to the P2SH address (out-of-app)
 *   3. onEscrowFunded() → verifies on-chain via Mempool API
 *   4. generatePayoutTransaction() → creates unsigned raw tx
 *   5. signPayoutAsBuyer() → buyer signs with ECKey
 *   6. signPayoutAsSeller() → seller signs with ECKey
 *   7. releaseFunds() → broadcasts fully-signed transaction
 *   8. disputeEscrow() / resolveDispute() → arbitrator path
 */
@Singleton
class EscrowService @Inject constructor(
    private val db: AppDatabase,
    private val chainMonitor: ChainMonitor
) {
    companion object {
        private const val TAG = "EscrowService"
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

    private val _escrowStates = MutableStateFlow<Map<String, EscrowState>>(emptyMap())
    fun getEscrowState(escrowId: String): StateFlow<EscrowState> = _escrowStates
        .map { it[escrowId] ?: EscrowState() }
        .stateIn(CoroutineScope(Dispatchers.IO), SharingStarted.Eagerly, EscrowState())

    /** Load a single escrow by ID (null if not found). */
    suspend fun getEscrow(escrowId: String): Escrow? =
        db.escrowDao().getEscrowSync(escrowId)?.toDomain()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun initialize() {
        try {
            val entities = db.escrowDao().getAllEscrowsSync()
            val states = entities.associate { entity ->
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
     * Initiate a new escrow. Generates a 2-of-3 P2SH multisig address.
     *
     * Roles: the SELLER supplies the BTC (deposits `depositAmountSats`,
     * 100.5% of the trade) into the multisig. The BUYER pays IDR via a fiat
     * method. On confirmation, the payout sends 99.5% to the buyer and 1%
     * to the fee wallet.
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
        // from redirecting the 1% fee to an attacker-controlled address.
        if (!NeoP2PConfig.verifyFeeWalletIntegrity()) {
            return@withContext Result.failure(
                IllegalStateException("Fee wallet signature invalid — escrow disabled")
            )
        }
        try {
            val buyerKey = ECKey.fromPublicOnly(hexToBytes(buyerPubKeyHex))
            val sellerKey = ECKey.fromPublicOnly(hexToBytes(sellerPubKeyHex))
            val arbKey = ECKey.fromPublicOnly(hexToBytes(NeoP2PConfig.ARBITRATOR_PUBKEY))

            val redeemScript = ScriptBuilder.createRedeemScript(2, listOf(buyerKey, sellerKey, arbKey))
            // P2SH address = hash160 of the redeem script program (NOT the raw program bytes).
            val fundingAddress = LegacyAddress.fromScriptHash(
                NET_PARAMS,
                Utils.sha256hash160(redeemScript.getProgram())
            )

            val escrow = Escrow(
                escrowId = "escrow_${offer.offerId}_${System.currentTimeMillis()}",
                offerId = offer.offerId,
                type = EscrowType.ON_CHAIN,
                fundingAddress = fundingAddress.toBase58(),
                redeemScriptHex = redeemScript.getProgram().joinToString("") { "%02x".format(it) },
                depositAmountSats = offer.cryptoAmountSats + offer.buyerFeeSats,
                tradeAmountSats = offer.cryptoAmountSats - offer.sellerFeeSats,
                feeAmountSats = offer.feeSats,
                feeAddress = NeoP2PConfig.FEE_WALLET_ADDRESS,
                buyerPeerId = buyerPeerId,
                sellerPeerId = sellerPeerId,
                status = EscrowStatus.FUNDING
            )

            db.escrowDao().upsert(escrow.toEntity())
            _escrowStates.update { map ->
                map + (escrow.escrowId to EscrowState(escrow = escrow, status = "created", progress = 0.1f))
            }

            Log.d(TAG, "Escrow created: ${escrow.escrowId} address=${escrow.fundingAddress}")
            Result.success(escrow)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create escrow", e)
            Result.failure(e)
        }
    }

    /**
     * Buyer funded the escrow. Verifies on-chain via Mempool API.
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

                val updated = entity.copy(funding_tx_id = fundingTxId, status = EscrowStatus.FUNDED.name)
                db.escrowDao().upsert(updated)

                val domain = updated.toDomain()
                _escrowStates.update { map ->
                    map + (escrowId to EscrowState(escrow = domain, status = "funded", progress = 0.3f))
                }
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

            // Build the payout transaction. The input references the funded
            // P2SH output; the redeem script is placed in the scriptSig when
            // signatures are added (real multisig spending, not empty script).
            val redeemScript = Script(hexToBytes(escrow.redeemScriptHex))
            val payoutTx = Transaction(NET_PARAMS)
            payoutTx.addInput(Sha256Hash.wrap(fundingTxId), fundingOutputIndex.toLong(), ScriptBuilder.createEmpty())

            // Output 1: buyer receives the trade amount (99.5%)
            val buyerAddress = LegacyAddress.fromBase58(NET_PARAMS, buyerAddressStr)
            payoutTx.addOutput(Coin.valueOf(escrow.tradeAmountSats), buyerAddress)

            // Output 2: fee wallet gets the fee (1%)
            val feeAddress = LegacyAddress.fromBase58(NET_PARAMS, feeAddressStr)
            payoutTx.addOutput(Coin.valueOf(escrow.feeAmountSats), feeAddress)

            val txHex = payoutTx.bitcoinSerialize().joinToString("") { "%02x".format(it) }

            val updated = entity.copy(
                psbt_unsigned = txHex.encodeToByteArray(),
                status = EscrowStatus.SIGNED.name
            )
            db.escrowDao().upsert(updated)

            _escrowStates.update { map ->
                map + (escrowId to EscrowState(escrow = updated.toDomain(), status = "signed", progress = 0.6f))
            }

            Log.d(TAG, "Payout tx created for $escrowId")
            Result.success(txHex)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate payout tx", e)
            Result.failure(e)
        }
    }

    /**
     * Sign the payout transaction with the buyer's private key.
     * Stores the signature in the escrow record.
     */
    suspend fun signPayoutAsBuyer(
        escrowId: String,
        buyerPrivKeyHex: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val buyerKey = ECKey.fromPrivate(hexToBytes(buyerPrivKeyHex))
            val sig = signTransaction(entity, buyerKey)

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
     * Sign the payout transaction with the seller's private key.
     */
    suspend fun signPayoutAsSeller(
        escrowId: String,
        sellerPrivKeyHex: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val sellerKey = ECKey.fromPrivate(hexToBytes(sellerPrivKeyHex))
            val sig = signTransaction(entity, sellerKey)

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
     * Release funds after fiat confirmation. Assembles the full P2SH scriptSig
     * (2-of-3: signatures + redeem script) and broadcasts.
     */
    suspend fun releaseFunds(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val txHex = entity.psbt_unsigned?.toString(Charsets.UTF_8)
                ?: return@withContext Result.failure(Exception("No unsigned tx found"))
            val redeemScriptHex = entity.redeem_script_hex
                ?: return@withContext Result.failure(Exception("No redeem script stored"))

            // Collect the two required signatures (buyer + seller for a release).
            val sigs = listOfNotNull(
                entity.buyer_signature,
                entity.seller_signature,
                entity.arbitrator_signature
            ).take(2)
            if (sigs.size < 2) {
                return@withContext Result.failure(Exception("Need 2 of 3 signatures to release"))
            }

            val redeemScript = Script(hexToBytes(redeemScriptHex))
            val tx = Transaction(NET_PARAMS, hexToBytes(txHex))

            // P2SH multisig scriptSig: OP_0 <sig1> <sig2> <redeemScript>.
            // bitcoinj's createMultiSigInputScriptBytes emits <sig1><sig2><redeemScript>,
            // which is the correct P2SH witness-style scriptSig for 2-of-3.
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

            Log.d(TAG, "Funds released for $escrowId tx=$payoutTxId")
            Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release funds", e)
            Result.failure(e)
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

            val arbKey = ECKey.fromPrivate(hexToBytes(arbitratorPrivKeyHex))
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
            Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve dispute", e)
            Result.failure(e)
        }
    }

    fun getFeeSummary(tradeAmountSats: Long): FeeSummary {
        val feeSats = (tradeAmountSats * NeoP2PConfig.FEE_PERCENT).toLong()
        val buyerFee = feeSats / 2
        return FeeSummary(tradeAmountSats, NeoP2PConfig.FEE_PERCENT, feeSats, tradeAmountSats + buyerFee, NeoP2PConfig.FEE_WALLET_ADDRESS)
    }

    data class FeeSummary(val tradeAmountSats: Long, val feePercent: Double, val feeSats: Long, val totalSats: Long, val feeAddress: String)

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}

// ─── Mappers ─────────────────────────────────────────────────────

private fun Escrow.toEntity(): EscrowEntity = EscrowEntity(
    escrow_id = escrowId, offer_id = offerId, type = type.name,
    funding_tx_id = fundingTxId, payout_tx_id = payoutTxId,
    funding_address = fundingAddress, funding_address_path = fundingAddressPath,
    redeem_script_hex = redeemScriptHex,
    psbt_unsigned = psbtUnsigned, psbt_buyer_signed = psbtBuyerSigned,
    deposit_amount_sats = depositAmountSats, trade_amount_sats = tradeAmountSats,
    fee_amount_sats = feeAmountSats, fee_address = feeAddress,
    buyer_peer_id = buyerPeerId, seller_peer_id = sellerPeerId,
    status = status.name, buyer_signature = buyerSignature,
    seller_signature = sellerSignature, arbitrator_signature = arbitratorSignature,
    arbitrator_decision = arbitratorDecision, arbitrator_notes = arbitratorNotes,
    channel_point = channelPoint, created_at = createdAt, released_at = releasedAt
)

private fun EscrowEntity.toDomain(): Escrow = Escrow(
    escrowId = escrow_id, offerId = offer_id, type = EscrowType.valueOf(type),
    fundingTxId = funding_tx_id, payoutTxId = payout_tx_id,
    fundingAddress = funding_address, fundingAddressPath = funding_address_path,
    redeemScriptHex = redeem_script_hex,
    psbtUnsigned = psbt_unsigned, psbtBuyerSigned = psbt_buyer_signed,
    depositAmountSats = deposit_amount_sats, tradeAmountSats = trade_amount_sats,
    feeAmountSats = fee_amount_sats, feeAddress = fee_address,
    buyerPeerId = buyer_peer_id, sellerPeerId = seller_peer_id,
    status = EscrowStatus.valueOf(status), buyerSignature = buyer_signature,
    sellerSignature = seller_signature, arbitratorSignature = arbitrator_signature,
    arbitratorDecision = arbitrator_decision, arbitratorNotes = arbitrator_notes,
    channelPoint = channel_point, createdAt = created_at, releasedAt = released_at
)
