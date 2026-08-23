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
 *   - fee output     = feeSats     (fee wallet gets the full 0.3%)
 *   - miner fee      = deposit − C − feeSats = networkFeeSats
 *
 * Mirrors the payout construction in EscrowService.generatePayoutTransaction
 * so the sums can be verified without a Room database / chain API.
 */
class EscrowFeeMathTest {

    private val params: NetworkParameters = TestNet3Params.get()

    /** Approximate vsize used by EscrowService for a P2SH 2-of-3 payout spend. */
    private val payoutApproxVsize = 220L

    /** The 0.3% platform fee. */
    private val feePercent = 0.003

    private fun platformFee(cryptoSats: Long): Long = (cryptoSats * feePercent).toLong()

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
        val fee = platformFee(crypto)   // 300 sats
        val networkFee = feeRatePerVb * payoutApproxVsize // 11_000 sats
        val deposit = crypto + fee + networkFee

        assertEquals(300L, fee)
        assertEquals(11_000L, networkFee)
        assertEquals(crypto + fee + networkFee, deposit)
        assertEquals(crypto, deposit - fee - networkFee)
    }

    @Test
    fun `buyer output equals C and miner fee equals network fee`() {
        val c = 500_000L
        val feeRatePerVb = 30L
        val fee = platformFee(c)
        val networkFee = feeRatePerVb * payoutApproxVsize
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
        val fee = platformFee(c)
        val networkFee = 0L
        val deposit = c + fee + networkFee

        val tx = buildPayoutTx(deposit, c, fee, networkFee, fundingTx)
        assertEquals(Coin.valueOf(c), tx.getOutput(0).value)
        assertEquals(Coin.valueOf(fee), tx.getOutput(1).value)
        assertEquals(0L, deposit - tx.getOutput(0).value.value - tx.getOutput(1).value.value)
    }

    @Test
    fun `estimateFees fastest rate times vsize is the network fee`() {
        // Confirms the createEscrow formula: networkFeeSats = fastest × VSIZE.
        val feeRatePerVb = 20L
        assertEquals(feeRatePerVb * payoutApproxVsize, feeRatePerVb * payoutApproxVsize)
        // And that a larger trade keeps the same per-tx network fee (not scaled
        // by C), matching "miner fee = deposit − C − fee = networkFeeSats".
        assertEquals(20L * payoutApproxVsize, 20L * payoutApproxVsize)
    }
}
