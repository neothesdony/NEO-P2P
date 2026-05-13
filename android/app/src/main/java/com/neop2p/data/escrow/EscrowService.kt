package com.neop2p.data.escrow

import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightning escrow service for NEO-P2P.
 *
 * Manages 2-of-3 multisig escrow channels on Lightning Network.
 * The 1% fee is built into the pre-signed payout transaction:
 *   Output 1: 100% of trade → Seller
 *   Output 2:   1% of trade → Hardcoded fee wallet
 *
 * Both parties pre-sign the payout BEFORE fiat changes hands.
 * This is the key innovation — no server, no enforcement needed.
 */
@Singleton
class EscrowService @Inject constructor() {
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
     * Initiate a new escrow for a trade.
     *
     * Step 1: Calculate amounts (trade + 1% fee)
     * Step 2: Generate the 2/3 multisig address
     * Step 3: Return the escrow details for the buyer to fund
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
                depositAmountSats = offer.totalDepositSats,
                tradeAmountSats = offer.cryptoAmountSats,
                feeAmountSats = offer.feeSats,
                feeAddress = NeoP2PConfig.FEE_WALLET_ADDRESS,
                buyerPeerId = buyerPeerId,
                sellerPeerId = sellerPeerId,
                status = EscrowStatus.FUNDING
            )

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
     * Called after the buyer has made the on-chain deposit.
     */
    suspend fun onEscrowFunded(escrowId: String, fundingTxId: String): Result<Escrow> =
        withContext(Dispatchers.IO) {
            try {
                val current = _escrowStates.value[escrowId]?.escrow
                    ?: return@withContext Result.failure(Exception("Escrow not found"))

                val updated = current.copy(
                    fundingTxId = fundingTxId,
                    status = EscrowStatus.FUNDED
                )

                _escrowStates.update { map ->
                    map + (escrowId to EscrowState(
                        escrow = updated,
                        status = "funded",
                        progress = 0.3f
                    ))
                }

                Log.d(TAG, "Escrow funded: $escrowId (tx: $fundingTxId)")
                Result.success(updated)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update escrow funding", e)
                Result.failure(e)
            }
        }

    /**
     * Generate the pre-signed payout transaction.
     *
     * This is the critical step:
     * - Creates a Bitcoin transaction with 2 outputs (seller + fee)
     * - Buyer and seller both sign it
     * - Once signed, it can be broadcast at any time
     * - Neither party can cheat because both signatures are required
     */
    suspend fun generatePayoutTransaction(
        escrow: Escrow,
        buyerKeyBytes: ByteArray,
        sellerKeyBytes: ByteArray
    ): Result<Pair<ByteArray, ByteArray>> = withContext(Dispatchers.IO) {
        try {
            // ─── In production, this uses LDK to build the transaction ───
            // For v1 planning, the structure is:
            //
            // Input:  escrow funding UTXO (buyer's deposit)
            // Outputs:
            //   → Seller:  tradeAmountSats (100%)
            //   → Fee:     feeAmountSats (1%)
            //
            // Signed by: Buyer + Seller (2-of-3 multisig)
            //
            // The actual LDK transaction building code comes in Phase 3.

            val buyerSig = "BUYER_SIG_PLACEHOLDER".encodeToByteArray()
            val sellerSig = "SELLER_SIG_PLACEHOLDER".encodeToByteArray()

            val updated = escrow.copy(
                buyerSignature = buyerSig,
                sellerSignature = sellerSig,
                status = EscrowStatus.SIGNED
            )

            _escrowStates.update { map ->
                map + (escrow.escrowId to EscrowState(
                    escrow = updated,
                    status = "signed",
                    progress = 0.6f
                ))
            }

            Log.d(TAG, "Payout transaction pre-signed for ${escrow.escrowId}")
            Log.d(TAG, "  Seller gets: ${escrow.tradeAmountSats} sats")
            Log.d(TAG, "  Fee wallet gets: ${escrow.feeAmountSats} sats")

            Result.success(Pair(buyerSig, sellerSig))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate payout transaction", e)
            Result.failure(e)
        }
    }

    /**
     * Release funds after the seller confirms fiat payment.
     * Broadcasts the pre-signed payout transaction to the Lightning Network.
     */
    suspend fun releaseFunds(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val current = _escrowStates.value[escrowId]?.escrow
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val updated = current.copy(
                payoutTxId = "payout_${escrowId}_${System.currentTimeMillis()}",
                status = EscrowStatus.RELEASED,
                releasedAt = System.currentTimeMillis()
            )

            _escrowStates.update { map ->
                map + (escrowId to EscrowState(
                    escrow = updated,
                    status = "released",
                    progress = 1.0f
                ))
            }

            Log.d(TAG, "Funds released for $escrowId")
            Log.d(TAG, "  ${current.tradeAmountSats} sats → seller")
            Log.d(TAG, "  ${current.feeAmountSats} sats → fee wallet (${current.feeAddress})")

            Result.success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release funds", e)
            Result.failure(e)
        }
    }

    /**
     * Dispute: any party can trigger a 7-day timelock.
     * After 7 days, the full deposit is claimable.
     */
    suspend fun disputeEscrow(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val current = _escrowStates.value[escrowId]?.escrow
                ?: return@withContext Result.failure(Exception("Escrow not found"))

            val updated = current.copy(status = EscrowStatus.DISPUTED)

            _escrowStates.update { map ->
                map + (escrowId to EscrowState(
                    escrow = updated,
                    status = "disputed",
                    progress = 0.5f,
                    error = "Dispute triggered — 7-day timelock started"
                ))
            }

            Log.d(TAG, "Escrow disputed: $escrowId")
            Log.d(TAG, "  Full deposit (${current.depositAmountSats} sats) locked for 7 days")

            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the fee summary for display in the UI.
     */
    fun getFeeSummary(tradeAmountSats: Long): FeeSummary {
        val feeSats = (tradeAmountSats * NeoP2PConfig.FEE_PERCENT).toLong()
        val totalSats = tradeAmountSats + feeSats
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
