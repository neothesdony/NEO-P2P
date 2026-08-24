package com.neop2p.data.p2p

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.SecureRandom

/**
 * JVM round-trip test mirroring SignalProtocol.encrypt / decryptWithKey EXACTLY
 * (same Bouncy Castle classes, same framing, same HKDF) to verify whether the
 * production decrypt path truncates plaintext by 16 bytes.
 */
class ChaChaRoundTripTest {

    private val HKDF_INFO = "neop2p-chat-v1"
    private val NONCE_SIZE = 12
    private val TAG_SIZE = 16
    private val random = SecureRandom()

    private fun deriveKey(localPriv: ByteArray, theirPub: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(localPriv, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(theirPub, 0), shared, 0)

        val hmacSha256 = javax.crypto.Mac.getInstance("HmacSHA256")
        hmacSha256.init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = hmacSha256.doFinal(shared)

        hmacSha256.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
        return hmacSha256.doFinal(HKDF_INFO.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
    }

    private fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val engine = ChaCha20Poly1305()
        engine.init(true, AEADParameters(KeyParameter(key.copyOf(32)), 128, nonce))
        val out = ByteArray(engine.getOutputSize(plaintext.size))
        val len = engine.processBytes(plaintext, 0, plaintext.size, out, 0)
        engine.doFinal(out, len)
        val result = ByteArray(nonce.size + out.size)
        System.arraycopy(nonce, 0, result, 0, nonce.size)
        System.arraycopy(out, 0, result, nonce.size, out.size)
        return result
    }

    /** Verbatim copy of the FIXED SignalProtocol.decryptWithKey body. */
    private fun decryptWithKey(key: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > NONCE_SIZE + TAG_SIZE) { "Ciphertext too short" }
        val nonce = ciphertext.copyOfRange(0, NONCE_SIZE)
        val body = ciphertext.copyOfRange(NONCE_SIZE, ciphertext.size)
        val engine = ChaCha20Poly1305()
        engine.init(false, AEADParameters(KeyParameter(key.copyOf(32)), 128, nonce))
        val out = ByteArray(engine.getOutputSize(body.size))
        val len = engine.processBytes(body, 0, body.size, out, 0)
        // doFinal() verifies the tag and writes exactly the plaintext bytes.
        val written = engine.doFinal(out, len)
        return out.copyOf(written)
    }

    @Test
    fun decryptWithKey_roundTrip_preservesPlaintext() {
        val aPriv = ByteArray(32).also { random.nextBytes(it) }
        val bPriv = ByteArray(32).also { random.nextBytes(it) }
        val bPub = X25519PrivateKeyParameters(bPriv, 0).generatePublicKey().encoded

        val keyA = deriveKey(aPriv, bPub) // sender's view
        val plaintext = "halo, ini pesan rahasia untuk dagang bitcoin 1234567890".toByteArray(Charsets.UTF_8)

        val ciphertext = encrypt(keyA, plaintext)
        val decrypted = decryptWithKey(keyA, ciphertext)

        assertArrayEquals("decryptWithKey must return the full plaintext", plaintext, decrypted)
    }
}
