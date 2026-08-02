# Android App Module

## Purpose

Android peer-to-peer crypto trading application. Full Jetpack Compose UI with Material 3, Hilt dependency injection, Room local database, and a multi-layer P2P networking stack (libp2p circuit relay, Signal Protocol E2EE, WebRTC, Nostr protocol over Ktor WebSocket).

## Ownership

- **Owner:** Android team
- **Scope:** All source under `app/src/main/java/com/neop2p/`, build configs in `app/build.gradle.kts`, Gradle version catalog `gradle/libs.versions.toml`, CI/CD in `fastlane/`

## Local Contracts

- **UI Layer:** Jetpack Compose with Material 3, screens in `ui/screens/*/`, navigation via `navigation/NavGraph.kt` using Compose Navigation
- **DI:** Hilt (`@HiltAndroidApp`, `@AndroidEntryPoint`, `@Module @InstallIn`)
- **Local Storage:** Room (`AppDatabase`, `@Dao`, `@Entity`) + DataStore preferences + SQLCipher for encrypted stores
- **P2P Networking:**
  - `data/p2p/P2PTransport.kt` — Common P2P transport interface
  - `data/p2p/LibP2PManager.kt` — Direct libp2p transport (TCP + WebSocket + Noise + Mplex)
  - `data/p2p/P2PTransportManager.kt` — WebSocket relay fallback transport
  - `data/p2p/HybridP2PTransport.kt` — Orchestrates libp2p direct + WebSocket relay fallback
  - `data/p2p/SignalProtocol.kt` — Signal Protocol E2EE (pre-keys, sessions, double-ratchet)
  - `data/p2p/WebRTCManager.kt` — Stream WebRTC SDK for media/data channels
  - `data/p2p/NostrClient.kt` — Nostr protocol over Ktor WebSocket (NIP-01 events, NIP-65 metadata)
  - `data/p2p/store/SqlCipherSignalStores.kt` — Encrypted Signal store persistence
  - `data/p2p/IdentityManager.kt` — BIP-39/32 key derivation for Nostr/libp2p identity
- **Escrow:** `data/escrow/EscrowService.kt` — multi-sig escrow logic
- **Reputation:** `data/reputation/ReputationSystem.kt` — peer reputation scoring
- **Background:** `service/P2PBackgroundService.kt` — WorkManager-based background sync
- **Config:** `NeoP2PConfig.kt` — relay addresses, fee wallet, network timeouts, permissions

## Work Guidance

- Target SDK 36, min SDK 26, Compose BOM 2026.03.00, Kotlin 2.1.0
- Hilt for DI, Room with KSP for local persistence
- All P2P identity derived from BIP-39/32 mnemonic seed phrase
- TURN credentials and P2P relay URL injected via BuildConfig from local.properties (never committed)
- libp2p direct transport is primary; WebSocket relay is fallback for strict NAT/firewall

## Verification

- Build: `./gradlew :app:assembleDebug` from `android/` directory
- Tests: `./gradlew :app:testDebugUnitTest`
- Lint: `./gradlew :app:lintDebug` (uses `lint-baseline.xml` for legacy issues)

## Child DOX Index

*No children — leaf module.*
