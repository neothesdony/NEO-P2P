package com.neop2p.data.escrow

import com.neop2p.domain.model.EscrowStatus

/**
 * The three escrow end states, in one place.
 *
 * Two callers must never disagree about what "finished" means:
 *  - [EscrowService.publishEscrowSync] broadcasts a terminal status once and
 *    skips the resume re-publish;
 *  - the chat composer refuses to send once the trade is over.
 *
 * Statuses are the exact upper-case strings persisted in `escrows.status`
 * ([EscrowService]'s own constants), so matching is exact — a lower-cased or
 * unknown status is NOT terminal. That is deliberately fail-open for chat:
 * an unrecognised status keeps the composer, which is the pre-existing
 * behaviour and never traps the user in a read-only thread.
 *
 * Pure and Android-free so it is unit-testable without Room or Robolectric.
 */
object EscrowStatusPolicy {

    /** RELEASED / REFUNDED / CANCELLED — no further on-chain movement is possible. */
    val TERMINAL: Set<String> = setOf("RELEASED", "REFUNDED", "CANCELLED")

    /** True when [status] is one of [TERMINAL]; null/blank/unknown are not. */
    fun isTerminal(status: String?): Boolean = status in TERMINAL
}

/**
 * C9 (Phase 1): the seller's CHECKLOCKTIMEVERIFY recovery gate. The seller may
 * spend a V1 escrow's deposit back to their attested refund address once the
 * redeem script's maturity has passed, but only while the trade is still live
 * (never after RELEASED / REFUNDED / CANCELLED) and only for a V1 escrow — a
 * null locktime is a legacy V0 script with no escape branch.
 *
 * Pure and Android-free so it is unit-testable without Room.
 */
object EscrowRecoveryPolicy {

    private val LIVE = setOf(
        EscrowStatus.FUNDED,
        EscrowStatus.PAYMENT_PENDING,
        EscrowStatus.RECEIPT_SENT,
        EscrowStatus.DISPUTED
    )

    fun canRecover(
        status: EscrowStatus,
        isSeller: Boolean,
        nowMs: Long,
        cltvLocktime: Long?
    ): Boolean =
        isSeller && cltvLocktime != null && nowMs / 1000 >= cltvLocktime && status in LIVE
}

/**
 * C-workstream (Phase 1): pre-deadline reminders, emitted at most once per
 * escrow. Pure so the timing rules are unit-testable without Room.
 *
 *  - [Reminder.FUNDING_T_15M]: the seller is in the last 15 minutes of the
 *    funding window and has not deposited.
 *  - [Reminder.PAYMENT_T_1H]: the payment window has closed (the buyer's
 *    "mark paid" hour elapsed) — the seller is now in the grace period.
 */
object DeadlineReminderPolicy {

    /** How long before the funding deadline the funding reminder fires. */
    const val FUNDING_REMINDER_MS = 15 * 60 * 1000L

    enum class Reminder { FUNDING_T_15M, PAYMENT_T_1H }

    fun due(
        status: EscrowStatus,
        elapsedMs: Long,
        fundingTimeoutMs: Long,
        paymentWindowMs: Long
    ): Reminder? = when (status) {
        EscrowStatus.FUNDING ->
            if (elapsedMs in (fundingTimeoutMs - FUNDING_REMINDER_MS) until fundingTimeoutMs) {
                Reminder.FUNDING_T_15M
            } else {
                null
            }
        EscrowStatus.PAYMENT_PENDING ->
            if (elapsedMs > paymentWindowMs) Reminder.PAYMENT_T_1H else null
        else -> null
    }
}
