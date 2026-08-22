# NEO-P2P Security Posture

**Updated:** 2026-08-22 (P0/P1 hardening pass)

## Threat Model (short)

- **Adversary:** relay operators, network observers, app-package analysts, device thieves.
- **Assumptions**: no server-side trust — all trade data lives on Nostr relays the user can switch; the app must be safe even when every relay is adversarial.
- **Out of scope today**: Tor transport, post-quantum key agreement, host-based attestation.

## Identity & Key Hierarchy

- BIP-39 mnemonic (12 words, checksum-validated) is the single recovery secret.
- Seed encrypted with AES-256-GCM by an **AndroidKeyStore** key
  (`neop2p_identity_seed`): StrongBox preferred, TEE fallback, key never exported.
- Derivation (BIP-32/SLIP-10, `IdentityManager`):
  | Purpose | Path | Key type |
  |---|---|---|
  | Nostr identity | `m/44'/1237'/0'/0/0` | secp256k1 (x-only) |
  | Nostr per-trade (P0-3) | `m/44'/1237'/0'/0/<index>` | secp256k1 — fresh key per offer/trade |
  | Bitcoin/Lightning | `m/44'/0'/0'/0/0` | secp256k1 |
  | libp2p | `m/44'/888'/0'/0/0` | Ed25519 |
  | Chat E2EE | `m/44'/999'/0'/0/0` | X25519 |
- Trade-key index persisted in SharedPreferences; rotating per offer prevents
  cross-trade linkability (Mostro-style).
- **P0-4**: on devices with a lock-screen credential, the seed key requires
  recent user authentication (5-minute validity window). A locked key raises
  `IdentityLockedException` — the app **never** silently generates a replacement
  identity (which would orphan the existing one).

## Data at Rest

- Room database is SQLCipher-encrypted (`neop2p.db`) with a passphrase derived
  from a KeyStore-wrapped AES key (`SqlCipherPassphraseManager`) — **no
  hardcoded passphrase**.
- Chat ciphertext and conversation keys live in the encrypted DB.
- `allowBackup=false` / `fullBackupContent=false` — no cloud backup of keys.

## Chat E2EE (P0-2)

- **libsignal-protocol-java removed** (archived upstream since Feb 2022;
  protobuf-javalite classes crashed under the full protobuf-java runtime).
- Replacement: a **custom, NIP-44-*inspired*** scheme via Bouncy Castle — **not**
  wire-compatible with NIP-44/59.
  - shared secret = X25519 ECDH (local key from mnemonic `m/44'/999'/0'/0/0`,
    peer key from a pre-key bundle handshake)
  - key = HKDF-SHA256(shared, info `neop2p-chat-v1`)
  - ciphertext = 12-byte random nonce ‖ ChaCha20-Poly1305 ct ‖ tag
- Peer keys persisted in `conversation_keys` (SQLCipher) — sessions survive
  restarts (previous in-memory stores were lost on restart).

### Known limitations (accepted for this pass)

- **Not NIP-44/59-compatible.** Real Nostr clients cannot read these messages.
  The scheme uses X25519 (not the secp256k1 Nostr key), ChaCha20-Poly1305 with
  a 12-byte nonce (not XChaCha20), no padding, and no NIP-59 gift-wrap. It is
  interoperable only between two NEO-P2P peers.
- **Handshake friction.** Both sides must be online to exchange X25519 public
  keys before any message can be sent; there is no async/offline delivery.
- **No forward secrecy.** Static-static ECDH — a leaked mnemonic decrypts all
  past messages. The removed Signal Protocol's double-ratchet provided forward
  secrecy; this scheme does not.
- **TOFU key trust.** Peer keys are auto-trusted on first exchange; explicit
  fingerprint verification UI is not implemented.

### Decision: NIP-59 / rust-nostr deferred

Full NIP-44/59 wire compatibility with real Nostr clients would require either
hand-rolling the specs in Kotlin (secp256k1 ECDH + XChaCha20 + padding +
NIP-59 gift-wrap) or adopting the **rust-nostr SDK** (native `.so` deps, which
must be verified 16 KB-aligned for this app). Both are deferred; the current
custom scheme is sufficient for a closed NEO-P2P-only network.

## Network

- `usesCleartextTraffic=false`; cleartext only for localhost/emulator.
- All relay traffic is wss:// via Ktor/OkHttp (hostname verification on).
- **No certificate pinning yet** (P1): pins could not be derived because the
  relay TLS endpoint was unreachable at hardening time; pinning public relays
  (nos.lol, damus.io) is intentionally avoided. Revisit after pins are
  verified against relay1.custom-minipc.com:7001 etc.
- TURN credentials are injected via BuildConfig from `local.properties`
  (never committed; debug defaults `changeme_*`).

## Planned (not implemented)

- **LDK Node** (Kotlin bindings): hold-invoice escrow — on-chain 2-of-3 P2SH
  stays the fallback for large trades.
- **Play Integrity + biometric prompt** on escrow-signing operations
  (device attestation before release/dispute signatures).
- **NIP-44/59 wire compatibility** (deferred): either hand-rolled Kotlin
  (secp256k1 ECDH + XChaCha20 + padding + NIP-59 gift-wrap) or the rust-nostr
  SDK. rust-nostr requires native `.so` deps that must be verified 16 KB-aligned
  before adoption. See "Decision: NIP-59 / rust-nostr deferred" above.

## Verification

```bash
cd android
./gradlew :app:assembleDebug   # build
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```
