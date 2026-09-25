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

/**
 * Creator-owned field (the escrow SELLER is always the creator): on the
 * creator's row the local value wins; on a mirror the creator's value is
 * adopted (blank remote never clobbers). Used for funding_script_type /
 * funding_address / script_template / cltv_locktime / seller_refund_*.
 */
fun mergeCreatorOwned(remote: String?, local: String?, localIsCreator: Boolean): String? =
    if (localIsCreator) local else mergeRemoteField(remote, local)

/**
 * Mirror-owned field (the BUYER supplies its payout address + attestation): on
 * the buyer's row the local value wins; on the creator's row the buyer's value
 * is adopted when non-blank.
 */
fun mergeMirrorOwned(remote: String?, local: String?, localIsCreator: Boolean): String? =
    if (localIsCreator) mergeRemoteField(remote, local) else local

/**
 * Adopt the remote value only when the local value is blank — never overwrite a
 * value that is already set. Used for a creator-supplied field a mirror consumes
 * once (the redeem script).
 */
fun mergeOnce(remote: String?, local: String?): String? =
    local?.takeIf { it.isNotBlank() } ?: remote?.takeIf { it.isNotBlank() }
