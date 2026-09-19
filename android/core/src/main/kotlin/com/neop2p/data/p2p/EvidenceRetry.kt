package com.neop2p.data.p2p

/**
 * Polarity of the undelivered-target rule for one durable retry sweep: a target
 * is retained iff its send FAILED. Lives in `:core` beside
 * [ResolutionBroadcaster] so the two ack-gated loops cannot drift apart again —
 * the app's evidence loop shipped this rule inverted (`filter { sendOk }`), so
 * an all-fail sweep deleted the row and lost the evidence.
 */
object EvidenceRetry {

    /**
     * The targets still needing delivery after one send attempt each. An EMPTY
     * result means every target acked and the caller may delete its row.
     *
     * Suspending because LXMF sends are: [send] is invoked once per target, in
     * order, and `filterNot` is inline so no extra allocation is introduced.
     */
    suspend fun undeliveredTargets(
        targets: List<String>,
        send: suspend (String) -> Boolean,
    ): List<String> = targets.filterNot { send(it) }
}
