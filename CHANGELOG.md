# Changelog

All notable changes to NEO-P2P will be documented in this file.

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
