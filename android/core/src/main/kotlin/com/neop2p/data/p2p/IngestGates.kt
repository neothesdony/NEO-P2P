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
     *  used to key [withinCap]. A NEW party-originated dispute must carry
     *  `opened_by` equal to the authenticated sender's peerId, otherwise the
     *  count (and the persisted row) is meaningless. */
    fun openedByIsSender(openedBy: String, fromPeerId: String): Boolean =
        openedBy.isNotBlank() && openedBy == fromPeerId

    /** F4 (2026-09-12): the sender-equality rule is scoped to NEW disputes.
     *  Existing rows are idempotent re-deliveries (router retry, 60s sweep,
     *  `healDisputePsbt` republish) and MUST always be processed even when the
     *  republisher is the counterparty rather than the original opener — the
     *  heal path that repairs a blank psbt depends on it. The cap stays
     *  meaningful because every NEW dispute is forced through [openedByIsSender]. */
    fun acceptOpenedBy(isNew: Boolean, openedBy: String, fromPeerId: String): Boolean =
        !isNew || openedByIsSender(openedBy, fromPeerId)
}

object SignalingSenderGate {
    /** The authenticated author of a signaling event, or null when the sender
     *  has no verified identity binding. The result is the ONLY value that may
     *  populate `authorPeerId` downstream — never a body field. */
    fun authorOf(verifiedForSender: Boolean, fromPeerId: String): String? =
        if (verifiedForSender && fromPeerId.isNotBlank()) fromPeerId else null
}

object OfferIngestGate {
    /** A served `offer` is ingested only when it answers a digest we requested
     *  from the SAME peer now serving it: a null digest (unsolicited offer) or
     *  a digest owned by another peer is rejected. */
    fun shouldIngest(
        offerId: String?,
        digestPresent: Boolean,
        digestPeerId: String?,
        sourcePeerId: String,
    ): Boolean = !offerId.isNullOrBlank() &&
        digestPresent &&
        !digestPeerId.isNullOrBlank() &&
        digestPeerId == sourcePeerId

    /** The offer's claimed creator must be the peer that served it. */
    fun creatorIsSource(creatorPeerId: String, sourcePeerId: String): Boolean =
        creatorPeerId.isNotBlank() && creatorPeerId == sourcePeerId
}
