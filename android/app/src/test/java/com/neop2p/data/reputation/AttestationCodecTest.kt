package com.neop2p.data.reputation

import com.neop2p.data.p2p.Schnorr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class AttestationCodecTest {

    private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun sign(payload: AttestationCodec.AttestationPayload, privKey: ByteArray): String {
        val data = AttestationCodec.canonicalData(
            payload.fromPeer, payload.targetPeer, payload.outcome,
            payload.volumeSats, payload.timestamp
        )
        return AttestationCodec.signatureHex(Schnorr.sign(privKey, data, SecureRandom().generateSeed(32)))
    }

    @Test
    fun `canonical data matches the legacy NEOP2P_ATTEST format`() {
        val data = AttestationCodec.canonicalData("peerA", "peerB", "POSITIVE", 100000L, 1234567890L)
        assertEquals(
            "NEOP2P_ATTEST:peerA:peerB:POSITIVE:100000:1234567890",
            String(data, Charsets.UTF_8)
        )
    }

    @Test
    fun `build and parse payload round-trips`() {
        val priv = randomKey()
        val pubHex = Schnorr.pubKey(priv).joinToString("") { "%02x".format(it) }
        val json = AttestationCodec.buildPayload(
            "peerA", "peerB", "POSITIVE", 100000L, 1234567890L, pubHex, "ab" + "cd".repeat(31)
        )
        val parsed = AttestationCodec.parsePayload(json)
        assertNotNull(parsed)
        assertEquals("peerA", parsed!!.fromPeer)
        assertEquals("peerB", parsed.targetPeer)
        assertEquals("POSITIVE", parsed.outcome)
        assertEquals(100000L, parsed.volumeSats)
        assertEquals(1234567890L, parsed.timestamp)
        assertEquals(pubHex, parsed.pubkeyHex)
    }

    @Test
    fun `parse rejects malformed json`() {
        assertNull(AttestationCodec.parsePayload("not json"))
        assertNull(AttestationCodec.parsePayload("{\"from_peer\":\"a\"}"))
    }

    @Test
    fun `verify accepts a genuine signature and rejects tampered data`() {
        val priv = randomKey()
        val pubHex = Schnorr.pubKey(priv).joinToString("") { "%02x".format(it) }
        val payload = AttestationCodec.AttestationPayload(
            "peerA", "peerB", "POSITIVE", 100000L, 1234567890L, pubHex, sign(
                AttestationCodec.AttestationPayload("peerA", "peerB", "POSITIVE", 100000L, 1234567890L, pubHex, ""), priv
            )
        )
        val data = AttestationCodec.canonicalData(payload.fromPeer, payload.targetPeer, payload.outcome, payload.volumeSats, payload.timestamp)
        assertTrue(AttestationCodec.verify(pubHex, data, payload.signatureHex))
        val tampered = AttestationCodec.canonicalData("peerA", "peerB", "NEGATIVE", payload.volumeSats, payload.timestamp)
        assertFalse(AttestationCodec.verify(pubHex, tampered, payload.signatureHex))
    }

    @Test
    fun `verify rejects a signature under a different pubkey`() {
        val priv = randomKey()
        val otherPub = Schnorr.pubKey(randomKey()).joinToString("") { "%02x".format(it) }
        val payload = AttestationCodec.AttestationPayload(
            "peerA", "peerB", "POSITIVE", 1L, 1L, otherPub, sign(
                AttestationCodec.AttestationPayload("peerA", "peerB", "POSITIVE", 1L, 1L, otherPub, ""), priv
            )
        )
        val data = AttestationCodec.canonicalData(payload.fromPeer, payload.targetPeer, payload.outcome, payload.volumeSats, payload.timestamp)
        assertFalse(AttestationCodec.verify(otherPub, data, payload.signatureHex))
    }

    @Test
    fun `validate accepts a genuine attestation from the sender`() {
        val payload = AttestationCodec.AttestationPayload("peerA", "peerB", "POSITIVE", 1L, 1L, "aa".repeat(32), "bb".repeat(64))
        assertEquals(AttestationCodec.AttestationValidation.OK, AttestationCodec.validate(payload, "peerA", null))
    }

    @Test
    fun `validate rejects a payload whose sender is not the signer`() {
        val payload = AttestationCodec.AttestationPayload("peerA", "peerB", "POSITIVE", 1L, 1L, "aa".repeat(32), "bb".repeat(64))
        assertEquals(AttestationCodec.AttestationValidation.WRONG_SENDER, AttestationCodec.validate(payload, "peerC", null))
    }

    @Test
    fun `validate rejects self rating`() {
        val payload = AttestationCodec.AttestationPayload("peerA", "peerA", "POSITIVE", 1L, 1L, "aa".repeat(32), "bb".repeat(64))
        assertEquals(AttestationCodec.AttestationValidation.SELF_RATING, AttestationCodec.validate(payload, "peerA", null))
    }

    @Test
    fun `validate rejects a pubkey that contradicts the stored one`() {
        val payload = AttestationCodec.AttestationPayload("peerA", "peerB", "POSITIVE", 1L, 1L, "aa".repeat(32), "bb".repeat(64))
        assertEquals(
            AttestationCodec.AttestationValidation.KEY_MISMATCH,
            AttestationCodec.validate(payload, "peerA", "cc".repeat(32))
        )
    }

    @Test
    fun `validate accepts when the stored pubkey matches`() {
        val payload = AttestationCodec.AttestationPayload("peerA", "peerB", "POSITIVE", 1L, 1L, "aa".repeat(32), "bb".repeat(64))
        assertEquals(AttestationCodec.AttestationValidation.OK, AttestationCodec.validate(payload, "peerA", "aa".repeat(32)))
    }
}
