# Roadmap

## Current: v1.0.28 (2026-09-07)

**Zero-backend P2P crypto trading on RNS/LXMF.**

- [x] Identity system (BIP-39 mnemonic + BIP-32/SLIP-10, Android KeyStore-wrapped)
- [x] RNS + LXMF transport (ONLY transport since Phase 4 2026-08-31; libp2p/Nostr/WebRTC/ws-relay removed)
- [x] Offer feed: `neop2p/offers` announce + commitment-only digest, paced 2.5s re-announce loop (2026-09-01); locked/terminal convergence + tombstones (2026-09-02); observer tombstone deletion + stale-MATCHED auto-cancel (2026-09-06/07)
- [x] E2EE chat (custom NIP-44-inspired, live 2026-08-24)
- [x] Real 2-of-3 P2SH escrow (bitcoinj, 0.5% seller-only fee)
- [x] Over/underpayment handling (2026-09-04): excess to seller, partial refundable (Room v24)
- [x] Payout-destination safety (2026-09-07): fee-wallet/self-multisig payouts rejected at accept and at build
- [x] Signaling resend queue (2026-09-07): send-time failures retry on the next announce
- [x] Personal wallet (receive QR, balance, history, send; real-UTXO fee estimate 2026-09-06)
- [x] On-chain arbitration (2-of-3, LXMF dispute/evidence/resolution signaling; v23 party delivery + sender auth 2026-09-02; FUNDING not disputable 2026-09-05)
- [x] Local reputation (signed attestations exchanged over LXMF since 2026-09-04; sender-authenticated verified ingest)
- [x] Room DB + SQLCipher v25
- [x] Dagger Hilt DI modules
- [x] 16 Compose UI screens
- [x] NavGraph routing
- [x] Foreground P2P service
- [x] In-app notifications (chat / offer matched / escrow / wallet, deep-link taps; transport-down notification 2026-09-04; matched-notification entitlement 2026-09-06)
- [x] Docker relay infrastructure (Oracle Free Tier): RNS transport node (official Python rnsd, IFAC private mesh) + LXMF propagation node
- [x] ProGuard rules
- [x] Product-completeness batches (2026-08-28): two-taker gate, pay card + kode unik, QR invite, offer TTL, block/export, home filters, seller reject-receipt, wallet fee preview, offer pause, saved payment methods, peer fingerprint, machine error codes, WCAG AA contrast, SIGNED forward escrow state
- [x] Trade hub (2026-09-02): post-accept Escrow+Chat destination, Trades-tab re-entry, invite links as system deep links, notification rationale
- [x] Test harness (2026-08-31…09-07): two/three-JVM RNS tests, fault proxy, latency, load (30-offer flood), soak (fd/heap) — 470 tests, 0 failures
- [x] CI/CD (GitHub Actions: build + tests + lint + dependency scan)
- [x] UI polish + animations (2026-09-06): nav transitions, list-item enter, morphing status chip, animated empty state

## v1.1 — Live Escrow (mostly done; LDK optional)

- [x] ~~Actual 2-of-3 multisig address generation~~ ✅ real P2SH/P2WSH (2026-08-22)
- [x] ~~Real pre-signed payout transaction construction~~ ✅ (2026-08-22, redeem-script signing)
- [x] ~~Broadcast payout transaction on fiat confirmation~~ ✅ (2026-08-26)
- [x] ~~Escrow recovery: app crashes mid-escrow~~ ✅ (2026-08-28: resume-heal re-publish; reorg-safe auto-refund E7 + depth re-check E4 2026-09-01; funding-tx freshness gate 2026-09-01; buyer dispute escape hatch 2026-09-02; over/underpayment handling 2026-09-04; payout-destination gate 2026-09-07)
- [ ] Offline push notifications (self-hosted notepush-style relay → FCM; local-only today — no alerts when the app process is dead)
- [ ] Dispute timelock enforcement (7-day CLTV) — disputes resolve as a plain 2-of-3 spend

## v1.2 — Crypto Identity (done)

- [x] ~~Full BIP-39 mnemonic generation + BIP-32 key derivation~~ ✅ (see IDENTITY_REWRITE.md)
- [x] ~~Nostr NIP-01 event signing~~ ✅ (implemented, then removed with the Nostr transport 2026-08-31)
- [x] ~~Seed phrase verification UI~~ ✅ (2026-08-07)
- [x] ~~Import identity from existing seed phrase~~ ✅ (2026-08-28: restore flow + mandatory warning)
- [ ] Identity backup export to encrypted file

## v1.3 — Real P2P Communications (superseded by Phase 4)

- [x] ~~WebRTC ICE / libp2p stream / auto-reconnect~~ ✅ (2026-08-31: replaced by RNS/LXMF — transport node mediates paths, keepalive + 20s re-announce, S05/S06 failed-signaling resend, RnsFaultInjectionTest)

## v2.0 — Production Release (mostly done)

- [x] ~~Bahasa Indonesia localization~~ ✅ EN/ID toggle
- [x] ~~Integration + unit tests~~ ✅ 470 tests (incl. two/three-JVM RNS harness)
- [x] ~~CI/CD pipeline~~ ✅ GitHub Actions
- [ ] UI tests (Compose testing)
- [x] ~~UI polish: animations, transitions~~ ✅ (2026-09-06: NeoMotion nav transitions, list-item enter, morphing status chip, animated empty state)
- [ ] Accessibility: content descriptions, minimum touch targets
- [ ] Memory/battery profiling at scale

## v2.1 — Multi-Asset

- [ ] USDT support (TRC-20)
- [ ] ETH support (2-of-3 multisig via Ethereum)
- [ ] Asset selector in Create Offer screen
- [ ] Multi-asset escrow contract generation
- [ ] Cross-asset trading (sell BTC for IDR, buy ETH with IDR)

## v3.0 — Advanced Privacy

- [ ] Tor integration via Orbot (all traffic routed through SOCKS5 proxy)
- [ ] Ephemeral identities per trade session
- [ ] CoinJoin integration for on-chain privacy
- [ ] Metadata minimization: trade offers without amounts visible on relays
- [ ] Stealth mode: no notification content previews

## v4.0 — Ecosystem

- [ ] iOS client (Kotlin Multiplatform / Swift)
- [ ] Desktop client (Kotlin Compose Desktop)
- [ ] Community relay marketplace (in-app)
- [ ] Decentralized arbitration
- [ ] Reputation portability (share attestations over LXMF)

## Future Ideas

- Atomic Swaps for cross-chain trading
- Group chat for cash meetup coordination
- P2P fiat-crypto price oracle
- Web of Trust for high-value traders
- Hardware wallet support (Ledger, Trezor)

---

*Roadmap is subject to change based on community feedback and funding.*
