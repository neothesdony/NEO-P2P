package com.neop2p.data.p2p.ratchet

/**
 * Immutable Double Ratchet session state (E2EE v2, 2026-09-23). Persisted as a
 * BLOB under SQLCipher; every ratchet step returns a new instance so a failed
 * decrypt can be discarded without corrupting the live session.
 *
 * `skipped` holds derived-but-unused message keys for out-of-order messages,
 * keyed by [skippedKey] (recvEpoch ‖ msgNum). Bounded by [MAX_SKIPPED] so a
 * hostile sender cannot exhaust memory with far-ahead message numbers.
 */
data class RatchetState(
    val rootKey: ByteArray,
    val dhSelfPriv: ByteArray,
    val dhSelfPub: ByteArray,
    val dhRemotePub: ByteArray,
    val sendChainKey: ByteArray,
    val sendCount: Long,
    val recvChainKey: ByteArray,
    val recvCount: Long,
    val prevChainLength: Long,
    val recvEpoch: Long,
    val skipped: Map<Long, ByteArray>,
    val remoteInitialPub: ByteArray,
) {
    companion object {
        const val MAX_SKIPPED = 2000
        const val REPLAY_WINDOW = 2000L
        const val MAX_SKIP_PER_CHAIN = 500L

        /** Pack (recvEpoch, msgNum) into one Long. Epochs are small in practice. */
        fun skippedKey(epoch: Long, msgNum: Long): Long = (epoch shl 32) or (msgNum and 0xFFFFFFFFL)
    }
}
