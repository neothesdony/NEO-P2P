package com.neop2p.data.p2p.ratchet

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 2026-09-24: the ratchet is a load→mutate→persist sequence. Two concurrent
 * sends to the same peer both read the same `sendCount`, derive the same
 * message key, and race the persist — violating the "a crash never reuses a
 * message key" invariant. One process-wide lock is sufficient (ratchet steps
 * are microseconds); correctness beats per-peer parallelism here.
 */
class RatchetOpLock {
    private val lock = Mutex()
    suspend fun <T> withPeer(block: suspend () -> T): T = lock.withLock { block() }
}
