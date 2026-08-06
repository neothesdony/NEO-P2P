package com.neop2p.data.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDerivationTest {

    private fun hex(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                    Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    // BIP-32 test vector 1 (secp256k1), seed 000102030405060708090a0b0c0d0e0f
    @Test
    fun `BIP32 secp256k1 vector1 m0h`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        assertEquals(
            "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea",
            toHex(KeyDerivation.deriveSecp256k1(seed, "m/0'"))
        )
    }

    @Test
    fun `BIP32 secp256k1 vector1 full path`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        assertEquals(
            "471b76e389e528d6de6d816857e012c5455051cad6660850e58372a6c3e6e7c8",
            toHex(KeyDerivation.deriveSecp256k1(seed, "m/0'/1/2'/2/1000000000"))
        )
    }

    @Test
    fun `BIP32 secp256k1 vector2 non-hardened first`() {
        val seed = hex("fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542")
        assertEquals(
            "abe74a98f6c7eabee0428f53798f0ab8aa1bd37873999041703c742f15ac7e1e",
            toHex(KeyDerivation.deriveSecp256k1(seed, "m/0"))
        )
    }

    // SLIP-10 test vector 1 (ed25519), same seed
    @Test
    fun `SLIP10 ed25519 vector1 full path`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        assertEquals(
            "8f94d394a8e8fd6b1bc2f3f49f5c47e385281d5c17e65324b0f62483e37e8793",
            toHex(KeyDerivation.deriveEd25519(seed, "m/0'/1'/2'/2'/1000000000'"))
        )
    }

    @Test
    fun `SLIP10 ed25519 vector1 m0h`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        assertEquals(
            "68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3",
            toHex(KeyDerivation.deriveEd25519(seed, "m/0'"))
        )
    }

    // SLIP-10 test vector 1 (curve25519), same seed
    @Test
    fun `SLIP10 curve25519 vector1 full path`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        assertEquals(
            "7a59954d387abde3bc703f531f67d659ec2b8a12597ae82824547d7e27991e26",
            toHex(KeyDerivation.deriveCurve25519(seed, "m/0'/1'/2'/2'/1000000000'"))
        )
    }

    @Test
    fun `libp2p peer ID is deterministic and matches libp2p derivation`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        val peerId1 = KeyDerivation.deriveLibp2pPeerId(seed, "m/44'/888'/0'/0/0")
        val peerId2 = KeyDerivation.deriveLibp2pPeerId(seed, "m/44'/888'/0'/0/0")
        assertEquals(peerId1, peerId2)
        assertTrue(peerId1.startsWith("12D3KooW"))
        val key = KeyDerivation.deriveEd25519(seed, "m/44'/888'/0'/0/0")
        val privKey = io.libp2p.crypto.keys.unmarshalEd25519PrivateKey(key)
        assertEquals(io.libp2p.core.PeerId.fromPubKey(privKey.publicKey()).toBase58(), peerId1)
    }

    @Test
    fun `different seeds produce different peer IDs`() {
        val seed1 = hex("000102030405060708090a0b0c0d0e0f")
        val seed2 = hex("fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542")
        assertNotEquals(
            KeyDerivation.deriveLibp2pPeerId(seed1, "m/44'/888'/0'/0/0"),
            KeyDerivation.deriveLibp2pPeerId(seed2, "m/44'/888'/0'/0/0")
        )
    }

    @Test
    fun `different seeds produce different keys`() {
        val seed1 = hex("000102030405060708090a0b0c0d0e0f")
        val seed2 = hex("fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542")
        assertNotEquals(
            toHex(KeyDerivation.deriveSecp256k1(seed1, "m/44'/1237'/0'/0/0")),
            toHex(KeyDerivation.deriveSecp256k1(seed2, "m/44'/1237'/0'/0/0"))
        )
    }

    @Test
    fun `same seed same path deterministic`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        assertEquals(
            toHex(KeyDerivation.deriveSecp256k1(seed, "m/44'/1237'/0'/0/0")),
            toHex(KeyDerivation.deriveSecp256k1(seed, "m/44'/1237'/0'/0/0"))
        )
    }
}
