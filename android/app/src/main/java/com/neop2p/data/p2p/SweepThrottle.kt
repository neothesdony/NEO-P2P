package com.neop2p.data.p2p

/**
 * P7.3 sweep re-send throttle + terminal-state set.
 *
 * The 60s sweep re-scans every entity; a reminder/re-publish must not fire on
 * every pass. The in-memory dedup in `EscrowService.emitOnce` dies on process
 * death, so the gap is exactly the persisted timestamp consulted here.
 * Pure so the decision is unit-testable.
 */
object SweepThrottle {

    /** Default suppression window for a reminted reminder. */
    const val DEFAULT_WINDOW_MS: Long = 5 * 60_000L

    /** True when [key] has never emitted or the window has elapsed. */
    fun shouldEmit(
        lastEmittedAtMs: Long?,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS
    ): Boolean = lastEmittedAtMs == null || nowMs - lastEmittedAtMs >= windowMs

    /** A terminal entity is never polled/re-sent again. */
    fun isTerminal(status: String, terminal: Set<String>): Boolean = status in terminal
}
