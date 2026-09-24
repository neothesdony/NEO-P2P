# NEO-P2P

**Peer-to-peer Bitcoin trading with nothing in the middle — a no-KYC, sell-only offer board with direct settlement, for Indonesia.**

![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF)
![P2P](https://img.shields.io/badge/P2P-RNS%20%2B%20LXMF-brightgreen)

**Current build:** `v0.1.2` — real funds require the **signed release APK** (`arm64-v8a`, R8-minified, not debuggable). Debug APKs are developer/QA only: a debuggable build refuses to run on mainnet (`DebugNetworkGate`), so use it on testnet or the emulator.

---

## ⚡ The Problem

Centralized P2P exchanges (Paxful, Binance P2P) require:
- KYC/phone verification
- Central servers that can be shut down
- Transaction monitoring by third parties
- Fee enforcement that only works with a backend

**NEO-P2P solves this with no backend, no accounts, and no database to seize — a relay node moves your encrypted packets and can't read them, and it is never a trust anchor.**

## 🔑 The Solution

| Feature | NEO-P2P | Centralized P2P |
|---------|---------|-----------------|
| Structure | Offer board + direct settlement (no orderbook, no matching engine) | Order book + central matching engine |
| Identity | Cryptographic keypair only | Phone/email/KYC |
| Infrastructure | No backend (a relay moves your encrypted packets and can't read them) | Central databases |
| Fee enforcement | 2-of-3 multisig (trustless) | Server-side deduction |
| Chat | E2EE (X3DH + double ratchet) | Server-mediated |
| Reputation | Signed attestations (local) | Central DB |
| Censorship resistance | Full (RNS + LXMF) | Vulnerable |

## 🏗 Architecture

NEO-P2P uses the Reticulum Network Stack (RNS) + LXMF messaging. There is no backend, no accounts, no database to seize — a relay node moves your encrypted packets and can't read them. The relay routes announces/paths/links, and a Python LXMF propagation node provides store-and-forward for offline peers:

| Role | Components |
|------|-----------|
| **Buyer Phone** | On-chain Wallet, RNS/LXMF, E2EE Chat |
| **Seller Phone** | On-chain Wallet, RNS/LXMF, E2EE Chat |
| **Discovery** | RNS announces (`neop2p.offers` digest feed) |
| **Transport** | RNS relay node (official Python rnsd) |
| **Messaging** | LXMF (DIRECT links + propagation node for offline) |
| **Escrow** | 2-of-3 Multisig (bitcoinj 0.17.1 on-chain) |
| **Fee** | Network-aware signed Native SegWit address (mainnet `bc1qdfs8ucu...`, testnet `tb1q05q8...`) |

- **RNS** routes announces, paths, and links between peers (replaces libp2p + WS relay + Nostr)
- **LXMF** carries chat, offer status, escrow sync, and arbitration signaling (replaces Nostr kinds + WebRTC)
- **E2EE chat** — X3DH key agreement over v2 pre-key bundles seeds an X25519 double ratchet; each message is ChaCha20-Poly1305 with an AAD binding the session, peer, offer, ratchet key, and counters (forward secrecy + post-compromise security)
- **2-of-3 multisig** holds funds until fiat payment is confirmed, with an optional CLTV timelock for seller recovery after maturity
- **Arbitrator** holds the 3rd key, resolves disputes via signed evidence (arbitrator role lives in the local-only `:admind` daemon)

Full pre-rendered SVG:

![Architecture Diagram](architecture.svg)

Render the D2 source yourself:
```
d2 ARCHITECTURE_DIAGRAMS.d2 output.svg
```

## 🚀 Quick Start

### Prerequisites
- Android Studio or IntelliJ IDEA
- **JDK 21** (pinned machine-wide; AGP 9.4.1 rejects newer JDKs)
- compileSdk 37 / targetSdk 36, min SDK 26
- Gradle 9.7.1 (via `android/gradlew` wrapper)

> Build gotcha: if AGP fails with a Java version error, pin JDK 21 via
> `org.gradle.java.home` in `~/.gradle/gradle.properties` (see `AGENTS.md`).

### Build

Release (signed, R8-minified — the only build that may be distributed for real funds):
```bash
cd android
./gradlew :app:assembleRelease
```

Debug (testnet / emulator only — a debuggable build refuses to run on mainnet):
```bash
cd android
./gradlew assembleDebug
```

> Release builds read signing credentials from `keystore.properties` (repo root,
> gitignored). Never distribute a debug APK for mainnet: debug builds are
> unminified and debugger-attachable.

### Install on device
```bash
# real funds — the signed release APK
adb install app/build/outputs/apk/release/app-release.apk

# testnet / emulator
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
| **Terms** | Versioned 18+ terms-acceptance gate (shown once per terms version) |
| **Home** | Offer feed with pull-to-refresh, peer reputation |
| **Create Offer** | Sell BTC (sell-only), market-price default with deviation warning, fiat method + bank details, edit/delete own offer |
| **Offer Detail** | Full trade summary, fee breakdown, peer profile, chat entry for locked trades |
| **Chat** | E2EE messages (double ratchet), Room history, v2 pre-key handshake over LXMF |
| **Wallet** | Personal BIP-44 HD wallet: rotating receive/change addresses, balance + history (instant open from an encrypted snapshot), send (branch-and-bound coin selection, fee tiers) |
| **Escrow** | 2-of-3 multisig state machine, optional CLTV timelock + seller recovery after maturity |
| **Trade Room** | Post-accept Escrow+Chat hub (status header + role-adaptive shortcuts) |
| **Dispute Evidence** | Upload bank receipts and evidence for arbitration |
| **Profile** | Keypair display, nickname editing, reputation stats |
| **Help** | In-app help / FAQ |
| **Legal** | In-app Terms of Service + Privacy Policy |
| **Settings** | RNS transport status, Tor (coming soon), update check, biometric-gated identity export/import, identity reset |

## 💰 How the 0.5% Fee Works (No Backend Required)

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
- BSI
- BTN
- Permata
- Danamon
- OCBC
- Maybank

### E-Wallet / Dompet Digital
- GoPay
- OVO
- Dana
- ShopeePay
- LinkAja

## 🔒 Security & Privacy

- **No phone, email, or name** ever required
- **No account creation** — just a cryptographic key
- **No backend** — no accounts, no KYC, no database to seize. A relay node moves your encrypted packets and can't read them (E2EE); it is not a trust anchor.
- **E2EE chat** — E2EE v2: X3DH over v2 pre-key bundles (long-term X25519 IK + Ed25519-signed SPK) seeds a double ratchet with per-message ChaCha20-Poly1305, giving forward secrecy and post-compromise security; keys derived from your BIP-39 mnemonic. Not NIP-44/59 wire-compatible — interop only between NEO-P2P peers, and both peers must be on v0.1.1+ (a legacy v1 bundle is refused).
- **No-KYC, not anonymous** — no phone, email, or name is ever required to trade. The Bitcoin chain is public (pseudonymous, not anonymous) and the IDR leg is a normal bank/e-wallet transfer to a real account, so your counterparty can see your real name — the KYC boundary moved to the bank, it didn't vanish.
- **Offline-first** — Room DB encrypted with SQLCipher
- **HD wallet privacy** — BIP-44 address rotation (external receive + internal change, 20-address gap limit) avoids address reuse; the cached wallet snapshot and HD pointers are AES-256-GCM encrypted and identity-scoped
- **No backup leak** — the SQLCipher database and encrypted preferences are excluded from both cloud backup and device-transfer; restore is via your BIP-39 mnemonic only
- **At-rest key hardening** — the identity seed key requires device unlock (auth-gating is retrofitted if you add a lock after creating your identity); identity export/import sit behind biometrics or device credential; newly generated SQLCipher wrapping keys are device-unlock-bound
- **What the seed phrase does not restore** — your reputation, ratings, trade history, and chats live only on this device. The recovery phrase restores your identity, wallet, and funds, but not those.
- **Invite links are identity-bound** — `neop2p://peer/<id>#<hash>` carries the peer's RNS identity hash so you can confirm you are adding the right key
- **Audited dependency** — on-chain escrow runs on bitcoinj 0.17.1 (patches `CVE-2026-44714`, a P2PKH/P2WPKH script-verification bypass)
- **Tor support** — optional routing through Tor for network-level anonymity (planned v3.0)
- **Open source** — all code auditable, fee address hardcoded

## 📡 Network Access (Blocked Domains in Indonesia)

On-chain lookups (balance, history, funding verification, fee estimates, and broadcast) use public Esplora/Mempool explorers. Some Indonesian ISPs — notably **Telkomsel mobile** — block or TLS-intercept `mempool.space` and `blockstream.info` (verified 2026-09-15: connection reset / an expired block-page certificate from `internetbaik.telkomsel.com`).

The app rotates through several fail-closed providers and fails over automatically, so it usually recovers on its own. On **mainnet** the order is `mempool.space` → `blockstream.info` → `mempool.emzy.de` → `btcscan.org` → `blockchain.com`; on **testnet4**, `mempool.emzy.de` serves tip/fees while `mempool.space` serves address scans (emzy's testnet4 index has no `/address` endpoint). A blocked provider costs one failed attempt before the rotation moves on. If balance, history, or escrow funding still looks stuck or slow:

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
The fee wallet address and the arbitrator public key/peer id are hardcoded constants — the fee wallet is signature-protected (see above).

**Third-party licenses:** this project embeds forks of [Reticulum](https://github.com/markqvist/Reticulum) and [LXMF](https://github.com/markqvist/LXMF) (MPL-2.0). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for full compliance details.

## ⚠️ Disclaimer

NEO-P2P is experimental software. Cryptocurrency trading carries financial risk.  
This tool is provided "as is" without warranty. Use at your own risk.  
Always verify the fee wallet address in the open-source code before using.

Full risk warning (English + Bahasa Indonesia): [disclaimer.md](disclaimer.md)

---

You can help support the continued development.

**Bitcoin (BTC) on Bitcoin network**

<img src="assets/donate-btc.png" alt="Bitcoin donation QR code" width="160">

Address: `bc1qdfpzym9pw5m7lk9tse2hkh5dy30jsyapmttz9x`
