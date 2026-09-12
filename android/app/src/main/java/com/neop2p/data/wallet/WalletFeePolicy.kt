package com.neop2p.data.wallet

/**
 * Pure fee policy for wallet sends (audit P1-2 / P3-1, 2026-09-12).
 *
 * Two rules, both enforced locally so a hostile or broken explorer cannot move
 * money after the user has confirmed:
 *
 *  1. The explorer-reported rate is CLAMPED at [MAX_FEE_RATE_SAT_VB] before it
 *     reaches the fee math (`ChainMonitor.estimateFees`).
 *  2. The fee shown in the confirm dialog is a CEILING. `WalletService.send`
 *     rejects a freshly computed fee above the confirmed value and asks the
 *     user to confirm again, instead of broadcasting a surprise fee.
 *
 * The 5% cap is a sanity ceiling, not a target: it bounds the blast radius of
 * an absurd rate on a large send, where the absolute ceiling alone is not
 * enough (500 sat/vB x 150 vB = 75_000 sats, which is 75% of a 100k-sat send).
 */
object WalletFeePolicy {
    /** Absolute ceiling on the explorer-reported rate, in sat/vB. */
    const val MAX_FEE_RATE_SAT_VB: Long = 500L

    /** A send may never pay more than this share of the amount sent (percent). */
    const val MAX_FEE_PERCENT_OF_AMOUNT: Long = 5L

    /** Absolute backstop, applied on top of the percentage cap. */
    const val MAX_SANE_FEE_SATS: Long = 5_000_000L

    /**
     * Fee floor for a wallet send. Mirrors WalletService.MIN_WALLET_FEE_SATS,
     * which stays where it is (it is also referenced by the send path itself).
     */
    const val MIN_SEND_FEE_SATS: Long = 250L

    /** Clamp a rate into [1, MAX_FEE_RATE_SAT_VB]. A 0 rate is never usable. */
    fun clampRate(rateSatVb: Long): Long = when {
        rateSatVb < 1L -> 1L
        rateSatVb > MAX_FEE_RATE_SAT_VB -> MAX_FEE_RATE_SAT_VB
        else -> rateSatVb
    }

    /**
     * Largest fee this send may pay. [amountSats] is bounded by the real
     * balance (max 21e14 sats), so `amountSats * 5` cannot overflow a Long.
     */
    fun maxFeeSats(amountSats: Long): Long {
        val percent = if (amountSats > 0L) amountSats * MAX_FEE_PERCENT_OF_AMOUNT / 100L else 0L
        return maxOf(MIN_SEND_FEE_SATS, percent).coerceAtMost(MAX_SANE_FEE_SATS)
    }

    /**
     * null when [feeSats] is acceptable for [amountSats]; otherwise a
     * user-facing reason. [confirmedMaxFeeSats] is the fee shown in the
     * confirm dialog (null when the preview failed).
     */
    fun rejectReason(
        feeSats: Long,
        amountSats: Long,
        confirmedMaxFeeSats: Long?
    ): String? = when {
        feeSats < 0L -> "Invalid network fee ($feeSats sats)"
        confirmedMaxFeeSats != null && feeSats > confirmedMaxFeeSats ->
            "Network fee changed (confirmed $confirmedMaxFeeSats sats, now $feeSats sats) — " +
                "confirm the send again"
        feeSats > maxFeeSats(amountSats) ->
            "Network fee $feeSats sats exceeds the ${MAX_FEE_PERCENT_OF_AMOUNT}% cap for this send"
        else -> null
    }

    /**
     * The fee actually paid when the change output is dropped as dust: the
     * remainder stays in the tx, so the miner collects it. [changeSats] of 0
     * means there was no change at all.
     */
    fun effectiveFeeSats(computedFeeSats: Long, changeSats: Long, dustThresholdSats: Long): Long =
        if (changeSats > 0L && changeSats <= dustThresholdSats) computedFeeSats + changeSats
        else computedFeeSats
}
