# Changelog

All notable changes to NEO-P2P will be documented in this file.

## [1.0.11] — 2026-08-25

### Fixed

#### Deleted offers no longer resurrect on app open / update
- **Root cause:** the relay stores the original kind:33333 offer event forever and replays it on every subscription. Deleting the Room row + publishing NIP-09 did nothing to stop the next reconnect from re-inserting the offer — deleted offers kept coming back on every app open.
- **Fix:** new `DeletedOfferStore` — a persistent (SharedPreferences) tombstone store keyed by BOTH the offer id and the Nostr event id.
  - `HomeViewModel.persistNostrOffers` skips any replayed event carrying a tombstone (HomeScreen.kt) — the relay replay can no longer resurrect a deleted offer.
  - Deleting your own offer (`OfferDetailViewModel.deleteOffer`) now tombstones the offer + event id.
  - Peer NIP-09 deletions (`P2POrchestrator.collectOfferDeletions`) now remove the local Room row AND tombstone it — previously peer deletions only fired a notification and the row lingered.
  - **Backfill:** our own NIP-09 deletion events are replayed by the relay on every connect; `NostrClient` now surfaces them (`ownDeletions`) and `P2POrchestrator.collectOwnDeletions` tombstones them — so offers deleted *before* this fix stop resurrecting from the first launch of the new build.
  - Status updates for tombstoned offers are harmless no-ops (SQLite UPDATE on a missing row).

## [1.0.10] — 2026-08-25

### Fixed

#### Notification deep links actually navigate (were dead)
- `MainActivity` now consumes the route string carried in `Intent.EXTRA_TEXT` by every notification and navigates via a `NavDeepLinkRequest` — on cold start (once the nav graph is ready) and warm start (`onNewIntent`). Previously no code read the intent, so every notification tap just opened Home.
- `NeoP2PNavGraph` exposes an `onNavControllerReady` callback (LaunchedEffect after composition) so the activity can navigate without racing graph setup.
- Unknown/malformed routes are ignored; navigation failures are logged, never crash.

#### Chat notifications now carry the real offer id
- `ChatRouter.receiveChat` emits a new `incomingChats` flow (`IncomingChat(fromPeerId, offerId, plaintext)`) — the offer id comes from `AppMessage.Chat`, which the old `signal.incomingMessages` collector dropped.
- `P2POrchestrator.notifyInboundChat` consumes that flow: notifications now group per conversation (unique notification id), deep-link to the correct `chat/{offerId}/{peerId}` thread, and `ChatScreen.cancelChatNotifications(offerId)` finally clears the exact notification that was posted (previously every chat collapsed into one fixed slot and could never be dismissed by opening the thread).

#### Escrow timeouts enforced at runtime + notify
- New 60s `sweepStaleEscrows()` loop in `P2POrchestrator` — previously `expireStaleEscrows()` ran only once at startup, so a long-lived process never auto-cancelled (30 min) or auto-refunded (6 h) a stalled escrow.
- The sweep's `FUNDING → CANCELLED` transition now updates `_escrowStates` and emits `EscrowTransition` — auto-cancel/auto-refund notify the user instead of silently flipping the DB row.

#### Wallet receive notifications survive restarts
- `WalletWatcher` persists last-seen txids in SharedPreferences (`neop2p_wallet_watch`); a process restart no longer replays up to 10 stale "Bitcoin received" notifications.

### Changed

- **Foreground suppression extended:** escrow and wallet notifications are suppressed while the app is foregrounded (chat already was). Only chat has per-conversation suppression; escrow/wallet suppression is app-wide.
- **NIP-09 self-delete filter:** deletion events signed by our own pubkey are ignored — deleting your own offer no longer pings you with "An offer you were watching was deleted."
- `NotificationDispatcher` posts through a permission-guarded helper; fixes the 6 `MissingPermission` lint errors that shipped with the dispatcher (lint now passes).
- `WalletWatcher` and `NotificationDispatcher` DI updated (application context + `AppForegroundTracker`).

## [1.0.9] — 2026-08-24

### Fixed

#### Wallet send hardening (real-money path)
- **Balance errors no longer fake a zero balance:** `WalletService.loadState()` now propagates balance/history API failures to the error screen instead of silently rendering `0.00000000 BTC` when mempool/blockstream is unreachable.
- **Fee computed after UTXO selection:** multi-input sends now pay `rate × (inputs×148 + outputs×34 + overhead)` instead of a fixed 1-input estimate (underpaid fees on multi-input sends could get stuck/rejected).
- **Double-send window closed:** a real `isSending` StateFlow guard (set before broadcast, cleared in `finally`) plus a **confirmation dialog** before any broadcast. The old `sending = true; onSend(); sending = false` was synchronous — the button re-enabled before the async send finished, so two quick taps broadcast twice.
- **bech32 destinations supported:** destination is parsed with `Address.fromString()` (was `LegacyAddress.fromBase58`, which threw on `bc1...`); invalid addresses get a clean error instead of a crash.
- **Pull-to-refresh** (M3 `PullToRefreshBox`) — the `onRefresh` callback was previously dead; refresh now keeps old content visible instead of flashing a full-screen spinner.
- **History is now meaningful:** per-tx timestamp, direction label (Received/Sent/Self from vin/vout `scriptpubkey_address` analysis), NET amount for sends (negative, includes fee), and a fee line for confirmed sends. The old gross-vout figure included your own change output and was misleading for sends.
- QR generation moved off the main thread; dead code removed (`showSendDialog`, unused `identityManager` injection, fake copy snackbar).

#### ChainMonitor → Testnet4
- **Testnet queries now hit Testnet4** (`mempool.space/testnet4/api`, `blockstream.info/testnet4/api`) — the dev faucet and all funded addresses live on Testnet4 (2026-08-24). Testnet3 and Testnet4 share address formats (`m...`/`n...`), so keys/signing (bitcoinj `TestNet3Params`) are unchanged; only the explorer API base URLs changed. Symptom fixed: wallet showed `0 BTC` while the same address held 6,000,272 sats confirmed on Testnet4.
- `Utxo.vout` is now `Int` (bitcoinj's `Transaction.addInput` requires it — the wallet send path was silently incompatible).

### Changed

- `ChainMonitor.AddressTx` gained `receivedSats`/`spentSats`/`netSats`/`direction` (computed from vout + vin prevout).

## [1.0.8] — 2026-08-24

### Added

#### Wallet (new)
- Personal BIP-44 wallet page (`data/wallet/WalletService.kt`, `ui/screens/wallet/WalletScreen.kt`): balance (confirmed + unconfirmed), receive address with **scannable QR** (`bitcoin:` URI, zxing) + copy button, send form (destination + sats), transaction history.
- Send flow: greedy confirmed-UTXO selection, raw P2PKH tx build + sign (mirrors `EscrowService` bitcoinj pattern, DER sig + SIGHASH_ALL), change back to sender (dust-guarded), broadcast via ChainMonitor.
- Wallet FAB on Home, left of "Create Offer" (+ nav route + EN/ID strings).

### Fixed
- **Chat E2EE end-to-end (live-verified on OnePlus7 + emulator):**
  - `P2POrchestrator` was dead code — only `P2PBackgroundService` (never started) called `start()`. It is now started from `HomeViewModel.startBackgroundSync()`, so pre-key handshakes, chat, and escrow signaling actually dispatch.
  - `SignalProtocol.createSession` no longer refuses unauthenticated transports (the WS relay always marks `authenticated=false`); identity binding (Ed25519 sig over prekey + peerId derivation) is the real trust anchor.
  - Two-shot pre-key handshake: on receiving a bundle, reply with our bundle **only if no session exists yet** (prevents an infinite bundle loop).
  - `decryptWithKey` no longer truncates the last 16 bytes (`doFinal` consumes the Poly1305 tag; the extra `copyOf(out.size - 16)` chopped real plaintext). Proven by a JVM round-trip test (`ChaChaRoundTripTest`).
  - Room chat history now loads and decrypts in `ChatViewModel` (`getMessages` finally has callers); `ChatRouter` persists + forwards via `ChatMessageDao`.
  - Queue drain works for unauthenticated peers (`collect` not `collectLatest`, which cancelled mid-drain on every peers emission).
- **Self-chat routing bug:** kind:33336 status events now carry `matched_peer_id` (the acceptor); persisted via DB v13 (`trade_offers.matched_peer_id`) so a creator's own matched offer routes chat to the buyer, not to self. Raw Nostr offer re-announcements never downgrade a locked status or wipe `matched_peer_id` (status-wipe race).
- **Chat target on the acceptor's device:** the offer-detail "Chat with Peer" button now routes by role — creator → `matchedPeerId`, acceptor → `creatorPeerId` (previously the acceptor's own device used its self-published `matchedPeerId` and chatted with itself).
- **Transport never starts after locked boot (P0-4-2):** if the app starts while the phone is locked, the P0-4 identity guard skips `startBackgroundSync()` and the relay never learns the peerId (peers hit "delivery failed: peer not found"). `HomeScreen` now retries on `ON_RESUME` when the transport is inactive, and `startBackgroundSync()` guards against duplicate starts via `HybridP2PTransport.isActive()`.
- **Mempool.space unreachable:** `ChainMonitor` falls back to Blockstream.info (identical JSON API) on any failure; also fixes escrow funding verification on networks where mempool.space times out.
- **No HTTP timeouts:** shared Ktor `HttpClient` now has 10s connect / 20s request/socket timeouts (wallet/chain calls previously hung forever).

### Changed
- `AppDatabase` bumped to **version 13** (12→13 adds `trade_offers.matched_peer_id`).
- Locked-offer detail now shows a **Chat with Peer** button (both own and others' matched offers); buyers see Chat + disabled Escrow buttons in the Home top bar (left of the NEO-P2P title).

## [1.0.7] — 2026-08-24

### Fixed

#### Onboarding
- All onboarding steps now scroll (`verticalScroll`) so small screens and the IME never push buttons off-screen.
- Seed phrase box now copies to the clipboard with a Snackbar confirmation (was a dead `TODO`).
- "Show again" now toggles seed visibility (Hide/Show) instead of being a no-op.
- `generateIdentity()` now surfaces errors to the user via a Snackbar instead of swallowing them.

#### Settings
- Settings content is now scrollable.
- "Add Relay" is now functional: a relay URL input field was added and the button enables for a non-blank, non-duplicate URL.
- `resetIdentity()` now requires a destructive confirmation dialog before wiping the identity keypair.

#### Chat
- Chat error state now shows an icon + Retry button instead of bare text.
- Send/attach failures now surface via a Snackbar instead of failing silently.
- Attach File button opens the system file picker (best-effort placeholder until real data-channel file transfer exists).
- Fixed the dual text-input state (single `messageText` StateFlow now drives the field).
- Chat banner strings and `timeAgo` are now localized via string resources.

#### Create Offer / Edit Offer
- Sell summary line no longer uses the error color for a normal action.
- Inline "${method} Account Number" label replaced with a localized string resource.
- `EditOfferScreen` no longer shows an infinite spinner when the offer fails to load; it now has a Loading/Error/Success state with a Retry action.
- `createOffer`/`updateOffer` no longer swallow failures: errors now surface via a Snackbar (`OfferFormState.error`).

#### Escrow / Profile
- Escrow content is now scrollable so bottom actions (release/dispute/refund) stay reachable on small screens.
- Profile content is now scrollable.

### L10n
- Added missing Indonesian (`values-in`) translations surfaced by lint `MissingTranslation` for settings, dispute, onboarding, and chat strings.
- Removed duplicate `chat_just_now` string entries that broke resource merging.
- Default `values/` `timeAgo` strings are now English (`%1$d minutes/hours/days ago`); Indonesian variants live only in `values-in/` so non-Indonesian locales no longer show mixed-language chat timestamps.

## [1.0.6] — 2026-08-24

### Changed

#### Fee model — 0.3%, seller-only
- `NeoP2PConfig.FEE_PERCENT = 0.003` (was 0.01).
- **Only the seller pays the fee; the buyer pays nothing and receives the full crypto amount.**
- `TradeOffer.buyerFeeSats = 0`, `sellerFeeSats = feeSats`, `totalDepositSats = cryptoAmountSats + feeSats`.
- Escrow fields: `depositAmountSats = C + feeSats`, `tradeAmountSats = C`, `feeAmountSats = feeSats`.

#### On-chain network/miner fee accounted for
- The payout tx previously carried a zero miner fee (invalid). A dynamic **network fee** (`rate × ~220 vbytes`, from `ChainMonitor.estimateFees()`) is now added to the seller's deposit and stored as `network_fee_sats` on the escrow. Buyer still receives full `C`; fee wallet gets the full 0.3%; miner fee = `network_fee_sats`.

#### Escrow — real on-chain 2-of-3 re-introduced
- `data/escrow/EscrowService.kt`, `ChainMonitor.kt`, and `ui/screens/escrow/EscrowScreen.kt` restored as a real 2-of-3 P2SH multisig on-chain escrow; `domain/model/Escrow.kt` added.
- `AppDatabase` bumped to **version 12** (v9 created escrows/dispute_evidence tables; v10→v11 added `funded_at`; v11→v12 added `network_fee_sats`).

#### Escrow timeouts split
- `ESCROW_FUNDING_TIMEOUT_MS` (30 min) → unfunded escrows auto-`CANCELLED`.
- `ESCROW_FUNDED_REFUND_TIMEOUT_MS` (6 h) → funded-but-stalled escrows auto-`REFUNDED` to the seller's own address.
- `EscrowStatus` gained `CANCELLED`.

#### Offer lifecycle
- Create-offer page is now **sell-only** (BUY tab removed).
- A seller's own offer shows **Edit + Delete** (never Accept); Edit reuses `CreateOfferScreen` pre-filled and saves back to the same offer (`edit_offer/{offerId}` route + `EditOfferScreen.kt`).
- Accepting another's offer locks it (`OfferStatus.MATCHED`/`ESCROWED`) via a custom Nostr `kind:33336` status event (`NostrClient.publishOfferStatus`/`offerStatusUpdates`).
- Offer deletion propagates via NIP-09 (`kind:5`).

### Fixed

- **Startup crash:** `HomeViewModel.startBackgroundSync()` no longer crashes on the main thread when the identity is locked behind device auth (P0-4) — it skips sync instead.
- **Offer-detail load failure:** `OfferDetailViewModel.loadOffer()` no longer fails the whole screen when identity is locked — it defaults `isOwnOffer=false` so the offer still renders.
- **Publish-returns-to-list:** `createOffer` persists locally first, then publishes to Nostr inside `withTimeoutOrNull(5000)` so it never hangs and always navigates back to the list.

## [1.0.5] — 2026-08-22

### Docs

- **Corrected E2EE description across README, AGENTS.md, and SECURITY_POSTURE.md.**
  The chat scheme is a **custom, NIP-44-*inspired*** design (X25519 ECDH +
  HKDF-SHA256 + ChaCha20-Poly1305, 12-byte nonce) — **not** XChaCha20 and
  **not** NIP-44/59 wire-compatible. It is interoperable only between NEO-P2P
  peers.
- **Documented accepted E2EE limitations:** no forward secrecy (static-static
  ECDH), TOFU key trust (no fingerprint verification UI), and the requirement
  that both peers be online to exchange keys.
- **Recorded the decision to defer NIP-59 / rust-nostr** (hand-rolled Kotlin
  NIP-44/59 or the rust-nostr SDK, whose native `.so` deps must be verified
  16 KB-aligned) in `docs/SECURITY_POSTURE.md`.

## [1.0.4] — 2026-08-22

### Security (P0/P1 hardening pass)

#### P0-1 — Bank details no longer leaked to public Nostr offers
- `CreateOfferScreen` no longer publishes `payment_details` (account_number / account_holder) in the public Nostr offer JSON. Account details are still collected in the form but only exchanged after a taker commits, inside an encrypted channel.

#### P0-2 — Dead Signal library replaced
- Removed `org.whispersystems:signal-protocol-java` (archived Feb 2022; protobuf-javalite classes crashed under the full `protobuf-java` runtime required by libp2p) from the version catalog, build, and ProGuard rules.
- New E2EE: NIP-44-style XChaCha20-Poly1305 (X25519 ECDH + HKDF-SHA256) via Bouncy Castle, keyed from the BIP-39 mnemonic (`m/44'/999'/0'/0/0`).
- Conversation keys persist in SQLCipher (`conversation_keys` table) so sessions survive restarts.
- DB migrated 7 → 8 with a drop-and-create migration (old Signal store tables removed); no destructive fallback.

#### P0-3 — Per-trade key rotation
- New `IdentityManager.getNextTradeNostrKeyPair()` derives from `m/44'/1237'/0'/0/<index>` with a persisted index; offers are signed by a fresh key each time, breaking cross-trade linkage.

#### P0-4 — Biometric/device-auth gate on identity seed
- On devices with a lock-screen credential, the identity seed key requires recent user auth (5-min validity window).
- `IdentityLockedException` is thrown when locked — the app never silently generates a replacement identity. Invalidated-key path surfaces a restore prompt.

#### P1 — libp2p 1.3.6 + posture doc
- `jvm-libp2p` bumped to 1.3.6-RELEASE.
- New `docs/SECURITY_POSTURE.md` documents the threat model, key hierarchy, and planned LDK / Play Integrity / rust-nostr work.

## [1.0.3] — 2026-08-22

### Changed

#### Escrow flow (seller funds, buyer receives)
- Clarified the escrow role model: the **seller** supplies BTC and locks `100.5%` (trade amount + buyer half-fee) into the 2-of-3 P2SH multisig; the **buyer** pays IDR via the selected fiat method; on confirmation the payout sends **99.5% → buyer** and **1% → fee wallet**.
- `generatePayoutTransaction()` now pays the **buyer** (99.5%) instead of the seller (previously the direction was inverted).

#### Real P2SH signing
- `signTransaction()` now signs the payout against the **real 2-of-3 P2SH redeem script** (previously signed with `ScriptBuilder.createEmpty()` which could never spend the multisig).
- `releaseFunds()` assembles the correct P2SH scriptSig via bitcoinj `createMultiSigInputScriptBytes()` before broadcasting.
- Escrow now persists the `redeem_script_hex` (DB migration v6 → v7) so the payout can be signed/spent.

#### Fixed
- **`createEscrow()` P2SH address bug**: derived the P2SH address from `Utils.sha256hash160(redeemScript)` instead of passing the raw 105-byte redeem script to `LegacyAddress.fromScriptHash` (which throws `AddressFormatException`).

#### Testing
- Added `EscrowCryptoTest` validating the 2-of-3 P2SH signing end-to-end against a testnet address, including the fee math (100.5% deposit, 99.5% buyer, 1% fee).

### Security
- **Fee wallet signature protection**: the fee address is now signed with an Ed25519 key held only by the owner. `createEscrow()` refuses to run if the signature is invalid, blocking forked builds from redirecting the fee.

## [1.0.2] — 2026-08-07

### Added

#### Onboarding
- **Seed phrase verification step**: New `VERIFY_SEED` step added between backup and finish. The user must re-enter 3 randomly-selected words from their 12-word phrase to confirm they saved it.
- **Nickname persistence**: Nickname entered during onboarding is now saved to the identity and shown on Profile/Home.

#### Create Offer
- **Market-price default**: `pricePerBtc` now pre-fills to `DEFAULT_BTC_MARKET_PRICE_IDR` (static placeholder until a live feed is wired up).
- **Payment method details**: Selecting a fiat method (e.g. BCA) now expands to collect the recipient's account number and account holder name. Details are validated (button stays disabled until complete) and published with the offer as `payment_details`.

#### Settings
- **Live relay status**: The relay list now shows the real connection state (`Connected` / `Not connected`) sourced from `NostrClient.relays`, instead of a hardcoded `Connected` for every relay.

### Changed

#### Relay domains
- Default Nostr, libp2p circuit, and TURN/STUN relay hostnames changed from `*.neop2p.io` to `*.custom-minipc.com` in `NeoP2PConfig.kt`.

#### 16 KB page-size alignment (Google Play requirement)
- Upgraded SQLCipher from the frozen `net.zetetic:android-database-sqlcipher:4.5.4` (4 KB-aligned `.so`) to the actively-maintained `net.zetetic:sqlcipher-android:4.17.0` (16 KB-aligned `.so`).
- Migrated `AppDatabase.kt` to `SupportOpenHelperFactory` (new package `net.zetetic.database.sqlcipher`) and added explicit `System.loadLibrary("sqlcipher")`.
- Removed unused `secp256k1-kmp` dependency (code uses pure Kotlin + Bouncy Castle; no JNI imports).
- Removed `android:pageSizeCompat="enabled"` from the manifest — the app is now truly 16 KB-native.
- **Result**: All native libraries (`libsqlcipher.so`, `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libjingle_peerconnection_so.so`) now report 16 KB LOAD alignment, and no compatibility dialog is shown.

### Fixed

- **Offer Details infinite loading spinner**: `loadOffer(offerId)` is now called on first composition via `LaunchedEffect` instead of only on the Retry button.
- **Profile Edit Nickname did nothing**: Added a working nickname-edit dialog that persists via `IdentityManager.updateNickname()`.
- **Profile View Attestations did nothing**: Now shows a snackbar when tapped.
- **Create Offer submit disabled forever**: `pricePerBtc` now pre-fills and `canSubmit` correctly evaluates filled fields + payment details.
- **Create Offer `Total: Rp Rp 0` double-prefix**: Removed the duplicate currency prefix; totals now compute from the market-price default.
- **Onboarding backup screen stuck spinner after Back**: `isConfirming` is now reset on back-navigation.
- **Chat screen crash** (`Failed to initialize chat: length=3; index=3`): Signal init is now non-fatal. `IdentityKeyPair` is serialized as raw EC bytes (avoiding a protobuf-javalite/full-protobuf conflict), and chat loads with mock messages even when Signal init is unavailable.

### Known Limitations

- Signal Protocol E2EE cannot be fully initialized under the full `protobuf-java` runtime (required by libp2p); javalite-generated message classes conflict. Chat currently runs on mock data; real E2EE needs an architectural protobuf fix.
- `DEFAULT_BTC_MARKET_PRICE_IDR` is a static placeholder; a live BTC/IDR price feed is not yet wired up.
- `relay*.custom-minipc.com` hostnames require DNS records pointing at the relay server before they resolve.

## [1.0.1] — 2026-07-08

### Changed

#### Fee Model
- **1% fee now split 50/50** between buyer and seller (0.5% each)
- Buyer deposits **100.5%** (trade amount + their half of fee)
- Seller receives **99.5%** (their half deducted from payout)
- Fee wallet receives full 1% from combined halves
- `TradeOffer.totalDepositSats` now excludes seller's half of fee
- Added `TradeOffer.buyerFeeSats` and `TradeOffer.sellerFeeSats` computed properties

## [1.0.0-alpha] — 2026-05-14

### Added

#### Core P2P
- Identity system: Ed25519 keypair generation in Android KeyStore (hardware-backed)
- libp2p host with AutoRelay, DHT bootstrap, peer discovery
- Nostr client: NIP-01 event publishing/subscription (Nostr WebSocket via Ktor)
- NIP-65 relay hint support for libp2p peer discovery
- Signal Protocol integration: session establishment, message encryption/decryption
- WebRTC data channel: payment proof P2P file transfer, ICE negotiation

#### Escrow
- Lightning 2-of-3 multisig escrow creation
- Pre-signed payout transaction: 100% seller + 1% fee wallet
- Fee wallet address hardcoded in open-source code (NeoP2PConfig.kt)
- Escrow state machine: FUNDING → FUNDED → SIGNED → RELEASED / DISPUTED / REFUNDED
- 7-day timelock for disputes (timeout-based, no arbitration server needed)

#### UI (8 Screens, Jetpack Compose + Material 3)
- **Onboarding**: 4-step flow (Welcome → Create Identity → Backup Seed → Finish)
- **Home**: Offer feed with pull-to-refresh, peer reputation scores
- **Create Offer**: Buy/Sell toggle, BTC amount, IDR price, fiat method selection
- **Offer Detail**: Full trade summary, fee breakdown, peer profile & reputation
- **Chat**: E2EE messages, file attachment, payment proof sharing
- **Escrow**: Live status tracking, confirm payment, release/trigger dispute
- **Profile**: Keypair display, nickname, reputation statistics
- **Settings**: Relay management, TURN server config, Tor toggle, identity reset

#### Data & Storage
- Room database with SQLCipher encryption
- DAOs: Peer, TradeOffer, Escrow, ChatMessage (Flow-based reactive queries)
- Entities with JSON fields for multiaddrs, relays, fiat methods
- Gossip-based reputation system with signed attestations

#### Infrastructure (Oracle Cloud Free Tier)
- Docker Compose with 4× strfry Nostr relays (3 public + 1 metadata)
- libp2p circuit relay v2 in Go (with Prometheus metrics)
- coturn TURN/STUN server for worst-case CGNAT
- 4 deployment scripts: deploy, status, restart, backup

#### Android Project
- Kotlin 2.1, AGP 8.7.0, Compose BOM 2026.03.00
- Dagger Hilt 2.52, Room 2.7, Ktor 2.4
- ProGuard/R8 rules for optimization
- Foreground service for P2P connection maintenance
- Network security config with cleartext rules for local dev
- Material 3 dark cyber-green theme

#### Fiat Method Support
- 4 bank transfers: BCA, Mandiri, BNI, BRI
- 5 e-wallets: GoPay, OVO, Dana, ShopeePay, LinkAja
- Cash meetup (Tunai)
- Configurable FiatMethod enum in NeoP2PConfig.kt

### Technical Notes
- Zero backend servers: everything runs on-device + Nostr relays + libp2p DHT
- 1% fee enforced via pre-signed multisig payout (no server can intercept)
- NAT traversal: AutoRelay (~80%) → STUN → TURN (~20% worst CGNAT)
- All communication channels are end-to-end encrypted (Signal Protocol)
- Seed phrase backup (12-word BIP-39 style, full derivation pending)

### Known Limitations (v1.0-alpha)
- LDK Lightning transaction building is scaffolded but uses placeholder signatures
- BIP-39 mnemonic generation is simplified (full BIP-32 derivation pending)
- Nostr NIP-01 event signing uses placeholder sigs (secp256k1 pending)
- WebRTC ICE negotiation is scaffolded (real offer/answer exchange pending)
- UI is English-only (Bahasa Indonesia localization pending)
- No unit or integration tests yet
- No CI/CD pipeline

---

[1.0.0-alpha]: https://code.neop2p.io/thesdony/neo-p2p/tree/v1.0.0-alpha
