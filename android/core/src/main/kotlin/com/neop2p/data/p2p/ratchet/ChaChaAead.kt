package com.neop2p.data.p2p.ratchet

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

/**
 * ChaCha20-Poly1305 with associated data (E2EE v2, 2026-09-23). Wire form is
 * nonce(12) ‖ ciphertext ‖ tag(16). The AAD binds the ratchet header + session
 * context, so a tampered header fails authentication.
 */
object ChaChaAead {
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private val random = SecureRandom()

    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val engine = ChaCha20Poly1305()
        engine.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(engine.getOutputSize(plaintext.size))
        val len = engine.processBytes(plaintext, 0, plaintext.size, out, 0)
        engine.doFinal(out, len)
        return nonce + out
    }

    fun open(key: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(ciphertext.size > NONCE_SIZE + TAG_SIZE) { "Ciphertext too short" }
        val nonce = ciphertext.copyOfRange(0, NONCE_SIZE)
        val body = ciphertext.copyOfRange(NONCE_SIZE, ciphertext.size)
        val engine = ChaCha20Poly1305()
        engine.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(engine.getOutputSize(body.size))
        val len = engine.processBytes(body, 0, body.size, out, 0)
        val written = engine.doFinal(out, len)
        return out.copyOf(len + written)
    }
}
