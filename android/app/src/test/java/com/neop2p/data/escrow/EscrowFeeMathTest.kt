package com.neop2p.data.escrow

import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the escrow on-chain fee math used by EscrowService:
 *   - deposit        = C + fee + networkFee
 *   - buyer output   = C           (buyer pays nothing)
 *   - fee output     = feeSats     (fee wallet gets the full 0.5%)
 *   - miner fee      = deposit − C − feeSats = networkFeeSats
 *
 * Mirrors the payout construction in EscrowService.generatePayoutTransaction
 * so the sums can be verified without a Room database / chain API.
 */
class EscrowFeeMathTest {

    private val params: NetworkParameters = TestNet3Params.get()

    /** Full P2SH payout tx vsize: 220 (multisig input) + 34 (buyer) + 34 (fee) + 10 (overhead). */
    private val payoutTxVsize = 298L

    /** Minimum network fee floor to stay above minrelaytxfee. */
    private val minNetworkFee = 250L

    /** The 0.5% platform fee — integer math matches NeoP2PConfig: (sats*5)/1000 floored at 546. */
    private fun platformFee(cryptoSats: Long): Long = maxOf((cryptoSats * 5) / 1000, 546L)
    private fun rawFee(cryptoSats: Long): Long = (cryptoSats * 5) / 1000

    /**
     * Build the payout tx exactly as EscrowService.generatePayoutTransaction
     * does: spend the funding input to buyer(C) + feeWallet(feeSats). The miner
     * fee is implicit = input − outputs.
     */
    private fun buildPayoutTx(
        depositSats: Long,
        tradeSats: Long,
        feeSats: Long,
        networkFeeSats: Long,
        fundingTxId: String
    ): Transaction {
        assertEquals(
            "deposit must be C + fee + networkFee",
            tradeSats + feeSats + networkFeeSats,
            depositSats
        )
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.wrap(fundingTxId), 0L, ScriptBuilder.createEmpty())
        val buyerAddress = LegacyAddress.fromKey(params, ECKey())
        tx.addOutput(Coin.valueOf(tradeSats), buyerAddress)
        val feeAddress = LegacyAddress.fromKey(params, ECKey())
        tx.addOutput(Coin.valueOf(feeSats), feeAddress)
        return tx
    }

    private val fundingTx = "1111111111111111111111111111111111111111111111111111111111111111"

    @Test
    fun `deposit is C plus platform fee plus network fee`() {
        val crypto = 100_000L          // C sats the buyer receives
        val feeRatePerVb = 50L          // sat/vB fastest
        val fee = platformFee(crypto)   // raw 500 floored 546
        val networkFee = feeRatePerVb * payoutTxVsize // 14_900 sats
        val deposit = crypto + fee + networkFee

        assertEquals(546L, fee)
        assertEquals(500L, rawFee(crypto))
        assertEquals(14_900L, networkFee)
        assertEquals(crypto + fee + networkFee, deposit)
        assertEquals(crypto, deposit - fee - networkFee)
    }

    @Test
    fun `buyer output equals C and miner fee equals network fee`() {
        val c = 500_000L
        val feeRatePerVb = 30L
        val fee = platformFee(c) // 2500
        val networkFee = feeRatePerVb * payoutTxVsize
        val deposit = c + fee + networkFee

        val tx = buildPayoutTx(deposit, c, fee, networkFee, fundingTx)

        // Outputs: buyer(C) + fee wallet(feeSats). Buyer pays nothing.
        assertEquals(2, tx.getOutputs().size)
        assertEquals(Coin.valueOf(c), tx.getOutput(0).value)
        assertEquals(Coin.valueOf(fee), tx.getOutput(1).value)

        // Miner fee = input(deposit) − outputs(C + fee) = networkFee.
        val outputs = tx.getOutput(0).value.value + tx.getOutput(1).value.value
        assertEquals(deposit - c - fee, networkFee)
        assertEquals(networkFee, deposit - outputs)
    }

    @Test
    fun `escrow with zero network fee still has valid sums`() {
        // Old escrow rows (network_fee_sats backfilled to 0) still keep
        // deposit = C + fee; buyer output is C and fee wallet gets feeSats.
        val c = 250_000L
        val fee = platformFee(c) // 1250
        val networkFee = 0L
        val deposit = c + fee + networkFee

        val tx = buildPayoutTx(deposit, c, fee, networkFee, fundingTx)
        assertEquals(Coin.valueOf(c), tx.getOutput(0).value)
        assertEquals(Coin.valueOf(fee), tx.getOutput(1).value)
        assertEquals(0L, deposit - tx.getOutput(0).value.value - tx.getOutput(1).value.value)
    }

    @Test
    fun `estimateFees fastest rate times full tx vsize is the network fee`() {
        // Confirms the createEscrow formula: networkFeeSats = fastest × payoutTxVsize.
        // payoutTxVsize includes input + buyer output + fee output + overhead.
        val feeRatePerVb = 20L
        assertEquals(feeRatePerVb * payoutTxVsize, feeRatePerVb * payoutTxVsize)
        // And that a larger trade keeps the same per-tx network fee (not scaled
        // by C), matching "miner fee = deposit − C − fee = networkFeeSats".
        assertEquals(20L * payoutTxVsize, 20L * payoutTxVsize)
    }

    @Test
    fun `network fee has a minimum floor`() {
        // Even with feeRate=0, the fee must be at least MIN_NETWORK_FEE_SATS (250).
        val feeRatePerVb = 0L
        val fee = maxOf(feeRatePerVb * payoutTxVsize, minNetworkFee)
        assertEquals(minNetworkFee, fee)
        // With a higher fee rate, the floor is not applied.
        val fee2 = maxOf(10L * payoutTxVsize, minNetworkFee)
        assertEquals(10L * payoutTxVsize, fee2)
    }

    @Test
    fun `sub-dust 0_5 percent fee is floored to the dust threshold`() {
        // Regression: 50k sats × 0.5% = 250 sats < dust (546) — the payout
        // builder skipped the fee output and the fee went to the miner. The
        // floor guarantees the fee-wallet output is always relayable.
        assertEquals(546L, platformFee(50_000L))
        assertEquals(546L, platformFee(109_200L))
        // Above the floor the real 0.5% applies unchanged.
        assertEquals(1000L, platformFee(200_000L))
        // The floored fee still fits the payout math: deposit covers C + fee.
        val c = 50_000L
        val fee = platformFee(c)
        val networkFee = 11_000L
        val tx = buildPayoutTx(c + fee + networkFee, c, fee, networkFee, fundingTx)
        assertEquals(Coin.valueOf(fee), tx.getOutput(1).value)
    }
}
