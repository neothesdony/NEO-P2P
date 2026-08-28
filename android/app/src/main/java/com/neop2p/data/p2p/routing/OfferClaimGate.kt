package com.neop2p.data.p2p.routing

/**
 * Pure decision logic for offer claims (two-taker collision handling).
 *
 * In a zero-backend relay-gossip system the RELAY delivery order is the
 * arbiter: the offer creator's device accepts the FIRST MATCHED event that
 * arrives (subsequent different-peer MATCHED events never overwrite a
 * non-blank match). Takers converge on the same rule:
 *
 *  - A MATCHED event whose `matchedPeerId` differs from my self-claim means
 *    I lost the relay race — adopt the winner so my UI shows "taken" instead
 *    of routing me into a chat for a trade that isn't mine.
 *  - Adoption only happens in CONTESTED states (OPEN/MATCHED). Once the
 *    offer is ESCROWED (or terminal), the match is settled and a stale
 *    replay must never flip it.
 *
 * Pure and JVM-testable (mirrors the EscrowRouterApplyTest style).
 */
object OfferClaimGate {

    /**
     * Effective status for a relay kind:33336 status event given the local
     * row state. Returns the status to persist, or null for no-op.
     *
     * @param localStatus    current local status (null when no row exists)
     * @param localMatched   current local matched_peer_id
     * @param remoteStatus   status carried by the relay event
     * @param remoteMatched  matched_peer_id carried by the relay event
     * @param authorPeerId   pubkey/peerId that authored the event
     * @param creatorPeerId  offer creator (the only peer allowed to unlock)
     */
    fun effectiveStatus(
        localStatus: String?,
        localMatched: String?,
        remoteStatus: String,
        remoteMatched: String?,
        authorPeerId: String?,
        creatorPeerId: String?
    ): String? = when {
        localStatus == null -> remoteStatus
        localStatus == "OPEN" -> remoteStatus
        localStatus == "CANCELLED" || localStatus == "COMPLETED" || localStatus == "DISPUTED" -> null
        // U4: a locked offer can ONLY go back to OPEN when the author is the
        // offer creator (the seller declining the match).
        (localStatus == "MATCHED" || localStatus == "ESCROWED") && remoteStatus == "OPEN" ->
            if (authorPeerId == creatorPeerId) remoteStatus else null
        // Forward-only for locked states: MATCHED→ESCROWED applies;
        // ESCROWED→MATCHED (stale replay) is rejected.
        localStatus == "MATCHED" && remoteStatus == "ESCROWED" -> remoteStatus
        // Another taker's MATCHED event for an already-matched offer: keep
        // the locked status (no downgrade) — the matched peer adoption is
        // decided separately in [adoptMatchedPeer].
        (localStatus == "MATCHED" || localStatus == "ESCROWED") && remoteStatus == "MATCHED" -> localStatus
        else -> null
    }

    /**
     * Whether the relay event's matched_peer_id should replace the local one.
     *
     * The event carries the WINNER of the relay race. Replace only in
     * contested states (OPEN/MATCHED — no escrow exists yet):
     *  1. the local match is MY OWN self-claim (I claimed locally, so a
     *     different winner's event means I lost), OR
     *  2. the local match is blank (first claim wins).
     * Never once ESCROWED/terminal — the match is settled; a stale replay
     * must not flip the buyer the escrow was built for. Never when the local
     * match belongs to a third party.
     *
     * @param myPeerId current device's peerId (the identity that self-claimed)
     */
    fun adoptMatchedPeer(
        localStatus: String?,
        localMatched: String?,
        remoteMatched: String?,
        myPeerId: String
    ): String? {
        if (remoteMatched.isNullOrBlank()) return null
        // Contested states only. ESCROWED/CANCELLED/COMPLETED/DISPUTED:
        // the match is settled — never adopt.
        if (localStatus != null && localStatus != "OPEN" && localStatus != "MATCHED") return null
        if (localMatched.isNullOrBlank()) return remoteMatched
        // I claimed this offer locally, and the relay delivered someone
        // else's MATCHED event → they won the race. Adopt the winner so my
        // UI shows "taken" instead of routing me into a lost trade's chat.
        if (localMatched == myPeerId && remoteMatched != myPeerId) return remoteMatched
        return null
    }
}
