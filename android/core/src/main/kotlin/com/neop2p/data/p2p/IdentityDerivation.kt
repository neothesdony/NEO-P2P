package com.neop2p.data.p2p

import com.neop2p.NeoP2PConfig

/**
 * All protocol keys derived from one BIP-39 seed. Returned by
 * [IdentityDerivation.derive] instead of assigning fields on an Android-bound
 * manager, so the same derivation runs in the headless `:admind` daemon.
 */
data class DerivedIdentity(
    val peerId: String,
    val nostrPubkeyHex: String,
    val nostrPrivateKeyHex: String,
    val signalPrivateKey: ByteArray,
    val libp2pPrivateKey: ByteArray
)

/**
 * Pure BIP-32/SLIP-10 identity derivation (no `Context`, no caches, no I/O).
 *
 * Architecture:
 *   seed → BIP-32/SLIP-10 master key
 *     ├─ m/44'/1237'/0'/0/0  → Nostr (secp256k1, x-only pubkey for NIP-01)
 *     ├─ m/44'/0'/0'/0/0      → Bitcoin (secp256k1)
 *     ├─ m/44'/888'/0'/0/0     → libp2p (Ed25519, SLIP-10)
 *     └─ m/44'/999'/0'/0/0    → Signal (Curve25519 via X25519, SLIP-10)
 *
 * Extracted from [com.neop2p.data.p2p.IdentityManager], which now delegates
 * here and keeps only its Android persistence + in-memory caches.
 */
object IdentityDerivation {

    // BIP-44 derivation paths per protocol
    const val PATH_NOSTR = "m/44'/1237'/0'/0/0"      // NIP-06 / NIP-01 (identity)
    const val PATH_BITCOIN = "m/44'/0'/0'/0/0"        // BIP-44 Bitcoin
    const val PATH_LIBP2P = "m/44'/888'/0'/0/0"       // libp2p Ed25519
    const val PATH_SIGNAL = "m/44'/999'/0'/0/0"        // Signal X25519
    // Arbitrator (dispute resolution) key: m/44'/999'/0'/1/0 — a dedicated
    // secp256k1 key derived from the ADMIN's mnemonic. Arbitrator Mode is
    // unlocked when this key matches the configured arbitrator pubkey, so
    // the arbitration key is born inside the admin's device and never
    // exists in an APK or on the relay.
    const val PATH_ARBITRATOR = "m/44'/999'/0'/1/0"

    // Per-trade Nostr keys: m/44'/1237'/0'/0/<index> — a fresh secp256k1
    // key per trade so offers and trade messages cannot be linked back to
    // the identity key (P0-3, mirrors Mostro's trade-key rotation).
    const val PATH_NOSTR_TRADE_PREFIX = "m/44'/1237'/0'/0/"

    /**
     * Clamp + sanitize a nickname: strip control characters (CR/LF/NUL —
     * a hostile nickname must not plant a bidi/RTL overflow or a CRLF into
     * the feed or a chat card), trim, then cap the length. Applied at the
     * single write point (updateNickname) and at offer ingest.
     */
    fun sanitizeNickname(nickname: String): String {
        val cleaned = nickname.filter { !it.isISOControl() }.trim()
        return cleaned.take(NeoP2PConfig.MAX_NICKNAME_LENGTH)
    }

    /**
     * Derive all protocol identities from the BIP-32/SLIP-10 master seed.
     * Uses the standard-compliant [KeyDerivation] (verified against BIP-32 and
     * SLIP-10 test vectors).
     */
    fun derive(seed: ByteArray): DerivedIdentity {
        // Nostr key (secp256k1 via BIP-32 path m/44'/1237'/0'/0/0)
        val nostrPrivKey = KeyDerivation.deriveSecp256k1(seed, PATH_NOSTR)
        val nostrPubKey = KeyDerivation.secp256k1XOnlyPubKey(nostrPrivKey)

        // libp2p key (Ed25519 via SLIP-10 path m/44'/888'/0'/0/0)
        val libp2pPrivKey = KeyDerivation.deriveEd25519(seed, PATH_LIBP2P)
        val peerId = KeyDerivation.deriveLibp2pPeerIdFromKey(libp2pPrivKey)

        // Signal key (X25519 via SLIP-10 path m/44'/999'/0'/0/0)
        val signalPrivKey = KeyDerivation.deriveCurve25519(seed, PATH_SIGNAL)

        return DerivedIdentity(
            peerId = peerId,
            nostrPubkeyHex = bytesToHex(nostrPubKey),
            nostrPrivateKeyHex = bytesToHex(nostrPrivKey),
            signalPrivateKey = signalPrivKey,
            libp2pPrivateKey = libp2pPrivKey
        )
    }

    /**
     * Derive the per-trade Nostr key at [index] (P0-3). Returns
     * `(x-only public key hex, private key hex)`.
     */
    fun tradeNostrKeyPair(seed: ByteArray, index: Int): Pair<String, String> {
        val priv = KeyDerivation.deriveSecp256k1(seed, PATH_NOSTR_TRADE_PREFIX + index)
        val pub = KeyDerivation.secp256k1XOnlyPubKey(priv)
        return Pair(bytesToHex(pub), bytesToHex(priv))
    }

    /**
     * The arbitrator's secp256k1 private key, derived from the seed at the
     * dedicated arbitrator path.
     *
     * Parity normalization (fix 2026-08-31): `NeoP2PConfig.ARBITRATOR_PUBKEY` is
     * x-only, but `EscrowService.xOnlyToCompressed` assumes even y (`0x02`).
     * If the derived priv yields odd y (`0x03`), we return `n-priv` which has
     * same x (so [arbitratorPubKeyHex] still matches) but even y, so the
     * on-chain 2-of-3 redeem script (`02 + x`) matches the signing key. Without
     * this, `arbitratorSignTx`'s sanity check (`Arbitrator signature failed
     * verification`) and `storeArbitrationDecision`'s `verifySignature` would
     * reject a valid signature 50% of the time.
     */
    fun arbitratorPrivEven(seed: ByteArray): ByteArray {
        val priv = KeyDerivation.deriveSecp256k1(seed, PATH_ARBITRATOR)
        val comp = KeyDerivation.secp256k1CompressedPubKey(priv)
        if (comp[0] == 0x02.toByte()) return priv
        // Odd y -> negate priv to get even y with same x (n-priv has same x, opposite y).
        val n = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
        val privInt = java.math.BigInteger(1, priv)
        val neg = n.subtract(privInt)
        val raw = neg.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    /** The arbitrator's parity-normalized private key as hex. */
    fun arbitratorPrivateKeyHex(seed: ByteArray): String =
        bytesToHex(arbitratorPrivEven(seed))

    /**
     * The arbitrator's secp256k1 x-only public key hex derived from the seed.
     * If it equals [com.neop2p.NeoP2PConfig.ARBITRATOR_PUBKEY], this identity
     * IS the arbitrator and Arbitrator Mode unlocks.
     */
    fun arbitratorPubKeyHex(seed: ByteArray): String =
        bytesToHex(KeyDerivation.secp256k1XOnlyPubKey(arbitratorPrivEven(seed)))

    /** Lowercase hex of [bytes]. */
    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
