package com.neop2p.data.p2p

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class IdentityManagerTest {

    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun `mnemonic produces deterministic peer ID`() {
        val seed = mnemonicToSeed(testMnemonic)
        val peerId1 = KeyDerivation.deriveLibp2pPeerId(seed, "m/44'/888'/0'/0/0")
        val peerId2 = KeyDerivation.deriveLibp2pPeerId(seed, "m/44'/888'/0'/0/0")
        assertEquals(peerId1, peerId2)
        assertTrue(peerId1.isNotEmpty())
    }

    @Test
    fun `mnemonic produces deterministic Nostr pubkey`() {
        val seed = mnemonicToSeed(testMnemonic)
        val nostrPrivKey = KeyDerivation.deriveSecp256k1(seed, "m/44'/1237'/0'/0/0")
        val nostrPubKey = KeyDerivation.secp256k1XOnlyPubKey(nostrPrivKey)
        assertEquals(32, nostrPubKey.size)
    }

    @Test
    fun `different mnemonics produce different keys`() {
        val seed1 = mnemonicToSeed(testMnemonic)
        val seed2 = mnemonicToSeed(listOf(
            "zoo", "zoo", "zoo", "zoo", "zoo", "zoo",
            "zoo", "zoo", "zoo", "zoo", "zoo", "wrong"
        ))
        val key1 = KeyDerivation.deriveSecp256k1(seed1, "m/44'/1237'/0'/0/0")
        val key2 = KeyDerivation.deriveSecp256k1(seed2, "m/44'/1237'/0'/0/0")
        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `libp2p and Nostr keys are different from same seed`() {
        val seed = mnemonicToSeed(testMnemonic)
        val libp2pKey = KeyDerivation.deriveEd25519(seed, "m/44'/888'/0'/0/0")
        val nostrKey = KeyDerivation.deriveSecp256k1(seed, "m/44'/1237'/0'/0/0")
        assertFalse(libp2pKey.contentEquals(nostrKey))
    }

    @Test
    fun `same mnemonic produces same keys deterministically`() {
        val seed1 = mnemonicToSeed(testMnemonic)
        val seed2 = mnemonicToSeed(testMnemonic)
        val key1 = KeyDerivation.deriveSecp256k1(seed1, "m/44'/1237'/0'/0/0")
        val key2 = KeyDerivation.deriveSecp256k1(seed2, "m/44'/1237'/0'/0/0")
        assertTrue(key1.contentEquals(key2))
    }

    @Test
    fun `derived peer id is a valid libp2p peer id`() {
        val seed = mnemonicToSeed(testMnemonic)
        val peerId = KeyDerivation.deriveLibp2pPeerId(seed, "m/44'/888'/0'/0/0")
        // Must round-trip through libp2p's own parser (rejects fabricated strings)
        val parsed = io.libp2p.core.PeerId.fromBase58(peerId)
        assertEquals(peerId, parsed.toBase58())
    }

    // ─── Test helpers ────────────────────────────────────────────

    private fun mnemonicToSeed(words: List<String>, passphrase: String = ""): ByteArray {
        val mnemonic = words.joinToString(" ")
        val salt = ("mnemonic$passphrase").toByteArray(Charsets.UTF_8)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = javax.crypto.spec.PBEKeySpec(mnemonic.toCharArray(), salt, 2048, 512)
        return factory.generateSecret(spec).encoded
    }
}
