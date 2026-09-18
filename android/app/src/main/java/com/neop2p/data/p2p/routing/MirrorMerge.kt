package com.neop2p.data.p2p.routing

/**
 * P7.1 partial-merge rule for mirror-ingress writes.
 *
 * A remote status/escrow/offer event may carry only a subset of the fields the
 * local row owns. A blank or absent remote value must NEVER clobber a
 * locally-owned value: the remote is merged field-by-field, not copied whole.
 *
 * This mirrors <code>remote?.takeIf { it.isNotBlank() } ?: local</code> — the
 * pattern already used across [EscrowRouter] and [OfferRouter] — as one named
 * rule so the invariant is testable and can't drift between ingress points.
 * Locally-owned fields (creator-authoritative columns, `matched_peer_id`,
 * attestations) additionally stay behind their owner guards.
 */
fun mergeRemoteField(remote: String?, local: String?): String? =
    remote?.takeIf { it.isNotBlank() } ?: local
