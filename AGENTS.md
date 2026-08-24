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

`CRITICAL.md` documents hard-earned engineering (single Ed25519 key was once used as identity for every protocol; now BIP-39/BIP-32 seed derivation with secp256k1-kmp + Bouncy Castle). Also `IDENTITY_REWRITE.md` for the key-derivation blueprint. Identity comes from a BIP-39 mnemonic, not a single KeyStore key.

Escrow is now a **real on-chain 2-of-3 P2SH multisig** (`data/escrow/EscrowService.kt`, `ChainMonitor.kt`), funded by the seller and verified on-chain via Mempool. The fee model is **0.3%, seller-only**: the seller deposits `crypto + 0.3% fee + network fee`, the buyer pays nothing and receives the full crypto amount, and the 0.3% goes to the fee wallet. The **Room DB is at version 15**. `ChainMonitor` falls back to Blockstream.info (identical JSON API) when mempool.space is unreachable; the shared Ktor `HttpClient` has 10s connect / 20s request timeouts.

## Build/runtime gotchas

- **JDK 17 is pinned machine-wide** via `org.gradle.java.home=/home/thesdony/.sdkman/candidates/java/17.0.12-tem` in `~/.gradle/gradle.properties` (user-level, NOT committed). The system default `java` is JDK 25, which AGP 8.7.3 rejects — this pin fixes the recurring "Build failed: 25.0.4" problem for every shell/IDE invocation without needing to export `JAVA_HOME`. If you need the build on a new machine, add the same key (or export `JAVA_HOME` to a JDK 17) or AGP will fail. Do NOT put `org.gradle.java.home` in `android/gradle.properties` (would be committed + machine-specific).
- **`AndroidLocationsException` guard:** if you see `Could not create provider ... AndroidLocationsBuildService`, the IDE has injected both `ANDROID_PREFS_ROOT` and `ANDROID_USER_HOME`, which AGP rejects. Run with `env -u ANDROID_PREFS_ROOT ./gradlew ...` or unset `ANDROID_USER_HOME`.
- `local.properties` (in `android/`) feeds BuildConfig fields: `P2P_RELAY_URL`, `TURN_USERNAME`, `TURN_CREDENTIAL`. **Never commit `local.properties`** — TURN credentials and relay URL are secrets loaded from it with `changeme_debug` fallbacks.
- Debug/release `TURN_*` come from `local.properties`; debug defaults to `changeme_debug`.
- `libp2p` requires full `protobuf-java` (its `crypto.pb` uses `ProtocolMessageEnum`, absent from javalite). `protobuf-java` is declared explicitly; `protobuf-javalite` is excluded from `bitcoinj`.
  - **E2EE (P0-2)**: `libsignal-protocol-java` was **removed** (archived upstream Feb 2022; its javalite classes crashed under full `protobuf-java`). Chat E2EE is a **custom NIP-44-*inspired*** scheme (X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305, 12-byte nonce) via Bouncy Castle, keyed from the BIP-39 mnemonic (`m/44'/999'/0'/0/0`). It is **NOT NIP-44/59 wire-compatible** — interop only between NEO-P2P peers. Peer keys persist in SQLCipher `conversation_keys`; DB is at version 15 (7→8 dropped the old Signal store tables; 8→12 added the escrow/dispute tables, `funded_at`, and `network_fee_sats`; 12→13 added `matched_peer_id` on trade_offers; 14→15 added `payment_details` on trade_offers). NIP-59/rust-nostr is deferred. See `docs/SECURITY_POSTURE.md`.
  - **Escrow**: real on-chain 2-of-3 P2SH multisig, funded by the **seller** with `crypto + 0.3% fee + network fee` (the payout tx needs a miner fee — estimated from `ChainMonitor.estimateFees()` and stored as `network_fee_sats`). Buyer receives the full crypto amount; 0.3% goes to the fee wallet. Split timeouts: 30 min → auto-cancel unfunded escrows; 6 h → auto-refund funded-but-stalled escrows to the seller's own address.
  - **One-tap escrow funding**: the escrow funding screen has a **"Send from my wallet to escrow"** button (with an irreversible-broadcast confirm dialog) that calls `WalletService.send()` with the exact `depositAmountSats`, auto-fills the txid, and verifies on-chain → moves the escrow to `FUNDED`. Before auto-cancelling a stale `FUNDING` escrow, `expireStaleEscrows()` now checks the P2SH address on-chain for a deposit (`hasOnChainDeposit`) and promotes to `FUNDED` instead of cancelling — so a funded-but-unverified escrow is never orphaned.
  - **`ChainMonitor.broadcastTx`** accepts Mempool's plain-text txid response for `POST /api/tx` (not just JSON).
  - **Offer flow**: create-offer is **sell-only**; a seller's own offer shows Edit + Delete (never Accept). Accepting another's offer locks it (`OfferStatus.MATCHED`/`ESCROWED`) via a custom Nostr `kind:33336` status event that now carries `matched_peer_id` (the acceptor's peerId) so the creator's chat button routes to the buyer; deleting an offer propagates via NIP-09 (`kind:5`). Raw offer re-announcements never downgrade a locked status or wipe `matched_peer_id`.
  - **Chat flow (fixed 2026-08-24, live-verified)**: the orchestrator is started from `HomeViewModel` (was dead code — `P2PBackgroundService` was never started). Two-shot pre-key handshake over the relay (request → bundle → reply bundle only if no session) establishes sessions both ways; `decryptWithKey` no longer truncates 16 bytes; Room history loads + decrypts in `ChatViewModel`; queue drain works for unauthenticated peers (the relay never marks `authenticated=true`; session identity binding is the actual trust anchor). **Chat is escrow-first**: the chat input is locked until the on-chain escrow is `FUNDED`; then the seller can tap **"Share payment details"** to send their bank number + holder name as a structured E2EE card to the buyer (bank details are stored in `trade_offers.payment_details` — never published to the Nostr relay, P0-1).
  - **Wallet**: `data/wallet/WalletService.kt` + `ui/screens/wallet/WalletScreen.kt` — personal BIP-44 wallet (receive QR via zxing, balance/history via ChainMonitor, raw-tx send with UTXO selection). FAB left of Create Offer.
- libp2p transport is primary; WebSocket relay (`P2PTransportManager`) is the strict-NAT fallback, orchestrated by `HybridP2PTransport`.
- SQLCipher is `net.zetetic:sqlcipher-android:4.17.0` (16 KB-aligned `.so`), **not** the old `android-database-sqlcipher` (frozen at 4.5.4, 4 KB-aligned). `AppDatabase.kt` uses `net.zetetic.database.sqlcipher.SupportOpenHelperFactory` and calls `System.loadLibrary("sqlcipher")`. App is 16 KB-native.
- Default relay hostnames are `*.custom-minipc.com` (Nostr, libp2p circuit, TURN/STUN) in `NeoP2PConfig.kt`.
- Package `com.neop2p`, namespace `com.neop2p`, applicationId `com.neop2p.app`.
- Lint `disable` list in `app/build.gradle.kts` is a deliberate AGP 8.7.3 + Kotlin 2.1.0 + Compose workaround — leave it.

## CI

`.github/workflows/ci.yml` is the active pipeline (build + unit tests + lint + dependency scan). `android-ci.yml` and `ios-ci.yml` are stale/legacy. iOS is not in the active build.

## Sub-module instruction files

`android/AGENTS.md`, `infrastructure/AGENTS.md`, `design-system/AGENTS.md` each hold their module's ownership, contracts, and verification commands — read the relevant one before editing that area.
