# NEO-P2P

**Zero-backend, pure Peer-to-Peer anonymous crypto seller app for Indonesia.**

![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF)
![P2P](https://img.shields.io/badge/P2P-RNS%20%2B%20LXMF-brightgreen)

---

## ⚡ The Problem

Centralized P2P exchanges (Paxful, Binance P2P) require:
- KYC/phone verification
- Central servers that can be shut down
- Transaction monitoring by third parties
- Fee enforcement that only works with a backend

**NEO-P2P solves this with zero servers.**

## 🔑 The Solution

| Feature | NEO-P2P | Centralized P2P |
|---------|---------|-----------------|
| Identity | Cryptographic keypair only | Phone/email/KYC |
| Infrastructure | Zero backend servers | Central databases |
| Fee enforcement | Pre-signed multisig (trustless) | Server-side deduction |
| Chat | E2EE (XChaCha20-Poly1305) | Server-mediated |
| Reputation | Signed attestations (local) | Central DB |
| Censorship resistance | Full (RNS + LXMF) | Vulnerable |

## 🏗 Architecture

NEO-P2P uses the Reticulum Network Stack (RNS) + LXMF messaging. Phones are client-only (TCP clients to a VPS transport node); the transport node routes announces/paths/links, and a Python LXMF propagation node provides store-and-forward for offline peers:

| Role | Components |
|------|-----------|
| **Buyer Phone** | On-chain Wallet, RNS/LXMF, E2EE Chat |
| **Seller Phone** | On-chain Wallet, RNS/LXMF, E2EE Chat |
| **Discovery** | RNS announces (`neop2p/offers` digest feed) |
| **Transport** | RNS (TCP client → VPS transport node, official Python rnsd) |
| **Messaging** | LXMF (DIRECT links + propagation node for offline) |
| **Escrow** | 2-of-3 Multisig (bitcoinj on-chain) |
| **Fee** | Hardcoded Native SegWit address (`bc1qdfs8ucu...`) |

- **RNS** routes announces, paths, and links between peers (replaces libp2p + WS relay + Nostr)
- **LXMF** carries chat, offer status, escrow sync, and arbitration signaling (replaces Nostr kinds + WebRTC)
- **E2EE chat** — X25519 ECDH + HKDF-SHA256 + XChaCha20-Poly1305 (NIP-44-style) encrypts all messages end-to-end
- **2-of-3 multisig** holds funds until fiat payment is confirmed
- **Arbitrator** holds the 3rd key, resolves disputes via signed evidence

Full pre-rendered SVG:

![Architecture Diagram](architecture.svg)

Render the D2 source yourself:
```
d2 ARCHITECTURE_DIAGRAMS.d2 output.svg
```

## 🚀 Quick Start

### Prerequisites
- Android Studio or IntelliJ IDEA
- **JDK 21** (pinned machine-wide; AGP 9.3.0 rejects newer JDKs)
- Android SDK 36 (`targetSdk`), min SDK 26
- Gradle 9.5.0 (via `android/gradlew` wrapper)

> Build gotcha: if AGP fails with a Java version error, pin JDK 21 via
> `org.gradle.java.home` in `~/.gradle/gradle.properties` (see `AGENTS.md`).

### Build
```bash
cd android
./gradlew assembleDebug
```

### Install on device
```bash
./gradlew installDebug
```

### Deploy RNS Infrastructure (Oracle Cloud Free Tier)
```bash
bash infrastructure/scripts/deploy.sh your-domain.com
```

## 📱 Screens

| Screen | Description |
|--------|------------|
| **Onboarding** | 5-step: Welcome → Create Identity → Backup Seed → Verify Seed → Finish |
| **Home** | Offer feed with pull-to-refresh, peer reputation |
| **Create Offer** | Sell BTC (sell-only), market-price default, fiat method + bank details, edit/delete own offer |
| **Offer Detail** | Full trade summary, fee breakdown, peer profile, chat entry for locked trades |
| **Chat** | E2EE messages, Room history, pre-key handshake over LXMF |
| **Wallet** | Personal BIP-44 wallet: receive QR + copy, balance, history, send (UTXO-selected raw tx) |
| **Escrow** | 2-of-3 multisig state machine |
| **Trade Room** | Post-accept Escrow+Chat hub (status header + role-adaptive shortcuts) |
| **Dispute Evidence** | Upload bank receipts and evidence for arbitration |
| **Profile** | Keypair display, nickname editing, reputation stats |
| **Settings** | RNS transport status, Tor (coming soon), identity reset |

## 💰 How the 0.5% Fee Works (No Server Required)

This is the key innovation in NEO-P2P:

1. **Seller deposits** `crypto amount + 0.5% fee + network fee` into a 2-of-3 P2SH multisig
2. **Buyer pays IDR** via the selected fiat method (BCA, GoPay, etc.) — the buyer pays **no fee** and receives the **full crypto amount**
3. **Both parties pre-sign** a payout transaction: full crypto → buyer, 0.5% → fee wallet
4. **Only the seller pays the fee** (0.5%); the miner fee is budgeted separately via a dynamic network fee
5. **Pre-signing happens BEFORE** any fiat money moves
6. **Neither party can cheat** — both signatures are needed to broadcast
7. **On IDR confirmation**, the pre-signed tx broadcasts atomically

The fee wallet address is **signature-protected** — only the project owner (holding the Ed25519 private key) can change it. Any fork that alters it is blocked from creating escrow. Since 2026-09-07 a **payout-destination gate** additionally rejects any payout that would send the buyer's sats to the fee wallet or back into the escrow's own multisig — at accept and at build.

## 🌐 Fiat Methods Supported

### Bank Transfer
- BCA
- Mandiri
- BNI
- BRI

### E-Wallet
- GoPay
- OVO
- Dana
- ShopeePay
- LinkAja

### Cash
- Cash Meetup (Tunai)

## 🧩 Tech Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| **Identity** | BIP-39 mnemonic + BIP-32/SLIP-10 derivation (Android KeyStore) | Hardware-backed seed, no KYC |
| **Discovery** | RNS announces (`neop2p/offers` digest feed) | Trade offer broadcast |
| **Transport** | RNS (rns-core, TCP client → VPS transport node) | Authenticated P2P routing |
| **Messaging** | LXMF (lxmf-core, DIRECT links + propagation node) | Chat + signaling, offline store-and-forward |
| **Chat** | XChaCha20-Poly1305 (X25519 ECDH + HKDF-SHA256) | End-to-end encrypted |
| **Files** | LXMF file attachments (auto-Resource) | Payment proof P2P transfer |
| **Escrow** | bitcoinj 2-of-3 multisig (mainnet on v0.1.0-beta-1) | Trustless, pre-signed payout |
| **Reputation** | Signed attestations (local-only) | No central database |
| **Storage** | Room + SQLCipher (`sqlcipher-android` 4.17, 16 KB-aligned) | Encrypted offline-first local DB |
| **UI** | Jetpack Compose + Material 3 | Modern Android UI |
| **DI** | Dagger Hilt | Dependency injection |
| **Theme** | Dark cyber-green | Anonymous trader aesthetic |

## 📡 Network Architecture

### RNS Infrastructure (Oracle Cloud Free Tier — $0/mo)
- 1× RNS transport node (official Python rnsd, `enableTransport=true`, TCP server on 42420, IFAC private-mesh gate)
- 1× LXMF propagation node (Python lxmd, store-and-forward for offline peers)

### NAT Traversal Strategy
| Method | Coverage | Cost |
|--------|----------|------|
| RNS TCP client → transport node | ~100% (single TCP egress) | Free |
| LXMF DIRECT links (peer-to-peer) | Best-effort when both online | Free |
| LXMF propagation node | Offline peers (store-and-forward) | Operator-run |

### Indonesian Carrier Compatibility
- **Telkomsel**: RNS TCP client works everywhere (single egress)
- **Indosat/IM3**: Same — no NAT traversal needed
- **XL Axiata**: Same
- **Tri (3)**: Same — CGNAT is irrelevant with a TCP client transport node

## 🔒 Security & Privacy

- **No phone, email, or name** ever required
- **No account creation** — just a cryptographic key
- **No central servers** — all data is peer-shared or on-device
- **E2EE chat** — X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305 (custom, NIP-44-inspired; not NIP-44/59 wire-compatible), keys derived from your BIP-39 mnemonic
- **Offline-first** — Room DB encrypted with SQLCipher
- **Tor support** — optional routing through Tor for maximum anonymity (planned v3.0)
- **Open source** — all code auditable, fee address hardcoded

## 🧪 Current Status

**Phase: v1.0.29 (RNS/LXMF transport live — chat E2EE + wallet + trade hub + reputation over LXMF)**

All base components are implemented:
- ✅ Identity system (BIP-39/BIP-32 + Android KeyStore)
- ✅ P2P transport (RNS + LXMF — libp2p/Nostr/WebRTC removed in Phase 4)
- ✅ E2EE chat (X25519 ECDH + ChaCha20-Poly1305, custom NIP-44-inspired)
- ✅ Multisig escrow (on-chain 2-of-3, 0.5% seller-only fee)
- ✅ One-tap escrow auto-fund from the in-app wallet
- ✅ Escrow-first payment-detail sharing (bank # + name over E2EE chat after funding)
- ✅ Offer propagation (RNS announce digest feed + LXMF on-demand fetch)
- ✅ Trade hub (post-accept Escrow+Chat destination, 2026-09-02)
- ✅ Invite links as system deep links (2026-09-02)
- ✅ Local reputation (signed attestations, exchanged over LXMF since 2026-09-04)
- ✅ Room database (SQLCipher-encrypted, v25)
- ✅ Dagger Hilt DI
- ✅ 9 Compose screens
- ✅ NavGraph routing
- ✅ P2P foreground service
- ✅ RNS infrastructure (Oracle Cloud Free Tier)
- ✅ Deploy / management scripts
- ✅ ProGuard / R8 rules
- ✅ Over/underpayment handling (2026-09-04): excess to seller, partial refundable
- ✅ Reputation over LXMF (2026-09-04): sender-authenticated attestation ingest
- ✅ Transport-down banner + notification (2026-09-04)
- ✅ Payout-destination safety (2026-09-07): fee-wallet/self-multisig payouts rejected at accept and at build
- ✅ Signaling resend queue (2026-09-07): send-time failures retry on the next announce
- ✅ Offer lifecycle hardening (2026-09-06/07): observer tombstone deletion, stale-MATCHED auto-cancel, locked-offer access gate
- ✅ Battery idle cadence (2026-09-07): backgrounded escrow sweep + wallet poll at 5 min, offer re-announce at 60 s (foreground: 60 s / 60 s / 2.5 s)
- ✅ Never-funded escrow cancel (2026-09-07): local cancel when no deposit exists — no on-chain move, offer marked CANCELLED + synced
- ✅ Auto pre-key handshake (2026-09-07): E2EE sends outside chat (receipt, reject, payment details) establish the session on demand
- ✅ UI polish + animations (2026-09-06): nav transitions, list-item enter, morphing status chip

**Needed for production:**
- [ ] Tor integration


## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

1. Fork the repo
2. Create your feature branch: `git checkout -b feature/amazing-feature`
3. Commit: `git commit -m 'Add amazing feature'`
4. Push: `git push origin feature/amazing-feature`
5. Open a PR

## 📜 License

MIT — use it, modify it, build on it.  
The fee wallet address is the only hardcoded constant — change it to your own before building.

## ⚠️ Disclaimer

NEO-P2P is experimental software. Cryptocurrency trading carries financial risk.  
This tool is provided "as is" without warranty. Use at your own risk.  
Always verify the fee wallet address in the open-source code before using.

Full risk warning (English + Bahasa Indonesia): [disclaimer.md](disclaimer.md)

---

*Built with ❤️ for the Indonesian P2P crypto community.*
