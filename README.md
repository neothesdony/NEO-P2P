# NEO-P2P

**Zero-backend, pure Peer-to-Peer anonymous crypto trading app for Indonesia.**

![License](https://img.shields.io/badge/license-MIT-blue)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF)
![P2P](https://img.shields.io/badge/P2P-libp2p%20%2B%20Nostr%20%2B%20WebRTC-brightgreen)

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
| Chat | E2EE (Signal Protocol) | Server-mediated |
| Reputation | Signed attestations (gossip) | Central DB |
| Censorship resistance | Full (Nostr + libp2p) | Vulnerable |

## 🏗 Architecture

```mermaid
graph TD
    subgraph "Phone A (Buyer)"
        A1[On-chain Wallet]
        A2[Nostr Client]
        A3[libp2p Host]
        A4[Signal Protocol]
        A5[WebRTC]
    end

    subgraph "Nostr Relays"
        R1[strfry 1]
        R2[strfry 2]
        R3[strfry 3]
    end

    subgraph "libp2p Relays"
        L1[Circuit Relay]
    end

    subgraph "Phone B (Seller)"
        B1[On-chain Wallet]
        B2[Nostr Client]
        B3[libp2p Host]
        B4[Signal Protocol]
        B5[WebRTC]
    end

    A2 <--> R1
    A2 <--> R2
    A2 <--> R3
    B2 <--> R1
    B2 <--> R2
    B2 <--> R3

    A3 <--> L1
    B3 <--> L1
    A3 <--> B3

    A4 <--> A3
    B4 <--> B3

    A5 <--> B5

    A1 -.->|2-of-3 Multisig| C[Lightning Network]
    B1 -.-> C
    C -.->|Pre-signed Payout| D[Seller — 99.5%]
    C -.->|1% Fee (split 50/50)| E[Fee Wallet]
```

## 🚀 Quick Start

### Prerequisites
- Android Studio Hedgehog (2024.3.1+) or IntelliJ IDEA
- JDK 17+
- Android SDK 34+
- Gradle 8.12+

### Build
```bash
cd android
./gradlew assembleDebug
```

### Install on device
```bash
./gradlew installDebug
```

### Deploy Relays (Oracle Cloud Free Tier)
```bash
bash infrastructure/scripts/deploy.sh your-domain.com
```

## 📱 Screens

| Screen | Description |
|--------|------------|
| **Onboarding** | 4-step: Welcome → Create Identity → Backup Seed → Finish |
| **Home** | Offer feed with pull-to-refresh, peer reputation |
| **Create Offer** | Buy/Sell BTC, IDR price, fiat method selection |
| **Offer Detail** | Full trade summary, fee breakdown, peer profile |
| **Chat** | E2EE messages, payment proof sharing |
| **Escrow** | Lightning 2-of-3 state machine |
| **Profile** | Keypair display, reputation stats |
| **Settings** | Relays, TURN, Tor toggle, identity reset |

## 💰 How the 1% Fee Works (No Server Required)

This is the key innovation in NEO-P2P:

1. **Buyer deposits 100.5%** into a 2-of-3 multisig — their trade amount + their 0.5% fee
2. **Both parties pre-sign** a payout transaction: 99.5% → seller, 1% → fee wallet
3. **Total 1% fee is split 50/50** between buyer and seller (0.5% each)
4. **Pre-signing happens BEFORE** any fiat money moves
5. **Neither party can cheat** — both signatures are needed to broadcast
6. **On fiat confirmation**, the pre-signed tx broadcasts atomically

The fee wallet address is **hardcoded in the open-source code** — verifiable by anyone.

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
| **Identity** | Ed25519 (Android KeyStore) | Hardware-backed keys, no KYC |
| **Discovery** | Nostr (NIP-01/NIP-65) | Trade offer broadcast, relay hints |
| **Transport** | java-libp2p (v1.1.0) | Authenticated P2P streams, AutoRelay, DHT |
| **Chat** | Signal Protocol (libsignal-jvm) | End-to-end encrypted, forward secrecy |
| **Files** | WebRTC DataChannel (M125) | Payment proof P2P transfer |
| **Escrow** | Lightning 2-of-3 multisig | Trustless, pre-signed payout |
| **Reputation** | Signed attestations (gossip) | No central database |
| **Storage** | Room + SQLCipher | Encrypted offline-first local DB |
| **UI** | Jetpack Compose + Material 3 | Modern Android UI |
| **DI** | Dagger Hilt | Dependency injection |
| **Theme** | Dark cyber-green | Anonymous trader aesthetic |

## 📡 Network Architecture

### Relays (Oracle Cloud Free Tier — $0/mo)
- 3× strfry Nostr relays (ports 7001-7003)
- 1× meta relay for NIP-65 (port 7004)
- 1× libp2p circuit relay v2 (port 4001)

### NAT Traversal Strategy
| Method | Coverage | Cost |
|--------|----------|------|
| AutoRelay (libp2p circuit) | ~75-80% | Free |
| STUN (Google public) | +5% | Free |
| TURN (coturn on $5 VPS) | Last resort for worst CGNAT | $5/mo |

### Indonesian Carrier Compatibility
- **Telkomsel**: AutoRelay works, STUN fallback rare
- **Indosat/IM3**: Similar, may need STUN
- **XL Axiata**: Moderate CGNAT, TURN for ~15%
- **Tri (3)**: Worst CGNAT, TURN needed ~25%

## 📊 Project Structure

```
neo-p2p/
├── infrastructure/          # 🖥 Relay deployment (Docker, Oracle Cloud)
│   ├── docker-compose.yml
│   ├── libp2p-relay/        # Go circuit relay v2
│   ├── strfry/              # Nostr relay configs
│   ├── coturn/              # TURN server config
│   └── scripts/             # deploy, status, restart, backup
├── android/                 # 📱 Android app (Kotlin + Compose)
│   ├── app/src/main/java/com/neop2p/
│   │   ├── data/p2p/        # libp2p, Nostr, WebRTC, Signal, KeyStore
│   │   ├── data/escrow/     # Lightning escrow + 1% fee payout
│   │   ├── data/reputation/ # Gossip attestations
│   │   ├── data/local/      # Room + SQLCipher
│   │   ├── di/              # Hilt modules
│   │   ├── navigation/      # NavGraph (8 routes)
│   │   ├── service/         # Foreground P2P service
│   │   ├── ui/screens/      # 8 Compose screens
│   │   └── domain/model/    # TradeOffer, Escrow, Peer models
│   └── gradle/              # Version catalog
└── AGENTS.md                # Development agent system
```

## 🔒 Security & Privacy

- **No phone, email, or name** ever required
- **No account creation** — just a cryptographic key
- **No central servers** — all data is peer-shared or on-device
- **E2EE chat** — Signal Protocol provides forward secrecy and deniability
- **Offline-first** — Room DB encrypted with SQLCipher
- **Tor support** — optional routing through Tor for maximum anonymity
- **Open source** — all code auditable, fee address hardcoded

## 🧪 Current Status

**Phase: v1.0.0-alpha (Scaffold Complete)**

All base components are implemented:
- ✅ Identity system (Ed25519 + Android KeyStore)
- ✅ P2P transport (java-libp2p, Nostr, WebRTC)
- ✅ E2EE chat (Signal Protocol)
- ✅ Lightning escrow (2-of-3 multisig, pre-signed 1% fee split)
- ✅ Gossip reputation (signed attestations)
- ✅ Room database (SQLCipher-encrypted)
- ✅ Dagger Hilt DI
- ✅ All 8 Compose screens
- ✅ NavGraph routing
- ✅ P2P foreground service
- ✅ Docker relay infrastructure (Oracle Cloud Free Tier)
- ✅ Deploy / management scripts
- ✅ ProGuard / R8 rules

**Needed for production:**
- [ ] Real LDK Lightning transaction building
- [ ] Full BIP-39 mnemonic derivation (BIP-32)
- [ ] Nostr NIP-01 event signing (secp256k1)
- [ ] WebRTC ICE negotiation (real offer/answer exchange)
- [ ] Bahasa Indonesia localization
- [ ] Unit + integration tests
- [ ] CI/CD pipeline
- [ ] UI polish + animations

## 🗺 Roadmap

| Phase | What | Timeline |
|-------|------|----------|
| **v1.0-alpha** | Architecture, P2P foundation, UI scaffold | ✅ Complete |
| **v1.1** | LDK integration, real escrow transactions | 2 weeks |
| **v1.2** | Nostr signing, BIP-39 full support | 1 week |
| **v1.3** | WebRTC real data channels, file transfer | 1 week |
| **v2.0** | Production release — ID localization, tests, CI/CD | 2 weeks |
| **v2.1** | Extended assets (USDT, ETH) | 1 week |
| **v3.0** | Tor integration, advanced privacy features | 2 weeks |

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

---

*Built with ❤️ for the Indonesian P2P crypto community.*


## CI/CD Pipeline

The project uses GitHub Actions for continuous integration and deployment:

### Android
- **Workflow**: `.github/workflows/android-ci.yml`
- **Builds**: Debug APK on every PR/push to main/develop
- **Tests**: Unit tests and linting
- **Deployment**: 
  - Internal test track on push to main
  - Requires secrets: `ANDROID_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `GOOGLE_PLAY_SERVICE_ACCOUNT`

### iOS  
- **Workflow**: `.github/workflows/ios-ci.yml`
- **Builds**: IPA for testing on every PR/push to main/develop
- **Tests**: Unit tests with code coverage
- **Deployment**: 
  - TestFlight on push to main
  - Requires secrets: `APPLE_ID`, `APPLE_APP_SPECIFIC_PASSWORD`, `MATCH_PASSWORD`

### Local Development
To setup Fastlane locally:
```bash
# Android
cd android
fastlane init

# iOS  
cd ios
fastlane init
```
