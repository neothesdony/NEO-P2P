# AGENTS.md

NEO-P2P: zero-backend, peer-to-peer anonymous crypto trading app for Indonesia. Android-only Kotlin app (Compose + Hilt + Room). **The root Gradle project is intentionally empty** — all real config lives under `android/`.

## Structure (only these dirs are live)

- `android/` — the app. `settings.gradle.kts` includes `:app`. All build/test commands run from `android/`.
- `infrastructure/` — relay deployment (Docker/Strfry/libp2p relay/coturn), Oracle Cloud ARM64. See `infrastructure/AGENTS.md`.
- `design-system/` — design tokens JSON. See `design-system/AGENTS.md`.
- `legacy/` — **dead** KMM code (`iosMain`, `commonMain`, `androidMain`, `iosApp`) NOT wired into any build. Do not edit; it exists as reference only.
- Root `build.gradle.kts` and `settings.gradle.kts` are intentionally minimal (only `include(":android")`). Don't add plugins at root — that causes version conflicts.

## Commands (run inside `android/`)

```bash
./gradlew :app:assembleDebug     # build
./gradlew :app:testDebugUnitTest # unit tests (plain JUnit 4, no Robolectric)
./gradlew :app:lintDebug         # lint (baseline: app/lint-baseline.xml)
```

Root repo is NOT a Gradle project to build from — you must `workdir: android`. Gradle 8.9, AGP 8.7.3, Kotlin 2.1.0, JDK 17, minSdk 26 / targetSdk 36.

## Read before touching identity/crypto/escrow/reputation

`CRITICAL.md` documents hard-earned structural history (single Ed25519 key was once used as identity for every protocol; now BIP-39/BIP-32 seed derivation with secp256k1-kmp + Bouncy Castle). Also `IDENTITY_REWRITE.md` for the key-derivation blueprint. Identity comes from a BIP-39 mnemonic, not a single KeyStore key.

## Build/runtime gotchas

- `local.properties` (in `android/`) feeds BuildConfig fields: `P2P_RELAY_URL`, `TURN_USERNAME`, `TURN_CREDENTIAL`. **Never commit `local.properties`** — TURN credentials and relay URL are secrets loaded from it with `changeme_debug` fallbacks.
- Debug/release `TURN_*` come from `local.properties`; debug defaults to `changeme_debug`.
- `libp2p` requires full `protobuf-java` (its `crypto.pb` uses `ProtocolMessageEnum`, absent from javalite). `protobuf-java` is declared explicitly; `protobuf-javalite` is excluded from `bitcoinj`.
  - **E2EE (P0-2)**: `libsignal-protocol-java` was **removed** (archived upstream Feb 2022; its javalite classes crashed under full `protobuf-java`). Chat E2EE is a **custom NIP-44-*inspired*** scheme (X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305, 12-byte nonce) via Bouncy Castle, keyed from the BIP-39 mnemonic (`m/44'/999'/0'/0/0`). It is **NOT NIP-44/59 wire-compatible** — interop only between NEO-P2P peers. Peer keys persist in SQLCipher `conversation_keys`; DB is at version 8 (7→8 drops the old Signal store tables). NIP-59/rust-nostr is deferred. See `docs/SECURITY_POSTURE.md`.
- libp2p transport is primary; WebSocket relay (`P2PTransportManager`) is the strict-NAT fallback, orchestrated by `HybridP2PTransport`.
- SQLCipher is `net.zetetic:sqlcipher-android:4.17.0` (16 KB-aligned `.so`), **not** the old `android-database-sqlcipher` (frozen at 4.5.4, 4 KB-aligned). `AppDatabase.kt` uses `net.zetetic.database.sqlcipher.SupportOpenHelperFactory` and calls `System.loadLibrary("sqlcipher")`. App is 16 KB-native.
- Default relay hostnames are `*.custom-minipc.com` (Nostr, libp2p circuit, TURN/STUN) in `NeoP2PConfig.kt`.
- Package `com.neop2p`, namespace `com.neop2p`, applicationId `com.neop2p.app`.
- Lint `disable` list in `app/build.gradle.kts` is a deliberate AGP 8.7.3 + Kotlin 2.1.0 + Compose workaround — leave it.

## CI

`.github/workflows/ci.yml` is the active pipeline (build + unit tests + lint + dependency scan). `android-ci.yml` and `ios-ci.yml` are stale/legacy. iOS is not in the active build.

## Sub-module instruction files

`android/AGENTS.md`, `infrastructure/AGENTS.md`, `design-system/AGENTS.md` each hold their module's ownership, contracts, and verification commands — read the relevant one before editing that area.
