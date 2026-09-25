package com.neop2p.data.escrow

/**
 * 2026-09-26: payout miner-fee policy. The deposit already funds a payout fee,
 * but a fee-market spike could strand an otherwise-ready release. The only
 * slack that can raise the miner fee without breaking the contract (buyer gets
 * the full trade amount, fee wallet the full 0.5%) is the original network fee
 * plus any seller overpayment. The bump is capped there; it is never taken from
 * the buyer or the platform fee.
 */
object PayoutFeePolicy {

    /** RBF-signalling sequence (BIP125) so a stalled payout can be fee-bumped. */
    const val RBF_SEQUENCE: Long = 0xfffffffdL

    data class Plan(val minerFeeSats: Long, val sellerExcessSats: Long, val bumped: Boolean)

    /**
     * Returns the plan, or null when the input cannot cover the buyer + fee
     * outputs and the relay-floor miner fee.
     *
     * @param feeOutputPresent false when the 0.5% fee is sub-dust and its output is dropped.
     */
    fun plan(
        storedFeeSats: Long,
        liveFeeSats: Long,
        inputValueSats: Long,
        tradeSats: Long,
        feeAmountSats: Long,
        feeOutputPresent: Boolean,
        dustSats: Long = 546L,
        minFeeSats: Long = 250L,
    ): Plan? {
        val buyerAndFeeOut = tradeSats + if (feeOutputPresent) feeAmountSats else 0L
        val slackCap = inputValueSats - buyerAndFeeOut
        if (slackCap < minFeeSats) return null
        val target = maxOf(storedFeeSats, liveFeeSats, minFeeSats)
        val minerFee = target.coerceAtMost(slackCap)
        val excess = inputValueSats - buyerAndFeeOut - minerFee
        return Plan(minerFee, excess, minerFee > storedFeeSats)
    }
}
