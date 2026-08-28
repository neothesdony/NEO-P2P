# NEO-P2P Security Posture

**Updated:** 2026-08-24 (chat E2EE live, wallet added, ChainMonitor fallback)

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
  (2026-08-24: handshake is now live over the relay — two-shot pre-key exchange
  with an idempotency guard; sessions survive restarts via SQLCipher.)
- **No forward secrecy.** Static-static ECDH — a leaked mnemonic decrypts all
  past messages. The removed Signal Protocol's double-ratchet provided forward
  secrecy; this scheme does not.
- **TOFU key trust.** Peer keys are auto-trusted on first exchange. Mitigated
  2026-08-28: an 8-word BIP-39 fingerprint of the counterparty identity renders
  in the chat top bar + escrow header (copyable, compare out-of-band) — the
  accepted TOFU anchor; explicit in-app confirmation is still not implemented.

### Decision: NIP-59 / rust-nostr deferred

Full NIP-44/59 wire compatibility with real Nostr clients would require either
hand-rolling the specs in Kotlin (secp256k1 ECDH + XChaCha20 + padding +
NIP-59 gift-wrap) or adopting the **rust-nostr SDK** (native `.so` deps, which
must be verified 16 KB-aligned for this app). Both are deferred; the current
custom scheme is sufficient for a closed NEO-P2P-only network.

## Escrow (on-chain 2-of-3 P2SH) — live

- Escrow is a **real on-chain 2-of-3 P2SH multisig**, wired into the app (`data/escrow/EscrowService.kt`, `ChainMonitor.kt`).
- **Fee model (0.3%, seller-only):** the seller deposits `crypto + 0.3% fee + network fee`; the buyer pays no fee and receives the full crypto amount; the 0.3% goes to the fee wallet.
- **Network (miner) fee is budgeted:** the payout tx previously had a zero miner fee (invalid); a dynamic fee (`rate × ~220 vbytes`, from `ChainMonitor.estimateFees()`) is now added to the seller's deposit and stored as `network_fee_sats`.
- **Timeouts:** unfunded escrows auto-`CANCELLED` after 90 min (2× for test; 45 min in production); funded-but-stalled escrows auto-`REFUNDED` to the seller's own address after 24 h + 96 h grace (2× for test; 12 h + 48 h in production).
- **Payment window (2026-08-25):** the buyer can mark the fiat payment as sent (`markPaid` → `PAYMENT_PENDING`). The seller then has **48 h + 24 h grace (2× for test; 24 h + 12 h in production)** to release or dispute; if the window expires the escrow auto-transitions to `DISPUTED` — never silently auto-refunded, because the buyer may have actually paid and the arbitrator decides with evidence.
- **Dispute evidence (2026-08-25):** both parties can attach payment receipts (image + description) to a disputed escrow via `DisputeEvidenceScreen`; evidence is stored in the SQLCipher-encrypted `dispute_evidence` table, never published to the relay.
- **Arbitration transport (2026-08-25):** disputes (`kind:33386`, with redeem script + unsigned payout tx), evidence (`kind:33387`) and resolutions (`kind:33388`) travel over the relay. The arbitrator key is derived from the admin's mnemonic at `m/44'/999'/0'/1/0`; Arbitrator Mode unlocks in Settings when the active identity's derived pubkey matches `NeoP2PConfig.ARBITRATOR_PUBKEY`. Resolutions are applied by parties via `storeArbitrationDecision` (idempotent) and **auto-broadcast as 2-of-3** — the arbitrator signature plus the local key filling the buyer/seller role slots assemble the scriptSig and move funds on-chain immediately (`RELEASE_TO_BUYER` → payout to the buyer; `REFUND_TO_SELLER` → refund to the seller). See `docs/ARBITRATION.md`.
- **Refund destination (2026-08-28, v20):** a `REFUND_TO_SELLER` resolution refunds to the **seller's** address — the seller's device publishes its refund address via kind:33337 at escrow creation, the dispute event (kind:33386) carries it to the arbitrator, the resolution (kind:33388) carries it back, and the applying party persists it (`escrows.refund_destination`) before broadcasting. Pre-v20 the refund tx was built to the **local device's** address, so an arbitrator-applied refund paid the arbitrator.
- **Configurable confirmations (2026-08-25):** `onEscrowFunded` requires `required_confirmations` (default 1) before accepting a funding tx. Depth is derived from `status.block_height` vs the explorer tip — Mempool/Esplora do **not** return a `confirmations` field (2026-08-28 fix; previously every funding tx read 0 confirmations and the gate always failed, with the 90-min sweep rescue promoting funded escrows instead).
- **Post-trade ratings (2026-08-25):** when an escrow reaches `RELEASED`/`REFUNDED` a rating dialog publishes a signed kind:33335 attestation (`ReputationSystem.createAttestation` + `NostrClient.publishAttestation`) — the missing half of the reputation loop.
- **Auto-cancel safety (2026-08-25):** `expireStaleEscrows()` now checks the P2SH funding address on-chain (`hasOnChainDeposit`) before auto-cancelling a stale `FUNDING` escrow. If a deposit exists (broadcast succeeded but verification failed, or the tx is slow to confirm), it promotes to `FUNDED` instead of cancelling — a funded escrow is never orphaned.
- **Broadcast parsing (2026-08-25):** `ChainMonitor.broadcastTx` accepts Mempool/Esplora's **plain-text txid** response for `POST /api/tx` (they do not return JSON there). Previously a *successful* broadcast was misreported as a failure.
- **One-tap funding:** `EscrowScreen` FUNDING adds a **"Send from my wallet to escrow"** button (irreversible-broadcast confirm) that sends the exact `depositAmountSats` from the seller's BIP-44 wallet (`WalletService.send`), auto-fills the txid, and verifies on-chain.
- **ChainMonitor fallback:** mempool.space is unreachable on some networks (incl. the dev LAN); all queries fall back to Blockstream.info (identical JSON API). The shared Ktor client has 10s connect / 20s request timeouts. **All testnet queries target Testnet4** (faucet + funded addresses live there since 2026-08; Testnet3 showed a false zero balance).
- **Current limitation:** both escrow role keys are pinned to the current user's key — the buyer key is not yet exchanged over the encrypted channel. This is a known gap to close before production.

## Personal Wallet

- Every identity derives a BIP-44 Bitcoin key (`m/44'/0'/0'/0/0`, secp256k1) → a legacy P2PKH address (`getBitcoinAddress()`), **testnet4 by default** (bitcoinj `TestNet3Params` — address format is identical on Testnet3/4). No balance/UTXO state is stored locally — the wallet is a thin client over ChainMonitor (Mempool/Blockstream, testnet4 endpoints).
- Send builds a raw P2PKH tx (greedy confirmed UTXO selection, change back to self, dust-threshold 546 sats), signs with the BIP-44 key, broadcasts via ChainMonitor. Private key never leaves the device (derived from the encrypted seed on demand).
- QR codes (zxing) carry `bitcoin:<address>` URIs so external wallets can scan them.

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
