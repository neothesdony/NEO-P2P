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
        // Phase 4: jvm-libp2p was removed — the local derivation must still
        // produce the exact libp2p PeerID format (base58btc of the identity
        // multihash of the protobuf-encoded Ed25519 pubkey). The known-good
        // vector below was produced by io.libp2p.core.PeerId.fromPubKey.
        val key = KeyDerivation.deriveEd25519(seed, "m/44'/888'/0'/0/0")
        val pub = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(key, 0)
            .generatePublicKey().encoded
        assertEquals(peerId1, KeyDerivation.deriveLibp2pPeerIdFromPublicKey(pub))
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

    // Bitcoin escrow signing key (m/44'/0'/0'/0/0) — used by IdentityManager
    // to sign the 2-of-3 payout. Must be deterministic per seed.
    @Test
    fun `bitcoin escrow key is deterministic and 32 bytes`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        val k1 = toHex(KeyDerivation.deriveSecp256k1(seed, "m/44'/0'/0'/0/0"))
        val k2 = toHex(KeyDerivation.deriveSecp256k1(seed, "m/44'/0'/0'/0/0"))
        assertEquals(k1, k2)
        assertEquals(64, k1.length)
    }

    @Test
    fun `bitcoin escrow key differs across seeds`() {
        val seed1 = hex("000102030405060708090a0b0c0d0e0f")
        val seed2 = hex("fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542")
        assertNotEquals(
            toHex(KeyDerivation.deriveSecp256k1(seed1, "m/44'/0'/0'/0/0")),
            toHex(KeyDerivation.deriveSecp256k1(seed2, "m/44'/0'/0'/0/0"))
        )
    }

    // RNS identity (m/44'/999'/0'/0/1 curve25519 + m/44'/999'/0'/0/2 ed25519) —
    // 64 bytes: X25519 priv (32) || Ed25519 priv (32), consumed by
    // Identity.fromPrivateKey in rns-core. Must be deterministic per seed.
    @Test
    fun `RNS identity is deterministic and 64 bytes`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        val a = KeyDerivation.rnsIdentity(seed)
        val b = KeyDerivation.rnsIdentity(seed)
        assertTrue(a.contentEquals(b))
        assertEquals(64, a.size)
    }

    @Test
    fun `RNS identity differs across seeds`() {
        val seed1 = hex("000102030405060708090a0b0c0d0e0f")
        val seed2 = hex("fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542")
        assertTrue(
            !KeyDerivation.rnsIdentity(seed1).contentEquals(KeyDerivation.rnsIdentity(seed2))
        )
    }

    @Test
    fun `RNS identity x25519 half matches SLIP10 curve25519 derivation`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        val rns = KeyDerivation.rnsIdentity(seed)
        val x25519 = KeyDerivation.deriveCurve25519(seed, "m/44'/999'/0'/0/1")
        val ed25519 = KeyDerivation.deriveEd25519(seed, "m/44'/999'/0'/0/2")
        assertTrue(rns.copyOfRange(0, 32).contentEquals(x25519))
        assertTrue(rns.copyOfRange(32, 64).contentEquals(ed25519))
    }

    /**
     * FIX 2 (P0-2) regression: an X25519 pre-key must be cryptographically bound
     * to the sender's Ed25519 identity (== their libp2p PeerID). This mirrors the
     * exact mechanism in SignalProtocol.getPreKeyBundle/createSession:
     *   1. Sign the X25519 pre-key with the libp2p Ed25519 private key.
     *   2. Verify the signature with the derived Ed25519 public key.
     *   3. Derive the peerId from identityPubKey — it MUST equal the key derived
     *      via deriveLibp2pPeerIdFromKey (so binding is consistent).
     */
    @Test
    fun `FIX2 X25519 prekey binds to libp2p Ed25519 identity`() {
        val seed = hex("000102030405060708090a0b0c0d0e0f")
        val libp2pPriv = KeyDerivation.deriveEd25519(seed, "m/44'/888'/0'/0/0")

        // X25519 pre-key (32 bytes) being bound.
        val x25519Priv = KeyDerivation.deriveCurve25519(seed, "m/44'/999'/0'/0/0")
        val preKeyPublic = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(x25519Priv, 0)
            .generatePublicKey().encoded

        // 1. Sign the pre-key with the libp2p Ed25519 private key.
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(libp2pPriv, 0))
        signer.update(preKeyPublic, 0, preKeyPublic.size)
        val identitySignature = signer.generateSignature()

        // Derive the Ed25519 identity public key (same bytes the libp2p host uses).
        val identityPub = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(libp2pPriv, 0)
            .generatePublicKey().encoded

        // 2. Verify the signature over the pre-key.
        val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
        verifier.init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(identityPub, 0))
        verifier.update(preKeyPublic, 0, preKeyPublic.size)
        assertTrue("Ed25519 signature over pre-key must verify", verifier.verifySignature(identitySignature))

        // A signature over a tampered pre-key must FAIL verification.
        val tampered = preKeyPublic.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()
        val verifier2 = org.bouncycastle.crypto.signers.Ed25519Signer()
        verifier2.init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(identityPub, 0))
        verifier2.update(tampered, 0, tampered.size)
        assertTrue("tampered pre-key must fail", !verifier2.verifySignature(identitySignature))

        // 3. The peerId derived from identityPubKey must match the host peerId.
        val hostPeerId = KeyDerivation.deriveLibp2pPeerIdFromKey(libp2pPriv)
        val fromIdentityPub = KeyDerivation.deriveLibp2pPeerIdFromPublicKey(identityPub)
        assertEquals(hostPeerId, fromIdentityPub)
        assertTrue(hostPeerId.startsWith("12D3KooW"))
    }

    @Test
    fun `wallet bitcoin path is the BIP-44 account the wallet has always used`() {
        // Audit P3-4 (2026-09-12): both getBitcoinPrivateKeyBytes() and
        // getBitcoinPrivateKeyHex() derive from this path, and it is the same
        // key the wallet addresses and the escrow role keys come from. Changing
        // it would move every address and strand existing funds.
        assertEquals("m/44'/0'/0'/0/0", IdentityManager.PATH_BITCOIN)
        val priv = KeyDerivation.deriveSecp256k1(ByteArray(64) { 7 }, IdentityManager.PATH_BITCOIN)
        assertEquals(32, priv.size)
        // The returned array is the caller's to wipe, and wiping it must not
        // affect a subsequent derivation (no shared cached buffer).
        priv.fill(0)
        assertEquals(
            32,
            KeyDerivation.deriveSecp256k1(ByteArray(64) { 7 }, IdentityManager.PATH_BITCOIN).size
        )
    }
}
