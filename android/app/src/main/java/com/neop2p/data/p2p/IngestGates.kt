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

    /** F4 (2026-09-12): `opened_by` is attacker-controlled on the wire and is
     *  used to key [withinCap]. A party-originated dispute must carry
     *  `opened_by` equal to the authenticated sender's peerId, otherwise the
     *  count (and the persisted row) is meaningless. */
    fun openedByIsSender(openedBy: String, fromPeerId: String): Boolean =
        openedBy.isNotBlank() && openedBy == fromPeerId
}
