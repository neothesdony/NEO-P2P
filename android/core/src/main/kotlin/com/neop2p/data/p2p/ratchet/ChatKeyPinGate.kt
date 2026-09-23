package com.neop2p.data.p2p.ratchet

/**
 * Pure key-pinning decision for chat sessions (E2EE v2, 2026-09-23).
 *
 * A session pins the peer's Ed25519 identity key, long-term X25519 IK, and
 * initial ratchet key. A later handshake that presents different keys is
 * refused (`KEY_CHANGED`) so a key swap can never be adopted silently; the user
 * must explicitly re-verify (which clears the pin). Mirrors ChatSessionBindingGate.
 */
object ChatKeyPinGate {

    const val CHAT_KEY_CHANGED = "CHAT_KEY_CHANGED"

    enum class Verdict { ALLOW, KEY_CHANGED }

    fun verdict(
        storedIdentityPub: ByteArray?,
        incomingIdentityPub: ByteArray,
        storedIkPub: ByteArray?,
        incomingIkPub: ByteArray,
        storedRatchetPub: ByteArray?,
        incomingRatchetPub: ByteArray,
    ): Verdict {
        if (storedIdentityPub != null && !storedIdentityPub.contentEquals(incomingIdentityPub)) {
            return Verdict.KEY_CHANGED
        }
        if (storedIkPub != null && !storedIkPub.contentEquals(incomingIkPub)) {
            return Verdict.KEY_CHANGED
        }
        if (storedRatchetPub != null && !storedRatchetPub.contentEquals(incomingRatchetPub)) {
            return Verdict.KEY_CHANGED
        }
        return Verdict.ALLOW
    }
}
