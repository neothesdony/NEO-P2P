# NEO-P2P Security Posture

**Updated:** 2026-08-31 (Phase 4: RNS/LXMF is the only transport — libp2p/Nostr/WebRTC removed)

## Threat Model (short)

- **Adversary:** transport-node operators, network observers, app-package analysts, device thieves.
- **Assumptions**: no server-side trust — all trade data travels over RNS/LXMF (the transport node only routes packets; it cannot read LXMF message content, which is encrypted to the destination identity); the app must be safe even when the transport node is adversarial.
- **Out of scope today**: Tor transport, post-quantum key agreement, host-based attestation.

## Identity & Key Hierarchy

- BIP-39 mnemonic (12 words, checksum-validated) is the single recovery secret.
- Seed encrypted with AES-256-GCM by an **AndroidKeyStore** key
  (`neop2p_identity_seed`): StrongBox preferred, TEE fallback, key never exported.
- Derivation (BIP-32/SLIP-10, `IdentityManager`):
  | Purpose | Path | Key type |
  |---|---|---|
  | Nostr identity (kept for escrow signing) | `m/44'/1237'/0'/0/0` | secp256k1 (x-only) |
  | Bitcoin/Lightning | `m/44'/0'/0'/0/0` | secp256k1 |
  | libp2p peerId (RNS displayName) | `m/44'/888'/0'/0/0` | Ed25519 |
  | RNS identity (X25519 + Ed25519) | `m/44'/999'/0'/0/1` + `m/44'/999'/0'/0/2` | X25519 + Ed25519 |
  | Chat E2EE | `m/44'/999'/0'/0/0` | X25519 |
- **P0-4**: on devices with a lock-screen credential, the seed key requires
  recent user authentication (5-minute validity window). A locked key raises
  `IdentityLockedException` — the app **never** silently generates a replacement
  identity (which would orphan the existing one). The background service posts
  an "Identitas terkunci" notification instead of failing silently (2026-08-28).
- **Restore guard (2026-08-28)**: `restoreFromSeedPhrase` refuses to overwrite
  a loadable identity (`RestoreGuard`); the locked/invalidated-key path
  (lock-screen change) still allows restore because it is the only recovery.
- **Recovery phrase UX (2026-08-28)**: Settings → "Lihat Frasa Pemulihan"
  reveals the phrase behind a BiometricPrompt (strong biometric or device
  credential), masked by default. The onboarding completion flag is durable
  (`OnboardingStore`) — a kill between identity generation and seed verification
  returns the user to the backup step instead of skipping it. Copied seed
  phrases auto-clear from the system clipboard after 60s (only if still ours).

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
  (2026-08-24: handshake is now live over LXMF — two-shot pre-key exchange
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
- **Fee model (0.5%, seller-only):** the seller deposits `crypto + 0.5% fee + network fee`; the buyer pays no fee and receives the full crypto amount; the 0.5% goes to the fee wallet. **Money is integer-only (G.M.01, 2026-08-29):** `feeSats = (sats * FEE_NUM) / FEE_DEN` (`5/1000`, `NeoP2PConfig.FEE_NUM/FEE_DEN`) and `fiatAmount = (btcSats * priceIdr) / 100_000_000` — no `Double` round-trip on money; a non-whole typed price is rejected (`parseIdrToLong`) so it can never be misread as a 10x integer.
- **Network (miner) fee is budgeted:** the payout tx previously had a zero miner fee (invalid); a dynamic fee (`rate × ~220 vbytes`, from `ChainMonitor.estimateFees()`) is now added to the seller's deposit and stored as `network_fee_sats`.
- **Timeouts (spec values, 2026-08-28):** unfunded escrows auto-`CANCELLED` after 45 min (warning at 30); funded-but-stalled escrows auto-`REFUNDED` to the seller's own address after 12 h + 48 h grace (reminder at 12 h). The previous "(2× for test)" multiplier was removed — the constants now match the product spec.
- **Payment window (2026-08-25):** the buyer can mark the fiat payment as sent (`markPaid` → `PAYMENT_PENDING`). The seller then has **24 h + 12 h grace** to release or dispute; if the window expires the escrow auto-transitions to `DISPUTED` — never silently auto-refunded, because the buyer may have actually paid and the arbitrator decides with evidence.
- **SIGNED is a forward state (2026-08-28):** `generatePayoutTransaction` persists SIGNED transiently before CONFIRMING; the router accepts SIGNED in the forward order, the sweep auto-refunds stalled SIGNED like FUNDED, `getEscrow` resume-heal re-publishes it, and `confirmReceipt` retries from it — a kill in the SIGNED→CONFIRMING window can no longer strand funds.
- **Dispute evidence (2026-08-25, LXMF since Phase 4):** both parties can attach payment receipts (image + description) to a disputed escrow via `DisputeEvidenceScreen`; evidence is stored in the SQLCipher-encrypted `dispute_evidence` table AND delivered over LXMF (file attachment, ≤60KB compressed). The arbitrator persists evidence to DB (`P2POrchestrator.applyEvidenceEvent`) so reboot survives; LXMF delivery is E2EE to the destination identity (do not include sensitive data beyond reference).
- **Arbitration transport (2026-08-25, LXMF since Phase 4):** disputes (with redeem script + unsigned payout tx (+ `refund_tx_hex`)), evidence, and resolutions (+ `signed_tx_hex`) travel as LXMF DIRECT messages (title = type, FIELD_CUSTOM_DATA = JSON; evidence images as file attachments). `PendingDisputeStore` retries every 60s via `P2POrchestrator.sweepStaleEscrows` if LXMF delivery fails. The arbitrator key is derived from the admin's mnemonic at `m/44'/999'/0'/1/0`; Arbitrator Mode unlocks in Settings when the active identity's derived pubkey matches `NeoP2PConfig.ARBITRATOR_PUBKEY`. `NeoP2PConfig.ARBITRATOR_PEER_ID` (blank = disabled) lets parties deliver disputes/evidence to the arbitrator over LXMF. Resolutions are applied by parties via `storeArbitrationDecision` (idempotent) and **auto-broadcast as 2-of-3** — the arbitrator signature plus the local key filling the buyer/seller role slots assemble the scriptSig and move funds on-chain immediately (`RELEASE_TO_BUYER` → payout to the buyer; `REFUND_TO_SELLER` → refund to the seller). **2026-08-31:** `DisputeFeed` merges SQLCipher `arbitrator_disputes` (Room v22, survives reboot) + LXMF stream, sorted newest-first, per-card busy. See `docs/ARBITRATION.md`.
- **Refund destination (2026-08-28, v20):** a `REFUND_TO_SELLER` resolution refunds to the **seller's** address — the seller's device publishes its refund address via escrow_status LXMF at escrow creation, the dispute message carries it to the arbitrator, the resolution carries it back, and the applying party persists it (`escrows.refund_destination`) before broadcasting. Pre-v20 the refund tx was built to the **local device's** address, so an arbitrator-applied refund paid the arbitrator.
- **Configurable confirmations (2026-08-25):** `onEscrowFunded` requires `required_confirmations` (default 1) before accepting a funding tx. Depth is derived from `status.block_height` vs the explorer tip — Mempool/Esplora do **not** return a `confirmations` field (2026-08-28 fix; previously every funding tx read 0 confirmations and the gate always failed, with the 90-min sweep rescue promoting funded escrows instead).
- **Post-trade ratings (2026-08-25, local-only since Phase 4):** when an escrow reaches `RELEASED`/`REFUNDED` a rating dialog creates a signed attestation (`ReputationSystem.createAttestation`) stored locally — the Nostr gossip publish was removed; reputation is computed from the local attestations table.
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
- **RNS/LXMF transport (Phase 4):** phones connect as TCP clients to the VPS transport node (`relay1.custom-minipc.com:42000`, rnsd-kt `enableTransport=true`). The transport node routes packets but cannot read LXMF message content — LXMF messages are encrypted to the destination identity (RNS link encryption + app-level E2EE envelope = two layers). The Python `lxmd` propagation node provides store-and-forward for offline peers (messages are stored encrypted; the node cannot decrypt them).
- **No certificate pinning yet (P1):** the RNS TCP transport uses the Reticulum identity system (not TLS); the transport node's identity is the trust anchor. Revisit pinning if a TLS-based interface is added.

## Planned (not implemented)

- **LDK Node** (Kotlin bindings): hold-invoice escrow — on-chain 2-of-3 P2SH
  stays the fallback for large trades.
- **Play Integrity + biometric prompt** on escrow-signing operations
  (device attestation before release/dispute signatures).
- **NIP-44/59 wire compatibility** (deferred): either hand-rolled Kotlin
  (secp256k1 ECDH + XChaCha20 + padding + NIP-59 gift-wrap) or the rust-nostr
  SDK. rust-nostr requires native `.so` deps that must be verified 16 KB-aligned
  before adoption. See "Decision: NIP-59 / rust-nostr deferred" above.
- **Attestation gossip** (deferred): post-trade attestations are local-only
  since Phase 4; a gossip path over LXMF is the planned replacement for the
  removed Nostr kind:33335.

## Verification

```bash
cd android
./gradlew :app:assembleDebug   # build
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```
