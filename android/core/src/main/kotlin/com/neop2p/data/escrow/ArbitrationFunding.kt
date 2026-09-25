package com.neop2p.data.escrow

import com.neop2p.data.wallet.WalletFeePolicy
import com.neop2p.domain.model.BitcoinAddressType

/**
 * C3 (Phase 1): the refund fee ceiling and expected input derive from the
 * value the arbitrator reads from the FUNDING OUTPUT on-chain, never the
 * dispute opener's claimed `deposit_sats` — a hostile opener could otherwise
 * understate the deposit and have the difference burned as miner fee.
 *
 * A-2 (2026-09-25): the ceiling is now RATE-AWARE. The old
 * `max(funded/100, 5000)` could sit below a real refund fee at normal rates
 * (EscrowService built at rate×264 while the ceiling was 1% of the deposit),
 * so the arbitrator refused a legitimate refund. The callers pass their OWN
 * live fee estimate and script type; the opener's claim is never trusted.
 */
object ArbitrationFunding {

    /** Relay floor; mirrors `EscrowService.MIN_NETWORK_FEE_SATS`. */
    const val MIN_NETWORK_FEE_SATS: Long = 250L

    /**
     * Allow a legitimate fee up to this multiple of (rate × vsize), tolerating
     * a party/arbitrator estimate skew without letting the ceiling explode.
     */
    const val CEILING_SAFETY_MULTIPLIER: Long = 4L

    /** Hard anti-burn cap: never allow more than 1/4 of the deposit as fee. */
    const val CEILING_BURN_CAP_DEN: Long = 4L

    /** Full refund tx vsize: multisig spend + one legacy output (upper bound) + overhead. */
    fun refundVsize(scriptType: BitcoinAddressType): Long =
        scriptType.spendVsize + BitcoinAddressType.LEGACY.outputVsize + BitcoinAddressType.FIXED_OVERHEAD_VSIZE

    /** `fundingScriptType` strings ("SEGWIT"/"LEGACY"/null/"P2WSH") -> address type; unknown = legacy. */
    fun scriptTypeOf(raw: String?): BitcoinAddressType =
        if (raw?.equals("SEGWIT", ignoreCase = true) == true) BitcoinAddressType.SEGWIT
        else BitcoinAddressType.LEGACY

    /**
     * Rate-aware refund fee ceiling. `feeRateSatVb` MUST be the caller's own
     * estimate (arbitrator: its own ChainMonitor; party: its own ChainMonitor).
     * The rate is clamped to [WalletFeePolicy.MAX_FEE_RATE_SAT_VB] and the whole
     * ceiling is capped at [CEILING_BURN_CAP_DEN] of the deposit.
     */
    fun feeCeiling(
        fundedInputSats: Long,
        feeRateSatVb: Long,
        scriptType: BitcoinAddressType,
    ): Long {
        val rateFee = feeRateSatVb.coerceIn(0L, WalletFeePolicy.MAX_FEE_RATE_SAT_VB) *
            refundVsize(scriptType) * CEILING_SAFETY_MULTIPLIER
        val burnCap = (fundedInputSats / CEILING_BURN_CAP_DEN).coerceAtLeast(MIN_NETWORK_FEE_SATS)
        return maxOf(MIN_NETWORK_FEE_SATS, minOf(rateFee, burnCap))
    }

    fun refundExpectation(
        destinationAddress: String,
        fundedInputSats: Long,
        feeRateSatVb: Long,
        scriptType: BitcoinAddressType,
    ): ResolutionGuard.RefundExpectation = ResolutionGuard.RefundExpectation(
        destinationAddress = destinationAddress,
        fundedInputSats = fundedInputSats,
        feeCeilingSats = feeCeiling(fundedInputSats, feeRateSatVb, scriptType),
    )

    /**
     * A-2b (2026-09-25): the expectation a party applies an ALREADY-SIGNED
     * refund with. The fee was frozen at build time and gated by the
     * arbitrator's own live rate at pre-sign, so re-deriving the ceiling from a
     * fresh local rate here could refuse a legitimately-signed resolution after
     * a >4x rate drop. Use the most permissive legitimate bound (the wallet
     * clamp) — the burn cap and the destination gate remain load-bearing.
     */
    fun applySideRefundExpectation(
        destinationAddress: String,
        fundedInputSats: Long,
        scriptType: BitcoinAddressType,
    ): ResolutionGuard.RefundExpectation =
        refundExpectation(destinationAddress, fundedInputSats, WalletFeePolicy.MAX_FEE_RATE_SAT_VB, scriptType)
}
