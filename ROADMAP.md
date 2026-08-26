# Roadmap

## Current: v1.0-alpha (✅ Complete)

**Zero-backend P2P crypto trading base.**

- [x] Identity system (BIP-39 + Android KeyStore)
- [x] libp2p transport (AutoRelay, DHT, circuit relay)
- [x] Nostr client (NIP-01 events, NIP-65 relay hints)
- [x] E2EE chat (custom NIP-44-inspired, live 2026-08-24)
- [x] WebRTC data channel scaffold (no callers yet)
- [x] Real 2-of-3 P2SH escrow (bitcoinj, 0.3% seller-only fee)
- [x] Personal wallet (receive QR, balance, history, send)
- [x] Gossip reputation system
- [x] Room DB + SQLCipher + DAOs (v13)
- [x] Dagger Hilt DI modules
- [x] 10 Compose UI screens (incl. Wallet)
- [x] NavGraph routing
- [x] Foreground P2P service
- [x] In-app notifications (chat / offer matched / escrow / wallet, deep-link taps, 2026-08-25)
- [x] Docker relay infrastructure (Oracle Free Tier)
- [x] Deploy / management scripts
- [x] ProGuard rules

## v1.1 — Live Escrow (2 weeks)

- [ ] LDK Android SDK integration for real Lightning transaction building
- [x] ~~Actual 2-of-3 multisig address generation~~ ✅ (done 2026-08-22, real P2SH)
- [x] ~~Real pre-signed payout transaction construction~~ ✅ (done 2026-08-22, redeem-script signing)
- [ ] Funding transaction monitoring (subscribe to Lightning Network events)
- [x] ~~Broadcast payout transaction on fiat confirmation~~ ✅ (done 2026-08-26, confirmReceipt → 2-of-3 broadcast; funding tx outputs bound to escrow address + real vout)
- [ ] Dispute timelock enforcement (7-day CLTV) — pending; disputes currently resolve as a plain 2-of-3 spend (no on-chain timelock)
- [ ] Escrow recovery: what happens if app crashes mid-escrow
- [ ] Offline push notifications (self-hosted notepush-style relay → FCM; local-only today — no alerts when the app process is dead)

## v1.2 — Crypto Identity (1 week)

- [ ] Full BIP-39 mnemonic generation + BIP-32 key derivation
- [ ] Nostr NIP-01 event signing using secp256k1 (Schnorr)
- [x] ~~Seed phrase verification UI (word selection challenge)~~ ✅ (done 2026-08-07)
- [ ] Import identity from existing seed phrase
- [ ] Identity backup export to encrypted file

## v1.3 — Real P2P Communications (1 week)

- [ ] WebRTC ICE full offer/answer exchange via libp2p signaling
- [ ] Real data channel file transfer for payment proofs
- [ ] libp2p stream multiplexing for Signal Protocol sessions
- [ ] Multi-stream support (chat + file transfer simultaneously)
- [ ] Connection quality monitoring (latency, packet loss)
- [ ] Auto-reconnection with exponential backoff

## v2.0 — Production Release (2 weeks)

- [ ] Bahasa Indonesia localization (all UI strings + documentation)
- [ ] Integration tests (escrow workflow end-to-end)
- [ ] Unit tests (ViewModel, UseCase, Repository layers)
- [ ] UI tests (Compose testing with Compose Test Rule)
- [ ] CI/CD pipeline (GitHub Actions)
- [ ] UI polish: animations, transitions, loading states
- [ ] Error handling: comprehensive error states in all screens
- [ ] Accessibility: content descriptions, minimum touch targets
- [ ] Memory profiling: WebRTC resource cleanup, battery efficiency

## v2.1 — Multi-Asset (1 week)

- [ ] USDT support (TRC-20 or Lightning)
- [ ] ETH support (2-of-3 multisig via Ethereum)
- [ ] Asset selector in Create Offer screen
- [ ] Multi-asset escrow contract generation
- [ ] Cross-asset trading (sell BTC for IDR, buy ETH with IDR)

## v3.0 — Advanced Privacy (2 weeks)

- [ ] Tor integration via Orbot (all traffic routed through SOCKS5 proxy)
- [ ] Onion service discovery for hidden Nostr relays
- [ ] Ephemeral identities per trade session
- [ ] CoinJoin integration for on-chain privacy
- [ ] Metadata minimization: trade offers without amounts visible on relays
- [ ] Stealth mode: no notification content previews

## v4.0 — Ecosystem (3 weeks)

- [ ] iOS client (Kotlin Multiplatform / Swift)
- [ ] Desktop client (Kotlin Compose Desktop)
- [ ] NEO-P2P browser extension for Nostr relay discovery
- [ ] Community relay marketplace (in-app)
- [ ] Decentralized arbitration (Nostr-based jury voting)
- [ ] Reputation portability (NIP-58 badges)

## Future Ideas

- Lightning Network swap integration (Loop, Boltz)
- Atomic Swaps for cross-chain trading
- Group chat for cash meetup coordination
- P2P fiat-crypto price oracle (signed price feeds from multiple peers)
- Web of Trust for high-value traders
- Hardware wallet support (Ledger, Trezor)

---

*Roadmap is subject to change based on community feedback and funding.*
