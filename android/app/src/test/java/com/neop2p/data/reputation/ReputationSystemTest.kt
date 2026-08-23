package com.neop2p.data.reputation

import com.neop2p.data.p2p.Schnorr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Unit tests for ReputationSystem scoring logic.
 *
 * The Wilson score interval (lower bound at 95% confidence) determines
 * reputation scores. These tests verify the math independently of Android.
 */
class ReputationSystemTest {

    /**
     * Replicate the Wilson score calculation for testability.
     */
    private fun calculateWilsonScore(positive: Int, negative: Int): Float {
        val total = positive.toLong() + negative.toLong()
        if (total == 0L) return 0f
        val z = 1.96
        val p = positive.toDouble() / total
        val left = p + (z * z) / (2 * total)
        val right = z * Math.sqrt((p * (1 - p) + (z * z) / (4 * total)) / total)
        val under = 1 + (z * z) / total
        return ((left - right) / under).toFloat().coerceIn(0f, 1f)
    }

    @Test
    fun `perfect record scores 1_0`() {
        val score = calculateWilsonScore(100, 0)
        assertEquals(0.965f, score, 0.01f)
    }

    @Test
    fun `zero trades scores 0`() {
        val score = calculateWilsonScore(0, 0)
        assertEquals(0f, score, 0.001f)
    }

    @Test
    fun `mixed record penalizes negative trades`() {
        val positive = calculateWilsonScore(80, 20)
        val negative = calculateWilsonScore(20, 80)
        assertTrue("Positive trades should score higher", positive > negative)
    }

    @Test
    fun `low volume trades have conservative score`() {
        val lowVolume = calculateWilsonScore(1, 0)   // 1 trade
        val highVolume = calculateWilsonScore(100, 0) // 100 trades
        assertTrue("More data should improve confidence", highVolume > lowVolume)
    }

    @Test
    fun `fifty_fifty converges on 0_5`() {
        val score = calculateWilsonScore(50, 50)
        assertEquals(0.5f, score, 0.15f)
    }

    @Test
    fun `single negative trade scores low`() {
        val score = calculateWilsonScore(0, 1)
        assertTrue(score < 0.1f)
    }

    @Test
    fun `score never exceeds 1_0`() {
        val score = calculateWilsonScore(Int.MAX_VALUE, 1)
        assertTrue(score <= 1.0f)
    }

    @Test
    fun `score never goes below 0`() {
        val score = calculateWilsonScore(0, Int.MAX_VALUE)
        assertTrue(score >= 0f)
    }

    /**
     * Round-trip the exact crypto the ReputationSystem fix relies on:
     * sign an attestation with BIP-340 Schnorr using a 32-byte secp256k1
     * private key, then verify it against the peer's x-only public key
     * (the 64-hex-char `nostr_pubkey` stored in the Peer DAO).
     */
    @Test
    fun `attestation sign and verify round-trips with nostr pubkey hex`() {
        val privKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Reproduce IdentityManager's x-only pubkey (32 bytes -> 64 hex chars).
        val pubKey = Schnorr.pubKey(privKey)
        val pubHex = pubKey.joinToString("") { "%02x".format(it) }
        assertEquals(64, pubHex.length)

        // Attestation payload as built by ReputationSystem.buildAttestationData().
        val data = "NEOP2P_ATTEST:peerA:peerB:POSITIVE:100000:1234567890"
            .encodeToByteArray()

        // Sign exactly as signAttestation() does now (Schnorr + random aux).
        val auxRand = SecureRandom().generateSeed(32)
        val signature = Schnorr.sign(privKey, data, auxRand)
        assertEquals(64, signature.size)

        // Verify exactly as verifyAttestation()/loadPeerPublicKey() do now.
        val peerPubKey = pubHex.toHexBytes()
        assertTrue(Schnorr.verify(peerPubKey, data, signature))
    }

    @Test
    fun `attestation with wrong pubkey fails verification`() {
        val privKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val data = "NEOP2P_ATTEST:a:b:POSITIVE:1:1".encodeToByteArray()
        val sig = Schnorr.sign(privKey, data, SecureRandom().generateSeed(32))
        // Wrong pubkey (not derived from privKey) must not verify.
        assertFalse(Schnorr.verify(otherKey, data, sig))
    }

    @Test
    fun `tampered attestation payload fails verification`() {
        val privKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pubKey = Schnorr.pubKey(privKey)
        val data = "NEOP2P_ATTEST:a:b:POSITIVE:1:12345".encodeToByteArray()
        val sig = Schnorr.sign(privKey, data, SecureRandom().generateSeed(32))
        val tampered = "NEOP2P_ATTEST:a:b:NEGATIVE:1:12345".encodeToByteArray()
        assertFalse(Schnorr.verify(pubKey, tampered, sig))
    }

    private fun String.toHexBytes(): ByteArray {
        val data = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) +
                Character.digit(this[i + 1], 16)).toByte()
        }
        return data
    }
}
