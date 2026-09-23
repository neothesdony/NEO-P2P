package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreKeyBundleCodecTest {

    private fun bundle() = RatchetPreKeyBundle(
        ikPub = ByteArray(32) { 1 },
        spkPub = ByteArray(32) { 2 },
        spkSignature = ByteArray(64) { 3 },
        identityPubKey = ByteArray(32) { 4 },
        identitySignature = ByteArray(64) { 5 },
    )

    @Test
    fun `encode-decode round-trips`() {
        val decoded = PreKeyBundleCodec.decode(PreKeyBundleCodec.encode(bundle()))
        assertArrayEquals(bundle().ikPub, decoded.ikPub)
        assertArrayEquals(bundle().spkPub, decoded.spkPub)
        assertArrayEquals(bundle().spkSignature, decoded.spkSignature)
        assertArrayEquals(bundle().identityPubKey, decoded.identityPubKey)
        assertArrayEquals(bundle().identitySignature, decoded.identitySignature)
    }

    @Test
    fun `a v1 bundle (no magic) is detected as legacy and refused`() {
        // Legacy bundle: registrationId(4) = 1, then deviceId, etc. No NP2R magic.
        val legacy = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1)
        assertTrue(PreKeyBundleCodec.isLegacy(legacy))
        assertThrows(PeerMustUpgradeException::class.java) { PreKeyBundleCodec.decode(legacy) }
    }

    @Test
    fun `a v2 bundle is not legacy`() {
        assertFalse(PreKeyBundleCodec.isLegacy(PreKeyBundleCodec.encode(bundle())))
    }

    @Test
    fun `decode rejects a truncated v2 bundle`() {
        val bytes = PreKeyBundleCodec.encode(bundle()).copyOf(8)
        assertThrows(IllegalArgumentException::class.java) { PreKeyBundleCodec.decode(bytes) }
    }
}
