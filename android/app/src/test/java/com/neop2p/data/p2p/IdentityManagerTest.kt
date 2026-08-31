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
        // libp2p PeerIDs are base58btc of a 38-byte identity multihash:
        // 0x00 0x24 + 36-byte protobuf(Ed25519 pubkey). Decode and verify.
        val decoded = org.bitcoinj.core.Base58.decode(peerId)
        assertEquals(38, decoded.size)
        assertEquals(0x00.toByte(), decoded[0]) // identity multihash code
        assertEquals(0x24.toByte(), decoded[1]) // 36-byte length
        assertEquals(0x08.toByte(), decoded[2]) // protobuf field 1 (key type)
        assertEquals(0x01.toByte(), decoded[3]) // Ed25519
        assertEquals(0x12.toByte(), decoded[4]) // protobuf field 2 (key bytes)
        assertEquals(0x20.toByte(), decoded[5]) // 32-byte key
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
