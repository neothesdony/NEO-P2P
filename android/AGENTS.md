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
  - `data/p2p/SignalProtocol.kt` — E2EE chat (custom NIP-44-inspired: X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305; NOT NIP-44/59 wire-compatible)
  - `data/p2p/WebRTCManager.kt` — Stream WebRTC SDK for media/data channels
  - `data/p2p/NostrClient.kt` — Nostr protocol over Ktor WebSocket (NIP-01 events, NIP-65 metadata)
  - `data/p2p/store/PeerRegistry.kt` — peer registry (Signal store classes removed with libsignal)
  - `data/p2p/IdentityManager.kt` — BIP-39/32 key derivation for Nostr/libp2p identity
- **Escrow:** `data/escrow/EscrowService.kt` — real on-chain 2-of-3 P2SH multisig. Seller deposits `crypto + 0.3% fee + network fee`; buyer receives the full crypto amount; 0.3% goes to the fee wallet. Funding verified on-chain via Mempool (`ChainMonitor`).
- **Reputation:** `data/reputation/ReputationSystem.kt` — peer reputation scoring
- **Background:** `service/P2PBackgroundService.kt` — WorkManager-based background sync
- **Config:** `NeoP2PConfig.kt` — relay addresses, fee wallet, network timeouts, permissions

## Work Guidance

- Target SDK 36, min SDK 26, Compose BOM 2026.03.00, Kotlin 2.1.0
- Hilt for DI, Room with KSP for local persistence
- All P2P identity derived from BIP-39/32 mnemonic seed phrase
- TURN credentials and P2P relay URL injected via BuildConfig from local.properties (never committed)
- libp2p direct transport is primary; WebSocket relay is fallback for strict NAT/firewall

## E2EE (P0-2) — libsignal removed

- `libsignal-protocol-java` was **removed** (archived upstream Feb 2022; its javalite message classes crashed under the full `protobuf-java` runtime required by libp2p).
- Chat E2EE is a **custom, NIP-44-*inspired*** scheme via Bouncy Castle — **not** wire-compatible with NIP-44/59: shared secret = X25519 ECDH (local key from BIP-39 mnemonic `m/44'/999'/0'/0/0`, peer key from pre-key bundle handshake), key = HKDF-SHA256, ciphertext = 12-byte nonce ‖ ChaCha20-Poly1305 ct ‖ tag.
- Peer public keys persist in SQLCipher `conversation_keys` (Room `ConversationKeyEntity`), so sessions survive restarts.
- `AppDatabase` is at **version 21**; the 7→8 migration dropped the old Signal store tables (`signal_pre_keys`, `signal_sessions`, `signal_signed_pre_keys`, `signal_identity_keys`); 8→12 added the escrow/dispute tables, `funded_at`, and `network_fee_sats`; 12→13 added `matched_peer_id` to `trade_offers` (chat routing for matched trades); 14→15 added `payment_details` to `trade_offers` (per-method bank account + holder, exchanged via E2EE chat after a taker commits); 15→16 added `paid_at` + `required_confirmations` to `escrows` (buyer payment window + configurable confirmations); 16→17 added `funding_script_type` to `escrows` (user-selectable P2SH/P2WSH funding address); 17→18 added `receipt_sent_at` + `receipt_reference` to `escrows` (guided-flow payment receipt) and normalized legacy `PAID` rows → `CONFIRMING`; 18→19 added `funding_vout` + `buyer_btc_address` to `escrows` and `btc_receive_address` to `trade_offers` (2-party escrow sync); 20→21 added `expires_at` to `trade_offers` (offer TTL: 6h/12h/24h/48h, claim-gated past expiry).
- The `SignalProtocol` public API (initialize/encrypt/decrypt/handleIncomingMessage/pre-key bundle) is preserved so callers and wire types are unchanged.
- **Known gaps (accepted):** not NIP-44/59-compatible (interop only between NEO-P2P peers); no forward secrecy (static-static ECDH); TOFU key trust — mitigated 2026-08-28 with an 8-word BIP-39 peer fingerprint in the chat top bar + escrow header (copyable, compare out-of-band); both peers must be online to exchange keys.
- **NIP-59 / rust-nostr deferred** — see `docs/SECURITY_POSTURE.md`.

## Escrow & Offer Flow (on-chain 2-of-3, 0.3% seller-only)

- **Escrow** is a **real on-chain 2-of-3 P2SH multisig**, funded by the seller and verified on-chain via Mempool (`ChainMonitor`). `EscrowStatus` includes `CANCELLED`.
- **Fee model (0.3%, seller-only):** seller deposits `cryptoAmountSats + 0.3% fee + network fee`; buyer pays nothing and receives the full crypto amount; 0.3% goes to the fee wallet. `TradeOffer.buyerFeeSats = 0`, `sellerFeeSats = feeSats`, `totalDepositSats = cryptoAmountSats + feeSats`.
- **Network (miner) fee** is dynamic (`rate × ~220 vbytes`, `ChainMonitor.estimateFees()`) and persisted as `network_fee_sats` on the escrow.
- **Guided flow (2026-08-26):** escrow statuses are `FUNDING → FUNDED → [SIGNED] → PAYMENT_PENDING → RECEIPT_SENT → CONFIRMING → RELEASED` (+ `DISPUTED`/`RESOLVING`/`CANCELLED`/`REFUNDED`). The buyer marks fiat sent (`markPaid` → `PAYMENT_PENDING`, `paid_at`) and sends a structured **E2EE payment receipt** (`sendReceipt` → `RECEIPT_SENT`: reference code + optional screenshot via the `payment_receipt` chat payload). The **seller confirming "IDR received" (`confirmReceipt`) is the ONLY release gate**; role checks are **peerId-based** (in the single-key model both role pubkeys are the same key, so pubkey comparison cannot distinguish roles — `EscrowRole` lives in `domain/model/Escrow.kt`). **SIGNED is a forward state (2026-08-28):** `generatePayoutTransaction` persists SIGNED transiently before CONFIRMING; `EscrowRouter.applyRemoteStatus` accepts SIGNED in the forward order, `expireStaleEscrows` auto-refunds stalled SIGNED like FUNDED, `getEscrow` resume-heal re-publishes it, and `confirmReceipt` retries from it — a kill in the SIGNED→CONFIRMING window can no longer strand funds. **Seller reject path (2026-08-28):** "Tolak Bukti" (RECEIPT_SENT, seller-only) sends a structured `payment_receipt_reject` E2EE chat payload (4 reason codes + optional note) — advisory only, never changes status; the buyer sees a reject card with a funds-locked line. **Resume-heal (2026-08-28):** `getEscrow` re-publishes kind:33337 on load for FUNDING-with-txid, FUNDED, SIGNED, PAYMENT_PENDING, RECEIPT_SENT, CONFIRMING — a kill between DB persist and relay write heals on next open (router is forward-only + no-downgrade).
- **Softened timeouts:** 45 min (`ESCROW_FUNDING_TIMEOUT_MS`, warning at `FUNDING_WARNING_MS` 30 min) → unfunded escrows auto-`CANCELLED`; funded-but-stalled escrows auto-`REFUNDED` to the seller only after 12 h (`ESCROW_FUNDED_REFUND_TIMEOUT_MS`) **+ 48 h grace** (`FUNDED_REFUND_GRACE_MS`); payment window is 24 h (`PAYMENT_WINDOW_MS`) **+ 12 h grace** (`PAYMENT_GRACE_MS`) → auto-`DISPUTED`, never silently auto-refunded. Grace reminders emit once per escrow (in-memory dedup, `emitOnce`).
- **Receipt UX:** role-adaptive step tracker (Fund→Pay→Confirm→Release) on `EscrowScreen`; new `ReceiptComposerScreen` at route `escrow/{escrowId}/receipt` (reference code, prefilled amount/method, image attach ≤1600px/≤60KB → base64); on dispute, the receipt reference pre-fills the evidence description (`DisputeEvidenceViewModel`).
- **Configurable confirmations:** `onEscrowFunded` requires the funding tx to have ≥ `required_confirmations` (default 1) before accepting the deposit.
- **Auto-cancel safety:** `expireStaleEscrows()` checks the P2SH funding address on-chain (`hasOnChainDeposit`) before auto-cancelling a stale `FUNDING` escrow. If a deposit exists (broadcast succeeded but verification failed / tx slow to confirm), it promotes to `FUNDED` instead of cancelling — a funded escrow is never orphaned.
- **One-tap auto-fund:** `EscrowViewModel.fundFromWallet()` (via `EscrowScreen` "Send from my wallet to escrow" button + confirm dialog) sends the exact `depositAmountSats` from the seller's BIP-44 wallet (`WalletService.send`), auto-fills the txid, verifies on-chain, and moves the escrow to `FUNDED`.
- **`ChainMonitor.broadcastTx`** accepts the plain-text txid Mempool returns for `POST /api/tx` (Mempool/Esplora do not return JSON there).
- **Offer lifecycle:** create-offer is **sell-only** (BUY tab removed; buyers shop the list). A seller's own offer shows Edit + Delete (never Accept); Edit reuses `CreateOfferScreen` pre-filled via the `edit_offer/{offerId}` route. Accepting locks the offer (`MATCHED`/`ESCROWED`) via a custom Nostr `kind:33336` status event; deletion syncs via NIP-09 (`kind:5`). **Pause (2026-08-28):** `PAUSED` is a claim-gated `OfferStatus` on kind:33336 — only the creator may pause/reactivate an OPEN offer; a live match can never be paused; paused offers leave the public feed (creator still sees own). Delete is gated to OPEN/PAUSED (locked offers show why). **Saved payment methods (2026-08-28):** `SavedPaymentMethodsStore` (SharedPreferences JSON) persists bank/QRIS/e-wallet details; Settings → "Metode Pembayaran Saya" manages them; Create Offer prefills from saved methods (blank fields only).
- **Payment-detail sharing (escrow-first):** the seller enters bank number + holder name per fiat method in the create-offer form; they are persisted to `trade_offers.payment_details` (never published to the Nostr relay, P0-1). Chat is **locked until the escrow is `FUNDED`**; then the seller can tap **"Share payment details"** (`ChatViewModel.sharePaymentDetails`) to send them as a structured E2EE JSON card to the buyer, who renders them in `ChatMessageItem`/`PaymentDetailsCard`.

## 16 KB Page-Size Alignment

- SQLCipher is `net.zetetic:sqlcipher-android:4.17.0` (16 KB-aligned `.so`), **not** the old `android-database-sqlcipher` (frozen at 4.5.4, 4 KB-aligned).
- `AppDatabase.kt` uses `net.zetetic.database.sqlcipher.SupportOpenHelperFactory` and calls `System.loadLibrary("sqlcipher")` before opening the DB.
- All native libs in the APK are 16 KB-aligned; the app runs 16 KB-native (no `pageSizeCompat`).

## Verification

- Build: `./gradlew :app:assembleDebug` from `android/` directory
- Tests: `./gradlew :app:testDebugUnitTest`
- Lint: `./gradlew :app:lintDebug` (uses `lint-baseline.xml` for legacy issues)

## Child DOX Index

*No children — leaf module.*
