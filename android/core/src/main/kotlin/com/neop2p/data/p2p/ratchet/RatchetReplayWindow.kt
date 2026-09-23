package com.neop2p.data.p2p.ratchet

/**
 * Pure replay-window decision policy (E2EE v2, 2026-09-23).
 *
 * The persisted `recvCount` plus the bounded `skipped` store ARE the replay
 * window: an in-order message must equal `recvCount`; a stale one is accepted
 * only if its message key was pre-derived into `skipped` (then removed on use);
 * anything else is a duplicate/replay/out-of-window and is rejected. Bounds
 * keep a hostile sender from forcing unbounded key derivation or storage.
 */
object RatchetReplayWindow {

    sealed interface Decision {
        data class InOrder(val msgNum: Long) : Decision
        data class SkipAhead(val msgNum: Long, val skipFrom: Long) : Decision
        data class FromSkipped(val msgNum: Long) : Decision
        data class Reject(val reason: String) : Decision
    }

    fun decide(recvCount: Long, msgNum: Long, hasSkippedKey: Boolean, skippedSize: Int): Decision {
        if (msgNum < 0) return Decision.Reject("negative_message_number")
        if (hasSkippedKey) return Decision.FromSkipped(msgNum)
        if (msgNum < recvCount) {
            return if (recvCount - msgNum > RatchetState.REPLAY_WINDOW) {
                Decision.Reject("out_of_window")
            } else {
                Decision.Reject("stale_or_duplicate")
            }
        }
        if (msgNum == recvCount) return Decision.InOrder(msgNum)
        val gap = msgNum - recvCount
        if (gap > RatchetState.MAX_SKIP_PER_CHAIN) return Decision.Reject("too_far_ahead")
        if (skippedSize + gap > RatchetState.MAX_SKIPPED) return Decision.Reject("skipped_overflow")
        return Decision.SkipAhead(msgNum, recvCount)
    }
}
