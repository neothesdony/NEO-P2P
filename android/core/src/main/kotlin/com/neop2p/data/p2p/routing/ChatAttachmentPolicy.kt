package com.neop2p.data.p2p.routing

/** Outbound chat-attachment size policy (mirrors the LP inbound file cap). */
object ChatAttachmentPolicy {
    /**
     * Largest plaintext attachment we will send.
     *
     * [com.neop2p.data.p2p.RnsSession.MAX_INBOUND_FILE_BYTES] gates the WRAPPED
     * wire bytes, so the inbound ceiling must leave at least
     * [ChatFileEnvelope.OVERHEAD_BYTES] of headroom for the framing + AEAD
     * overhead — see `ChatAttachmentPolicyTest` for the enforced invariant.
     */
    const val MAX_BYTES: Int = 1024 * 1024

    fun allows(size: Int): Boolean = size in 1..MAX_BYTES
}
