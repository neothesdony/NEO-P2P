# IDENTITY_REWRITE.md — Architectural Blueprint

> **TL;DR**: Replace the single Ed25519 KeyStore key with a BIP-39 master seed + BIP-32 derivation model. Generate correct key types for each protocol. Seed encrypted with KeyStore. All identities derivable from one recoverable mnemonic.

## 1. The Problem

Current `IdentityManager` generates **one** Ed25519 keypair in Android KeyStore and derives all protocol identities from it via SHA-256:

```
Ed25519 keypair (KeyStore)
  ├── SHA-256(pubkey) → "libp2p PeerID" (fake string, not a real PeerID)
  ├── hex(SHA-256(pubkey)) → "Nostr npub" (wrong curve: Nostr needs secp256k1)
  ├── SHA-256(pubkey) → "Lightning node ID" (wrong curve: LN needs secp256k1)
  └── fresh Curve25519 (in-memory) → "Signal key" (lost on restart)
```

**Three categories of failure:**

1. **Cryptographic incompatibility**: Nostr (Schnorr/secp256k1), Lightning (ECDSA/secp256k1), and Signal (X25519) all need different key types. Ed25519 cannot produce valid signatures for any of them.
2. **Cross-protocol linkability**: Every identity is SHA-256 of the same Ed25519 public key. An attacker who sees Nostr npub `A` and libp2p PeerID `B` computes SHA-256 on both, confirms they're from the same Ed25519 pubkey, and correlates the user's activity across protocols.
3. **Data loss on restart**: Signal's Curve25519 key is generated fresh in `initialize()` and stored in heap-only mutable maps. Every app restart requires re-handshaking with every peer.

## 2. The Solution

### 2.1 Architecture Overview

```
┌─────────────────────────────────────────┐
│  Android KeyStore (StrongBox)            │
│  ├─ Master wrapping key (AES-256-GCM)    │
│  └─ No protocol keys stored here         │
└─────────────────────────────────────────┘
                  │
                  ▼ encrypt/decrypt
┌─────────────────────────────────────────┐
│  Encrypted BIP-39 seed (SQLCipher)       │
│  ├─ 128-bit entropy + 4-bit checksum     │
│  └─ 12-word mnemonic (BIP-39 English)    │
└─────────────────────────────────────────┘
                  │
                  ▼ BIP-32 HD derivation
        ┌────────┴────────┐
        ▼                 ▼
   m/44'/1237'...    m/44'/0'/0'...
   (Nostr)            (Bitcoin)
   secp256k1          secp256k1
        │                 │
        ▼                 ▼
   ┌──────────┐     ┌──────────┐
   │ Nostr    │     │ Lightning│
   │ Schnorr  │     │ ECDSA    │
   └──────────┘     └──────────┘

        m/44'/888'/0'...
        (libp2p)
        Ed25519
             │
             ▼
        ┌──────────┐
        │ libp2p   │
        │ PeerID   │
        └──────────┘

        m/44'/999'/0'...
        (Signal)
        Curve25519
             │
             ▼
        ┌──────────┐
        │ Signal   │
        │ X3DH     │
        └──────────┘
```

### 2.2 Derivation Paths (Standard Where Possible)

| Protocol | BIP-44 Coin Type | Derivation Path | Key Type | Library |
|----------|-----------------|-----------------|----------|---------|
| Nostr | 1237 (SLIP-44) | `m/44'/1237'/0'/0/0` | secp256k1 | `fr.acinq.secp256k1` |
| Bitcoin/Lightning | 0 | `m/44'/0'/0'/0/0` | secp256k1 | `org.bitcoinj` |
| libp2p | 888 (arbitrary, documented) | `m/44'/888'/0'/0/0` | Ed25519 | `org.bouncycastle` ed25519 |
| Signal | 999 (arbitrary, documented) | `m/44'/999'/0'/0/0` | Curve25519 | `org.signal.libsignal` |

**Note**: Nostr coin type 1237 is registered in SLIP-44. Bitcoin is coin type 0. libp2p and Signal use arbitrary coin types documented in this file. All paths use hardened derivation for the first three levels (') and non-hardened for the last two.

### 2.3 Seed Storage Model

```
User input / generate:
  └─ 12-word BIP-39 mnemonic
         │
         ▼ BIP-39 mnemonicToSeed (PBKDF2, 2048 rounds)
  ┌──────────────┐
  │ 64-byte seed │ ← BIP-32 master seed
  └──────────────┘
         │
         ▼ AES-256-GCM encryption (KeyStore-wrapped key)
  ┌──────────────────┐
  │ encryptedSeedBlob  │ → Stored in SQLCipher as BLOB
  └──────────────────┘
```

**On first launch**: Generate fresh entropy → BIP-39 mnemonic → display to user → encrypt seed → store in SQLCipher.

**On subsequent launches**: Load encrypted seed from SQLCipher → decrypt with KeyStore key → derive all protocol keys on-demand.

**On backup**: Export mnemonic (12 words). Never export raw seed or private keys.

**On restore**: User enters 12 words → validate BIP-39 checksum → derive master seed → encrypt → store → derive all keys.

### 2.4 Key Lifecycle In Memory

Protocol private keys are derived once at app startup and held in a `SecureKeyCache` (plaintext in memory, zeroed on app backgrounding via `Application.onTrimMemory()` callback). Public keys are cached in plaintext (they're public).

```kotlin
class SecureKeyCache {
    // Private keys held as ByteArray (not objects that pin in GC)
    private val nostrPrivateKey: ByteArray   // 32 bytes secp256k1 scalar
    private val lightningPrivateKey: ByteArray
    private val libp2pPrivateKey: ByteArray    // 32 bytes Ed25519 seed
    private val signalPrivateKey: ByteArray  // 32 bytes Curve25519 scalar

    fun clear() {
        // Zero all arrays on app background
        nostrPrivateKey.fill(0)
        lightningPrivateKey.fill(0)
        libp2pPrivateKey.fill(0)
        signalPrivateKey.fill(0)
    }
}
```

## 3. Implementation Plan

### Phase A: Dependencies (1 hour)

Add to `android/app/build.gradle.kts`:

```kotlin
// BIP-39 (proper 2048-word mnemonic with checksum)
implementation("io.github.novacrypto:BIP39:0.1.2")
implementation("io.github.novacrypto:BIP32:0.1.2")

// secp256k1 for Nostr + Bitcoin
implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.15.0")

// Bouncy Castle for Ed25519 (libp2p needs the raw key material)
implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

// Already present: Tink (HKDF), SQLCipher, Room
```

Add to `libs.versions.toml`:
```toml
[versions]
novacrypto = "0.1.2"
secp256k1 = "0.15.0"
bouncycastle = "1.78.1"

[libraries]
novacrypto-bip39 = { module = "io.github.novacrypto:BIP39", version.ref = "novacrypto" }
novacrypto-bip32 = { module = "io.github.novacrypto:BIP32", version.ref = "novacrypto" }
secp256k1 = { module = "fr.acinq.secp256k1:secp256k1-kmp-jni-android", version.ref = "secp256k1" }
bouncycastle = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
```

### Phase B: New IdentityManager (1 day)

Replace `IdentityManager.kt` entirely. New structure:

```kotlin
@Singleton
class IdentityManager @Inject constructor(
    private val context: Context,
    private val db: AppDatabase  // Need to inject DB here — requires DI restructuring
) {
    companion object {
        private const val KEYSTORE_ALIAS = "neop2p_master_wrap"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val SEED_TABLE = "encrypted_seed"
    }

    // ── Public API ────────────────────────────────────────

    /** Returns all protocol identities (cached). */
    fun getIdentities(): ProtocolIdentities

    /** Generate fresh identity + show mnemonic to user. */
    suspend fun generateNewIdentity(): Result<Mnemonic>

    /** Restore from 12-word mnemonic. */
    suspend fun restoreFromMnemonic(words: List<String>): Result<Unit>

    /** Reset everything — new seed, new keys. */
    suspend fun resetIdentity(): Result<Unit>

    /** Sign a Nostr event (Schnorr secp256k1). */
    fun signNostrEvent(eventHash: ByteArray): ByteArray

    /** Sign a Lightning transaction (ECDSA secp256k1). */
    fun signLightningTx(txHash: ByteArray): ByteArray

    /** Get libp2p PrivKey (Ed25519 raw bytes). */
    fun getLibP2PPrivateKey(): ByteArray

    /** Get Signal X25519 key pair. */
    fun getSignalKeyPair(): SignalKeyPair

    /** Export mnemonic (only for backup flow). */
    suspend fun exportMnemonic(): Result<List<String>>
}
```

### Phase C: Data Layer Changes (2 hours)

New Room entity for encrypted seed:

```kotlin
@Entity(tableName = "encrypted_seed")
data class EncryptedSeedEntity(
    @PrimaryKey val id: Int = 1,  // Single-row table
    val encryptedBlob: ByteArray,   // AES-256-GCM ciphertext
    val iv: ByteArray,              // 12-byte nonce
    val tag: ByteArray,             // 16-byte GCM auth tag
    val createdAt: Long = System.currentTimeMillis()
)
```

New DAO:
```kotlin
@Dao
interface SeedDao {
    @Query("SELECT * FROM encrypted_seed WHERE id = 1")
    suspend fun getSeed(): EncryptedSeedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSeed(seed: EncryptedSeedEntity)

    @Query("DELETE FROM encrypted_seed")
    suspend fun deleteAll()
}
```

Update `AppDatabase` to include `EncryptedSeedEntity` and `SeedDao`.

### Phase D: Nostr Client Rewrite (4 hours)

Current `NostrClient.buildSignedEvent()` produces `"placeholder_sig"`. Rewrite to:

1. Compute event ID: `sha256(serializeEvent(event))` per NIP-01
2. Sign with secp256k1 Schnorr: `schnorrSign(eventId, nostrPrivateKey)`
3. Produce valid NIP-01 event JSON

New `NostrSigner` class:
```kotlin
class NostrSigner(private val privateKey: ByteArray) {
    fun signEvent(pubkey: String, kind: Int, content: String, tags: List<List<String>>): SignedEvent {
        val createdAt = System.currentTimeMillis() / 1000
        val event = UnsignedEvent(pubkey, createdAt, kind, tags, content)
        val hash = sha256(event.serialize())
        val signature = secp256k1SchnorrSign(hash, privateKey)
        return SignedEvent(event, hash.toHex(), signature.toHex())
    }
}
```

### Phase E: Signal Protocol Persistence (1 day)

Current in-memory stores → Room-backed SQLCipher stores.

New `SignalProtocolStore` implementations:

```kotlin
class SqlCipherPreKeyStore(private val db: AppDatabase) : PreKeyStore {
    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val entity = db.preKeyDao().load(preKeyId) ?: throw InvalidKeyIdException()
        return PreKeyRecord(entity.serializedData)
    }
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        db.preKeyDao().save(PreKeyEntity(preKeyId, record.serialize()))
    }
    // ... containsPreKey, removePreKey
}

class SqlCipherSessionStore(private val db: AppDatabase) : SessionStore {
    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val entity = db.sessionDao().load(address.name) ?: return SessionRecord()
        return SessionRecord(entity.serializedData)
    }
    // ... storeSession, containsSession, deleteSession
}

// Same pattern for SignedPreKeyStore, IdentityKeyStore
```

New Room entities:
```kotlin
@Entity(tableName = "signal_pre_keys")
data class PreKeyEntity(
    @PrimaryKey val preKeyId: Int,
    val serializedData: ByteArray
)

@Entity(tableName = "signal_sessions")
data class SessionEntity(
    @PrimaryKey val peerId: String,
    val deviceId: Int,
    val serializedData: ByteArray
)

@Entity(tableName = "signal_identity_keys")
data class IdentityKeyEntity(
    @PrimaryKey val id: Int = 1,
    val identityKeyPair: ByteArray,
    val registrationId: Int
)
```

This is the full fix for #4 (Signal persistence) — all four stores become SQLCipher-backed.

### Phase F: libp2p Identity Fix (2 hours)

Current `LibP2PManager.convertToLibp2pKey()` takes the Ed25519 KeyStore key and wraps it via `KeyKt.unmarshalPrivateKey()`. This was already wrong because the KeyStore key isn't the libp2p key.

Replace with:
```kotlin
private fun getLibp2pPrivKey(): PrivKey {
    val seed = identityManager.getLibP2PPrivateKey() // 32-byte Ed25519 seed from BIP-32
    return Ed25519Kt.generateEd25519KeyPair(seed).second
}
```

The PeerID is now correctly derived from the Ed25519 public key via libp2p's multihash encoding, not a synthetic SHA-256 string.

### Phase G: Lightning Integration (2-3 days)

Replace `EscrowService.generatePayoutTransaction()` placeholder with actual PSBT construction:

```kotlin
// Using bitcoinj (not LDK for MVP — LDK adds massive complexity)
val tx = Transaction(params)
tx.addInput(fundingTxHash, outputIndex, ScriptBuilder.createP2SHMultiSigInputScript(
    null,  // signatures filled later
    redeemScript  // 2-of-3 multisig
))
tx.addOutput(Coin.valueOf(tradeAmountSats), sellerAddress)
tx.addOutput(Coin.valueOf(feeSats), Address.fromString(params, feeWalletAddress))

// Return PSBT for buyer + seller to sign separately
return Psbt.fromUnsignedTx(tx)
```

This is Phase 1.5 material. For v1.0, the escrow flow can stay as a state machine with PSBT generation. On-chain signing comes in v1.1.

## 4. Files to Modify

| File | Change | Effort |
|------|--------|--------|
| `IdentityManager.kt` | Full rewrite to BIP-39 + BIP-32 + KeyStore encryption | 1 day |
| `AppDatabase.kt` | Add `EncryptedSeedEntity`, `SeedDao`, signal store entities | 2 hours |
| `AppModule.kt` | Update DI graph — IdentityManager now needs DB | 30 min |
| `NostrClient.kt` | Replace placeholder signing with secp256k1 Schnorr | 4 hours |
| `SignalProtocol.kt` | Replace in-memory stores with SQLCipher stores | 1 day |
| `LibP2PManager.kt` | Fix `convertToLibp2pKey()` to use derived Ed25519 key | 2 hours |
| `EscrowService.kt` | Replace placeholder with PSBT generation (Phase 1.5) | 2-3 days |
| `build.gradle.kts` (app) | Add novacrypto, secp256k1, bouncycastle deps | 30 min |
| `libs.versions.toml` | Add version entries | 15 min |
| `NeoP2PConfig.kt` | Add derivation path constants | 15 min |

## 5. Migration Path

**Problem**: Existing installs have the old Ed25519-only identity. They need to migrate.

**Solution**: On app update, detect old identity format. Show user a migration screen:

```
┌─────────────────────────────┐
│  Identity Upgrade Required   │
│                             │
│  Your current identity uses │
│  an older format. We'll     │
│  generate a new one with    │
│  better security.             │
│                             │
│  [Generate New Identity]    │
│  [Restore from Mnemonic]    │
└─────────────────────────────┘
```

Old identity is abandoned. Old trade data in SQLCipher becomes orphaned (peer IDs won't match). For a pre-release app, this is acceptable. For production, we'd need a migration that re-derives the old identity as a "legacy" entry and links it to the new one.

## 6. Security Model

| Threat | Mitigation |
|--------|-----------|
| Seed stolen from disk | AES-256-GCM encrypted. KeyStore key never leaves TEE. |
| Seed stolen from memory | Keys held in `ByteArray` (not pinned objects). Zeroed on background. |
| Malicious app backup | `android:allowBackup="false"` in manifest. Exclude from Google Backup. |
| Brute force mnemonic | 128-bit entropy = 2^128 combinations. Infeasible. |
| Shoulder-surf mnemonic | Require PIN/biometric before showing. Display one word at a time. |
| KeyStore key invalidated | Detect on launch. Prompt for mnemonic restore. |
| Cross-protocol correlation | Documented tradeoff. Mitigation: user can create separate identities per protocol (advanced). |

## 7. Testing Strategy

1. **Unit tests**: BIP-39 checksum validation, derivation path correctness, HKDF passphrase determinism
2. **Integration tests**: Nostr event roundtrip (sign → verify with independent secp256k1), Signal session serialize/deserialize, SQLCipher passphrase consistency
3. **Property tests**: All derivations produce deterministic keys, all stores survive app restart
4. **Security tests**: Verify encrypted seed cannot be decrypted without KeyStore key (instrumented test with alternate KeyStore)

## 8. Timeline Estimate

| Phase | Effort | Parallelizable? |
|-------|--------|----------------|
| Dependencies + build config | 2 hours | No |
| New IdentityManager | 1 day | No |
| DB schema + DAOs | 2 hours | With IdentityManager |
| Nostr signing rewrite | 4 hours | Yes |
| Signal persistence | 1 day | Yes |
| libp2p identity fix | 2 hours | Yes |
| Integration testing | 1 day | After all above |
| **Total** | **~3-4 days** | |

## 9. What This Fixes

| Issue # | Before | After |
|---------|--------|-------|
| #1 | One Ed25519 key, fake identities | Proper BIP-32 derived keys per protocol |
| #2 | "placeholder_sig" Nostr events | Valid secp256k1 Schnorr signatures |
| #5 | In-memory Signal stores | SQLCipher-backed persistent stores |
| #6 | "BUYER_SIG_PLACEHOLDER" escrow | Real PSBT with bitcoinj (Phase 1.5) |
| #7 | "SIG_${peerId}" fake attestations | Ed25519 signatures over attestation payload |
| #8 | 256-word broken BIP-39 | Full 2048-word BIP-39 with checksum |

## 10. What This Does NOT Fix

- #3 (SQLCipher passphrase) — already fixed in CRITICAL.md / SqlCipherPassphraseManager.kt
- #4 (TURN credentials) — already fixed in CRITICAL.md / build.gradle.kts
- #9 (Nostr reconnection) — needs Ktor WebSocket heartbeat + reconnection loop
- #10 (Escrow state persistence) — needs `EscrowService` to write to Room on every transition
- #13 (Tor support) — not in scope for v1.0

---

*This rewrite is the only path to a functional v1.0. Everything else is dependent on correct identity derivation.*
