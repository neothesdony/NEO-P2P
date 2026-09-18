package com.neop2p.data.p2p

/**
 * P7.4 node/transport failover state machine.
 *
 * Hardens the self-heal retry into the Cake shape: a health interval, a bounded
 * attempt count, a cooldown after exhaustion, and counters that reset on a
 * successful start. Pure and fully unit-testable.
 *
 * NOT a permanent latch: after [maxAttempts] consecutive failures the policy
 * enters a cooldown and then resets the counter, so a long-locked device still
 * recovers once its identity becomes available.
 */
class NodeFailoverPolicy(
    val maxAttempts: Int = 5,
    val cooldownMs: Long = 15_000L
) {
    data class State(
        val consecutiveFailures: Int = 0,
        val cooldownUntilMs: Long = 0L,
        val lastAttemptAtMs: Long = 0L
    )

    /** True when another start attempt may run at [nowMs]. */
    fun canAttempt(state: State, nowMs: Long): Boolean =
        nowMs >= state.cooldownUntilMs

    /** A successful start clears the counters. */
    fun onSuccess(state: State): State = State()

    /**
     * Record a failure. At [maxAttempts] consecutive failures a cooldown
     * begins; once it elapses the counter resets (bounded backoff, not a
     * permanent latch).
     */
    fun onFailure(state: State, nowMs: Long): State {
        val failures = state.consecutiveFailures + 1
        return if (failures >= maxAttempts) {
            State(consecutiveFailures = 0, cooldownUntilMs = nowMs + cooldownMs, lastAttemptAtMs = nowMs)
        } else {
            state.copy(consecutiveFailures = failures, lastAttemptAtMs = nowMs)
        }
    }
}
