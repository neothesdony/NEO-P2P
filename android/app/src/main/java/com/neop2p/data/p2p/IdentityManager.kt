package com.neop2p.data.p2p

import android.content.Context
import android.util.Base64
import android.util.Log
import com.neop2p.BuildConfig
import com.neop2p.domain.model.BitcoinAddressType
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.LegacyAddress
import org.bitcoinj.core.SegwitAddress
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user's cryptographic identity using BIP-39 mnemonic + BIP-32/SLIP-10 HD derivation.
 *
 * Architecture:
 *   BIP-39 mnemonic (12 words) → seed → BIP-32/SLIP-10 master key
 *     ├─ m/44'/1237'/0'/0/0  → Nostr (secp256k1, x-only pubkey for NIP-01)
 *     ├─ m/44'/0'/0'/0/0      → Bitcoin/Lightning (secp256k1)
 *     ├─ m/44'/888'/0'/0/0     → libp2p (Ed25519, SLIP-10)
 *     └─ m/44'/999'/0'/0/0    → Signal (Curve25519 via X25519, SLIP-10)
 *
 * Seed encrypted with AES-256-GCM, key wrapped by Android KeyStore, stored in SharedPreferences.
 * Each protocol gets the correct key type. No more cross-curve type violation.
 *
 * Migration: legacy plaintext identity is detected and re-encrypted on first load.
 */
@Singleton
class IdentityManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "IdentityManager"
        private const val KEYSTORE_ALIAS = "neop2p_identity"
        private const val KEYSTORE_LEGACY_ALIAS = "neop2p_identity"  // Old Ed25519 alias
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_SIZE = 256

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
        private const val PREF_TRADE_KEY_INDEX = "nostr_trade_key_index"

    }

    data class Identity(
        val peerId: String,
        val nostrPubkeyHex: String,
        val nostrPrivateKeyHex: String,
        val seedPhrase: List<String>,
        val nickname: String = "Anonymous",
        val lnNodeId: String = ""
    )

    private var cachedIdentity: Identity? = null

    private val seedCipher: SeedCipher = SeedCipher(KeyStoreAesGcmCipher(context))

    // Derived keys (computed on demand, cached in memory)
    private var nostrKeyPair: NostrKeyPair? = null
    private var libp2pPrivateKey: ByteArray? = null
    private var signalPrivateKey: ByteArray? = null

    data class NostrKeyPair(
        val publicKeyHex: String,    // x-only pubkey (32 bytes hex)
        val privateKeyHex: String    // secp256k1 private key (32 bytes hex)
    )

    /**
     * Returns existing identity or generates a new one on first launch.
     */
    fun getOrCreateIdentity(): Identity {
        cachedIdentity?.let { return it }

        // Try to load from encrypted storage
        val stored = loadIdentityFromStorage()
        if (stored != null) {
            cachedIdentity = stored
            return stored
        }

        // Generate new identity
        return generateNewIdentity()
    }

    /**
     * The current identity's libp2p peer ID (creates the identity on first
     * launch). Convenience wrapper used by role gating (Ruling W4: escrow
     * roles are bound by PEER ID, not pubkey — in the single-key model both
     * role pubkeys are the same key).
     */
    fun myPeerId(): String = getOrCreateIdentity().peerId

    /**
     * Check if an identity already exists (without creating one).
     */
    fun hasIdentity(): Boolean {
        cachedIdentity?.let { return true }
        val prefs = context.getSharedPreferences("neop2p_identity", Context.MODE_PRIVATE)
        return prefs.contains("encrypted_identity")
    }

    /**
     * Restores identity from a BIP-39 seed phrase.
     * Derives all protocol keys from the mnemonic.
     *
     * Refuses to overwrite a loadable identity unless [force] is set. A
     * locked/invalidated identity (KeyStore auth-gated or lock-screen change)
     * is NOT loadable — restore is the only recovery, so it stays allowed.
     */
    fun restoreFromSeedPhrase(seedPhrase: List<String>, force: Boolean = false): Identity {
        if (!RestoreGuard.allowRestore(
                existingLoadable = runCatching { loadIdentityFromStorage() != null }.getOrDefault(false),
                force = force
            )
        ) {
            throw IllegalStateException(
                "An identity already exists on this device. Restore would overwrite it."
            )
        }
        // Validate checksum
        if (!validateBip39Checksum(seedPhrase)) {
            throw IllegalArgumentException("Invalid BIP-39 checksum")
        }

        val seed = mnemonicToSeed(seedPhrase)
        val identity = deriveIdentityFromSeed(seed, seedPhrase)
        saveIdentityToStorage(identity)
        cachedIdentity = identity
        return identity
    }

    /**
     * Clears the stored identity and generates a new one.
     */
    fun resetIdentity(): Identity {
        cachedIdentity = null
        nostrKeyPair = null
        libp2pPrivateKey = null
        signalPrivateKey = null

        // Delete from KeyStore
        val keyStore = java.security.KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.deleteEntry(KEYSTORE_ALIAS)

        // Delete encrypted seed from SharedPreferences
        context.getSharedPreferences("neop2p_identity", Context.MODE_PRIVATE)
            .edit().clear().apply()

        return generateNewIdentity()
    }

    /**
     * Updates the user's nickname and persists it to encrypted storage.
     * Returns the updated identity.
     */
    fun updateNickname(nickname: String): Identity {
        val current = getOrCreateIdentity()
        val updated = current.copy(nickname = nickname)
        saveIdentityToStorage(updated)
        cachedIdentity = updated
        return updated
    }

    /**
     * Generates a new BIP-39 mnemonic and derives all protocol keys.
     */
    private fun generateNewIdentity(): Identity {
        // Generate proper BIP-39 mnemonic with checksum
        val (seedPhrase, seed) = generateBip39Mnemonic()
        val identity = deriveIdentityFromSeed(seed, seedPhrase)
        saveIdentityToStorage(identity)
        cachedIdentity = identity
        return identity
    }

    /**
     * Generate a proper BIP-39 mnemonic (128-bit entropy + 4-bit checksum = 12 words).
     */
    private fun generateBip39Mnemonic(): Pair<List<String>, ByteArray> {
        val entropy = ByteArray(16)  // 128 bits = 12 words
        SecureRandom().nextBytes(entropy)

        // Compute checksum: SHA-256 of entropy, take first 4 bits
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumBits = (hash[0].toInt() and 0xFF) shr 4  // Upper 4 bits

        // Combine entropy + checksum bits into 11-bit groups
        val bits = ByteArray(16 + 1)  // 128 bits entropy + 4 bits checksum
        System.arraycopy(entropy, 0, bits, 0, 16)
        bits[16] = (checksumBits shl 4).toByte()

        // Map 11-bit groups to words (12 words = 132 bits)
        val words = mutableListOf<String>()
        var bitBuffer = 0
        var bitCount = 0
        val allBits = bits.flatMap { byte ->
            (7 downTo 0).map { ((byte.toInt() shr it) and 1).toByte() }
        }

        for (i in allBits.indices) {
            bitBuffer = (bitBuffer shl 1) or (allBits[i].toInt() and 1)
            bitCount++
            if (bitCount == 11) {
                words.add(BIP39_FULL_WORDLIST[bitBuffer])
                bitBuffer = 0
                bitCount = 0
            }
        }

        val seed = mnemonicToSeed(words)
        return Pair(words, seed)
    }

    /**
     * Validates BIP-39 checksum.
     */
    private fun validateBip39Checksum(words: List<String>): Boolean {
        if (words.size != 12) return false
        // Decode words to 11-bit indices, concatenate to bit stream
        val bits = StringBuilder()
        for (word in words) {
            val idx = BIP39_FULL_WORDLIST.indexOf(word.lowercase())
            if (idx < 0) return false
            // Convert the 0..2047 index to an 11-bit binary string, left-padded
            // with zeros. (String.format("%011d") would emit DECIMAL digits,
            // which then fail to parse as binary below.)
            bits.append(Integer.toBinaryString(idx).padStart(11, '0'))
        }

        // Last 4 bits are checksum
        val checksumBits = bits.takeLast(4).toString()
        val entropyBits = bits.dropLast(4).toString()

        // Convert entropy bits to bytes
        val entropy = ByteArray(16)
        for (i in 0 until 16) {
            val byteStr = entropyBits.substring(i * 8, (i + 1) * 8)
            entropy[i] = byteStr.toInt(2).toByte()
        }

        // Verify checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksum = (hash[0].toInt() and 0xFF) shr 4
        val actualChecksum = checksumBits.toInt(2)
        return expectedChecksum == actualChecksum
    }

    // BIP-39 wordlist — full 2048 words
    @Suppress("MaxLineLength")
    private val BIP39_FULL_WORDLIST: List<String> by lazy {
        // Standard BIP-39 English wordlist — 2048 words
        // Using the well-known list at https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt
        loadBip39Wordlist()
    }

    private fun loadBip39Wordlist(): List<String> {
        // Load the canonical 2048-word BIP-39 English wordlist from res/raw/bip39_english.txt.
        val resource = context.resources.getIdentifier("bip39_english", "raw", context.packageName)
        require(resource != 0) { "BIP-39 wordlist resource (res/raw/bip39_english.txt) is missing" }
        return context.resources.openRawResource(resource).bufferedReader().readLines()
    }

    /**
     * Convert BIP-39 mnemonic to seed using PBKDF2.
     */
    private fun mnemonicToSeed(words: List<String>, passphrase: String = ""): ByteArray {
        val mnemonic = words.joinToString(" ")
        val salt = ("mnemonic$passphrase").toByteArray(Charsets.UTF_8)

        // PBKDF2 with HMAC-SHA512, 2048 iterations
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        val spec = javax.crypto.spec.PBEKeySpec(
            mnemonic.toCharArray(),
            salt,
            2048,
            512
        )
        return factory.generateSecret(spec).encoded
    }

    /**
     * Derive all protocol identities from the BIP-32/SLIP-10 master seed.
     * Uses the standard-compliant KeyDerivation (verified against BIP-32 and SLIP-10 test vectors).
     */
    private fun deriveIdentityFromSeed(seed: ByteArray, seedPhrase: List<String>): Identity {
        // Nostr key (secp256k1 via BIP-32 path m/44'/1237'/0'/0/0)
        val nostrPrivKey = KeyDerivation.deriveSecp256k1(seed, PATH_NOSTR)
        val nostrPubKey = KeyDerivation.secp256k1XOnlyPubKey(nostrPrivKey)

        // libp2p key (Ed25519 via SLIP-10 path m/44'/888'/0'/0/0)
        val libp2pPrivKey = KeyDerivation.deriveEd25519(seed, PATH_LIBP2P)
        val peerId = KeyDerivation.deriveLibp2pPeerIdFromKey(libp2pPrivKey)

        // Signal key (X25519 via SLIP-10 path m/44'/999'/0'/0/0)
        signalPrivateKey = KeyDerivation.deriveCurve25519(seed, PATH_SIGNAL)

        // Cache derived keys
        nostrKeyPair = NostrKeyPair(
            publicKeyHex = bytesToHex(nostrPubKey),
            privateKeyHex = bytesToHex(nostrPrivKey)
        )
        libp2pPrivateKey = libp2pPrivKey

        return Identity(
            peerId = peerId,
            nostrPubkeyHex = bytesToHex(nostrPubKey),
            nostrPrivateKeyHex = bytesToHex(nostrPrivKey),
            seedPhrase = seedPhrase,
            lnNodeId = "" // TODO: derive from PATH_BITCOIN when LDK integrated
        )
    }

    // ─── Persistence ────────────────────────────────────────────

    /**
     * Save identity to encrypted SharedPreferences (AES-256-GCM blob, KeyStore-wrapped key).
     */
    private fun saveIdentityToStorage(identity: Identity) {
        try {
            val blob = IdentityBlob(
                seedPhrase = identity.seedPhrase,
                peerId = identity.peerId,
                nostrPubkeyHex = identity.nostrPubkeyHex,
                nostrPrivateKeyHex = identity.nostrPrivateKeyHex,
                nickname = identity.nickname,
                lnNodeId = identity.lnNodeId
            )
            val encrypted = seedCipher.encrypt(IdentityBlobCodec.encode(blob))
            val prefs = context.getSharedPreferences("neop2p_identity", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("encrypted_identity", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply()
            Log.d(TAG, "Identity saved to encrypted storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save identity", e)
        }
    }

    /**
     * Load identity from encrypted SharedPreferences, migrating legacy plaintext on first load.
     */
    private fun loadIdentityFromStorage(): Identity? {
        try {
            val prefs = context.getSharedPreferences("neop2p_identity", Context.MODE_PRIVATE)
            val encryptedB64 = prefs.getString("encrypted_identity", null)
            if (encryptedB64 != null) {
                val bytes = seedCipher.decrypt(Base64.decode(encryptedB64, Base64.NO_WRAP))
                val blob = IdentityBlobCodec.decode(bytes)
                val seed = mnemonicToSeed(blob.seedPhrase)
                val identity = deriveIdentityFromSeed(seed, blob.seedPhrase)
                Log.d(TAG, "Identity loaded from encrypted storage")
                return identity
            }
            return migrateLegacyIdentity(prefs)
        } catch (e: Exception) {
            // P0-4: an auth-gated key that has not been unlocked within the
            // validity window throws UserNotAuthenticatedException. NEVER fall
            // through to generating a fresh identity here — that would silently
            // destroy the existing one.
            if (e is android.security.keystore.UserNotAuthenticatedException) {
                Log.w(TAG, "Identity locked behind device auth — refusing to generate a replacement", e)
                throw IdentityLockedException()
            }
            if (e is android.security.keystore.KeyPermanentlyInvalidatedException) {
                Log.w(TAG, "Identity key invalidated (lock-screen changed?) — restore from mnemonic", e)
                throw IdentityLockedException(
                    "Identity key was invalidated. Restore your identity from the seed phrase."
                )
            }
            Log.e(TAG, "Failed to load identity", e)
            return null
        }
    }

    /**
     * Migrate a legacy plaintext identity (v1) to the encrypted blob, then wipe plaintext keys.
     */
    private fun migrateLegacyIdentity(prefs: android.content.SharedPreferences): Identity? {
        val seedPhraseStr = prefs.getString("seed_phrase", null) ?: return null
        val seedPhrase = seedPhraseStr.split(" ")
        if (!validateBip39Checksum(seedPhrase)) {
            Log.w(TAG, "Legacy seed phrase fails checksum — proceeding for migration compatibility")
        }
        val seed = mnemonicToSeed(seedPhrase)
        val identity = deriveIdentityFromSeed(seed, seedPhrase)
        saveIdentityToStorage(identity)
        prefs.edit()
            .remove("seed_phrase")
            .remove("peer_id")
            .remove("nostr_pubkey")
            .remove("nostr_privkey")
            .remove("nickname")
            .remove("ln_node_id")
            .apply()
        Log.d(TAG, "Migrated legacy identity to encrypted storage")
        return identity
    }

    // ─── Key Access ──────────────────────────────────────────────

    /**
     * Get the Nostr key pair for signing events.
     */
    fun getNostrKeyPair(): NostrKeyPair {
        nostrKeyPair?.let { return it }
        getOrCreateIdentity()  // Triggers derivation
        return nostrKeyPair ?: throw IllegalStateException("Nostr key pair not available")
    }

    /**
     * Derive the NEXT per-trade Nostr key (P0-3) and advance the persisted
     * trade-key index. Each offer/trade gets a fresh secp256k1 key so events
     * cannot be linked to the identity key or across trades.
     */
    fun getNextTradeNostrKeyPair(): NostrKeyPair {
        val seed = currentSeed()
        val index = nextTradeKeyIndex()
        val priv = KeyDerivation.deriveSecp256k1(seed, PATH_NOSTR_TRADE_PREFIX + index)
        val pub = KeyDerivation.secp256k1XOnlyPubKey(priv)
        return NostrKeyPair(
            publicKeyHex = bytesToHex(pub),
            privateKeyHex = bytesToHex(priv)
        )
    }

    /** Current BIP-39 seed, derived from the stored mnemonic. */
    private fun currentSeed(): ByteArray {
        val identity = getOrCreateIdentity()
        return mnemonicToSeed(identity.seedPhrase)
    }

    /** Returns the next trade-key index and persists the incremented value. */
    private fun nextTradeKeyIndex(): Int {
        val prefs = context.getSharedPreferences("neop2p_identity", Context.MODE_PRIVATE)
        val current = prefs.getInt(PREF_TRADE_KEY_INDEX, 1)
        prefs.edit().putInt(PREF_TRADE_KEY_INDEX, current + 1).apply()
        return current
    }

    /**
     * Get the libp2p Ed25519 private key bytes for host initialization.
     */
    fun getLibp2pPrivateKey(): ByteArray {
        libp2pPrivateKey?.let { return it }
        getOrCreateIdentity()  // Triggers derivation
        return libp2pPrivateKey ?: throw IllegalStateException("libp2p key not available")
    }

    /**
     * Get the Signal X25519 private key bytes for session initialization.
     */
    fun getSignalPrivateKey(): ByteArray {
        signalPrivateKey?.let { return it }
        getOrCreateIdentity()
        return signalPrivateKey ?: throw IllegalStateException("Signal key not available")
    }

    /**
     * Get the Bitcoin (secp256k1) private key hex for 2-of-3 escrow signing.
     * Derived deterministically from the BIP-39 seed at m/44'/0'/0'/0/0 so the
     * signing key is consistent with the identity (and recoverable from the seed).
     */
    fun getBitcoinPrivateKeyHex(): String {
        val seed = currentSeed()
        val priv = KeyDerivation.deriveSecp256k1(seed, PATH_BITCOIN)
        return bytesToHex(priv)
    }

    /**
     * Get the Bitcoin (secp256k1) COMPRESSED public key hex for the 2-of-3
     * escrow. Matches org.bitcoinj.core.ECKey.publicKeyAsHex, so it can be fed
     * into the multisig redeem script / role-pubkey pinning (P0-1).
     */
    fun getBitcoinPubKeyHex(): String {
        val seed = currentSeed()
        val priv = KeyDerivation.deriveSecp256k1(seed, PATH_BITCOIN)
        val pub = KeyDerivation.secp256k1CompressedPubKey(priv)
        return bytesToHex(pub)
    }

    /**
     * Get the user's own Bitcoin receive address for [type], derived from the
     * same m/44'/0'/0'/0/0 key as [getBitcoinPrivateKeyHex] — LEGACY renders
     * P2PKH (m…/1…), SEGWIT renders P2WPKH (tb1…/bc1…). Both types share one
     * key, so a SegWit receive can be spent by the exact same key that already
     * spends the legacy address.
     */
    fun getBitcoinAddress(type: BitcoinAddressType): String {
        val seed = currentSeed()
        val priv = KeyDerivation.deriveSecp256k1(seed, PATH_BITCOIN)
        val key = ECKey.fromPrivate(priv)
        val params = if (BuildConfig.NETWORK == "mainnet") MainNetParams.get() else TestNet3Params.get()
        return when (type) {
            BitcoinAddressType.LEGACY -> LegacyAddress.fromKey(params, key).toBase58()
            BitcoinAddressType.SEGWIT -> SegwitAddress.fromKey(params, key).toBech32()
        }
    }

    /** Both user addresses (legacy + SegWit) for the wallet balance/toggle. */
    fun getBitcoinAddresses(): Map<BitcoinAddressType, String> =
        BitcoinAddressType.entries.associateWith { getBitcoinAddress(it) }

    /**
     * The arbitrator's secp256k1 private key hex, derived from THIS identity's
     * seed at the dedicated arbitrator path. Only the admin's mnemonic yields
     * the key that matches [com.neop2p.NeoP2PConfig.ARBITRATOR_PUBKEY]; every
     * other identity derives a different (harmless) key.
     *
     * Parity normalization (fix 2026-08-31): `NeoP2PConfig.ARBITRATOR_PUBKEY` is
     * x-only, but `EscrowService.xOnlyToCompressed` assumes even y (`0x02`).
     * If the derived priv yields odd y (`0x03`), we return `n-priv` which has
     * same x (so `getArbitratorPubKeyHex` still matches) but even y, so the
     * on-chain 2-of-3 redeem script (`02 + x`) matches the signing key. Without
     * this, `arbitratorSignTx`'s sanity check (`Arbitrator signature failed
     * verification`) and `storeArbitrationDecision`'s `verifySignature` would
     * reject a valid signature 50% of the time.
     */
    fun getArbitratorPrivateKeyHex(): String {
        return bytesToHex(arbitratorPrivEven())
    }

    /**
     * The arbitrator's secp256k1 x-only public key hex derived from THIS
     * identity. If it equals [com.neop2p.NeoP2PConfig.ARBITRATOR_PUBKEY], this
     * identity IS the arbitrator and Arbitrator Mode unlocks.
     */
    fun getArbitratorPubKeyHex(): String {
        return bytesToHex(KeyDerivation.secp256k1XOnlyPubKey(arbitratorPrivEven()))
    }

    private fun arbitratorPrivEven(): ByteArray {
        val seed = currentSeed()
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

    /**
     * Check if a legacy Ed25519 KeyStore identity exists (for migration).
     */
    fun hasLegacyIdentity(): Boolean {
        val keyStore = java.security.KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.containsAlias(KEYSTORE_LEGACY_ALIAS)
    }

    // ─── Utility ─────────────────────────────────────────────────

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}