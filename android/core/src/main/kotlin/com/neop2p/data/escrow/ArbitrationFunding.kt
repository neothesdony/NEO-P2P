package com.neop2p.data.escrow

/**
 * C3 (Phase 1): the refund fee ceiling and expected input derive from the
 * value the arbitrator reads from the FUNDING OUTPUT on-chain, never the
 * dispute opener's claimed `deposit_sats` — a hostile opener could otherwise
 * understate the deposit and have the difference burned as miner fee.
 */
object ArbitrationFunding {

    fun feeCeiling(fundedInputSats: Long): Long = maxOf(fundedInputSats / 100, 5_000L)

    fun refundExpectation(
        destinationAddress: String,
        fundedInputSats: Long,
    ): ResolutionGuard.RefundExpectation = ResolutionGuard.RefundExpectation(
        destinationAddress = destinationAddress,
        fundedInputSats = fundedInputSats,
        feeCeilingSats = feeCeiling(fundedInputSats),
    )
}
