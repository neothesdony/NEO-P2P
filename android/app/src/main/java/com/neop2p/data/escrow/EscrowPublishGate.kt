package com.neop2p.data.escrow

/**
 * Pure policy for deciding whether an `escrow_status` sync should be sent.
 *
 * The storm (2026-09-15): `getEscrow()` republished on every load and the
 * LXMF failed-delivery path re-handled a message with no attempt cap, so a
 * persisted escrow with no DIRECT link produced a continuous republish loop.
 * This gate is stateless-input / state-returning so it is trivially testable
 * and cannot regress into a spin: an unchanged payload after a successful
 * send is skipped, and a failed send backs off exponentially.
 */
object EscrowPublishGate {
    enum class Reason { TRANSITION, RESUME, SWEEP, RETRY }
    enum class Decision { SEND, SKIP_UNCHANGED, SKIP_TERMINAL_DONE, SKIP_BACKOFF }

    data class State(
        val lastSignature: String? = null,
        val failures: Int = 0,
        val backoffUntilMs: Long = 0L,
        val terminalSent: Boolean = false,
    )

    const val BASE_BACKOFF_MS = 15_000L
    const val MAX_BACKOFF_MS = 300_000L
    const val MAX_FAILURES = 5

    /** Stable content fingerprint: status + all wire fields, order-independent. */
    fun signature(status: String, fields: Map<String, String>): String =
        buildString {
            append(status)
            fields.toSortedMap().forEach { (k, v) -> append('|').append(k).append('=').append(v) }
        }

    fun nextBackoffMs(failures: Int): Long {
        if (failures <= 0) return 0L
        val shift = (failures - 1).coerceIn(0, 20)
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun decide(
        state: State?,
        signature: String,
        isTerminal: Boolean,
        reason: Reason,
        nowMs: Long,
        resumeAlreadyDone: Boolean,
    ): Decision {
        if (state?.terminalSent == true) return Decision.SKIP_TERMINAL_DONE
        if (isTerminal && state?.lastSignature == signature && state.failures == 0) {
            return Decision.SKIP_TERMINAL_DONE
        }
        if (state != null && state.lastSignature == signature) {
            if (state.failures == 0) {
                // Resume-heal is allowed one publish per process even if unchanged.
                if (reason == Reason.RESUME && !resumeAlreadyDone) return Decision.SEND
                return Decision.SKIP_UNCHANGED
            }
            if (nowMs < state.backoffUntilMs) return Decision.SKIP_BACKOFF
        }
        return Decision.SEND
    }

    fun onSuccess(signature: String, isTerminal: Boolean): State =
        State(lastSignature = signature, failures = 0, backoffUntilMs = 0L, terminalSent = isTerminal)

    fun onFailure(state: State?, signature: String, isTerminal: Boolean, nowMs: Long): State {
        val failures = (state?.failures ?: 0) + 1
        if (isTerminal && failures >= MAX_FAILURES) {
            // Terminal states must not retry forever — give up after the cap.
            return State(lastSignature = signature, failures = failures, backoffUntilMs = 0L, terminalSent = true)
        }
        return State(
            lastSignature = signature,
            failures = failures,
            backoffUntilMs = nowMs + nextBackoffMs(failures),
            terminalSent = false,
        )
    }
}
