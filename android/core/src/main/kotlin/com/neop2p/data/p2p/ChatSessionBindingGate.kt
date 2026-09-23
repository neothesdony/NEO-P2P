package com.neop2p.data.p2p

/**
 * Pure decision logic for binding an inbound chat pre-key bundle to a verified
 * RNS identity (Option 1, 2026-09-20).
 *
 * The chat scheme itself is static-static X25519 and trusts the first-seen key
 * (TOFU). This gate narrows that trust to identities the transport has actually
 * verified: a peerId is only usable once a signed `neop2p.identity` announce
 * proves it owns an RNS identity hash ([RnsSession.isVerifiedSender]). When the
 * pairing came from an invite carrying `#<identityHash>`, that hash must match;
 * when a session already exists, its pinned hash must match. Everything else
 * fails closed. Pure and JVM-testable (mirrors the `OfferFeedGate` style).
 */
object ChatSessionBindingGate {

    enum class Verdict {
        /** Establish (or refresh) the session. */
        ALLOW,
        /** No verified identity binding for this sender yet — hold/drop. */
        UNVERIFIED,
        /** Verified identity differs from the identity the invite pinned. */
        INVITE_MISMATCH,
        /** An established session's pinned identity changed. */
        IDENTITY_CHANGED,
    }

    fun verdict(
        verifiedForSender: Boolean,
        verifiedIdentityHash: String?,
        expectedInviteHash: String?,
        storedSessionHash: String?,
    ): Verdict {
        if (!verifiedForSender || verifiedIdentityHash.isNullOrBlank()) return Verdict.UNVERIFIED
        val verified = verifiedIdentityHash.lowercase()
        if (!expectedInviteHash.isNullOrBlank() && expectedInviteHash.lowercase() != verified) {
            return Verdict.INVITE_MISMATCH
        }
        if (!storedSessionHash.isNullOrBlank() && storedSessionHash.lowercase() != verified) {
            return Verdict.IDENTITY_CHANGED
        }
        return Verdict.ALLOW
    }
}
