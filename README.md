# NEO-P2P

**Zero-backend, pure Peer-to-Peer anonymous crypto seller app for Indonesia.**

![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF)
![P2P](https://img.shields.io/badge/P2P-RNS%20%2B%20LXMF-brightgreen)

**Current build:** `v0.1.0-beta-7` — debug APKs are produced for both mainnet and testnet.

---

## ⚡ The Problem

Centralized P2P exchanges (Paxful, Binance P2P) require:
- KYC/phone verification
- Central servers that can be shut down
- Transaction monitoring by third parties
- Fee enforcement that only works with a backend

**NEO-P2P solves this with zero backend — the app runs peer-to-peer; a VPS transport node (and optional community nodes) only amplifies reach as an encrypted packet ferry, never a trust anchor or a central database.**

## 🔑 The Solution

| Feature | NEO-P2P | Centralized P2P |
|---------|---------|-----------------|
| Identity | Cryptographic keypair only | Phone/email/KYC |
| Infrastructure | Zero backend (packet-ferry transport node only) | Central databases |
| Fee enforcement | 2-of-3 multisig (trustless) | Server-side deduction |
| Chat | E2EE (ChaCha20-Poly1305) | Server-mediated |
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
| **Escrow** | 2-of-3 Multisig (bitcoinj 0.17.1 on-chain) |
| **Fee** | Network-aware signed Native SegWit address (mainnet `bc1qdfs8ucu...`, testnet `tb1q05q8...`) |

- **RNS** routes announces, paths, and links between peers (replaces libp2p + WS relay + Nostr)
- **LXMF** carries chat, offer status, escrow sync, and arbitration signaling (replaces Nostr kinds + WebRTC)
- **E2EE chat** — X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305 (NIP-44-inspired) encrypts all messages end-to-end
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

### Deploy RNS Infrastructure 
You can build your own reticulum:
## config example:
```
[reticulum]
enable_transport = Yes
share_instance = no
panic_on_interface_error = No
discover_interfaces = yes

[logging]
loglevel = 4

[interfaces]

   [[VPS TCP Server]]
     type = BackboneInterface
     enabled = Yes
     listen_ip = 0.0.0.0
     listen_port = 42000
     mode = gateway
     discoverable = no
 
```

## 📱 Screens

| Screen | Description |
|--------|------------|
| **Onboarding** | 7-step: Disclaimer → Welcome → Create/Restore Identity → Backup Seed → Verify Seed → Finish |
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

1. **Seller deposits** `crypto amount + 0.5% fee + network fee` into a 2-of-3 P2SH/P2WSH multisig
2. **Buyer pays IDR** via the selected fiat method (BCA, GoPay, etc.) — the buyer pays **no fee** and receives the **full crypto amount**
3. **Only the seller pays the fee** (0.5%); the miner fee is budgeted separately via a dynamic network fee
4. **Neither party can cheat** — the 2-of-3 multisig requires two signatures to broadcast (the arbitrator holds the third key for disputes)
5. **After the seller confirms IDR received**, the payout transaction is signed (buyer + seller) and broadcast: full crypto → buyer, 0.5% → fee wallet
6. The payout is destination-gated: it can never pay the fee wallet or the escrow's own multisig

The fee wallet address is **signature-protected** — only the project owner (holding the Ed25519 private key) can change it. Any fork that alters it is blocked from creating escrow. Since 2026-09-07 a **payout-destination gate** additionally rejects any payout that would send the buyer's sats to the fee wallet or back into the escrow's own multisig — at accept and at build.

## 🌐 Fiat Methods Supported

### Bank Transfer
- BCA
- Mandiri
- BNI
- BRI
- CIMB Niaga
- Jago
- SeaBank

### E-Wallet
- GoPay
- OVO
- Dana
- ShopeePay
- LinkAja

### QRIS
- QRIS (any QRIS-compatible payment app)

## 🔒 Security & Privacy

- **No phone, email, or name** ever required
- **No account creation** — just a cryptographic key
- **No backend** — no accounts, no KYC, no central database. A VPS transport node (and optionally community nodes) amplifies reach as a packet ferry; it cannot read traffic (E2EE) and is not a trust anchor.
- **E2EE chat** — X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305 (custom, NIP-44-inspired; not NIP-44/59 wire-compatible), keys derived from your BIP-39 mnemonic
- **Offline-first** — Room DB encrypted with SQLCipher
- **No backup leak** — the SQLCipher database and encrypted preferences are excluded from both cloud backup and device-transfer; restore is via your BIP-39 mnemonic only
- **Invite links are identity-bound** — `neop2p://peer/<id>#<hash>` carries the peer's RNS identity hash so you can confirm you are adding the right key
- **Audited dependency** — on-chain escrow runs on bitcoinj 0.17.1 (patches `CVE-2026-44714`, a P2PKH/P2WPKH script-verification bypass)
- **Tor support** — optional routing through Tor for maximum anonymity (planned v3.0)
- **Open source** — all code auditable, fee address hardcoded

## 📡 Network Access (Blocked Domains in Indonesia)

On-chain lookups (balance, history, funding verification, fee estimates, and broadcast) use public Esplora/Mempool explorers. Some Indonesian ISPs — notably **Telkomsel mobile** — block or TLS-intercept `mempool.space` and `blockstream.info` (verified 2026-09-15: connection reset / an expired block-page certificate from `internetbaik.telkomsel.com`).

The app tries several mirrors and remembers the last one that worked (`mempool.emzy.de` is tried first), so it usually recovers on its own. If balance, history, or escrow funding looks stuck or slow:

- Install the **Cloudflare 1.1.1.1 (One Dot One)** app with **WARP** enabled — [Play Store](https://play.google.com/store/apps/details?id=com.cloudflare.onedotonedotone&pcampaignid=web_share) — or **ProtonVPN** — [Play Store](https://play.google.com/store/apps/details?id=ch.protonvpn.android&referrer=utm_source%3Dprotonvpn.com%26utm_medium%3Dweb%26utm_campaign%3Dpvpn_all_auto) — or use any VPN, then tap Retry.

> A DNS-only change won't help here: this is a **TLS/SNI-level** block, so you need WARP or a full VPN tunnel, not just a different DNS resolver.

## 📖 User Manual

- **English:** [manual/USER_MANUAL.md](manual/USER_MANUAL.md)
- **Bahasa Indonesia:** [manual/USER_MANUAL_ID.md](manual/USER_MANUAL_ID.md)

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

1. Fork the repo
2. Create your feature branch: `git checkout -b feature/amazing-feature`
3. Commit: `git commit -m 'Add amazing feature'`
4. Push: `git push origin feature/amazing-feature`
5. Open a PR

## 📜 License

MIT — use it, modify it, build on it. See [LICENSE](LICENSE).  
The fee wallet address, the arbitrator public key/peer id, and the RNS transport node host/port are hardcoded constants — the fee wallet is signature-protected (see above).

**Third-party licenses:** this project embeds forks of [Reticulum](https://github.com/markqvist/Reticulum) and [LXMF](https://github.com/markqvist/LXMF) (MPL-2.0). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for full compliance details.

## ⚠️ Disclaimer

NEO-P2P is experimental software. Cryptocurrency trading carries financial risk.  
This tool is provided "as is" without warranty. Use at your own risk.  
Always verify the fee wallet address in the open-source code before using.

Full risk warning (English + Bahasa Indonesia): [disclaimer.md](disclaimer.md)

---

You can help support the continued development at the bottom.

**Bitcoin (BTC) on Bitcoin network** — Address: `bc1qdfs8ucuq8dm3k3tfuzlvhfyevhs0swz4098fwk`

<img src="assets/donate-btc.png" alt="Bitcoin donation QR code" width="160">
