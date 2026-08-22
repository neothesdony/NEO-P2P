# Changelog

All notable changes to NEO-P2P will be documented in this file.

## [1.0.5] — 2026-08-22

### Docs

- **Corrected E2EE description across README, AGENTS.md, and SECURITY_POSTURE.md.**
  The chat scheme is a **custom, NIP-44-*inspired*** design (X25519 ECDH +
  HKDF-SHA256 + ChaCha20-Poly1305, 12-byte nonce) — **not** XChaCha20 and
  **not** NIP-44/59 wire-compatible. It is interoperable only between NEO-P2P
  peers.
- **Documented accepted E2EE limitations:** no forward secrecy (static-static
  ECDH), TOFU key trust (no fingerprint verification UI), and the requirement
  that both peers be online to exchange keys.
- **Recorded the decision to defer NIP-59 / rust-nostr** (hand-rolled Kotlin
  NIP-44/59 or the rust-nostr SDK, whose native `.so` deps must be verified
  16 KB-aligned) in `docs/SECURITY_POSTURE.md`.

## [1.0.4] — 2026-08-22

### Security (P0/P1 hardening pass)

#### P0-1 — Bank details no longer leaked to public Nostr offers
- `CreateOfferScreen` no longer publishes `payment_details` (account_number / account_holder) in the public Nostr offer JSON. Account details are still collected in the form but only exchanged after a taker commits, inside an encrypted channel.

#### P0-2 — Dead Signal library replaced
- Removed `org.whispersystems:signal-protocol-java` (archived Feb 2022; protobuf-javalite classes crashed under the full `protobuf-java` runtime required by libp2p) from the version catalog, build, and ProGuard rules.
- New E2EE: NIP-44-style XChaCha20-Poly1305 (X25519 ECDH + HKDF-SHA256) via Bouncy Castle, keyed from the BIP-39 mnemonic (`m/44'/999'/0'/0/0`).
- Conversation keys persist in SQLCipher (`conversation_keys` table) so sessions survive restarts.
- DB migrated 7 → 8 with a drop-and-create migration (old Signal store tables removed); no destructive fallback.

#### P0-3 — Per-trade key rotation
- New `IdentityManager.getNextTradeNostrKeyPair()` derives from `m/44'/1237'/0'/0/<index>` with a persisted index; offers are signed by a fresh key each time, breaking cross-trade linkage.

#### P0-4 — Biometric/device-auth gate on identity seed
- On devices with a lock-screen credential, the identity seed key requires recent user auth (5-min validity window).
- `IdentityLockedException` is thrown when locked — the app never silently generates a replacement identity. Invalidated-key path surfaces a restore prompt.

#### P1 — libp2p 1.3.6 + posture doc
- `jvm-libp2p` bumped to 1.3.6-RELEASE.
- New `docs/SECURITY_POSTURE.md` documents the threat model, key hierarchy, and planned LDK / Play Integrity / rust-nostr work.

## [1.0.3] — 2026-08-22

### Changed

#### Escrow flow (seller funds, buyer receives)
- Clarified the escrow role model: the **seller** supplies BTC and locks `100.5%` (trade amount + buyer half-fee) into the 2-of-3 P2SH multisig; the **buyer** pays IDR via the selected fiat method; on confirmation the payout sends **99.5% → buyer** and **1% → fee wallet**.
- `generatePayoutTransaction()` now pays the **buyer** (99.5%) instead of the seller (previously the direction was inverted).

#### Real P2SH signing
- `signTransaction()` now signs the payout against the **real 2-of-3 P2SH redeem script** (previously signed with `ScriptBuilder.createEmpty()` which could never spend the multisig).
- `releaseFunds()` assembles the correct P2SH scriptSig via bitcoinj `createMultiSigInputScriptBytes()` before broadcasting.
- Escrow now persists the `redeem_script_hex` (DB migration v6 → v7) so the payout can be signed/spent.

#### Fixed
- **`createEscrow()` P2SH address bug**: derived the P2SH address from `Utils.sha256hash160(redeemScript)` instead of passing the raw 105-byte redeem script to `LegacyAddress.fromScriptHash` (which throws `AddressFormatException`).

#### Testing
- Added `EscrowCryptoTest` validating the 2-of-3 P2SH signing end-to-end against a testnet address, including the fee math (100.5% deposit, 99.5% buyer, 1% fee).

### Security
- **Fee wallet signature protection**: the fee address is now signed with an Ed25519 key held only by the owner. `createEscrow()` refuses to run if the signature is invalid, blocking forked builds from redirecting the fee.

## [1.0.2] — 2026-08-07

### Added

#### Onboarding
- **Seed phrase verification step**: New `VERIFY_SEED` step added between backup and finish. The user must re-enter 3 randomly-selected words from their 12-word phrase to confirm they saved it.
- **Nickname persistence**: Nickname entered during onboarding is now saved to the identity and shown on Profile/Home.

#### Create Offer
- **Market-price default**: `pricePerBtc` now pre-fills to `DEFAULT_BTC_MARKET_PRICE_IDR` (static placeholder until a live feed is wired up).
- **Payment method details**: Selecting a fiat method (e.g. BCA) now expands to collect the recipient's account number and account holder name. Details are validated (button stays disabled until complete) and published with the offer as `payment_details`.

#### Settings
- **Live relay status**: The relay list now shows the real connection state (`Connected` / `Not connected`) sourced from `NostrClient.relays`, instead of a hardcoded `Connected` for every relay.

### Changed

#### Relay domains
- Default Nostr, libp2p circuit, and TURN/STUN relay hostnames changed from `*.neop2p.io` to `*.custom-minipc.com` in `NeoP2PConfig.kt`.

#### 16 KB page-size alignment (Google Play requirement)
- Upgraded SQLCipher from the frozen `net.zetetic:android-database-sqlcipher:4.5.4` (4 KB-aligned `.so`) to the actively-maintained `net.zetetic:sqlcipher-android:4.17.0` (16 KB-aligned `.so`).
- Migrated `AppDatabase.kt` to `SupportOpenHelperFactory` (new package `net.zetetic.database.sqlcipher`) and added explicit `System.loadLibrary("sqlcipher")`.
- Removed unused `secp256k1-kmp` dependency (code uses pure Kotlin + Bouncy Castle; no JNI imports).
- Removed `android:pageSizeCompat="enabled"` from the manifest — the app is now truly 16 KB-native.
- **Result**: All native libraries (`libsqlcipher.so`, `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libjingle_peerconnection_so.so`) now report 16 KB LOAD alignment, and no compatibility dialog is shown.

### Fixed

- **Offer Details infinite loading spinner**: `loadOffer(offerId)` is now called on first composition via `LaunchedEffect` instead of only on the Retry button.
- **Profile Edit Nickname did nothing**: Added a working nickname-edit dialog that persists via `IdentityManager.updateNickname()`.
- **Profile View Attestations did nothing**: Now shows a snackbar when tapped.
- **Create Offer submit disabled forever**: `pricePerBtc` now pre-fills and `canSubmit` correctly evaluates filled fields + payment details.
- **Create Offer `Total: Rp Rp 0` double-prefix**: Removed the duplicate currency prefix; totals now compute from the market-price default.
- **Onboarding backup screen stuck spinner after Back**: `isConfirming` is now reset on back-navigation.
- **Chat screen crash** (`Failed to initialize chat: length=3; index=3`): Signal init is now non-fatal. `IdentityKeyPair` is serialized as raw EC bytes (avoiding a protobuf-javalite/full-protobuf conflict), and chat loads with mock messages even when Signal init is unavailable.

### Known Limitations

- Signal Protocol E2EE cannot be fully initialized under the full `protobuf-java` runtime (required by libp2p); javalite-generated message classes conflict. Chat currently runs on mock data; real E2EE needs an architectural protobuf fix.
- `DEFAULT_BTC_MARKET_PRICE_IDR` is a static placeholder; a live BTC/IDR price feed is not yet wired up.
- `relay*.custom-minipc.com` hostnames require DNS records pointing at the relay server before they resolve.

## [1.0.1] — 2026-07-08

### Changed

#### Fee Model
- **1% fee now split 50/50** between buyer and seller (0.5% each)
- Buyer deposits **100.5%** (trade amount + their half of fee)
- Seller receives **99.5%** (their half deducted from payout)
- Fee wallet receives full 1% from combined halves
- `TradeOffer.totalDepositSats` now excludes seller's half of fee
- Added `TradeOffer.buyerFeeSats` and `TradeOffer.sellerFeeSats` computed properties

## [1.0.0-alpha] — 2026-05-14

### Added

#### Core P2P
- Identity system: Ed25519 keypair generation in Android KeyStore (hardware-backed)
- libp2p host with AutoRelay, DHT bootstrap, peer discovery
- Nostr client: NIP-01 event publishing/subscription (Nostr WebSocket via Ktor)
- NIP-65 relay hint support for libp2p peer discovery
- Signal Protocol integration: session establishment, message encryption/decryption
- WebRTC data channel: payment proof P2P file transfer, ICE negotiation

#### Escrow
- Lightning 2-of-3 multisig escrow creation
- Pre-signed payout transaction: 100% seller + 1% fee wallet
- Fee wallet address hardcoded in open-source code (NeoP2PConfig.kt)
- Escrow state machine: FUNDING → FUNDED → SIGNED → RELEASED / DISPUTED / REFUNDED
- 7-day timelock for disputes (timeout-based, no arbitration server needed)

#### UI (8 Screens, Jetpack Compose + Material 3)
- **Onboarding**: 4-step flow (Welcome → Create Identity → Backup Seed → Finish)
- **Home**: Offer feed with pull-to-refresh, peer reputation scores
- **Create Offer**: Buy/Sell toggle, BTC amount, IDR price, fiat method selection
- **Offer Detail**: Full trade summary, fee breakdown, peer profile & reputation
- **Chat**: E2EE messages, file attachment, payment proof sharing
- **Escrow**: Live status tracking, confirm payment, release/trigger dispute
- **Profile**: Keypair display, nickname, reputation statistics
- **Settings**: Relay management, TURN server config, Tor toggle, identity reset

#### Data & Storage
- Room database with SQLCipher encryption
- DAOs: Peer, TradeOffer, Escrow, ChatMessage (Flow-based reactive queries)
- Entities with JSON fields for multiaddrs, relays, fiat methods
- Gossip-based reputation system with signed attestations

#### Infrastructure (Oracle Cloud Free Tier)
- Docker Compose with 4× strfry Nostr relays (3 public + 1 metadata)
- libp2p circuit relay v2 in Go (with Prometheus metrics)
- coturn TURN/STUN server for worst-case CGNAT
- 4 deployment scripts: deploy, status, restart, backup

#### Android Project
- Kotlin 2.1, AGP 8.7.0, Compose BOM 2026.03.00
- Dagger Hilt 2.52, Room 2.7, Ktor 2.4
- ProGuard/R8 rules for optimization
- Foreground service for P2P connection maintenance
- Network security config with cleartext rules for local dev
- Material 3 dark cyber-green theme

#### Fiat Method Support
- 4 bank transfers: BCA, Mandiri, BNI, BRI
- 5 e-wallets: GoPay, OVO, Dana, ShopeePay, LinkAja
- Cash meetup (Tunai)
- Configurable FiatMethod enum in NeoP2PConfig.kt

### Technical Notes
- Zero backend servers: everything runs on-device + Nostr relays + libp2p DHT
- 1% fee enforced via pre-signed multisig payout (no server can intercept)
- NAT traversal: AutoRelay (~80%) → STUN → TURN (~20% worst CGNAT)
- All communication channels are end-to-end encrypted (Signal Protocol)
- Seed phrase backup (12-word BIP-39 style, full derivation pending)

### Known Limitations (v1.0-alpha)
- LDK Lightning transaction building is scaffolded but uses placeholder signatures
- BIP-39 mnemonic generation is simplified (full BIP-32 derivation pending)
- Nostr NIP-01 event signing uses placeholder sigs (secp256k1 pending)
- WebRTC ICE negotiation is scaffolded (real offer/answer exchange pending)
- UI is English-only (Bahasa Indonesia localization pending)
- No unit or integration tests yet
- No CI/CD pipeline

---

[1.0.0-alpha]: https://code.neop2p.io/thesdony/neo-p2p/tree/v1.0.0-alpha
