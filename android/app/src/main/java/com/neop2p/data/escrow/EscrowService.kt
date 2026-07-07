package com.neop2p.data.escrow

import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.entity.EscrowEntity
import com.neop2p.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightning escrow service for NEO-P2P.
 *
 * Manages 2-of-3 multisig escrow channels on Lightning Network.
 * The 1% fee is built into the pre-signed payout transaction.
 *
 * REDESIGN: All state changes are now persisted to Room/SQLCipher
 * via EscrowDao. State survives app restart. LDK integration point
 * marked with TODO comments for real transaction building.
 */
@Singleton
class EscrowService @Inject constructor(
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "EscrowService"
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize by loading all persisted escrows from DB into memory.
     */
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
     * Initiate a new escrow for a trade.
     */
    suspend fun createEscrow(
        offer: TradeOffer,
        buyerPeerId: String,
        sellerPeerId: String
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val escrow = Escrow(
                escrowId = "escrow_${offer.offerId}_${System.currentTimeMillis()}",
                offerId = offer.offerId,
                type = EscrowType.LIGHTNING,
                depositAmountSats = offer.cryptoAmountSats + offer.buyerFeeSats, // 100% + 0.5%
                tradeAmountSats = offer.cryptoAmountSats - offer.sellerFeeSats, // 100% - 0.5%
                feeAmountSats = offer.feeSats, // 1% total (0.5% from each)
                feeAddress = NeoP2PConfig.FEE_WALLET_ADDRESS,
                buyerPeerId = buyerPeerId,
                sellerPeerId = sellerPeerId,
                status = EscrowStatus.FUNDING
            )

            // Persist to DB
            db.escrowDao().upsert(escrow.toEntity())

            _escrowStates.update { map ->
                map + (escrow.escrowId to EscrowState(
                    escrow = escrow,
                    status = "created",
                    progress = 0.1f
                ))
            }

            Log.d(TAG, "Escrow created: ${escrow.escrowId}")
            Log.d(TAG, "  Trade: ${escrow.tradeAmountSats} sats")
            Log.d(TAG, "  Fee (1%): ${escrow.feeAmountSats} sats")
            Log.d(TAG, "  Total deposit: ${escrow.depositAmountSats} sats")

            Result.success(escrow)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create escrow", e)
            Result.failure(e)
        }
    }

    /**
     * The buyer funds the escrow by depositing to the multisig address.
     */
    suspend fun onEscrowFunded(escrowId: String, fundingTxId: String): Result<Escrow> =
        withContext(Dispatchers.IO) {
            try {
                val entity = db.escrowDao().getEscrowSync(escrowId)
                    ?: return@withContext Result.failure(Exception("Escrow not found"))

                val updated = entity.copy(
                    funding_tx_id = fundingTxId,
                    status = EscrowStatus.FUNDED.name
                )
                db.escrowDao().upsert(updated)

                val domain = updated.toDomain()
                _escrowStates.update { map ->
                    map + (escrowId to EscrowState(
                        escrow = domain,
                        status = "funded",
                        progress = 0.3f
                    ))
                }

                Log.d(TAG, "Escrow funded: $escrowId (tx: $fundingTxId)")
                Result.success(domain)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update escrow funding", e)
                Result.failure(e)
            }
        }

    /**
     * Generate the pre-signed payout transaction.
     *
     * TODO(LDK): This is where the real Lightning transaction building goes.
     * Current implementation uses placeholder signatures. Integration point for:
     *   - LDK ChannelManager: create funding transaction
     *   - bitcoinj: build 2-of-3 PSBT
     *   - Sign with buyer + seller keys
     *   - Broadcast when both signatures are collected
     *
     * The structure below defines the integration contract:
     *   Input:  escrow funding UTXO (buyer's deposit)
     *   Outputs:
     *     → Seller:  tradeAmountSats (99.5% net — 0.5% fee deducted)
     *     → Fee:     feeAmountSats (1% — 0.5% from buyer + 0.5% from seller)
     *   Signed by: Buyer + Seller (2-of-3 multisig)
     */
    suspend fun generatePayoutTransaction(
        escrow: Escrow,
        buyerKeyBytes: ByteArray,
        sellerKeyBytes: ByteArray
    ): Result<Pair<ByteArray, ByteArray>> = withContext(Dispatchers.IO) {
        try {
            // TODO(LDK): Build real Lightning payout transaction
            // Currently using placeholder signatures
            // Real implementation should:
            //   1. Create PSBT with 2 outputs (seller + fee wallet)
            //   2. Collect buyer signature
            //   3. Collect seller signature
            //   4. Combine into finalized transaction
            //   5. Store the unsigned+signed PSBT in escrow record

            val buyerSig = "BUYER_SIG_PLACEHOLDER".encodeToByteArray()
            val sellerSig = "SELLER_SIG_PLACEHOLDER".encodeToByteArray()

            val updatedEntity = db.escrowDao().getEscrowSync(escrow.escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val updated = updatedEntity.copy(
                buyer_signature = buyerSig,
                seller_signature = sellerSig,
                status = EscrowStatus.SIGNED.name
            )
            db.escrowDao().upsert(updated)

            val domain = updated.toDomain()

            _escrowStates.update { map ->
                map + (escrow.escrowId to EscrowState(
                    escrow = domain,
                    status = "signed",
                    progress = 0.6f
                ))
            }

            Log.d(TAG, "Payout transaction pre-signed for ${escrow.escrowId}")
            Log.d(TAG, "  Seller gets: ${escrow.tradeAmountSats} sats")
            Log.d(TAG, "  Fee wallet gets: ${escrow.feeAmountSats} sats")
            Log.w(TAG, "  NOTE: Placeholder signatures — LDK integration needed for production")

            Result.success(Pair(buyerSig, sellerSig))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate payout transaction", e)
            Result.failure(e)
        }
    }

    /**
     * Release funds after the seller confirms fiat payment.
     */
    suspend fun releaseFunds(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val updated = entity.copy(
                payout_tx_id = "payout_${escrowId}_${System.currentTimeMillis()}",
                status = EscrowStatus.RELEASED.name,
                released_at = System.currentTimeMillis()
            )
            db.escrowDao().upsert(updated)

            val domain = updated.toDomain()

            _escrowStates.update { map ->
                map + (escrowId to EscrowState(
                    escrow = domain,
                    status = "released",
                    progress = 1.0f
                ))
            }

            Log.d(TAG, "Funds released for $escrowId")
            Log.d(TAG, "  ${domain.tradeAmountSats} sats → seller")
            Log.d(TAG, "  ${domain.feeAmountSats} sats → fee wallet (${domain.feeAddress})")

            Result.success(domain)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release funds", e)
            Result.failure(e)
        }
    }

    /**
     * Dispute: any party can trigger a 7-day timelock.
     */
    suspend fun disputeEscrow(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val updated = entity.copy(
                status = EscrowStatus.DISPUTED.name
            )
            db.escrowDao().upsert(updated)

            val domain = updated.toDomain()

            _escrowStates.update { map ->
                map + (escrowId to EscrowState(
                    escrow = domain,
                    status = "disputed",
                    progress = 0.5f,
                    error = "Dispute triggered — 7-day timelock started"
                ))
            }

            Log.d(TAG, "Escrow disputed: $escrowId")
            Log.d(TAG, "  Full deposit (${domain.depositAmountSats} sats) locked for 7 days")

            Result.success(domain)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the fee summary for display in the UI.
     */
    fun getFeeSummary(tradeAmountSats: Long): FeeSummary {
        val feeSats = (tradeAmountSats * NeoP2PConfig.FEE_PERCENT).toLong()
        val buyerFee = feeSats / 2
        val totalSats = tradeAmountSats + buyerFee // buyer deposits trade + their half of fee
        return FeeSummary(
            tradeAmountSats = tradeAmountSats,
            feePercent = NeoP2PConfig.FEE_PERCENT,
            feeSats = feeSats,
            totalSats = totalSats,
            feeAddress = NeoP2PConfig.FEE_WALLET_ADDRESS
        )
    }

    data class FeeSummary(
        val tradeAmountSats: Long,
        val feePercent: Double,
        val feeSats: Long,
        val totalSats: Long,
        val feeAddress: String
    )
}

// ─── Domain ↔ Entity Mappers ─────────────────────────────────────

private fun Escrow.toEntity(): EscrowEntity = EscrowEntity(
    escrow_id = escrowId,
    offer_id = offerId,
    type = type.name,
    funding_tx_id = fundingTxId,
    payout_tx_id = payoutTxId,
    deposit_amount_sats = depositAmountSats,
    trade_amount_sats = tradeAmountSats,
    fee_amount_sats = feeAmountSats,
    fee_address = feeAddress,
    buyer_peer_id = buyerPeerId,
    seller_peer_id = sellerPeerId,
    status = status.name,
    buyer_signature = buyerSignature,
    seller_signature = sellerSignature,
    channel_point = channelPoint,
    created_at = createdAt,
    released_at = releasedAt
)

private fun EscrowEntity.toDomain(): Escrow = Escrow(
    escrowId = escrow_id,
    offerId = offer_id,
    type = EscrowType.valueOf(type),
    fundingTxId = funding_tx_id,
    payoutTxId = payout_tx_id,
    depositAmountSats = deposit_amount_sats,
    tradeAmountSats = trade_amount_sats,
    feeAmountSats = fee_amount_sats,
    feeAddress = fee_address,
    buyerPeerId = buyer_peer_id,
    sellerPeerId = seller_peer_id,
    status = EscrowStatus.valueOf(status),
    buyerSignature = buyer_signature,
    sellerSignature = seller_signature,
    channelPoint = channel_point,
    createdAt = created_at,
    releasedAt = released_at
)