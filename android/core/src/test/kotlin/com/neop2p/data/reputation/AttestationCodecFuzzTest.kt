package com.neop2p.data.reputation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AttestationCodecFuzzTest {

    private val rng = Random(0xA77E57L)

    @Test
    fun `parsePayload never throws on random bytes`() {
        repeat(5_000) {
            val bytes = ByteArray(rng.nextInt(256)).also(rng::nextBytes)
            AttestationCodec.parsePayload(bytes.toString(Charsets.ISO_8859_1))
        }
    }

    @Test
    fun `parsePayload round-trips a well-formed payload`() {
        val json = AttestationCodec.buildPayload(
            fromPeer = "peerA", targetPeer = "peerB", outcome = "POSITIVE",
            volumeSats = 1_000L, timestamp = 42L,
            pubkeyHex = "ab".repeat(32), signatureHex = "cd".repeat(64)
        )
        val payload = AttestationCodec.parsePayload(json)
        assertNotNull(payload)
        assertTrue(AttestationCodec.validate(payload!!, senderPeerId = "peerA", storedPubkeyHex = null)
            == AttestationCodec.AttestationValidation.OK)
    }

    @Test
    fun `verify rejects malformed lengths without throwing`() {
        assertFalse(AttestationCodec.verify("ab", ByteArray(1), "cd"))
        assertFalse(AttestationCodec.verify("zz".repeat(32), ByteArray(1), "cd".repeat(64)))
    }
}
