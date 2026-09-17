package com.neop2p.data.p2p.routing

/**
 * Framing for E2EE-wrapped chat file payloads:
 * `4-byte magic ("NPF1") | 1-byte version | ciphertext`.
 * A payload without the magic is treated as legacy/plain by callers that opt in.
 */
object ChatFileEnvelope {
    private val MAGIC = byteArrayOf(0x4E, 0x50, 0x46, 0x31) // "NPF1"
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 5

    /**
     * Bytes [wrap] adds to a ciphertext: the NPF1 header + the AEAD nonce (12)
     * and Poly1305 tag (16) produced by `SignalProtocol`. Kept here so the
     * inbound cap in `RnsSession` can be proven to fit a cap-sized plaintext.
     */
    const val OVERHEAD_BYTES: Int = HEADER_SIZE + 12 + 16

    fun wrap(ciphertext: ByteArray): ByteArray =
        ByteArray(HEADER_SIZE + ciphertext.size).also { out ->
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
            out[4] = VERSION
            System.arraycopy(ciphertext, 0, out, HEADER_SIZE, ciphertext.size)
        }

    fun isWrapped(bytes: ByteArray): Boolean = unwrap(bytes) != null

    fun unwrap(bytes: ByteArray): ByteArray? {
        if (bytes.size <= HEADER_SIZE) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        if (bytes[4] != VERSION) return null
        return bytes.copyOfRange(HEADER_SIZE, bytes.size)
    }
}
