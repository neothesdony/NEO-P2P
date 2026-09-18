package com.neop2p.data.p2p

/** Candidate propagation node, parsed from the msgpack announce appData
 *  [legacy, timebase, isActive, perTransferLimit, perSyncLimit,
 *  [cost, flex, peering], metadata]. */
data class PropagationNodeInfo(
    val destHashHex: String,
    val isActive: Boolean,
    val hops: Int,
)

object PropagationNodeSelector {
    /** Sideband rule (core.py:80-88): first active node wins; a closer one
     *  (fewer hops) replaces it. Inactive nodes are never selected. */
    fun best(candidates: List<PropagationNodeInfo>): String? =
        candidates.filter { it.isActive }.minByOrNull { it.hops }?.destHashHex
}
