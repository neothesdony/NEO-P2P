# NEO-P2P Production Readiness Analysis

> **Status (2026-09-02): Historical planning document from the scaffold era.** The "Missing" lists below predate Phase 4 and are largely resolved or superseded: BIP-39/BIP-32 identity, CI/CD, unit/integration tests, on-chain escrow, and Bahasa localization are all done; Nostr, libp2p, WebRTC, and Lightning escrow were **removed** (RNS/LXMF is the only transport; escrow is on-chain 2-of-3 P2SH, not Lightning). See `SCENARIO_MATRIX.md` and `CHANGELOG.md` for the live state. The roadmap is retained for historical reference.

## Current State (2026-09-02 — supersedes the original list)
- Identity: full BIP-39 mnemonic + BIP-32/SLIP-10 derivation, Android Keystore-wrapped seed encryption (see `IDENTITY_REWRITE.md`)
- Transport: RNS + LXMF only (VPS transport node — official Python rnsd — + LXMF propagation node); libp2p/Nostr/WebRTC removed; Tier 1 LAN discovery + Tier 3 multi-node (2026-09-01)
- Escrow: real on-chain 2-of-3 P2SH multisig (bitcoinj, testnet4), 0.5% seller-only fee, Mempool/Blockstream verification
- E2EE chat: custom NIP-44-inspired (X25519 + HKDF-SHA256 + ChaCha20-Poly1305), not libsignal
- Tests: 323 unit/integration tests (incl. two-JVM/three-JVM RNS harness), 0 failures
- CI: GitHub Actions pipeline (build + tests + lint + dependency scan); localization EN/ID; Room/SQLCipher v23

## Gaps Identified for Production Readiness (historical — pre-Phase-4)

### 1. Core P2P Functionality
**Missing/LDK Lightning Integration**
- Current: Real 2-of-3 P2SH on-chain escrow (bitcoinj) — seller funds 100.5%, payout 99.5% → buyer + 1% fee wallet, redeem-script signing (2026-08-22). Testnet4 is the test network (faucet + funded addresses; Testnet3 abandoned 2026-08-24).
- Still needed (Lightning-specific):
  - LDK Android SDK integration for Lightning-based escrow (optional enhancement)
  - Funding transaction monitoring (subscribe to Lightning Network events)
  - Broadcast payout transaction on fiat confirmation (bitcoinj path exists; live Testnet4 broadcast pending)
  - Dispute timelock enforcement (7-day CLTV)
  - Escrow recovery: what happens if app crashes mid-escrow

**Notification/Offline Delivery**
- Current: In-process notifications (chat / offer matched / escrow / wallet) with deep-link taps, foreground-suppression, and a 60s escrow stale-sweep that enforces the 30-min auto-cancel / 6-h auto-refund windows (2026-08-25). Escrow auto-transitions notify the user.
- Still needed (Phase 2 — offline push):
  - Self-hosted notepush-style push relay (Nostr events → FCM/APNs) on the Oracle box so offers matched / escrow timeouts / wallet receives alert even when the app process is dead
  - Escrow deadline heads-up reminders (T-15m funding, T-1h refund) via Android Live Updates

**Missing/BIP-39 & Nostr Signing**
- Current: Identity system (Ed25519 + Android KeyStore) and Nostr client (NIP-01 events, NIP-65 relay hints) but without proper signing
- Needed:
  - Full BIP-39 mnemonic generation + BIP-32 key derivation
  - Nostr NIP-01 event signing using secp256k1 (Schnorr)
  - Seed phrase verification UI (word selection challenge)
  - Import identity from existing seed phrase
  - Identity backup export to encrypted file

**Missing/WebRTC Real Communication**
- Current: WebRTC data channel scaffold
- Needed:
  - WebRTC ICE full offer/answer exchange via libp2p signaling
  - Real data channel file transfer for payment proofs
  - libp2p stream multiplexing for Signal Protocol sessions
  - Multi-stream support (chat + file transfer simultaneously)
  - Connection quality monitoring (latency, packet loss)
  - Auto-reconnection with exponential backoff

### 2. Localization & Internationalization
- Missing: Bahasa Indonesia localization (all UI strings + documentation)
- Needed: Complete translation of all UI strings, date/time formats, number formats, right-to-left support if needed

### 3. Testing & Quality Assurance
- Missing: Unit + integration tests, UI tests
- Needed:
  - Unit tests (ViewModel, UseCase, Repository layers) - target 80%+ coverage
  - Integration tests (escrow workflow end-to-end)
  - UI tests (Compose testing with Compose Test Rule)
  - Test automation in CI/CD pipeline

### 4. CI/CD & DevOps
- Missing: CI/CD pipeline
- Needed:
  - GitHub Actions workflow for Android (build, test, lint, deploy to internal/test tracks)
  - Fastlane setup for Android (and later iOS)
  - Automated signing and versioning
  - Release management (alpha/beta/production)

### 5. Security & Privacy
- Missing: OWASP Mobile Top 10 compliance, biometrics/passkeys
- Needed:
  - Secure storage improvements (beyond SQLCipher)
  - Biometric authentication (fingerprint/face ID) for app unlock and transaction confirmation
  - Passkey support (WebAuthn) for identity backup/restore
  - Regular security audits and dependency scanning
  - Protection against common mobile vulnerabilities (insecure data storage, insufficient cryptography, etc.)

### 6. UI/UX Polish
- Missing: UI polish + animations, loading states, error handling, accessibility
- Needed:
  - Material Design 3 animations and transitions
  - Proper loading states and skeletons
  - Comprehensive error states in all screens
  - Accessibility: content descriptions, minimum touch targets, screen reader support
  - Memory profiling: WebRTC resource cleanup, battery efficiency
  - Offline-first indicators and retry mechanisms

### 7. Cross-Platform Support (iOS)
- Missing: iOS version
- Needed:
  - Decision on approach: Kotlin Multiplatform (shared business logic) vs native Swift/UIKit
  - Implementation of core P2P functionality on iOS (libp2p, Nostr, Signal Protocol, WebRTC, LDK)
  - Platform-specific UI (SwiftUI or UIKit)
  - Shared testing and CI/CD for both platforms

### 8. Infrastructure & DevOps
- Current: Docker relay infrastructure (Oracle Cloud Free Tier) with deploy/management scripts
- Needed:
  - Monitoring and alerting for relays
  - Automatic scaling and failover strategies
  - Documentation for self-hosted relay deployment
  - Chaos testing for network partitions

## MVP Definition for Production Readiness
The MVP should include:
1. Core P2P trading functionality with real Lightning transactions
2. Bahasa Indonesia localization
3. Basic unit and integration tests (60% coverage minimum for MVP, targeting 80%+)
4. CI/CD pipeline for Android
5. Basic security measures (OWASP Mobile Top 10 baseline)
6. UI polished to a usable standard (not necessarily pixel-perfect)
7. iOS version with feature parity to Android MVP (core trading flow)

## MoSCoW Prioritization

### Must Have (MVP)
- Real LDK Lightning transaction building and monitoring
- Full BIP-39 mnemonic support and seed phrase handling
- Nostr NIP-01 event signing
- WebRTC ICE negotiation and real data transfer
- Bahasa Indonesia localization
- Unit tests (ViewModel, Repository) - 60% coverage
- Integration tests for escrow workflow
- CI/CD pipeline (GitHub Actions) for Android
- Basic security: SQLCipher encryption, secure key storage, HTTPS/TLS for relay connections
- UI polished to functional state (loading, error states, basic animations)
- iOS version with core trading flow (offer creation, discovery, chat, escrow)

### Should Have (Post-MVP)
- UI/UX polish advanced (custom animations, transitions, sophisticated loading)
- Higher test coverage (80%+ unit, integration, UI tests)
- Advanced security: biometrics, passkeys, regular dependency scanning
- Fastlane automation for deployment
- Memory and battery optimization
- Accessibility improvements (screen reader, touch targets)
- Infrastructure monitoring and alerting

### Could Have (Future)
- Multi-asset support (USDT, ETH)
- Advanced privacy features (Tor integration, ephemeral identities)
- Desktop and web clients
- Community relay marketplace
- Decentralized arbitration
- Hardware wallet support

### Won't Have (Initial MVP)
- Lightning Network swap integration (Loop, Boltz)
- Atomic Swaps for cross-chain trading
- Group chat for cash meetup coordination
- P2P fiat-crypto price oracle
- Web of Trust for high-value traders

## MVP Roadmap (Timeline: 8 weeks) — historical; Phase 1-3 largely delivered, Phase 4 (iOS) deferred

### Phase 1: Foundation (Weeks 1-2)
- [ ] Integrate LDK Android SDK for real Lightning transactions
- [ ] Implement full BIP-39 mnemonic generation and BIP-32 derivation
- [ ] Add Nostr NIP-01 event signing (secp256k1)
- [ ] Implement WebRTC ICE offer/answer exchange via libp2p signaling
- [ ] Enable real data channel file transfer for payment proofs

### Phase 2: Localization & Testing (Weeks 3-4)
- [ ] Complete Bahasa Indonesia localization (all UI strings)
- [ ] Write unit tests for ViewModel and Repository layers (target 60% coverage)
- [ ] Write integration tests for escrow workflow end-to-end
- [ ] Set up GitHub Actions CI/CD pipeline (build, test, lint)
- [ ] Implement basic error handling and loading states

### Phase 3: Security & Polish (Weeks 5-6)
- [ ] Implement biometric authentication for app unlock and transaction confirmation
- [ ] Enhance secure storage (investigate Android Keystore improvements)
- [ ] Polish UI: animations, transitions, accessibility basics
- [ ] Conduct OWASP Mobile Top 10 baseline security review
- [ ] Implement passkey support for identity backup (WebAuthn)

### Phase 4: Cross-Platform & Release (Weeks 7-8)
- [ ] Begin iOS implementation (Kotlin Multiplatform shared core or native Swift)
- [ ] Implement iOS UI for core trading flow (SwiftUI)
- [ ] Ensure feature parity: offer creation, discovery, chat, escrow
- [ ] Set up CI/CD for iOS (if using separate pipeline)
- [ ] Beta testing and feedback incorporation
- [ ] Prepare for production release (version 2.0)

## Files Created
- `/home/thesdony/neo-p2p/PRODUCTION_READINESS_ANALYSIS.md` - This analysis

## Next Steps
1. Review this analysis with stakeholders
2. Break down user stories into detailed tasks
3. Begin implementation according to the roadmap
4. Regularly update the analysis as work progresses
