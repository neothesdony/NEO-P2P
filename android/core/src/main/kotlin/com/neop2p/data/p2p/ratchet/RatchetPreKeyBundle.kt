package com.neop2p.data.p2p.ratchet

/**
 * E2EE v2 pre-key bundle (2026-09-23). Carries the sender's long-term X25519
 * identity key (IK), a fresh signed pre-key (SPK) that seeds the ratchet, and
 * the Ed25519 libp2p identity that binds both to the peerId.
 */
data class RatchetPreKeyBundle(
    val ikPub: ByteArray,
    val spkPub: ByteArray,
    val spkSignature: ByteArray,
    val identityPubKey: ByteArray,
    val identitySignature: ByteArray,
)
