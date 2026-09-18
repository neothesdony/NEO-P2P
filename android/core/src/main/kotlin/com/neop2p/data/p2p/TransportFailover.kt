package com.neop2p.data.p2p

object TransportFailover {
    /** Primary (default VPS) node is ALWAYS preferred; community nodes are
     *  backup packet-ferries, connected ONLY while the primary is offline.
     *  On primary recovery the set snaps back to primary-only (applyTransportNodes
     *  already tears down removed nodes — RnsSession.kt:458+). */
    fun desiredNodes(
        primary: Pair<String, Int>,
        community: List<Pair<String, Int>>,
        primaryOnline: Boolean,
    ): List<Pair<String, Int>> = if (primaryOnline) listOf(primary) else listOf(primary) + community
}
