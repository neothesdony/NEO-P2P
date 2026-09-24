package com.neop2p.data.p2p

import android.content.Context
import android.util.Base64
import android.util.Log
import com.neop2p.NeoP2PConfig
import com.neop2p.domain.model.BitcoinAddressType
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the user's cryptographic identity using BIP-39 mnemonic + BIP-32/SLIP-10 HD derivation.
 *
 * Architecture:
 *   BIP-39 mnemonic (12 words) → seed → BIP-32/SLIP-10 master key
 *     ├─ m/44'/1237'/0'/0/0  → Nostr (secp256k1, x-only pubkey for NIP-01)
 *     ├─ m/44'/0'/0'/0/0      → Bitcoin (secp256k1)
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
    private val context: Context,
    private val encryptedPrefsStore: com.neop2p.data.local.EncryptedPrefsStore
) {
    companion object {
        private const val TAG = "IdentityManager"
        private const val KEYSTORE_ALIAS = "neop2p_identity"
        private const val KEYSTORE_LEGACY_ALIAS = "neop2p_identity"  // Old Ed25519 alias
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_SIZE = 256

        // BIP-44 derivation paths — canonical values live in [IdentityDerivation]
        // (shared with the headless :admind daemon).
        const val PATH_NOSTR = IdentityDerivation.PATH_NOSTR      // NIP-06 / NIP-01 (identity)
        const val PATH_BITCOIN = IdentityDerivation.PATH_BITCOIN        // BIP-44 Bitcoin
        const val PATH_LIBP2P = IdentityDerivation.PATH_LIBP2P       // libp2p Ed25519
        const val PATH_SIGNAL = IdentityDerivation.PATH_SIGNAL        // Signal X25519

        // Per-trade Nostr keys: m/44'/1237'/0'/0/<index> — a fresh secp256k1
        // key per trade so offers and trade messages cannot be linked back to
        // the identity key (P0-3, mirrors Mostro's trade-key rotation).
        const val PATH_NOSTR_TRADE_PREFIX = IdentityDerivation.PATH_NOSTR_TRADE_PREFIX
        private const val PREF_TRADE_KEY_INDEX = "nostr_trade_key_index"

        /**
         * B2 (2026-09-23): every identity-scoped pref file, cleared on identity
         * reset. Deliberately excludes `locale_prefs` (a UI preference, not
         * trade/identity data).
         */
        private val PREF_FILES = listOf(
            "neop2p_identity", "neop2p_blocked_peers", "neop2p_reported_peers",
            "neop2p_onboarding", "neop2p_notified_events", "neop2p_notif_rationale",
            "escrow_rated", "neop2p_deleted_offers", "neop2p_pending_disputes",
            "neop2p_pending_arbitration", "saved_payment_methods", "transport_nodes",
            "peer_bindings", "wallet_snapshot", "wallet_address_state",
            "sweep_throttle", "receipt_drafts",
        )

        /**
         * Clamp + sanitize a nickname: strip control characters (CR/LF/NUL —
         * a hostile nickname must not plant a bidi/RTL overflow or a CRLF into
         * the feed or a chat card), trim, then cap the length. Applied at the
         * single write point (updateNickname) and at offer ingest.
         */
        fun sanitizeNickname(nickname: String): String = IdentityDerivation.sanitizeNickname(nickname)

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

    private val seedKeyCipher = KeyStoreAesGcmCipher(context)
    private val seedCipher: SeedCipher = SeedCipher(seedKeyCipher)

    /**
     * One PBKDF2 stretch per identity, plus memoized per-index keys. An HD
     * scan of N addresses must not stretch the mnemonic N times (P0.0).
     */
    private val seedCache = SeedCache { mnemonicToSeed(it) }

    // Derived keys (computed on demand, cached in memory)
    private var nostrKeyPair: NostrKeyPair? = null
    private var libp2pPrivateKey: ByteArray? = null
    private var signalPrivateKey: ByteArray? = null
    private var rnsIdentityHash: String? = null

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
     * The local RNS identity hash (16-byte truncated identity hash, lowercase
     * hex) — the exact value the `neop2p.identity` binding announce carries
     * (see RnsSession.handleIdentityAnnounce). Used to bind an invite link to
     * the identity that will announce it. Returns null when the identity is not
     * available (e.g. locked behind device auth).
     */
    fun myRnsIdentityHash(): String? {
        rnsIdentityHash?.let { return it }
        return runCatching {
            network.reticulum.identity.Identity
                .fromPrivateKey(KeyDerivation.rnsIdentity(getMasterSeed()))
                .hexHash
        }.getOrNull()?.also { rnsIdentityHash = it }
    }

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
        rnsIdentityHash = null
        // The cached seed belongs to the OLD identity — wipe it before the new
        // one can be read (P0.0).
        seedCache.invalidate()
        return identity
    }

    /**
     * Clears the stored identity and generates a new one.
     *
     * B2 (2026-09-23): wipes EVERY KeyStore alias the identity owns and every
     * identity-scoped pref file, then deletes the SQLCipher DB files (the DB
     * passphrase alias is gone, so the old DB is unreadable). The prefs + DB
     * keys are dropped through their managers so an in-process cached key
     * cannot outlive the deleted alias.
     */
    fun resetIdentity(): Identity {
        cachedIdentity = null
        nostrKeyPair = null
        libp2pPrivateKey?.fill(0)
        libp2pPrivateKey = null
        signalPrivateKey?.fill(0)
        signalPrivateKey = null
        rnsIdentityHash = null
        seedCache.invalidate()

        val keyStore = java.security.KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        runCatching { keyStore.deleteEntry(KEYSTORE_ALIAS) }
        runCatching { keyStore.deleteEntry(KEYSTORE_LEGACY_ALIAS) }
        runCatching { encryptedPrefsStore.deleteKey() }
        runCatching { com.neop2p.data.local.SqlCipherPassphraseManager.deleteKey() }

        PREF_FILES.forEach { name ->
            runCatching {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
            }
        }

        deleteDatabaseFiles()

        return generateNewIdentity()
    }

    /** Delete the SQLCipher DB + its journal/WAL companions so Room recreates it. */
    private fun deleteDatabaseFiles() {
        val base = context.getDatabasePath(com.neop2p.data.local.AppDatabase.DB_NAME)
        listOf(base.path, "${base.path}-wal", "${base.path}-shm", "${base.path}-journal")
            .forEach { runCatching { java.io.File(it).delete() } }
    }

    /**
     * Updates the user's nickname and persists it to encrypted storage.
     * Returns the updated identity.
     *
     * C10/I6: the nickname is clamped to [NeoP2PConfig.MAX_NICKNAME_LENGTH]
     * and control characters stripped — the single write point for the local
     * nickname, so callers (onboarding, profile edit) need no per-screen cap.
     */
    fun updateNickname(nickname: String): Identity {
        val current = getOrCreateIdentity()
        val sanitized = sanitizeNickname(nickname)
        val updated = current.copy(nickname = sanitized)
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
        val words = Bip39.generateMnemonic()
        return Pair(words, Bip39.mnemonicToSeed(words))
    }

    /**
     * Validates BIP-39 checksum.
     */
    private fun validateBip39Checksum(words: List<String>): Boolean = Bip39.validateChecksum(words)

    /**
     * Convert BIP-39 mnemonic to seed using PBKDF2.
     */
    private fun mnemonicToSeed(words: List<String>, passphrase: String = ""): ByteArray =
        Bip39.mnemonicToSeed(words, passphrase)

    /**
     * Derive all protocol identities from the BIP-32/SLIP-10 master seed.
     * Uses the standard-compliant [IdentityDerivation] (verified against BIP-32 and SLIP-10 test vectors).
     */
    private fun deriveIdentityFromSeed(seed: ByteArray, seedPhrase: List<String>): Identity {
        val derived = IdentityDerivation.derive(seed)

        // Cache derived keys
        nostrKeyPair = NostrKeyPair(
            publicKeyHex = derived.nostrPubkeyHex,
            privateKeyHex = derived.nostrPrivateKeyHex
        )
        libp2pPrivateKey = derived.libp2pPrivateKey
        signalPrivateKey = derived.signalPrivateKey

        return Identity(
            peerId = derived.peerId,
            nostrPubkeyHex = derived.nostrPubkeyHex,
            nostrPrivateKeyHex = derived.nostrPrivateKeyHex,
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
                val bytes = Base64.decode(encryptedB64, Base64.NO_WRAP)
                // 2026-09-24: an identity created before a lock screen existed
                // kept a permanently un-gated seed key. One-time re-wrap now
                // that the device is secure (no-op otherwise).
                val reWrapped = seedKeyCipher.ensureAuthBound(bytes)
                if (reWrapped != null) {
                    prefs.edit()
                        .putString("encrypted_identity", Base64.encodeToString(reWrapped, Base64.NO_WRAP))
                        .apply()
                    Log.d(TAG, "Seed key re-wrapped with user-auth binding")
                }
                val blob = IdentityBlobCodec.decode(
                    if (reWrapped != null) seedCipher.decrypt(reWrapped) else seedCipher.decrypt(bytes)
                )
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
            Log.e(TAG, "Identity is present but unreadable — refusing to generate a replacement", e)
            throw IdentityRestoreRequiredException()
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
        val (pub, priv) = IdentityDerivation.tradeNostrKeyPair(seed, index)
        return NostrKeyPair(
            publicKeyHex = pub,
            privateKeyHex = priv
        )
    }

    /** Current BIP-39 seed, derived from the stored mnemonic (cached, P0.0). */
    private fun currentSeed(): ByteArray {
        val identity = getOrCreateIdentity()
        return seedCache.seedFor(identity.seedPhrase)
    }

    /**
     * The current BIP-39 master seed (public). Used by RnsTransport to derive
     * the deterministic RNS identity (SLIP-10 m/44'/999'/0'/0/1 + /0/2).
     *
     * Returns a copy: the cache retains its own buffer (P0.0).
     */
    fun getMasterSeed(): ByteArray = currentSeed().copyOf()

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
     * The Bitcoin (secp256k1) private key as a FRESH 32-byte array the caller
     * owns. Audit P3-4 (2026-09-12): signing paths use this and zero the array
     * in a `finally` block — a hex String cannot be wiped once created.
     */
    fun getBitcoinPrivateKeyBytes(): ByteArray =
        getBitcoinPrivateKeyBytes(index = 0, internal = false)

    /**
     * Indexed BIP-44 key at `m/44'/0'/0'/{0|1}/index` (P0.1). Backed by the
     * [seedCache] so an HD scan reuses one PBKDF2 stretch. Returns a fresh
     * copy the caller owns.
     */
    fun getBitcoinPrivateKeyBytes(index: Int, internal: Boolean = false): ByteArray {
        val identity = getOrCreateIdentity()
        return seedCache.bitcoinKey(identity.seedPhrase, index, internal)
    }

    /**
     * Get the Bitcoin (secp256k1) private key hex for 2-of-3 escrow signing.
     * Derived deterministically from the BIP-39 seed at m/44'/0'/0'/0/0 so the
     * signing key is consistent with the identity (and recoverable from the seed).
     */
    fun getBitcoinPrivateKeyHex(): String = bytesToHex(getBitcoinPrivateKeyBytes())

    /**
     * Get the Bitcoin (secp256k1) COMPRESSED public key hex for the 2-of-3
     * escrow. Matches org.bitcoinj.crypto.ECKey.publicKeyAsHex, so it can be fed
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
     *
     * Index 0 external is bit-identical to the legacy single-address wallet
     * (PATH_BITCOIN), so existing funds and escrow role addresses never move.
     */
    fun getBitcoinAddress(type: BitcoinAddressType): String =
        getBitcoinAddress(type, index = 0, internal = false)

    /**
     * Indexed BIP-44 address at `m/44'/0'/0'/{0|1}/index` (P0.1). [internal]
     * selects the change chain (`/1`). Pure formatting lives in
     * [SeedCache.addressFor] so it is testable without a `Context`.
     */
    fun getBitcoinAddress(
        type: BitcoinAddressType,
        index: Int,
        internal: Boolean = false
    ): String {
        val priv = getBitcoinPrivateKeyBytes(index, internal)
        return try {
            SeedCache.addressFor(type, priv, params)
        } finally {
            priv.fill(0)
        }
    }

    private val params: NetworkParameters
        get() = if (NeoP2PConfig.network == "mainnet") MainNetParams.get() else TestNet3Params.get()

    /** Both user addresses (legacy + SegWit) for the wallet balance/toggle. */
    fun getBitcoinAddresses(): Map<BitcoinAddressType, String> =
        BitcoinAddressType.entries.associateWith { getBitcoinAddress(it) }

    /**
     * Check if a legacy Ed25519 KeyStore identity exists (for migration).
     */
    fun hasLegacyIdentity(): Boolean {
        val keyStore = java.security.KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.containsAlias(KEYSTORE_LEGACY_ALIAS)
    }

    // ─── Utility ─────────────────────────────────────────────────

    private fun bytesToHex(bytes: ByteArray): String = IdentityDerivation.bytesToHex(bytes)
}