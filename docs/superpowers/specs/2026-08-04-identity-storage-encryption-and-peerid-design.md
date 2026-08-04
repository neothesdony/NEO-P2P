# Design: Identity Storage Encryption & Real libp2p PeerID (Cluster B)

**Date:** 2026-08-04
**Status:** Approved (design review)
**Applies to:** `android/` module (NEO-P2P Android app)

## Summary

Two security/correctness defects in `IdentityManager` (`data/p2p/IdentityManager.kt`):

1. **At-rest plaintext.** `saveIdentityToStorage()` writes the raw seed phrase and
   all derived private keys (Nostr, libp2p, Signal) to **plaintext
   SharedPreferences** (`IdentityManager.kt:446-461`). This contradicts the
   documented security contract in `CRITICAL.md` (finding #3 was only fixed for
   the SQLCipher DB passphrase, not the identity seed itself).

2. **Fabricated PeerID.** `deriveLibp2pPeerId()` returns
   `"12D3KooW" + base58(SHA-256(privkey)[:14])` (`IdentityManager.kt:433-439`) —
   a synthetic string not cryptographically bound to the real Ed25519 key. It can
   diverge from the PeerID `LibP2PManager` actually derives when it initializes
   its host from the same seed.

This design encrypts the identity blob at rest with a KeyStore-wrapped AES-GCM key
and derives a real libp2p PeerID from the Ed25519 public key, exactly matching the
host.

## Context (current state)

- `IdentityManager` derives all protocol keys from a BIP-39/BIP-32 seed. It caches
  them in memory and persists the seed phrase + derived keys to SharedPreferences
  in plaintext.
- `LibP2PManager.start()` calls `identityManager.getLibp2pPrivateKey()` and
  `unmarshalEd25519PrivateKey(seed)` to build its host; the host derives a PeerID
  from the public key via libp2p's `PeerId.fromPubKey(...)`.
- The jvm-libp2p API exposes `io.libp2p.crypto.keys.Ed25519Kt.unmarshalEd25519PrivateKey(seed)`,
  `PrivKey.publicKey()`, and `io.libp2p.core.PeerId.fromPubKey(PubKey)`.
- `SqlCipherPassphraseManager` already uses the KeyStore-wrapped-AES-GCM pattern we
  mirror for the identity seed.

## Approach

Two coordinated changes confined to `IdentityManager` plus a new `SeedCipher` helper:

1. **`SeedCipher`** — a small `@Singleton` wrapping an AndroidKeyStore AES-256-GCM key
   for encrypt/decrypt of the identity blob. Non-extractable key; only ciphertext and
   IV ever hit disk.
2. **`IdentityManager` storage** — serialize the identity into one compact blob,
   encrypt it via `SeedCipher`, store the single ciphertext field in SharedPreferences,
   and decrypt on load. Migrate any legacy plaintext fields into the blob.
3. **`IdentityManager` PeerID** — derive the PeerID from the real Ed25519 public key
   via `unmarshalEd25519PrivateKey` + `PeerId.fromPubKey`, replacing the fabricated
   SHA-256 string.

## Detailed Design

### 1. `SeedCipher` (`data/p2p/SeedCipher.kt`)

Mirrors the existing `SqlCipherPassphraseManager` pattern.

- KeyStore alias: `neop2p_identity_seed`.
- Key: AES-256-GCM, `PURPOSE_ENCRYPT | PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`,
  `ENCRYPTION_PADDING_NONE`, `setIsStrongBoxBacked(true)` (falls back gracefully if
  StrongBox unavailable).
- `fun encrypt(plaintext: ByteArray): ByteArray` — AES/GCM/NoPadding with a random
  12-byte IV; returns `iv || ciphertext || tag` (GCM appends the tag to ciphertext).
- `fun decrypt(blob: ByteArray): ByteArray` — parse `iv (12) || rest`, decrypt,
  throw `IllegalArgumentException` on malformed input or auth failure.
- `@Singleton`, constructed via Hilt from `Context`.

### 2. Identity blob serialization + storage

- Define a private binary format for the identity (length-prefixed fields, matching
  the `EnvelopeCodec` framing style used elsewhere):
  `[seedPhrase (joined " ")] [peerId] [nostrPubkeyHex] [nostrPrivateKeyHex] [nickname] [lnNodeId]`.
- `saveIdentityToStorage(identity)`:
  - serialize the 6 fields to a `ByteArray`;
  - `val encrypted = SeedCipher.encrypt(bytes)`;
  - store a single SharedPreferences string key `encrypted_identity` (Base64 of
    `encrypted`) plus a version marker `identity_version = 2`.
- `loadIdentityFromStorage()`:
  - read `encrypted_identity`; if present, `SeedCipher.decrypt`, deserialize, then
    re-derive all keys from the seed phrase (existing derivation path) and return the
    `Identity`.
  - **Migration:** if `encrypted_identity` is absent but legacy plaintext fields
    (`seed_phrase`, `peer_id`, `nostr_pubkey`, `nostr_privkey`, `nickname`,
    `ln_node_id`) exist, build the blob from those, save via the encrypted path, and
    delete the legacy plaintext keys.
- `resetIdentity()` clears the `encrypted_identity` + `identity_version` keys (in
  addition to its existing KeyStore-alias delete).

### 3. Real libp2p PeerID

Replace `deriveLibp2pPeerId(privateKeySeed: ByteArray)`:

```kotlin
private fun deriveLibp2pPeerId(privateKeySeed: ByteArray): String {
    val priv = io.libp2p.crypto.keys.Ed25519Kt.unmarshalEd25519PrivateKey(privateKeySeed)
    return io.libp2p.core.PeerId.fromPubKey(priv.publicKey()).toBase58()
}
```

- Remove the now-unused `bytesToBase58(...)` SHA-256 fabrication and the
  `MessageDigest`-based PeerID code path in that function.
- The peerId is persisted in the blob (Part 2); on load it is re-derived from the
  seed so it stays consistent with what the host derives.

## Testability

- `SeedCipher` depends on AndroidKeyStore, which is unavailable in plain JUnit
  (no Robolectric). To keep the framing and (de)serialization unit-testable:
  - Extract the **binary framing** (serialize/deserialize identity ↔ `ByteArray`)
    into pure Kotlin functions/object testable without Android.
  - `SeedCipher`'s `encrypt`/`decrypt` byte-layout (`iv || ct || tag`) is exercised
    by a **test-only seam**: a pure-JVM `AesGcm` helper (using standard
    `javax.crypto`) with the same IV/ciphertext layout, shared by the test, so the
    blob format is verified on JVM without AndroidKeyStore.
- New unit tests: `IdentityBlobCodecTest` (framing round-trip), and
  `SeedCipherLayoutTest` (IV-prefix + round-trip via the JVM AES-GCM seam).
- Existing `IdentityManagerTest` continues to pass.

## Out of Scope

- Changing the BIP-32 derivation paths (they stay).
- UI/onboarding changes.
- Cross-protocol linkability redesign (keys remain derived from one seed by design).
- WebRTC / reputation / escrow changes.

## Verification

- Build: `./gradlew :app:assembleDebug` (from `android/`).
- Unit tests: `./gradlew :app:testDebugUnitTest`.
- Lint: `./gradlew :app:lintDebug` (lint-baseline.xml retained).
