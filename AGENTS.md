# AGENTS.md

You are working in NEO-P2P — zero-backend, peer-to-peer anonymous crypto trading app for Indonesia; Android-only Kotlin (Compose + Hilt + Room). The root Gradle project is intentionally empty — all real config lives under `android/`.

## Golden rules (apply to every task)

1. **Build/test from `android/` only** (`workdir: android`) — the root is NOT a Gradle project.
2. **Never edit `legacy/`** — dead KMM code (`iosMain`, `commonMain`, `androidMain`, `iosApp`), not in any build, reference only.
3. **Never add root Gradle plugins** — only `include(":android")` already; root plugins cause version conflicts.
4. **Money is integer-only (G.M.01, 2026-08-29):** fee = `(sats * FEE_NUM) / FEE_DEN` (`FEE_NUM=5`, `FEE_DEN=1000` in `NeoP2PConfig`); offer fiat = `(btcSats * priceIdr) / 100_000_000`. No `Double` round-trip on money anywhere — `TradeOffer.feeSats`, the Room default, and `CreateOfferScreen` create/edit share the exact formula. `parseIdrToLong` rejects non-whole prices so a decimal can't become a 10x integer.
5. **No secrets in `local.properties`** (Phase 4 removed TURN + WS relay). BuildConfig only sets `NETWORK="testnet"`; the transport node comes from the hardcoded `NeoP2PConfig.RNS_TRANSPORT_NODE_HOST/PORT` consts. Never reintroduce relay/TURN credential wiring.

## Repo structure (only these dirs are live)

- `android/` — the app. `settings.gradle.kts` includes `:app`. All build/test commands run from here.
- `infrastructure/` — RNS transport node (official Python rnsd, replaced the rnsd-kt fork 2026-09-01) + LXMF propagation node (lxmd) Docker stack, Oracle Cloud ARM64. See `infrastructure/AGENTS.md`.
- `design-system/` — design tokens JSON. See `design-system/AGENTS.md`.
- `legacy/` — dead KMM code, NOT wired into any build. Do not edit.

## Commands

```bash
# from inside android/
./gradlew :app:assembleDebug     # build
./gradlew :app:testDebugUnitTest # unit tests (plain JUnit 4, no Robolectric)
./gradlew :app:lintDebug         # lint (baseline: app/lint-baseline.xml)
```

Toolchain: Gradle 9.5.0, AGP 9.3.0 (built-in Kotlin), Kotlin 2.3.0, JDK 21, minSdk 26 / targetSdk 36.

**Dual-network debug builds (user convention, 2026-09-13):** when asked to "build", always produce BOTH a mainnet and a testnet debug APK. `NETWORK` is hardcoded at `app/build.gradle.kts:75` — build mainnet, then temporarily set it to `testnet`, rebuild, and `git checkout -- app/build.gradle.kts` to restore. Verify each APK with `apkanalyzer dex code --class com.neop2p.BuildConfig <apk> | grep NETWORK`. Copy outputs to `app/build/outputs/apk/debug/neop2p-mainnet-debug.apk` and `neop2p-testnet-debug.apk` (both debug variants share applicationId `com.neop2p.app.debug`). **Commit/push network invariant:** the committed value must be `mainnet` on every branch EXCEPT `main`, where it must be `testnet`. Keep the hardcoded single-variant approach — do NOT introduce product flavors.

## Read before touching identity / crypto / escrow / reputation

- `CRITICAL.md` — hard-earned engineering history (a single Ed25519 key was once the identity for every protocol; now BIP-39/BIP-32 seed derivation with secp256k1-kmp + Bouncy Castle).
- `IDENTITY_REWRITE.md` — key-derivation blueprint. Identity comes from a BIP-39 mnemonic, not a single KeyStore key.

Escrow is a **real on-chain 2-of-3 P2SH multisig** (`data/escrow/EscrowService.kt`, `ChainMonitor.kt`), funded by the seller, verified on-chain via Mempool (falls back to Blockstream.info — identical JSON API). Fee model **0.5%, seller-only**: seller deposits `crypto + 0.5% fee + network fee` (the payout tx needs a miner fee — estimated from `ChainMonitor.estimateFees()` and stored as `network_fee_sats`); buyer pays nothing and receives the full crypto; 0.5% goes to the fee wallet. **Payout-destination gate (2026-09-07):** a payout must never send the buyer's sats to the fee wallet or back into the escrow's own multisig — `PayoutAddressGate.isForbidden` rejects both at accept AND at build; `resolveBuyerPayoutAddress` resolves escrow row → offer row and NEVER falls back to the multisig funding address. **Room DB at version 28** (migration map below). Shared Ktor `HttpClient`: 10s connect / 20s request timeouts.

## Intent map — read the section before editing that area

| Touching | Read |
|---|---|
| Escrow / status machine | Escrow + funding |
| Chat / E2EE | Chat, E2EE |
| Arbitration / disputes | Arbitration |
| Offer feed / announces | Offers, RNS/LXMF |
| Wallet | Wallet |
| Reputation | Reputation |
| Room schema | Migration map (E2EE section) |
| Transport / signaling | RNS/LXMF |
| Build / toolchain | Build/runtime gotchas |

## Escrow (status machine)

Forward-only, no-downgrade: `FUNDING → FUNDED → [SIGNED] → PAYMENT_PENDING → RECEIPT_SENT → CONFIRMING → RELEASED` (+ DISPUTED / RESOLVING / CANCELLED / REFUNDED). Status flow guided 2026-08-26; role-adaptive step tracker (Fund→Pay→Confirm→Release) + ReceiptComposerScreen (`escrow/{escrowId}/receipt`).

- Buyer: `markPaid` → `PAYMENT_PENDING`; `sendReceipt` (reference + optional screenshot) → `RECEIPT_SENT`.
- **Seller `confirmReceipt` ("IDR received") is the ONLY release gate** — peerId role-gated (single-key model: pubkeys can't distinguish roles).
- **SIGNED is a forward state (2026-08-28):** `generatePayoutTransaction` persists SIGNED transiently before CONFIRMING; the router accepts SIGNED in the forward order; the sweep escalates a stalled SIGNED to a dispute like FUNDED (F-1, 2026-09-13); `getEscrow` resume-heal re-publishes it; `confirmReceipt` retries from it — a kill in the SIGNED→CONFIRMING window can't strand funds.
- Seller reject: "Tolak Bukti" sends a structured `payment_receipt_reject` E2EE chat payload (4 reason codes + note) — advisory only, never changes status.
- Resume-heal: `getEscrow` re-publishes escrow_status over LXMF on load (FUNDING-with-txid, FUNDED, SIGNED, PAYMENT_PENDING, RECEIPT_SENT, CONFIRMING) — a kill between DB persist and LXMF send heals on next open.
- Timeouts (mainnet release, 2026-09-09; refund escalation 2026-09-13): 15 min → auto-cancel unfunded escrows (warning at 10); funded-but-stalled (FUNDED/SIGNED) → auto-`DISPUTED` only after 2 h (`ESCROW_FUNDED_STALL_TIMEOUT_MS`) **+ 2 h grace** (`FUNDED_STALL_GRACE_MS`) — a refund now needs the arbitrator's co-signature (F-1), never a unilateral seller refund; payment window 1 h + 1 h grace → auto-`DISPUTED`, never silently refunded. Grace reminders fire once (in-memory dedup). Funding verification enforces `required_confirmations` (default 1).
- **Money-path audit fixes (2026-09-13, F-1/F-2/F-3):**
  - **F-3 pre-broadcast payout gate:** `ReleaseIntegrity.verdict` is the ONLY check run immediately before `chainMonitor.broadcastTx` — the stored `psbt_unsigned` is untrusted, so every output must pay the attested buyer address (anchored in the already-funded redeem script), the fee wallet, or the seller's refund address, with the buyer receiving ≥ the trade amount; on refusal the honest payout is rebuilt once (`releaseGateRecovery`), never looped. The router no longer adopts a remote `psbt` at all on the creator's row and only adopts one on a mirror after the same verdict passes (`shouldAdoptRemotePsbt`).
  - **F-2 authenticated escrow_status:** `P2POrchestrator` requires a verified sender binding (`rnsTransport.isVerifiedSender`) and `EscrowRouter.senderIsCounterparty` requires the sender to BE a party of the local row (never the attacker-controlled body claims); the creator's authoritative row refuses a remote `CANCELLED` (`applyRemoteStatus(localStatus, remoteStatus, localIsCreator)`) so no peer can terminate it.
  - **F-1 arbitrator co-signed refunds:** the single-key both-slots refund was unsatisfiable after C1 — `refundInternal` is deleted. "Cancel & Refund" is now "Request refund" (`refundRequestKind`): a never-funded escrow cancels locally (`cancelUnfundedEscrow`), anything funded opens a dispute (`escalateToDispute`) the arbitrator co-signs via the existing resolution flow. There is NO unilateral seller on-chain refund.

### Funding

- One-tap: **"Send from my wallet to escrow"** button (irreversible-broadcast confirm dialog) calls `WalletService.send()` with the exact `depositAmountSats`, auto-fills the txid, verifies on-chain → `FUNDED`.
- Before auto-cancelling a stale `FUNDING` escrow, `expireStaleEscrows()` checks the P2SH address on-chain (`hasOnChainDeposit`) and promotes to `FUNDED` instead — a funded-but-unverified escrow is never orphaned.
- Overpayment (2026-09-04): funding accepts a deposit ≥ `depositAmountSats` (`findFundingOutputAtLeast`); the ACTUAL on-chain value is recorded as `escrows.funded_amount_sats` (Room v24); the excess returns to the SELLER via payout and refund paths — never kept as fee (0.5% seller-only fee is the documented contract). SegWit BIP-143 signing commits the real input value.
- Underpayment (2026-09-04): partial deposit persisted (`findFundingOutputAny`/`fundedValueAny`) so the seller can request a refund; the sweep never auto-cancels or promotes a partial deposit. **Top-up NOT supported** — a second deposit creates a second output the payout/refund cannot spend; the seller requests a refund, then creates a fresh escrow.
- **Never-funded cancel (2026-09-07):** a FUNDING escrow with no bound txid and no recorded deposit cancels LOCALLY (no on-chain move) — `cancelEscrowRefund` runs `recoverFundingTxId` first (a manual deposit without an entered txid must never be orphaned), then marks CANCELLED + syncs offer/escrow over LXMF, mirroring the sweep's auto-cancel branch. The old path always built a refund tx and threw "No funding transaction recorded".
- Network fees (2026-09-06): `fundingNetworkFeeSats`/`refundNetworkFeeSats` use the FULL tx vsize + a 250-sat floor (`MIN_NETWORK_FEE_SATS`), shared by create/switch-funding-type and build-refund/estimate so the displayed amount equals the broadcast amount (the funding-type toggle once used input-only vsize and produced an un-relayable payout fee).
- `ChainMonitor.broadcastTx` accepts Mempool's plain-text txid response for `POST /api/tx` (not just JSON).

## Offers

- Create-offer is **sell-only**; a seller's own offer shows Edit + Delete (never Accept).
- Accepting another's offer locks it (`MATCHED`/`ESCROWED`) via an LXMF `offer_status` message carrying `matched_peer_id` (the acceptor's peerId) so the creator's chat button routes to the buyer. Deleting an offer is local + tombstoned.
- Raw offer re-announcements never downgrade a locked status or wipe `matched_peer_id`.
- Pause (2026-08-28): `PAUSED` is claim-gated — only the creator may pause/reactivate an OPEN offer; a live match can never be paused; paused offers leave the public feed (creator still sees own). Delete gated to OPEN/PAUSED (locked offers show why).
- **Locked-offer access gate (2026-09-06):** only the seller, the matched buyer, or the arbitrator may open a locked offer's details (`OfferDetailAccessGate`); a third-party observer gets no notification and no detail view.
- **Match lifecycle (2026-09-05/06/07):** `locked_at` (Room v25) stamps when an offer becomes MATCHED; the orchestrator sweep auto-cancels a MATCHED offer whose escrow is never created after `MATCHED_ESCROW_TIMEOUT_MS` (1h), creator-gated, synced to the former matched peer over LXMF. An OPEN effective status clears the match (`OfferClaimGate.clearsMatch` → `matched_peer_id` + `locked_at` nulled) so a declined/re-activated offer's former taker can re-accept. Editing MATCHED/ESCROWED offers is blocked (terms are a live agreement).
- **Observer tombstone deletion (2026-09-06):** a terminal tombstone DELETES the offer row on a 3rd device (neither creator nor matched peer) so a finished trade disappears from the feed entirely; party rows stay (marked COMPLETED) — the buyer's escrow detail reads fiat + bank details from the offer row. `OfferFeedGate.tombstoneDeletesRow`.
- **Lost-claim re-publish (2026-09-07):** `republishLostClaims` targets the offer CREATOR (never the matched peer — that re-sends to ourselves) and carries the buyer payout address via `lostClaimBuyerAddress` (blank and fee-wallet destinations dropped).

## Chat

- Orchestrator is started from `HomeViewModel` (fixed 2026-08-24) and hosted in the `P2PBackgroundService` foreground service (started from `HomeScreen`; `specialUse` FGS type, START_STICKY, transport-down/identity-locked notifications).
- Two-shot pre-key handshake over LXMF DIRECT (request → bundle → reply bundle only if no session) establishes sessions both ways.
- `decryptWithKey` MUST return `len + written` from BC `processBytes`/`doFinal` — the old `copyOf(written)` chopped every payload >64 bytes at the block boundary (fixed 2026-08-27).
- Room history loads + decrypts in `ChatViewModel`; queue drain works once the peer has announced (identity binding via the LXMF announce is the actual trust anchor).
- **Chat is escrow-first:** input locked until the escrow is `FUNDED`; then the seller can tap **"Share payment details"** to send bank number + holder name as a structured E2EE card (stored in `trade_offers.payment_details` — never published to the RNS feed, P0-1).

## Arbitration (Option 1, 2026-08-25)

- Disputes travel as LXMF DIRECT messages: `dispute` + unsigned payout tx (+ `refund_tx_hex`), `evidence`, `resolution` (+ `signed_tx_hex`). Titles = type; FIELD_CUSTOM_DATA = JSON; evidence images as file attachments.
- Arbitrator key: admin's mnemonic at `m/44'/999'/0'/1/0`; Arbitrator Mode (Settings → Dispute Feed) unlocks when the identity matches `ARBITRATOR_PUBKEY`. `NeoP2PConfig.ARBITRATOR_PEER_ID` (blank = RNS arbitration delivery disabled) lets parties deliver disputes/evidence to the arbitrator over LXMF.
- **Ack-gated (2026-08-31):** `dispute`/`evidence` use publish-then-commit (`EscrowScreen.disputeEscrow`); pending retry via `PendingDisputeStore` + `P2POrchestrator.sweepStaleEscrows` every 60s; auto-disputes (payment window + grace expiry) deliver to `ARBITRATOR_PEER_ID` with per-target durable retry (`PendingDisputeStore.targets`).
- `DisputeFeed` persists to SQLCipher `arbitrator_disputes` (Room v22, survives reboot/prune) + merges DB+LXMF, per-card busy, sorted newest-first (the Phase 4 relay-health banner was removed — no relay anymore).
- **Signature gate:** `applyResolutionEvent` verifies the arbitrator's signature BEFORE marking the feed resolved — a forged resolution can no longer hide a dispute or drop the legitimate pending resolution.
- **Sender-authenticated ingest:** dispute/evidence opener/submitter must be the sender; resolution sender must be `ARBITRATOR_PEER_ID`.
- Parties apply resolutions idempotently and broadcast 2-of-3. See `docs/ARBITRATION.md`. Split admin APK deferred (Phase C).
- Room v23 added `buyer_peer_id`/`seller_peer_id` on `arbitrator_disputes` so the arbitrator — who has NO local escrow row — can deliver the `resolution` to the parties (pre-v23 the resolution was sent to nobody and funds stayed locked in the multisig). `escrow_status` sync carries `payout_tx_id` + `redeem_script_hex` so the buyer's mirrored row can show the payout tx and apply an arbitration resolution.
- **Buyer escape hatch (2026-09-02):** the buyer can dispute from FUNDED/PAYMENT_PENDING/RECEIPT_SENT instead of waiting on a stuck seller (a stuck seller must never leave the buyer with no exit).
- **FUNDING is not disputable (2026-09-05):** a dispute may only be opened once the escrow is FUNDED — FUNDING is either not yet broadcast (nothing to arbitrate; the 15-min window auto-cancels) or in flight (unconfirmed; the arbitrator's payout/refund would spend a nonexistent output). Pure `EscrowService.canDisputeFromStatus` (mirrored by `P2POrchestrator`).
- **Dedup:** dispute/evidence/resolution re-deliveries (LXMF router retry + 60s sweep re-send) are processed + notified only on first delivery (`shouldProcessDispute`, `DisputeRedeliveryGateTest`).
- **Resolution destination gates (F2, 2026-09-12):** role-signed `neop2p-attest-v1` ECDSA attestations — `SELLER_REFUND` signed by the seller escrow key at `createEscrow` (scope=escrowId), `BUYER_PAYOUT` signed by the buyer escrow key at accept (scope=offerId); they travel in `escrow_status`, `offer_status`, and the dispute payload. `ResolutionGuard` verifies tx destinations at BOTH ends: the arbitrator before signing (`DisputeFeedScreen`), every party before broadcasting (`EscrowService`). Legacy/pre-v27 disputes with no attestations REFUSE to sign/apply (fail closed). New dispute wire fields: `offer_id`, `buyer_btc_address`, `buyer_pubkey_hex`, `seller_pubkey_hex`, `trade_sats`, `seller_refund_attestation`, `buyer_address_attestation`; `confirmRefundDestination` refuses to overwrite a non-blank local refund destination.
- **Peer identity binding (F1, 2026-09-12):** new `neop2p.identity` announce, appData signed by the libp2p Ed25519 key over `neop2p-binding-v1|<peerId>|<rnsIdentityHash>|<identityDestHash>`; `PeerBindingRegistry` binds peerId ↔ RNS identity hash (NOT the delivery dest). Dispute/evidence/resolution are dropped unless the sender's delivery dest maps to the peerId AND its identity hash matches the verified binding; send-side applies the verified destination and fails closed for an unverified arbitrator. An unverified announce can never rebind a verified peerId.
- **Escrow script attestation (F3, 2026-09-12):** `EscrowScriptGate` verifies a received redeem script is 2-of-3, contains the official arbitrator key, and hashes to the advertised funding address; `markPaid` blocks on failure and a warning banner shows on the escrow screen.
- **Inbound hardening (F4, 2026-09-12):** inbound rate limiter keyed by sender destination (not the claimed peerId), idle-bucket eviction in the sweep, evidence persisted only for a known escrow/dispute, and a per-sender cap on new disputes (`DisputeIngestGate.MAX_UNRESOLVED_PER_SENDER = 25`).
- **Fail-closed upgrade (2026-09-12):** pre-v27 escrows carry no F2/F3 attestations and can no longer be arbitrated — both parties must update to the same build before trading.

## Wallet

`data/wallet/WalletService.kt` + `ui/screens/wallet/WalletScreen.kt` — personal BIP-44 wallet (receive QR via zxing, balance/history via ChainMonitor, raw-tx send with UTXO selection; send-confirm shows estimated fee + total since 2026-08-28). **2026-09-06:** `estimateSendFee` runs the SAME greedy selection as `send` (real UTXOs) via the pure `selectSpend` helper — a multi-input spend shows the true fee, not a 1-input guess; exact BigDecimal sats parsing (no Double round-trip); localized input validation with explicit `ERR_` codes. FAB left of Create Offer.

## Reputation (2026-09-04)

- Post-trade attestations travel over LXMF DIRECT (`attestation` signaling type, `RnsSession.sendAttestation`, re-queued via `RESENDABLE_TYPES`).
- `AttestationCodec` (pure-JVM) defines the wire payload `{from_peer, target_peer, outcome, volume_sats, timestamp, pubkey, signature}` with BIP-340 Schnorr signing.
- Ingest is sender-authenticated (`processAttestation(json, senderPeerId)` — the LXMF sender must BE the signer); self-ratings rejected; non-blank pubkey pinned (TOFU); persists to SQLCipher `attestations` (IGNORE-deduped PK `from:target:ts`). Never sent to self (single-key demo).

## RNS/LXMF (only transport since Phase 4, 2026-08-31)

libp2p, the WS relay, Nostr, and WebRTC were removed. RNS/LXMF is the **ONLY** transport.

- Core: `RnsSession` (pure-JVM) + `RnsTransport` (Android wrapper) connect as TCP clients to the VPS transport node (`NeoP2PConfig.RNS_TRANSPORT_NODE_HOST/PORT`; default `relay1.custom-minipc.com:42420` in `NeoP2PConfig.kt`). **Port map: host-public 42420 → container-internal 42000** (compose publish); the container's rnsd binds 42000 and the Dockerfile HEALTHCHECK probes 127.0.0.1:42000. The propagation node listens on container 42000, reachable from the transport node via docker DNS (`[[Propagation Link]]` — REQUIRED, do not comment out); its loopback-only host publish `127.0.0.1:42001` exists solely for the healthchecker probe. Python `lxmd` propagation node provides store-and-forward for offline peers (the Kotlin lxmf-core fork is client-only for propagation). Peer addressing: the libp2p peerId rides as the LXMF announce displayName; inbound LXMF maps back to the sender's peerId.
- **No IFAC (2026-09-09, d519225):** the transport + propagation nodes and the app connect as OPEN TCP peers — no Interface Access Code anywhere. Anyone with the APK can extract embedded secrets, and IFAC dropped every packet on any mismatch, so the shared-secret gate was removed outright (it is NOT per-peer auth anyway). Security posture now rests on signed announces + E2EE traffic only.
- Transport node (2026-09-01): the VPS node runs **official Python rnsd** (the two rnsd-kt fork fixes from 2026-08-31 — spawned-client registration + receiving-interface wiring — are native Python behavior).
- **Tier 1 LAN (2026-09-01):** phones register an RNS `AutoInterface` (IPv6 link-local multicast + per-peer UDP unicast) alongside the VPS TCP transport — two devices on one Wi-Fi exchange announces/paths/DIRECT LXMF links with no transport node in the path; `RnsTransport` holds a `WifiManager.MulticastLock` for the session lifetime (stock Android filters multicast without it).
- **Tier 3 multi-node (2026-09-01):** users can add extra RNS transport nodes in Settings (`TransportNodeStore`, SharedPreferences JSON, live-apply without a network restart). Every node is a packet ferry, not a trust anchor — traffic stays end-to-end encrypted and announces are signed, so more nodes = more reach, never less security.
- **Transport health (2026-08-31):** `TCPClientInterface` is created with `keepAlive = true`; `RnsSession` re-announces every 20s — the first announce races the TCP connect (broadcasts on 0 interfaces) and the next one heals it; idle links are kept alive by the re-announce traffic.
- **Transport self-heal (2026-08-31):** `P2POrchestrator.sweepStaleEscrows` retries `rnsTransport.start()` every 60s while it is not running — an identity-lock failure at launch (locked screen, `Identity locked behind device auth`) no longer leaves the phone dead after the user unlocks.
- **Offer feed (2026-09-01):** the `neop2p/offers` announce carries a compact `RnsOfferDigest` (~200B — RNS announce appData is capped at ~300B: MTU 500 − header 19 − announce overhead 180); the full offer JSON is fetched on demand over LXMF (`offer_request` → `offer` → `OfferRouter.ingestRnsOffer`). Digest identity is cross-checked against the peer's `lxmf.delivery` announce.
- **Network-scoped feed (2026-09-12):** offers are chain-specific. MAINNET keeps the legacy `neop2p.offers` aspect; TESTNET uses `neop2p.offers.testnet` (`RnsOfferDigest.offerAspects(network)`), so the two chains never exchange offer announces on the transport node, LAN AutoInterface, or user-added nodes. Second layer: `canonicalJson` carries `network`, and `OfferRouter.isValidOfferPayload(json, localNetwork)` drops a mismatch at ingest (missing field = legacy testnet).
- **Paced re-announce loop:** 30s tick, one digest per tick, round-robin (`P2POrchestrator.rehydrateOfferReannounce` feeds it from the offer table; 60s when backgrounded). The one-shot `publishOffer` at create/edit was removed. Pacing is mandatory: the fork drops same-second re-announces to one destination and rate-limits to `MAX_RATE_TIMESTAMPS=16`/30s per dest. 2026-09-10: delivery announce slowed 20s→60s (pure `AnnouncePacing` helper; announce is a discovery accelerator, not the delivery mechanism — the router retry loop + propagation fallback deliver).
- **Locked/terminal convergence (2026-09-02):** locked offers (MATCHED/ESCROWED) stay on the paced loop — the digest embeds status, so a status change alters the commitment hash and receivers re-fetch + re-ingest (no-downgrade gates apply); terminal offers (COMPLETED/CANCELLED) re-announce a digest-only tombstone once per feed cycle + on pull-to-refresh so a 3rd device converges on 'taken' instead of a stale OPEN row. **Observer deletion (2026-09-06):** a terminal tombstone DELETES the row on a 3rd device (neither creator nor matched peer) so a finished trade disappears from the feed entirely.
- **Deferral (2026-09-01):** never call `Identity.remember` — the fork remembers every valid announce; a redundant call clobbered the packetHash. Offer digests arriving before their peer's `lxmf.delivery` announce are deferred in a bounded buffer (≤32/identity, ≤64 identities) then flushed on the delivery announce.
- **Signaling:** `P2POrchestrator` routes inbound LXMF signaling to the same handlers the Nostr collectors used (`applyDisputeEvent`/`applyEvidenceEvent`/`applyResolutionEvent`); `EscrowService.publishEscrowSync` + `publishOfferStatusDual` deliver to the counterparty over LXMF; `OfferRouter.applyOfferStatus` is shared by the LXMF path. Attestations travel over LXMF DIRECT since 2026-09-04. **Resend queue (2026-09-07):** send-time signaling failures (no RNS path / unknown identity yet) queue in `RnsSession.pendingResends` for the peer's next announce — shared with the LXMF failed-delivery callback via the pure `ResendQueue.kt` policy (chat/pre-key stay on the durable OfflineQueue and would double-send).
- `KeyDerivation.deriveLibp2pPeerIdFromKey` is implemented locally (base58btc of the identity multihash of the protobuf Ed25519 pubkey) so peerIds stay stable without jvm-libp2p.

## E2EE (P0-2)

Custom **NIP-44-*inspired*** scheme: X25519 ECDH + HKDF-SHA256 + ChaCha20-Poly1305, 12-byte nonce, via Bouncy Castle, keyed from the BIP-39 mnemonic (`m/44'/999'/0'/0/0`). **NOT NIP-44/59 wire-compatible** — interop only between NEO-P2P peers. Peer keys persist in SQLCipher `conversation_keys`. NIP-59/rust-nostr is deferred. See `docs/SECURITY_POSTURE.md`.

**Room migration map (current: 28):** 7→8 dropped the old Signal store tables; 8→12 escrow/dispute tables + `funded_at` + `network_fee_sats`; 12→13 `matched_peer_id` on trade_offers; 13→14 `attestations` table (reputation); 14→15 `payment_details` on trade_offers; 15→16 `paid_at` + `required_confirmations` on escrows; 16→17 `funding_script_type` (P2SH/P2WSH); 17→18 `receipt_sent_at` + `receipt_reference`, legacy PAID → CONFIRMING; 18→19 `funding_vout` + `buyer_btc_address` on escrows + `btc_receive_address` on trade_offers; 19→20 `refund_destination` + `seller_refund_address` on escrows; 20→21 `expires_at` on trade_offers (offer TTL); 21→22 `arbitrator_disputes` (dispute-feed durability); 22→23 `buyer_peer_id`/`seller_peer_id` on `arbitrator_disputes`; 23→24 `funded_amount_sats` on escrows — the ACTUAL on-chain funding value, higher than the deposit when the seller overpays, spent by the payout/refund so the excess returns to the seller; 24→25 `locked_at` on trade_offers — the MATCHED timestamp that drives the 1h stale-lock auto-cancel; 25→26 `creator_pubkey_hex`/`buyer_pubkey_hex` on trade_offers — the real 2-of-3 buyer key (distinct from the seller, C1); 26→27 F2/F3 columns — `seller_refund_attestation`/`buyer_address_attestation` on escrows, `buyer_address_attestation` on trade_offers, plus `buyer_btc_address`/`buyer_pubkey_hex`/`seller_pubkey_hex`/`seller_refund_attestation`/`buyer_address_attestation`/`offer_id`/`trade_sats` on `arbitrator_disputes`; 27→28 `disputed_at` on escrows — when a dispute opened, for the age indicator.

**TOFU fingerprint (2026-08-28):** an 8-word BIP-39 fingerprint of the counterparty identity renders in the chat top bar + escrow header (copyable, compare out-of-band) — the accepted TOFU trust anchor.

## Build/runtime gotchas

- **JDK 21 is pinned machine-wide** via `org.gradle.java.home=/home/thesdony/.sdkman/candidates/java/21.0.3-tem` in `~/.gradle/gradle.properties` (user-level, NOT committed). The system default `java` is JDK 25, which AGP rejects ("Build failed: 25.0.4") — the pin fixes this for every shell/IDE invocation without exporting `JAVA_HOME`. New machine: add the same key (or export `JAVA_HOME` to a JDK 21) or AGP will fail. Do NOT put `org.gradle.java.home` in `android/gradle.properties` (would be committed + machine-specific). JDK 21 is required because rns-core/lxmf-core are Java 21 bytecode (jvmTarget 21 in the forks) and the unit-test JVM runs on the daemon.
- **`AndroidLocationsException` guard:** `Could not create provider ... AndroidLocationsBuildService` = the IDE injected both `ANDROID_PREFS_ROOT` and `ANDROID_USER_HOME`, which AGP rejects. Run with `env -u ANDROID_PREFS_ROOT ./gradlew ...` or unset `ANDROID_USER_HOME`.
- **libp2p/protobuf note is historical:** libp2p and its full `protobuf-java` requirement were removed in Phase 4; `libsignal-protocol-java` was removed earlier (archived upstream Feb 2022; its javalite classes crashed under the full `protobuf-java` runtime that libp2p required). No protobuf-java/javalite pinning remains (see the `android/gradle/libs.versions.toml` comment).
- SQLCipher is `net.zetetic:sqlcipher-android:4.17.0` (16 KB-aligned `.so`), **not** the old `android-database-sqlcipher` (frozen at 4.5.4, 4 KB-aligned). `AppDatabase.kt` uses `net.zetetic.database.sqlcipher.SupportOpenHelperFactory` and calls `System.loadLibrary("sqlcipher")`. App is 16 KB-native.
- Package `com.neop2p`, namespace `com.neop2p`, applicationId `com.neop2p.app`.
- The lint `disable` list in `app/build.gradle.kts` is a deliberate AGP 9.3.0 + Kotlin 2.3.0 + Compose workaround — leave it.

## Verify after changes

| Changed | Required checks |
|---|---|
| Any Kotlin | `./gradlew :app:assembleDebug` |
| Escrow / offer / arbitration / E2EE logic | `./gradlew :app:testDebugUnitTest` |
| UI / Compose | `./gradlew :app:lintDebug` |

## CI

`.github/workflows/ci.yml` is the active pipeline (build + unit tests + lint + dependency scan). `android-ci.yml` and `ios-ci.yml` are stale/legacy. iOS is not in the active build.

## Sub-module instruction files

`android/AGENTS.md`, `infrastructure/AGENTS.md`, `design-system/AGENTS.md` each hold their module's ownership, contracts, and verification commands — read the relevant one before editing that area.
