package com.neop2p.data.p2p

import network.reticulum.lxmf.LXMRouter

/**
 * Cadence + watchdog policy for pulling queued messages from the active LXMF
 * propagation node. Pure so the timing rules are unit-testable; the tick loop
 * lives in [RnsSession].
 */
object PropagationSyncPolicy {
    /** First sync after start — let announces/paths settle. */
    const val INITIAL_DELAY_MS: Long = 5 * 60_000L
    /** Loop tick; cheap, the real gate is [shouldSync]. */
    const val TICK_MS: Long = 60_000L
    /** Foreground retrieval cadence. */
    const val FOREGROUND_INTERVAL_MS: Long = 15 * 60_000L
    /** Background cadence (battery). */
    const val IDLE_INTERVAL_MS: Long = 60 * 60_000L

    fun intervalMs(idle: Boolean): Long = if (idle) IDLE_INTERVAL_MS else FOREGROUND_INTERVAL_MS

    /** Terminal/resting states may start a new transfer; anything else is in flight. */
    fun isBusy(state: LXMRouter.PropagationTransferState): Boolean = when (state) {
        LXMRouter.PropagationTransferState.IDLE,
        LXMRouter.PropagationTransferState.COMPLETE,
        LXMRouter.PropagationTransferState.FAILED,
        LXMRouter.PropagationTransferState.NO_PATH,
        LXMRouter.PropagationTransferState.NO_LINK,
        -> false

        else -> true
    }

    fun shouldSync(
        lastSyncAtMs: Long,
        nowMs: Long,
        idle: Boolean,
        state: LXMRouter.PropagationTransferState,
    ): Boolean {
        if (isBusy(state)) return false
        if (lastSyncAtMs <= 0L) return true
        return nowMs - lastSyncAtMs >= intervalMs(idle)
    }
}
