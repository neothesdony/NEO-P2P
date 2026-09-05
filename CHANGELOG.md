# Changelog

All notable changes to NEO-P2P will be documented in this file.

## [1.0.27] — 2026-09-05

### Added

#### Reputation over LXMF (2026-09-04)
- **Attestations now travel over LXMF DIRECT** — the post-trade rating dialog (`EscrowScreen` RELEASED/REFUNDED) sends the signed attestation to the counterparty via a new `attestation` LXMF signaling type (`RnsSession.sendAttestation`, title = `attestation`, FIELD_CUSTOM_DATA = JSON). Previously the attestation was stored locally only (Nostr gossip was removed in Phase 4). Failed deliveries re-queue via `RESENDABLE_TYPES` and resend on the peer's next announce. Never sent to self (single-key demo: buyer and seller are the same peerId).
- **Pure `AttestationCodec`** (`data/reputation/AttestationCodec.kt`, no Android imports) — wire payload `{from_peer, target_peer, outcome, volume_sats, timestamp, pubkey, signature}` with BIP-340 Schnorr signing (x-only secp256k1 pubkey, 64-hex; 128-hex signature). The pubkey travels IN the payload because RNS-era peers store `nostr_pubkey=""`.
- **Sender-authenticated verified ingest** — `ReputationSystem.processAttestation(json, senderPeerId)` verifies the BIP-340 signature against the payload pubkey, enforces sender-authentication (the LXMF sender must BE the signer), rejects self-ratings, and pins a stored non-blank pubkey (TOFU — a payload carrying a different pubkey is rejected; a blank stored key is adoptable). Attestations persist to the SQLCipher `attestations` table (IGNORE-deduped by PK `from:target:ts`), so LXMF re-deliveries never double-count.
- **Honest 0-trade score + data-destroy wipe** — a peer with no trades shows an honest 0-trade score (no fabricated reputation); `destroyLocalData` wipes in-memory scores too.

#### Escrow over/underpayment (2026-09-04, Room v24)
- **Overpayment accepted** — funding verification accepts a deposit paying the escrow address AT LEAST `depositAmountSats` (`findFundingOutputAtLeast`/`fundedValueSats`); the ACTUAL on-chain value is recorded as `escrows.funded_amount_sats` (Room v24, `MIGRATION_23_24`) and the excess is returned to the SELLER by the payout and refund paths — never kept as fee. SegWit BIP-143 signing commits the real input value; the arbitrator signs the refund with the real value too.
- **Underpayment persisted** — a partial deposit is recorded (`findFundingOutputAny`/`fundedValueAny`) so the seller can Cancel & Refund it; the sweep never auto-cancels or promotes a partial deposit (top-up is NOT supported — a second deposit creates a second output the payout/refund cannot spend; the seller cancels & refunds, then creates a fresh escrow). `EscrowOverpaymentTest` + `EscrowUnderpaymentTest`.

#### Transport / home resilience (2026-09-04)
- **Transport-down banner with retry** — Home shows a `TransportDownBanner` (error container, WifiOff icon, Retry button) when the RNS transport is not running; `P2POrchestrator` exposes `transportReady: StateFlow<Boolean>` + `transportStartFailure` for UI surfacing. Pull-to-refresh re-landed on the IO dispatcher.
- **Transport-down notification** — `P2PBackgroundService` posts a transport-down notification on any non-lock start failure (dead node / unreachable network), distinct from the existing identity-locked notification; the 60s sweep keeps retrying in the background.
- **Orchestrator start off the main thread** — `HomeViewModel.startBackgroundSync` runs `orchestrator.start()` on `Dispatchers.IO` (RNS/LXMF init was slow enough to ANR the main looper, seen on Pixel 8).

#### Onboarding / invite (2026-09-04)
- **Seed-verify escape hatch** — the BACKUP_SEED/verify step now has a "start over" action (confirm-gated) that discards the current identity + seed and returns to Create Identity (`abortSeedVerification`).
- **Invites queued during onboarding** — a `neop2p://peer/<id>` deep link tapped mid-onboarding is queued and replayed once Home is reached (snapshot-backed nav-controller effect).

#### Offer / UI polish (2026-09-04/05)
- **Rp 5M minimum trade** — `NeoP2PConfig.MIN_OFFER_FIAT_IDR = 5_000_000` enforced on offer create AND ingest (`OfferRouter.isValidOfferPayload`); a hostile sub-5M offer is dropped, not persisted.
- **Peer blocking wired up** — the `BlockedPeerStore` + feed filter had no UI trigger; now a block icon button on each offer card + a Block trader button on offer detail (both confirm-gated), and the Home feed combine keys on a blocklist version so a block/unblock hides/restores offers immediately.
- **Accept-dialog address persisted** — the accept-dialog BTC receive address survives rotation/process death (`rememberSaveable`).
- **Inline field validation + QRIS completeness gate** — Create Offer validates fields inline and gates Publish on complete QRIS details; button hint copy. `OfferFormStateTest`.
- **Microinteraction polish** — offer accept dialog shows a spinner while the claim is in flight (dismiss/cancel gated); step-tracker dots + countdown colors animate via `NeoMotion` color springs; haptic ticks on money actions (long-press for broadcasts/dispute, confirm for transitions); spring-in release checkmark on RELEASED; escrow copy feedback via Snackbar (Toasts dropped, incl. a hardcoded ID string). `HapticsTest`, `StepTrackerStateTest`, `OfferDetailAcceptGateTest`.
- **48dp touch target** for the escrow copy-address button.
- **Dispute feed** — pull-to-refresh on the arbitrator feed; localized feed strings + not-arbitrator error; empty-state copy fixed.
- **Delete-offer confirmation** — deleting an offer now requires a confirm dialog (irreversible tombstone broadcast).

#### Offer auto-expiry (2026-09-05)
- **Expired OPEN/PAUSED offers are auto-deleted** — the orchestrator's 60s sweep now deletes offers past their creator-picked TTL (OPEN/PAUSED only): Room row dropped, re-announce digest untracked, tombstoned so a stale feed re-announce can't resurrect them. Previously expired offers stayed visible-but-blocked forever.
- **Stale MATCHED offers auto-cancel (24h)** — a MATCHED offer whose escrow is never created is auto-CANCELLED 24h after the match (`locked_at`, Room v25; `MATCHED_ESCROW_TIMEOUT_MS`), role-gated to the creator, and the terminal status syncs to the matched peer over LXMF. An offer is ESCROWED the moment an escrow row exists, so a disputed trade (disputes live only on ESCROWED offers) is never touched by this sweep.

### Fixed

- **Receipt composer error strings localized** (were hardcoded English).
- **i18n parity** — dispute-feed, receipt, and offer-form string keys added in EN+ID.

### Changed

- Room DB **v24 → v25** (`trade_offers.locked_at`).
- Room DB **v23 → v24** (`escrows.funded_amount_sats`).
- 375 unit tests (was 323): + `EscrowOverpaymentTest`, + `EscrowUnderpaymentTest`, + `AttestationCodecTest`, + `OfferFormStateTest`, + `StepTrackerStateTest`, + `OfferDetailAcceptGateTest`, + `HapticsTest`, + `RnsSessionTest` attestation cases, + `OfferRouterIngestValidationTest` Rp 5M case.
- String parity 822 = 822 EN/ID (was 794).

## [1.0.26] — 2026-09-02

### Added

#### Trade hub (post-accept destination)
- **Trade Room is now the post-accept destination** — `ui/screens/trade/TradeRoomScreen.kt` (route `trade/{offerId}`) is an Escrow+Chat hub with a status header and role-adaptive next-action shortcuts. `TradeRoomData` resolves the hub state (pure, unit-tested); the view model observes the escrow live. Post-accept trades route there (`OfferDetailScreen`), and the Trades tab re-enters it for in-flight trades (`HistoryScreen`). The previously dead `trade/` route is now in `isKnownRoute`.
- **`neop2p://peer/<peerId>` invite links are system deep links** — an `intent-filter` on MainActivity (`consumeInviteIntent`) records the peer and lands on Home; cold + warm start handled; self/malformed/pre-onboarding links ignored. `InviteViewModelTest` covers parsing.
- **One-time notification rationale** — Home shows a rationale card before the `POST_NOTIFICATIONS` prompt (`NotifRationale`, `NotifRationaleTest`).

#### Arbitration delivery (Room v23)
- **Dispute events carry `buyer_peer_id`/`seller_peer_id`** (persisted on `arbitrator_disputes`, `MIGRATION_22_23`) so the arbitrator — who has NO local escrow row — can deliver the `resolution` to the parties. Pre-v23 the resolution was sent to nobody and funds stayed locked in the multisig.
- **Auto-disputes deliver to the arbitrator** — payment-window + grace expiry now send a `dispute` event to `ARBITRATOR_PEER_ID` with per-target durable retry (`PendingDisputeStore.targets`).
- **`applyResolutionEvent` verifies the arbitrator's signature BEFORE marking the feed resolved** — a forged resolution can no longer hide a dispute or drop the legitimate pending resolution.
- **Sender-authenticated ingest** — dispute opener / evidence submitter must be the LXMF sender; a resolution is only accepted from `ARBITRATOR_PEER_ID`.
- **Buyer dispute escape hatch** — the buyer can dispute from FUNDING / PAYMENT_PENDING / RECEIPT_SENT instead of waiting on a stuck seller (a stuck seller must never leave the buyer with no exit before the funding window expires).
- **`escrow_status` sync carries `payout_tx_id` + `redeem_script_hex`** so the buyer's mirrored row can show the payout tx and apply an arbitration resolution.
- **Dispute/evidence/resolution re-delivery dedup** — LXMF router retries + 60s sweep re-sends are processed + notified only on first delivery (`shouldProcessDispute`, `DisputeRedeliveryGateTest`).

#### Transport
- **Transport node on official Python rnsd (2026-09-01)** — the VPS node now runs `python:3.11-slim` + `pip install rns lxmf` (lxmf is a HARD runtime dep: the TCP server interface auto-configures to gateway mode and hard-panics without it). The two rnsd-kt fork fixes (spawned-client registration + receiving-interface wiring) are native Python behavior. `rnsd-kt.jar` is dead weight.
- **Tier 1 LAN discovery (2026-09-01)** — phones register an RNS `AutoInterface` (IPv6 link-local multicast + per-peer UDP unicast) alongside the VPS TCP transport; two devices on one Wi-Fi exchange announces/paths/DIRECT LXMF links with no transport node in the path. `RnsTransport` holds a `WifiManager.MulticastLock` for the session lifetime.
- **Tier 3 multi-node (2026-09-01)** — users can add extra RNS transport nodes in Settings (`TransportNodeStore`, SharedPreferences JSON, live-apply without a network restart). Every node is a packet ferry, not a trust anchor.
- **Pull-to-refresh feed + `offer_delete` tombstone propagation (2026-09-01)** — `RnsSession.refreshFeed` re-announces tracked digests NOW (rate-capped); `offer_delete` LXMF messages propagate deletions via `OfferRouter.applyOfferDelete`.
- **Locked/terminal offer convergence (2026-09-02)** — locked offers (MATCHED/ESCROWED) stay on the paced re-announce loop (status change → commitment-hash change → receivers re-fetch + re-ingest); terminal offers (COMPLETED/CANCELLED) re-announce a digest-only tombstone once per feed cycle + on pull-to-refresh so a 3rd device converges on 'taken' instead of showing a stale OPEN row.
- **Tombstone pacing under the node rate cap (2026-09-02)** — tombstone cadence is now independent of live-set size (one per 4 ticks ≈ 3/30s; combined live+tombstone ≈ 15/30s per dest). Node config requires `announce_rate_target=1`, `announce_rate_grace=20`, `announce_rate_penalty=0` on every interface (documented as REQUIRED in `infrastructure/AGENTS.md`).

#### Chat / escrow hardening
- **Payment-details share hardening (2026-09-01)** — the seller's own share bubble renders the bank card (never raw JSON); a missing E2EE session no longer hard-fails the manual share (the 60s sweep delivers it, queued snackbar); `autoSharePaymentDetails` once per offer per run; inbound envelopes persist into the offer row (P0-1: E2EE-only, never relayed).
- **2-of-3 release restored (2026-09-01)** — `assemble2of3Spend` had two compounding bugs: pubkey-level slot dedup skipped the second role slot (single-key model: same pubkey occupies both slots, CHECKMULTISIG needs one sig per slot) and list aliasing in the trim destroyed the collected signatures (P2WSH witness went out with ZERO sigs). Verified live: broadcast tx `1946926c…` confirmed on testnet4 (block 150579), buyer healed to RELEASED via LXMF sync.
- **Funding-tx freshness gate (2026-09-01)** — deterministic funding addresses reuse across escrows between the same peers, so a stale deposit from a previous escrow could re-bind to a new escrow and promote it to FUNDED without fresh funds. Confirmed tx mined before escrow creation = stale; unconfirmed (mempool) never stale; confirmed-without-block_time falls back to creation time (sweep promote path does not fail closed).
- **`PendingArbitrationStore` (2026-09-01)** — durable evidence/resolution retry with per-target tracking; evidence meta now carries `image_base64` so the arbitrator's feed persists it; dispute delivery verdict gates on the counterparty only (arbitrator best-effort); sweep retries pending arbitration every 60s.

### Fixed

- **DB downgrade crash (2026-09-02)** — a test build from a newer branch (Room v23) left on-device DBs above main's v22; Room refused the downgrade and the app crashed on every launch. `fallbackToDestructiveMigrationOnDowngrade()` is now set — identity mnemonic + wallet keys live in SharedPreferences (KeyStore-encrypted), not this DB, so destructive downgrade is safe.
- **`RnsSession.stop` leaked destinations** — a leaked destination made a later session's announce for the same identity look local and get dropped (RnsSoakTest flake). `stop()` now deregisters its destinations.

### Changed

- Room DB **v22 → v23** (`arbitrator_disputes.buyer_peer_id`/`seller_peer_id`).
- `NeoP2PConfig.ARBITRATOR_PEER_ID` is now set (LXMF arbitration delivery enabled).
- 323 unit tests (was 269): + `EscrowStatusFieldsTest`, + `DisputeRedeliveryGateTest`, + `RnsSessionTest` (refresh/delete/transport-node cases), + `OfferFeedGateTest`, + `TransportNodeStoreTest`, + `PendingArbitrationStoreTest`, + `EscrowFundingBindingTest`, + `EscrowSegwitSpendForensicsTest`, + `TradeRoomDataTest`, + `NotifRationaleTest`, + `InviteViewModelTest`, + `ChainMonitorTxInfoTest` cases.
- String parity 794 = 794 EN/ID (was 789): + `chat_share_queued`, + `chat_payment_qris_label`, + invite deep-link + notification-rationale strings.

## [1.0.25] — 2026-09-01

### Fixed — production bugs surfaced by the load/soak harness

- **Redundant `Identity.remember` removed (Bug A1)** — the `lxmf.delivery` announce handler called `Identity.remember` with a **zeroed `packetHash`**, overwriting the fork's real remember (which stores `packet.packetHash`) in the shared `knownDestinations` and polluting `saveKnownDestinations` persistence. rns-core already remembers every valid announce before handlers dispatch, so the app-side call was both redundant and harmful. Outbound `Identity.recall(destHash)` now resolves purely from the fork's entry; `RnsSessionTest.identity recall works without an app-side remember` asserts it.
- **Unbounded offer-digest deferral buffer bounded (Bug A2)** — a `neop2p/offers` digest that arrives before its peer's `lxmf.delivery` announce is deferred and flushed once the delivery announce maps identity → peerId. The buffer was unbounded: a hostile peer announcing offers under an identity that never delivers a delivery-announce could grow memory without limit. Now capped at ≤32 digests per identity (deduped) and ≤64 identities (oldest evicted); overflow drops + logs. `RnsSessionTest` covers both caps and the flush path.
- **Offer-feed cadence tightened + one-shot announce removed (Bug B)** — the paced re-announce tick is now **2.5s** (was 10s): 12 announces/30s per destination, ~25% headroom under the fork's `MAX_RATE_TIMESTAMPS=16`/30s cap (the load test showed 1.5s pacing dropped 8×, 2.0s was clean; 2.5s is the production tick). The one-shot `publishOffer` at create/edit was removed — the paced loop owns every feed announce, eliminating the duplicate-announce-in-the-same-30s-window risk. `RnsLoadTest` now asserts **zero** rate-limit drops at the production cadence.

### Changed

- `SCENARIO_MATRIX.md` J1/C1/C8 updated: paced 2.5s loop is the sole feed-announce path; 100 offers cycle in ~4 min; verified no rate-limit drops at production cadence.

## [1.0.24] — 2026-09-01

### Fixed — matrix unknowns + offer-feed re-announce (SCENARIO_MATRIX C5/C8/C10/D8/E4/I6/J1-J3)

- **Offer feed was announced ONCE per create/edit and never re-announced (undocumented defect)** — `publishOffer` fired `offersDest.announce` once (`RnsSession.publishOffer`); the 20s loop only re-announced the LXMF delivery destination. A peer that joined after an offer was created never discovered it, and a cold-started seller with open offers re-announced nothing. Added a **paced offer re-announce loop** (`RnsSession`, 10s tick, one digest per tick, round-robin) fed by `P2POrchestrator.rehydrateOfferReannounce` from the durable offer table (OPEN/PAUSED, own offers only); `trackOfferDigest`/`untrackOfferDigest` wire create/edit/delete. Pacing is mandatory: the fork's path admission drops same-second re-announces to one destination AND rate-limits to `MAX_RATE_TIMESTAMPS=16`/dest/30s.
- **Cross-process offer digests were dropped as "unknown identity"** — the `lxmf.delivery` announce handler discarded the announced identity, so `Identity.recall()` never resolved for peers and `peerIdOfIdentityHash` (an O(n) scan) returned null → every offer digest was ignored. The handler now `Identity.remember`s the identity and maps its hash → peerId; digests arriving before their delivery announce are deferred and flushed on the delivery announce. Found by RnsLoadTest.
- **C5/D8: field-level offer ingest gate** — the LXMF byte caps bound the container, not the money fields. `OfferRouter.isValidOfferPayload` clamps `crypto_amount_sats` (1k…100M), `fiat_amount` (1…100B IDR), `price_per_unit` (finite, >0, ≤10B), and `fiat_methods` (≤14 entries, ≤64 chars each) — a hostile offer is dropped, not persisted. Long money math can no longer overflow (D8 closed by construction).
- **C10/I6: nickname capped at write AND ingest** — `IdentityManager.sanitizeNickname` (32 chars, control-char strip, trim) applied in `updateNickname` and at offer ingest (`OfferRouter`). A hostile peer's nickname can no longer plant a bidi/RTL overflow or CRLF into the feed or a chat card.
- **E4: depth re-check before auto-refund** — the sweep now uses `fundingRefundDecision`: a reorg that shaves confirmed depth below `required_confirmations` while the address is still funded reverts to `FUNDING` (previously only full unconfirm+gone — E7 — reverted, and explorer failure fell through to refund despite the "fail closed" comment). EscrowReorgTest extended.

### Added

- **J1/J2/J3 harness** — `RnsLoadTest` (30-offer paced flood, fd/heap instrumentation), `RnsSoakTest` + `RnsSoakServerMain` (accelerated-clock soak of chat + offer_status round-trips with per-iteration fd/heap sampling), `RnsOfferFloodServerMain`. `RnsSessionTest` gained an in-JVM paced-reannounce test (test-only interval seam `offerReannounceIntervalMs`).

### Changed

- **`SCENARIO_MATRIX.md`** — every remaining UNKNOWN resolved: 9 → COVERED/ACCEPTED (A6/A7/B7/B10/D6/I10 documented as accepted risk under a new ACCEPTED legend). 266 tests, 0 failures.

## [1.0.23] — 2026-08-31

### Fixed — first live RNS transport-node pass

- **Transport node ran with no config (root cause of the 30s drop cycle)** — the rnsd-kt container started with `transport=disabled` and `No interfaces configured or started` because rnsd-kt loads exactly `File(dir, "config")` (no extension) while the repo shipped `config.yml`, and the named volume at `/etc/reticulum` was empty. Phones connected to docker-proxy's 42000 (dead backend) and hit EOF every ~14-33s. Fixed by renaming `rns-transport/config.yml` → `rns-transport/config` and bind-mounting it `:ro` into the container in both compose files — a fresh volume can no longer orphan the config.
- **No peer discovery / no data routing between phones (fork fix)** — `TCPServerInterface.acceptLoop` never registered spawned client interfaces with `Transport` (`onClientConnected` was documented as the wiring point but nothing connected it). Announces were only queued on the Propagation Link, and `nextHopInterface()` could not resolve a client's path entry. Each accepted client is now registered (`Transport.registerInterface(client.toRef())`, deregistered in `clientDisconnected`); announce fan-out reaches every connected phone and paths route back to clients.
- **LINKREQUESTs silently dropped on the transport node (fork fix, second pass)** — `InterfaceConfigFactory`/`DaemonRunner` wired `onPacketReceived` with the PARENT interface ref, so every path learned from a client announce pointed at the parent whose `processOutgoing` is a no-op. The server logged `Transport forwarding LINKREQUEST ... via VPS TCP Server` and the packet vanished; the phone saw `Link establishment timed out` + `LXMF delivery failed (offer_request)`. The callback now passes the actual receiving interface (`(receivedIface ?: iface).toRef()`), matching the conformance bridge. After this fix the server forwards via `VPS TCP Server/client-N` and links establish.
- **lxmf-propagation crash loop** — `lxmd.sh` wrote the daily prune to `/etc/periodic/daily` which does not exist in `python:3.11-slim` (no cron); the prune is now guarded. The `lxmd` invocation was also fixed: `--identity` is not a CLI option (argparse would reject it), `-p` (propagation node) and `--rnsconfig` were missing — the node now runs `lxmd --config <dir> --rnsconfig <dir> -p` and starts as an actual LXMF Propagation Node.
- **Transport → propagation reachability** — the transport node's config now defines a `[[Propagation Link]]` TCPClientInterface → `lxmf-propagation:42000` (docker DNS); before, the propagation node's TCP server was never published and the transport node had no client link to it, so store-and-forward was unreachable.
- **rns-transport healthcheck** — curled `http://127.0.0.1:42000/health`, but 42000 is a raw Reticulum TCP interface, not HTTP; replaced with a raw TCP probe (`bash /dev/tcp`). Healthchecker container also installs `netcat-openbsd` (alpine has no `nc`).

### Changed

- **App: transport self-heal** — `P2POrchestrator.sweepStaleEscrows` retries `rnsTransport.start()` every 60s while the transport is not running. A launch behind a locked screen (`Identity locked behind device auth`) previously left the phone dead forever after unlock; the retry now brings RNS up within one sweep.
- **App: 20s re-announce** — `RnsSession` re-announces the LXMF delivery destination every 20s (was 5 min). This keeps the VPS link alive (idle connections were dropped by the firewall/NAT), heals the first-announce race (the first announce broadcasts on 0 interfaces before the TCP link is up), and makes peer discovery fast (a peer joining later learns us within one interval).
- **App: TCP keepalive** — `TCPClientInterface` created with `keepAlive = true` (was `false`).
- **App: peer-seen logging** — `RnsSession.handlePeerAnnounce` logs `[RnsSession] Peer seen: <peerId> (dest <hash>…)` so peer discovery is visible in logcat.

## [1.0.22] — 2026-08-31

### Changed — Phase 4: RNS/LXMF is the ONLY transport

- **Removed libp2p, the WS relay, Nostr, and WebRTC** — `LibP2PManager`, `P2PTransportManager`, `NostrClient`, `NostrEventSigner`, `WebRTCManager`, `WebRTCSignalCodec`, `HybridP2PTransport` deleted. `RnsSession`/`RnsTransport` now connect as a TCP client to the VPS transport node (`NeoP2PConfig.RNS_TRANSPORT_NODE_HOST/PORT`, rnsd-kt `enableTransport=true`).
- **All signaling is LXMF DIRECT** — offer_status / escrow_status / dispute / evidence / resolution travel as LXMF messages (title = type, FIELD_CUSTOM_DATA = JSON; evidence images as file attachments). `P2POrchestrator` routes inbound LXMF signaling to the same handlers the Nostr collectors used; `retryPendingDisputes`/`healDisputePsbt` deliver over LXMF.
- **Offer feed is announce-based** — `neop2p/offers` announce carries a compact `RnsOfferDigest` (~200B); the full offer JSON is fetched on demand over LXMF (`offer_request` → `offer`). Digest identity is cross-checked against the peer's `lxmf.delivery` announce.
- **Attestations are local-only** — the Nostr gossip path was removed; reputation is computed from the local attestations table.
- **`KeyDerivation.deriveLibp2pPeerIdFromKey` reimplemented locally** (base58btc of the identity multihash of the protobuf Ed25519 pubkey) — peerIds stay stable without jvm-libp2p.
- **Build deps trimmed** — libp2p, stream-webrtc, ktor-websockets, protobuf-java removed; ktor-client-core/okhttp kept (ChainMonitor Mempool API + market price). TURN BuildConfig fields removed.
- **Infrastructure** — `strfry/`, `libp2p-relay/`, `ws-relay/`, `coturn/` removed; replaced by `rns-transport/` (rnsd-kt, TCP server 42000) + `lxmf-propagation/` (Python lxmd store-and-forward). Compose files, deploy/status/backup/healthcheck scripts updated.

## [1.0.21] — 2026-08-31

### Fixed

#### Dispute feed empty — reliability fix-all (P0/P1, DB v21→22)

- **Arbitrator dispute durability (P0, `arbitrator_disputes` Room v22)** — `P2POrchestrator.consumeDisputes` and `DisputeFeedViewModel` now persist every `kind:33386` to SQLCipher `arbitrator_disputes` (`MIGRATION_21_22`) and `DisputeEvidenceDao` is merged as source-of-truth. The relay holds only the last 200 arbitration events and prunes on restart; previously `DisputeFeedScreen` was pure in-memory `LinkedHashMap` seeded only from relay replay, so a reboot or relay prune lost all disputes. The feed now seeds from DB before live relay, observes `arbitratorDisputeDao.observeAll()` + `disputeEvidenceDao.observeAll()` and merges with live `nostrClient` flows, sorted `openedAt DESC` newest-first. `refresh()` was a no-op (`Loading`→`publishState` same maps) — now re-seeds from DB and logs `seeded N disputes`.
- **Ack-gated publish-then-commit with retry (P0)** — `EscrowScreen.disputeEscrow` already did `publishDispute(33386)` before `disputeEscrow()` (2026-08-30), but a `confirmed.isEmpty()` (0 connected relays / 5s NIP-20 OK timeout / no custom relay) left the escrow undisputed with no retry. Now `PendingDisputeStore` (`SharedPreferences pending_dispute_<escrowId>` JSON) saves the payload on publish failure and `P2POrchestrator.sweepStaleEscrows` (60s) retries `publishDispute` until acked, then flips locally `disputeEscrow` and clears the pending key (idempotent — skips if already `DISPUTED`). Error now surfaces `saved for retry — will auto-retry every 60s`.
- **Relay health banner (P1)** — `DisputeFeedScreen` shows `Relays X/Y · custom N` and a `errorContainer` banner when `custom 0` (`custom-minipc.com` `NostrClient.kt:160` — public `nos.lol/damus` never carry `33386/33387/33388`). Previously empty vs offline were indistinguishable. `NostrClient.handleNostrMessage` now logs `Rejected kind=X id=Y pub=Z relay=…` on Schnorr fail (`verifyEventSignature:372`) so forked builds are diagnosable.
- **Per-card busy + richer card (P1)** — global `_busy` blocked all cards; now `_busyEscrowIds:Set<String>` allows concurrent resolves with per-card spinner. Cards now show `openedAt` (`dd MMM yyyy HH:mm`), `Deposit: sats · scriptType`, and `Refund → addr` alongside `openedBy`/`reason`.

### Changed

- Room DB **v21 → v22** (`arbitrator_disputes` + `PendingDisputeStore` retry queue). `EscrowStatus.RESOLVING` now formally `@Deprecated("Use DISPUTED")` with v23 removal note (`UPDATE escrows SET status='DISPUTED' WHERE status='RESOLVING'`).
- `DisputeFeedScreen` and `EscrowScreen` now depend on `ArbitratorDisputeDao` + `PendingDisputeStore` via Hilt (`AppModule`).

## [1.0.20] — 2026-08-29

### Added

#### Audit-fix batch (G.M.01 money precision + connection honesty + local report)
- **Integer-only money math (G.M.01)** — the platform fee and fiat amount are now computed without any `Double` round-trip. `feeSats = (sats * FEE_NUM) / FEE_DEN` with `FEE_NUM=3`, `FEE_DEN=1000` (exact for every Long), and `fiatAmount = (btcSats * priceIdr) / 100_000_000` (whole rupiah). `TradeOffer.feeSats` default, the Room `trade_offers.fee_sats` default, and the create/edit paths in `CreateOfferScreen` all share the same integer formula. A typed price that is not a whole number (e.g. a stray decimal) is rejected by `parseIdrToLong` and falls back to the display-only Double path — a decimal price can never be misread as a 10x integer.
- **Stale-DIRECT revocation (money safety)** — `PeerRegistry.markPeerOffline` now downgrades DIRECT→OFFLINE, and `LibP2PManager` sweeps every 30 s, revoking any DIRECT claim whose live libp2p connection has closed (Wi-Fi ↔ cellular handoff, OEM kill, peer gone). Previously the monotonic DIRECT rule kept a dead link "direct" forever, silently skipping the escrow relay-confirm gate on money actions. The escrow gate (`EscrowScreen.gateRelayed`) now fires for **anything not DIRECT** — RELAYED and OFFLINE-stale both require the explicit confirm sheet.
- **Honest connection pipeline (F05b)** — `ConnectionQuality` grew from 3 to 6 states: `OFFLINE / CONNECTING / RELAYED / RECONNECTING / RELAY_QUOTA / DIRECT`. A WS-relay drop now marks peers RECONNECTING (not OFFLINE) while the backoff loop re-raises them; a relay delivery failure maps to a per-peer RELAY_QUOTA state via the error's `to` field (the ws-relay Go server now carries the intended recipient on `delivery failed` errors). The `ConnectionQualityChip` renders all six with distinct colors and `conn_relay_quota` ("Kuota relai habis") copy in EN/ID. No holepunch/DCUtR is claimed (jvm-libp2p has none) — the chip stays honest.
- **Local trader report (F18)** — new `ReportedPeerStore` (SharedPreferences: peerId + reason + timestamp) records a report on-device only; it never travels to a relay/server, never changes escrow or release state, and can be reviewed/removed in Settings ("Pedagang Dilaporkan"). The offer-detail screen shows a "Lapor" action with 4 reason codes (scam / harassment / fake receipt / other). `destroyLocalData` clears reports too.

### Changed
- 244 unit tests (was 203): PeerRegistry tests updated for the new state semantics (relay drop → RECONNECTING, DIRECT survives relay drop until the libp2p link closes via `markPeerOffline`, quota-exceeded recovers on contact), + `ReportedPeerStore` is JVM-testable via SharedPreferences-free API.
- String parity grew: `conn_relay_quota` + 13 report strings in EN/ID.
- Room DB stays at **v21** — zero migrations this batch.
- ws-relay server (`infrastructure/ws-relay/main.go`) now includes `to` on delivery-failure error frames so clients can attribute quota per-peer.

## [1.0.19] — 2026-08-28

### Added

#### Debug-fix batch (P1–P3) — from the flow-1 debug pass + deep check
- **SIGNED is a forward escrow state (P1, money safety)** — `generatePayoutTransaction` persists SIGNED transiently before CONFIRMING; a process kill in that window previously left the escrow permanently stuck (router rejected the buyer's PAYMENT_PENDING, the sweep never auto-refunded, `confirmReceipt` refused to retry). Now: `EscrowRouter.applyRemoteStatus` accepts SIGNED in the forward order (FUNDING→FUNDED→SIGNED→PAYMENT_PENDING→…), the stale-escrow sweep auto-refunds stalled SIGNED like FUNDED (12 h + 48 h grace from `funded_at`), `getEscrow` resume-heal re-publishes SIGNED, and the seller can retry `confirmReceipt` from SIGNED.
- **Durable onboarding gate (P1)** — `OnboardingStore` persists a completion flag; `MainActivity` start destination is now `hasIdentity && onboardingComplete`. A kill between identity generation and seed verification returns the user to the BACKUP_SEED step instead of silently skipping straight to HOME with an unbacked-up seed (seed loss = wallet loss). Pure `OnboardingGate` policy, JVM-tested.
- **Auth-gated recovery phrase (P2)** — Settings → "Lihat Frasa Pemulihan" reveals the 12-word BIP-39 phrase behind a BiometricPrompt (strong biometric or device credential), masked by default, copyable, with a never-share warning. The seed is recoverable after onboarding for the first time.
- **Restore guard (P2)** — `restoreFromSeedPhrase` refuses to overwrite a loadable identity (`RestoreGuard`); the locked/invalidated-key path (lock-screen change) still allows restore because it is the only recovery. `force` param for explicit override.
- **Dispute evidence size cap (P3)** — new shared `ImageCompressor` (≤1600px edge, ≤60KB JPEG, quality 85→30 loop) extracted from the receipt composer and now applied to dispute evidence before local store + kind:33387 relay publish (a raw 10MB photo previously produced a multi-MB Nostr event).
- **Locked-identity notification (P3)** — `P2PBackgroundService` catches `IdentityLockedException` and posts "Identitas terkunci" instead of failing silently; P2P outage is no longer invisible.
- **Seed clipboard auto-clear (P3)** — the copied seed phrase clears from the system clipboard after 60s (only if it is still our phrase — never clobbers a later copy).

### Changed
- **Escrow timeout constants reverted to product spec** — `ESCROW_FUNDING_TIMEOUT_MS` 90→45 min, `FUNDING_WARNING_MS` 60→30 min, `ESCROW_FUNDED_REFUND_TIMEOUT_MS` 24→12 h, `FUNDED_REFUND_GRACE_MS` 96→48 h, `PAYMENT_WINDOW_MS` 48→24 h, `PAYMENT_GRACE_MS` 24→12 h. The "(2x for test)" multiplier was a leftover test hack; the code now matches AGENTS.md and the escrow-ux spec.
- 203 unit tests (was 194): + `OnboardingGateTest` (3), + `RestoreGuardTest` (4), + `EscrowRouterApplyTest` SIGNED case, + `EscrowTimeoutTest` SIGNED case.
- String parity 706 = 706 EN/ID (was 697): + recovery-phrase dialog (7), + locked-identity notification (2).
- Room DB stays at **v21** — zero migrations this batch.
- Dead `identity_version` prefs write removed.

## [1.0.18] — 2026-08-28

### Added

#### Completeness batch 2 — fixes (P0–P2)
- **Seller reject-receipt path (P0)** — the seller can now reject a payment receipt with a structured reason instead of being forced into confirm-or-dispute. "Tolak Bukti" (RECEIPT_SENT, seller-only) opens a dialog with 4 machine-readable reason codes (JUMLAH_SALAH / NAMA_BEDA / BELUM_MASUK / LAINNYA) + optional note; the rejection travels the existing E2EE chat envelope (`payment_receipt_reject` payload, zero DB migration, no new relay kind) and renders as a card on the buyer's side with a funds-locked line. `confirmReceipt` remains the ONLY release gate — rejection is advisory evidence so the buyer can fix/resubmit or dispute.
- **Wallet send fee + total preview (P1)** — the send-confirm dialog now shows the estimated network fee and the total (amount + fee) before broadcast (`estimateSendFee`, honest single-input preview; real fee recomputed after UTXO selection).
- **Receipt draft persistence (P1)** — the receipt composer saves reference + screenshot to SharedPreferences on every change; a kill or back-nav restores the draft with a "Draf dipulihkan" banner and a Start Fresh discard. A sent receipt never resurrects.
- **History search (F14)** — search field filters by TradeID / kode unik / reference, with a distinct "no matching transactions" empty state.
- **Notification-denied banner (F17)** — Home shows a dismissible "Notifikasi mati" banner when POST_NOTIFICATIONS is denied, with a Fix button into the OEM notification help screen.
- **Edit-with-live-taker warning** — editing a MATCHED offer now warns that changing price/amount/rails can break the pending agreement.
- **Rail-mismatch warning** — the pay card warns to use only the registered payment methods (paying via another bank/e-wallet makes proof ambiguous).
- **Trade-completion summary card (M3)** — RELEASED/REFUNDED/CANCELLED escrows show a summary card (BTC, IDR, fee, kode unik, funding/payout txids, TradeID, date) with a Save/Share Proof button (Bisq bisq-mobile#420 pattern).

#### Completeness batch 3 — adds (P1–P2)
- **Offer pause/resume (P1)** — sellers can pause an ACTIVE offer ("Jeda"); PAUSED is a new `OfferStatus` on kind:33336, claim-gated (only the creator may pause/reactivate; a live match can never be paused), hidden from the public feed except the creator.
- **Saved payment methods (P1)** — bank/QRIS/e-wallet details persist in SharedPreferences JSON (`SavedPaymentMethodsStore`); Settings → "Metode Pembayaran Saya" manages them; Create Offer prefills from saved methods (blank fields only — manual edits never overwritten).
- **Peer fingerprint (P1)** — 8-word BIP-39 fingerprint of the counterparty identity in the chat top bar + escrow header (TOFU trust anchor, copyable, compare out-of-band to detect relay-level MITM).
- **History grouping (M3)** — trades list groups into Perlu Tindakan Anda / Menunggu / Selesai (role-aware, zero new queries).
- **Delete gating + reason copy** — delete is enabled only while OPEN/PAUSED; locked offers show why ("Tidak bisa dihapus — ada pembeli yang menunggu…").
- **Empty-market nudges** — the empty feed now offers "Buat Tawaran Pertama" + "Undang Teman" CTAs.
- **Language toggle (F16)** — Settings → Bahasa: ID/EN override (manual Configuration override, applies on restart; FragmentActivity, no AppCompatDelegate).
- **Destroy local data (F16)** — Settings danger zone: type-to-confirm "Hapus Semua Data Lokal" wipes offers/escrows/chat/peers/keys/blocked/deleted stores but keeps identity + seed.
- **Offer sort (F03)** — feed sort menu: Terbaru / Segera Kedaluwarsa.
- **Kode unik on trade rows (F19)** — history rows show the deterministic 3-digit code so the buyer recognizes the trade before opening it.

#### Completeness batch 4 — residual (P1–P2)
- **Resume-heal for payment states (P1)** — `getEscrow` now re-publishes kind:33337 on load for PAYMENT_PENDING / RECEIPT_SENT / CONFIRMING (previously only FUNDING-with-txid and FUNDED). A kill between DB persist and relay write no longer strands the counterparty on the pre-transition status (router is forward-only + no-downgrade, so the heal is idempotent).
- **48dp tap targets (a11y)** — primary escrow actions (fund / verify / mark-paid / open-receipt / confirm / reject) bumped 40→48dp (M3 minimum).
- **Machine error codes** — new `ui/util/ErrorCodes.kt` maps money-path failures to stable codes (ERR_INSUFFICIENT_BALANCE, ERR_BROADCAST, ERR_FUNDING_TIMEOUT, ERR_INVALID_ADDRESS, ERR_INVALID_QR, ERR_INVALID_SEED); escrow/wallet/invite/onboarding error surfaces render a "Kode: ERR_X" secondary line so users can report a stable token (no support desk).
- **BI-FAST/RTGS copy** — the pay card notes per-bank BI-FAST limits and RTGS for large amounts (neutral copy, cap deliberately NOT hardcoded).
- **Light-theme WCAG AA contrast** — light scheme primary/secondary/tertiary darkened (#007A41 / #00707A / #B33A00, 5.6–6.0:1 on white); `sellColor` is now scheme-aware (#C62828 on light, #FF6B6B on dark); TalkBack labels on the funding message/error dismiss buttons.

### Changed
- 194 unit tests (was 172): + `ChatRouterRejectPayloadTest`, + `SavedPaymentMethodsStoreTest`, + `PeerFingerprintTest`, + `ErrorCodesTest`, + `OfferClaimGateTest` PAUSED cases.
- String parity 697 = 697 EN/ID (was 620).
- Room DB stays at **v21** — zero migrations this batch (reject path rides the E2EE chat envelope; saved methods use SharedPreferences; drafts use SharedPreferences).

## [1.0.17] — 2026-08-28

### Added

#### Product-completeness batch (A1–A5, B1–B4, C1–C4)
- **IDR formatting (PUEBI)** — new `ui/util/FiatFormat.kt`: `formatIdr` renders "Rp 1.250.000" (space after Rp, dot thousands, no decimals) everywhere (Home, Create Offer, Offer Detail). The old `%,d`/`%,.0f` produced "Rp 1,250,000" on en-US devices.
- **Two-taker collision gate** — `OfferDao.claimOffer` is a compare-and-set (OPEN + unmatched + unexpired only); new pure `OfferClaimGate` decides relay-race winners (no status downgrades, matched-peer adoption only in pre-escrow states). The loser now sees "Tawaran sudah diambil" and is never routed into a chat for a lost trade (previously `onAccepted(null)` sent them into the winner's chat).
- **Pay instruction card (money-stuck fix)** — buyer sees the exact IDR amount with a deterministic 3-digit unique code (Indodax/Flip "kode unik" convention: `uniquePaymentCode(escrowId, fiatAmount)` — both devices derive the same code, no extra message), copy button, QRIS note, own-account-only warning, and no-crypto-words-in-note warning. Receipt composer now shows the IDR amount (was sats-only).
- **Localized notifications** — all `P2POrchestrator` + `NotificationDispatcher` strings moved to resources (both locales). Chat notifications no longer leak a raw peer-id prefix: title is "Pesan baru" / "New message". Onboarding errors ("exactly 12 words", "words don't match") localized.
- **Mempool pending card** — FUNDING screen shows an in-progress card with the txid and a live explorer link (blockstream.info/testnet4) while the deposit waits for confirmation.
- **Block peer (local)** — `BlockedPeerStore` (SharedPreferences): block from an offer card hides that peer's offers from the feed immediately; Settings → Blocked Traders lists and unblocks. Local-only, never gossiped.
- **Evidence export** — Dispute Evidence screen exports the local evidence bundle (escrow id, status, txids, evidence metadata) as JSON via the system share sheet (FileProvider, `ic_insert_drive_file`).
- **QR invite** — Profile → "Undang Rekan": show my QR (`neop2p://peer/<id>?relay=…`), paste a link or peer id, or scan theirs (new `com.journeyapps:zxing-android-embedded` dependency; CAMERA already declared). Handles invalid QR, self-invite, already-known peers.
- **Offer TTL (DB v21)** — creator picks 6h/12h/24h/48h at create/edit; `expires_at` travels in the offer event so both sides converge; Home shows "Berakhir dalam mm:ss" under 1h and "Kedaluwarsa" past TTL; the accept button disables and the DAO claim gate refuses expired offers. Migration `MIGRATION_20_21` is additive (NULL = legacy never-expiring).
- **Home filters** — method chips (Semua / BCA / Mandiri / …) + Min/Max IDR fields, local-only filtering of the loaded feed; offline sync banner when no relay is connected; unread badge on the active-trade chat icon (per-offer count).
- **Sticky next-action bar (escrow)** — one primary message per role+state pinned at the bottom of the escrow detail with a live countdown while a window runs (RoboSats/Binance pattern): FUNDING seller → fund; FUNDED buyer → pay `formatIdr` amount; RECEIPT_SENT seller → confirm & release; DISPUTED → "bukti Anda adalah satu-satunya senjata".
- **Chat delivery status** — `ChatRouter.sendText` now reports live delivery vs queued; own bubbles show "✓ Terkirim" (delivered) / "Mengirim…" (<10s) / "Menunggu rekan online" (queued, Briar MessageStatus pattern).
- **OEM notification help** — Settings → "Aktifkan Notifikasi (Ponsel Ini)": per-brand checklist (Xiaomi/Redmi/POCO, Samsung, OPPO/realme, vivo, Huawei/Honor, generic — dontkillmyapp paths) + direct jump to this app's notification settings.
- **Restore warning** — after a successful seed restore, a mandatory dialog explains open trades/history live on the OLD device (no cloud inbox); on-chain funds are safe (seed-derived).

### Changed
- Room DB **v20 → v21** (`trade_offers.expires_at`).
- `zxing-core` → + `zxing-android-embedded` in the version catalog.
- 172 unit tests (was 146): + `FiatFormatTest`, + `OfferClaimGateTest` (race/adoption/downgrade cases), + `ChainMonitorTxInfoTest` retained.

## [1.0.16] — 2026-08-28

### Fixed

- **Funding verification always failed (P1, the happy-path killer)** — `ChainMonitor.getTxInfo` read a `confirmations` field that Mempool/Esplora's `GET /api/tx/:txid` never returns (verified live: only `status.confirmed` + `status.block_height`). Every funding tx was rejected with "0 confirmation(s); 1 required", so `FUNDING → FUNDED` only ever happened via the 90-minute sweep rescue (`hasOnChainDeposit` promote). Depth is now derived from the explorer tip height (`tip − block_height + 1`) via a pure, unit-tested `ChainMonitor.parseTxInfo`; when the tip is unreachable a confirmed tx reports 1 (satisfies the default `required_confirmations=1` gate). New `ChainMonitorTxInfoTest` (6 cases).
- **Remote refund paid the resolver's own wallet (P2)** — `resolveDispute` and `storeArbitrationDecision` built `REFUND_TO_SELLER` txs to the **local device's** address, so an arbitrator-applied refund paid the arbitrator. The seller's refund address now travels the full chain: set at escrow creation → kind:33337 → dispute event (kind:33386) → resolution (kind:33388) → persisted as `escrows.refund_destination` before the decision is applied. Both resolution paths refund to the seller. Room DB **v19 → v20** (`refund_destination`, `seller_refund_address`).
- **Receipt send blocked by a dead chat session (P3)** — `ReceiptComposerViewModel.send` required BOTH the escrow transition AND the E2EE chat send to succeed; a dead session left the buyer stuck at `PAYMENT_PENDING`. The escrow transition is now the source of truth; the chat copy is best-effort.
- **Dead `DISPUTE_TIMELOCK_DAYS` constant removed (P3)** — the last remnant of the false 7-day timelock claim.

## [1.0.15] — 2026-08-26

### Fixed

- **Arbitration resolution now actually broadcasts the 2-of-3** — `storeArbitrationDecision` (party side, via `P2POrchestrator.consumeResolutions`) previously flipped the local escrow to `RELEASED`/`REFUNDED` but never moved funds on-chain, leaving the P2SH output locked while the UI reported it resolved. It now assembles the 2-of-3 scriptSig (arbitrator sig + the local key filling the buyer/seller slots) and broadcasts via `ChainMonitor.broadcastTx`, persisting the payout/refund txid. `releaseFunds` and `resolveDispute` were refactored to the same `assemble2of3ScriptSig` helper. Fixes the stuck-funds bug in arbitration.
- **Normal payout release reached only 1-of-3** — `confirmPayout()` signed a single role slot, but `releaseFunds` required 2 valid signatures, so the payout could never broadcast. In the single-key model the local key is both buyer and seller, so `releaseFunds` now fills both role slots.
- **Inverted arbitration decision semantics** — `ResolutionDecision.RELEASE_TO_SELLER`/`REFUND_TO_BUYER` described the opposite on-chain recipient. Renamed to `RELEASE_TO_BUYER` (payout sends `tradeAmountSats` to the buyer + fee to the wallet) and `REFUND_TO_SELLER` (refund returns the deposit to the seller). `P2POrchestrator.consumeResolutions` still accepts the old kind:33388 names for backward compatibility.
- **New unit test** — `EscrowArbitrationResolutionTest` proves the arbitration resolution path assembles a spendable 2-of-3 scriptSig (arbitrator + local key), that a single signature alone cannot broadcast, and that refund uses the refund-tx semantics.

## [1.0.14] — 2026-08-25

### Added

#### Dispute arbitration transport (Option 1: transport first, admin mode, split APK later)
- **`kind:33386` dispute events** — `disputeEscrow()` now publishes the dispute to the relay (escrow id, opener, reason, redeem script, unsigned payout tx). Parties AND the arbitrator learn about the dispute from the network; `P2POrchestrator.consumeDisputes` syncs the local escrow status to `DISPUTED` (idempotent) and notifies.
- **`kind:33387` evidence events** — submitting evidence now also publishes it (image base64 + description) so the arbitrator can review receipts remotely.
- **`kind:33388` resolution events** — the arbitrator signs the payout/refund tx carried in the dispute event (`EscrowService.arbitratorSignTx`, remote signing — no local escrow row needed) and publishes the decision + signature. Parties apply it via `storeArbitrationDecision` (idempotent, never overwrites) and can broadcast the 2-of-3 payout/refund with the arbitrator's signature.
- **Arbitrator identity derivation** — `IdentityManager` now derives the arbitrator key at `m/44'/999'/0'/1/0` from the admin's mnemonic. `getArbitratorPubKeyHex()`/`getArbitratorPrivateKeyHex()`; the arbitration key is born inside the admin's device, never embedded in an APK.
- **Arbitrator Mode** — when the active identity IS the arbitrator (derived key == `ARBITRATOR_PUBKEY`), Settings shows an "Arbitrator Mode" card opening the **Dispute Feed** (`DisputeFeedScreen`): disputes + evidence from the relay, Release-to-Buyer / Refund-to-Seller resolution buttons with notes.
- Refund guards now allow `DISPUTED` (funded disputes can be settled/refunded post-resolution).

### Fixed
- Comment rot in `EscrowService` (15-minute → 6-hour auto-refund references).

## [1.0.13] — 2026-08-25

### Added

#### Payment window — buyer marks fiat as sent (`PAID`)
- New `EscrowStatus.PAID` + `paid_at` column (Room v15→v16). The buyer confirms the IDR transfer ("I've Sent the Payment"), the escrow shows a **live 2 h countdown** (`PAYMENT_WINDOW_MS`), and the seller must release or dispute before the window expires.
- If the window expires, `expireStaleEscrows()` auto-transitions the escrow to `DISPUTED` — **never silently auto-refunded**, because the buyer may have actually paid and the arbitrator decides with evidence.
- `P2POrchestrator` maps the `paid` transition to a push notification ("Payment marked as sent — release or dispute within the payment window").

#### Post-trade rating dialog (closes the reputation loop)
- `ReputationSystem.createAttestation` + `NostrClient.publishAttestation` (kind:33335) existed but had **zero call sites** — nobody could ever rate a counterparty.
- When an escrow reaches `RELEASED`/`REFUNDED`, a "Rate your counterparty" dialog appears once (dismissible, per-escrow dedupe in-process) and publishes a signed BIP-340 Schnorr attestation to the relay, feeding peers' reputation scores.

#### Dispute evidence submission
- `DisputeEvidenceScreen` (route `dispute_evidence/{escrowId}`): attach a payment receipt image + description; stored in the SQLCipher-encrypted `dispute_evidence` table (never published to the relay).
- `DISPUTED` shows "Submit Payment Evidence"; `RESOLVING` shows "View Submitted Evidence" — previously dead-end text with no actions.

#### Configurable funding confirmations
- New `required_confirmations` column (default 1). `onEscrowFunded` now rejects funding txs with fewer confirmations (HodlHodl-style).

### Docs

- `docs/FIDELITY_BOND_DESIGN.md` — P2 deferred design for anti-ghost-offer bonds (RoboSats/Mostro reference), incl. cheaper alternative (reputation-gated posting).

## [1.0.12] — 2026-08-25

### Added

#### Offer propagation now waits for relay confirmation
- `NostrClient.publishTradeOffer` / `publishOfferDeletion` / `publishOfferStatus` / `publishAttestation` now reuse the **persistent** per-relay socket (`socketsByRelay`) instead of opening a transient socket that closed before the relay persisted the event.
- Publishes wait for a **NIP-20 (`EVENT/OK`) ack per relay** (`pendingAcks` keyed `"eventId|relayUrl"`, 5 s timeout) and return the set of relays that actually confirmed storage. Fixes the bug where an offer was logged as "published" but never reached other peers.

#### One-tap escrow auto-fund from the seller's wallet
- `EscrowScreen` (FUNDING state) adds a **"Send from my wallet to escrow"** button behind an irreversible-broadcast confirm dialog.
- `EscrowViewModel.fundFromWallet()` sends the exact `depositAmountSats` to the 2-of-3 P2SH address via `WalletService.send`, auto-fills the txid, verifies on-chain (`onEscrowFunded`), and moves the escrow to `FUNDED`.

#### Escrow-first payment-detail sharing
- Bank number + holder name are now persisted per fiat method on `trade_offers.payment_details` (new Room column, v14→v15) when an offer is created/edited — previously they were entered only to enable Publish and then discarded.
- Chat is **locked until the on-chain escrow is `FUNDED`**; once funded, the seller can tap **"Share payment details"** to send a structured E2EE JSON card (bank # + name) to the buyer, who renders it as a `PaymentDetailsCard`. Bank details are never published to the Nostr relay (P0-1).

### Fixed

#### `ChainMonitor.broadcastTx` rejected a successful broadcast
- Mempool/Esplora's `POST /api/tx` returns the txid as **plain text**, not JSON. `apiPost` used a JSON-only validator, so a *successful* broadcast was misreported as a failure (the tx was actually accepted into the mempool). It now accepts a 64-hex txid response as success.

#### Auto-cancel could orphan a funded escrow
- `EscrowService.expireStaleEscrows` auto-cancelled a stale `FUNDING` escrow after 30 min **without checking whether the P2SH address received a deposit** — a broadcast that succeeded but wasn't verified (e.g. due to the broadcast bug or a slow confirmation) would be cancelled with funds already on-chain. It now checks `hasOnChainDeposit()` and promotes to `FUNDED` instead of cancelling.

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
- 2-of-3 multisig escrow creation
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
- 2-of-3 multisig transaction building is scaffolded but uses placeholder signatures
- BIP-39 mnemonic generation is simplified (full BIP-32 derivation pending)
- Nostr NIP-01 event signing uses placeholder sigs (secp256k1 pending)
- WebRTC ICE negotiation is scaffolded (real offer/answer exchange pending)
- UI is English-only (Bahasa Indonesia localization pending)
- No unit or integration tests yet
- No CI/CD pipeline

---

[1.0.0-alpha]: https://code.neop2p.io/thesdony/neo-p2p/tree/v1.0.0-alpha
