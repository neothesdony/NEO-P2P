package com.neop2p.data.p2p

object EvidenceIngestGate {
    /** Evidence is only persisted when the escrow is known here: an existing
     *  dispute row (arbitrator side) or a local escrow (party side). */
    fun shouldPersist(hasDisputeRow: Boolean, hasLocalEscrow: Boolean): Boolean =
        hasDisputeRow || hasLocalEscrow
}

object DisputeIngestGate {
    const val MAX_UNRESOLVED_PER_SENDER = 25

    /** New disputes from one sender are capped; re-deliveries of already
     *  persisted disputes are always processed (idempotency wins). */
    fun withinCap(isNew: Boolean, unresolvedFromSender: Int): Boolean =
        !isNew || unresolvedFromSender < MAX_UNRESOLVED_PER_SENDER
}
