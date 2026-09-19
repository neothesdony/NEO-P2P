package com.neop2p.data.p2p

import com.neop2p.NeoP2PConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityDerivationTest {

    private val abandonAbout = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    private val zooWrong = listOf(
        "zoo", "zoo", "zoo", "zoo", "zoo", "zoo",
        "zoo", "zoo", "zoo", "zoo", "zoo", "wrong"
    )

    private fun seed(words: List<String>): ByteArray = Bip39.mnemonicToSeed(words)

    @Test
    fun `derive is deterministic and yields a 32-byte x-only nostr pubkey`() {
        val a = IdentityDerivation.derive(seed(abandonAbout))
        val b = IdentityDerivation.derive(seed(abandonAbout))

        assertTrue(a.peerId.isNotEmpty())
        assertEquals(64, a.nostrPubkeyHex.length)
        assertEquals(64, a.nostrPrivateKeyHex.length)
        assertEquals(a.peerId, b.peerId)
        assertEquals(a.nostrPubkeyHex, b.nostrPubkeyHex)
        assertEquals(a.nostrPrivateKeyHex, b.nostrPrivateKeyHex)
        assertTrue(a.libp2pPrivateKey.contentEquals(b.libp2pPrivateKey))
        assertTrue(a.signalPrivateKey.contentEquals(b.signalPrivateKey))
    }

    @Test
    fun `different mnemonics derive a different peer id`() {
        val a = IdentityDerivation.derive(seed(abandonAbout))
        val b = IdentityDerivation.derive(seed(zooWrong))
        assertNotEquals(a.peerId, b.peerId)
        assertNotEquals(a.nostrPrivateKeyHex, b.nostrPrivateKeyHex)
    }

    @Test
    fun `arbitrator key is parity-normalized to even y`() {
        val s = seed(abandonAbout)
        val priv = IdentityDerivation.arbitratorPrivEven(s)
        assertEquals(32, priv.size)
        assertEquals(0x02.toByte(), KeyDerivation.secp256k1CompressedPubKey(priv)[0])

        // Parity normalization preserves x, so the x-only pubkey is unchanged.
        assertEquals(
            IdentityDerivation.bytesToHex(KeyDerivation.secp256k1XOnlyPubKey(priv)),
            IdentityDerivation.arbitratorPubKeyHex(s)
        )
    }

    @Test
    fun `arbitrator keys are stable and distinct from identity keys`() {
        val s = seed(abandonAbout)
        assertEquals(IdentityDerivation.arbitratorPrivateKeyHex(s), IdentityDerivation.arbitratorPrivateKeyHex(s))
        assertNotEquals(
            IdentityDerivation.arbitratorPrivateKeyHex(s),
            IdentityDerivation.bytesToHex(KeyDerivation.deriveSecp256k1(s, IdentityDerivation.PATH_NOSTR))
        )
        assertNotEquals(
            IdentityDerivation.arbitratorPrivateKeyHex(s),
            IdentityDerivation.bytesToHex(KeyDerivation.deriveSecp256k1(s, IdentityDerivation.PATH_BITCOIN))
        )
    }

    @Test
    fun `sanitizeNickname strips control characters and caps length`() {
        assertEquals("Alice", IdentityDerivation.sanitizeNickname(" Alice\n\r"))
        assertEquals("", IdentityDerivation.sanitizeNickname("\u0000\u0001\u0002"))
        assertEquals(
            NeoP2PConfig.MAX_NICKNAME_LENGTH,
            IdentityDerivation.sanitizeNickname("a".repeat(200)).length
        )
    }

    @Test
    fun `trade keys rotate per index and differ from the identity key`() {
        val s = seed(abandonAbout)
        val (_, priv1) = IdentityDerivation.tradeNostrKeyPair(s, 1)
        val (_, priv2) = IdentityDerivation.tradeNostrKeyPair(s, 2)
        assertNotEquals(priv1, priv2)
        assertNotEquals(IdentityDerivation.derive(s).nostrPrivateKeyHex, priv1)
    }

    @Test
    fun `bytesToHex renders lowercase two-digit bytes`() {
        assertEquals("00ff10", IdentityDerivation.bytesToHex(byteArrayOf(0x00, 0xFF.toByte(), 0x10)))
        assertFalse(IdentityDerivation.bytesToHex(byteArrayOf(0xAB.toByte())).contains("AB"))
    }
}
