# NEO-P2P SCENARIO_MATRIX

Date: 2026-09-02 · HEAD: 75cf667
Status legend: UNKNOWN (not exercised) · COVERED (test/design covers) · FAILING (known defect) · N/A (not applicable / removed) · ACCEPTED (documented risk, no code)
Handler = file:line of the code path that handles the scenario.

---

## A. Lifecycle / install

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| A1 | Fresh install: generate identity, wallet, first-run | COVERED | IdentityManager.generateNewIdentity (IdentityManager.kt:186-193), OnboardingGate/OnboardingStore, WalletService. Not end-to-end tested on device in this session. |
| A2 | Restart: identities persist, open orders persist, in-flight trades recover | COVERED | Encrypted blob load (IdentityManager.kt:361-392); Room persists offers/escrows; resume-heal re-publish (EscrowService.kt:262-291). |
| A3 | Kill -9 during TERMS_LOCKED / FUNDED / SETTLED | COVERED (design) | Status persisted BEFORE broadcast (crash-safe); SIGNED zombie fixed (confirmReceipt retries from SIGNED, EscrowService.kt:1527-1533); resume-heal covers FUNDING+txid→RELEASED. Kill between DB persist and LXMF send heals on next getEscrow. |
| A4 | Clock jump forward/back | COVERED | Wall-jump guard >2h + per-entity rollback guard (EscrowService.kt:447-469). Countdown skew between devices documented. |
| A5 | App upgrade with old state files | COVERED | Room v23 migrations (7→23 documented in AGENTS.md); `fallbackToDestructiveMigrationOnDowngrade` (2026-09-02); legacy identity migration (IdentityManager.kt:397-416). |
| A6 | Two instances same identity dir | ACCEPTED | `configDir` is per-app `filesDir`; in-process guard is `AtomicBoolean` (rns-core Reticulum.kt:271). Cross-process sharing needs a copied config dir — out of threat model. |
| A7 | Read-only FS / missing home dir | ACCEPTED | `RnsTransport.start()` propagates failure; `P2POrchestrator.sweepStaleEscrows` retries every 60s (P2POrchestrator.kt:654-658). Transport-down app stays usable; graceful-degradation UI deferred. |

## B. Network formation

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| B1 | Two peers, TCP, mutual announce, link up | COVERED | RnsTwoProcessIntegrationTest (2-JVM TCP, chat + offer_status round-trip). |
| B2 | Three peers, A↔B↔C transport, A trades with C via hop | **COVERED 2026-09-01** | RnsThreePeerTest: one parent + two child JVMs (distinct peerIds), two TCP client interfaces — discovers both, chats + signals with both simultaneously. |
| B3 | Peer never announces | COVERED | send() fails fast "No RNS path" (RnsSession.kt:220-221); OfflineQueue holds chat. |
| B4 | Announce after long delay | COVERED | 20s re-announce (RnsSession.kt:190-195); drain on peerSeen (P2POrchestrator.kt:300-304). |
| B5 | Interface down mid-link | **COVERED 2026-09-01** | RnsFaultInjectionTest: TCP proxy kill mid-conversation → client auto-reconnect + re-announce → failed DIRECT signaling re-sent on next announce (RnsSession.pendingResends). |
| B6 | High latency / jitter / drop | **COVERED 2026-09-01** | RnsLatencyTest: proxy 400ms + 0-300ms jitter per chunk both directions — announce/path/link/chat/signaling all complete. |
| B7 | Asymmetric connectivity | ACCEPTED | Client-only phones over the VPS transport node is the shipped architecture; RnsThreePeerTest exercises transport-mediated routing. |
| B8 | Dest hash typo / truncated hash in UI | N/A | UI addresses peers by peerId (invite QR), not raw dest hash. |
| B9 | Max concurrent links / many noisy peers | **COVERED 2026-09-01** | RnsLoadTest: 30-offer flood ingested; fork caps verified (see J1). |
| B10 | IPv6 / IPv4 / mixed | ACCEPTED | `TCPClientInterface` resolves the DNS host; literal-v6 test low value vs cost. |
| B11 | RNS interfaces on device | COVERED | Only TCPClientInterface to VPS (RnsSession.kt:133-146); AutoInterface not used on device. |

## C. Discovery & orders

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| C1 | Advertise order, peer sees it | COVERED | trackOfferDigest → paced 2.5s re-announce loop (RnsSession) → announce handler → offer_request/offer round-trip (P2POrchestrator.kt:222-266). In-JVM test: RnsSessionTest offer-announce cases. |
| C2 | Multiple orders, filter by pair/amount/method | COVERED | HomeScreen filters; digest carries f/m/s fields. |
| C3 | Cancel locally; remote stale cache | COVERED | DeletedOfferStore tombstone + ingest skip (OfferRouter.kt:283-287); terminal statuses never resurrect (OfferRouter.kt:304-328). |
| C4 | Order TTL expire | COVERED | expires_at (DB v21), claim gate past expiry, stale badge (HomeScreen.kt:814-823). |
| C5 | Malformed offer payload | **COVERED 2026-09-01** | Json parse guarded (OfferRouter.kt:266-267, 434-444); missing fields default (OfferRouter.kt:330-362). **Field-level gate added:** `isValidOfferPayload` clamps `crypto_amount_sats`/`fiat_amount`/`price_per_unit`/`fiat_methods` (OfferRouter.kt, NeoP2PConfig) — LXMF caps bound the container, field caps bound the money (C5+D8). OfferRouterIngestValidationTest. |
| C6 | Replay old advertise | COVERED | No-downgrade (OfferRouter.kt:304-328); digest re-announce skipped when offer known (P2POrchestrator.kt:262). |
| C7 | Two peers advertise identical terms | COVERED | offerId distinct; no dedup issue. |
| C8 | Peer advertises then goes offline | COVERED | send fails fast → queue; **offer-feed re-announce fixed 2026-09-01** (one-shot announce defect: peers joining later / seller restarts were undiscoverable — now a paced re-announce loop, RnsSession + P2POrchestrator.rehydrateOfferReannounce). **2026-09-01 (Bug B):** the one-shot `publishOffer` at create/edit was removed — the paced 2.5s loop owns all feed announces (the duplicate-in-30s-window risk is gone). LXMF propagation node for offline (documented). |
| C9 | Rate flood of fake orders | **COVERED (fork)** | rns-core has announce rate limiting (MAX_RATE_TIMESTAMPS=16/dest/30s) + ingress burst detection hooks (Transport.kt); app-level flood test ABSENT. |
| C10 | Unicode / RTL / long notes | **COVERED 2026-09-01** | Nickname clamped at write (IdentityManager.updateNickname → sanitizeNickname, 32 chars + control-char strip) and at ingest (OfferRouter). No notes field. |

## D. Negotiation

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| D1 | Happy path accept | COVERED | applyOfferStatus (OfferRouter.kt:104-235) + OfferClaimGateTest. |
| D2 | Counter-offer then accept | N/A | No counter-offer protocol (accept-only). |
| D3 | Simultaneous accept race | COVERED | OfferClaimGate.adoptMatchedPeer (OfferClaimGate.kt:93-109) + tests. |
| D4 | Negotiate then ghost | COVERED | Funding timeout auto-cancel (EscrowService.kt:481-543); payment window auto-dispute (569-601). |
| D5 | Terms change after lock | COVERED | Locked offers immutable (no-downgrade; edit gated to OPEN/PAUSED). |
| D6 | Version skew | ACCEPTED | Digest carries `v:1`; a mismatched `v` drops the digest cleanly (RnsOfferDigest.kt:82). Negotiation deferred until a second wire version exists. |
| D7 | Unsupported asset | COVERED | CryptoAsset.BTC only; OfferType.valueOf guarded. |
| D8 | Min/max/zero/negative/NaN/overflow | **COVERED 2026-09-01** | parseIdrToLong rejects non-whole; integer money (G.M.01); fee floor MIN_FEE_SATS. Overflow closed by construction: `isValidOfferPayload` clamps ingest magnitudes (C5) so Long money math can't overflow. |
| D9 | Locale/decimal comma vs dot | COVERED | parseIdrToLong + FiatFormat tests. |

## E. Funding & settlement

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| E1 | Seller-funded path | COVERED | createEscrow (EscrowService.kt:673-775); onEscrowFunded (835-917). |
| E2 | Correct amount + confs → SETTLED both sides | COVERED | findFundingOutput binding (125-131); conf depth from tip (ChainMonitor.kt:64-82); EscrowFundingBindingTest, ChainMonitorTxInfoTest. |
| E3 | Underpay / overpay / pay to previous address | COVERED | At-least-amount binding accepts overpayment and records the ACTUAL on-chain value (`findFundingOutputAtLeast`/`fundedValueSats`, EscrowService.kt); the excess is returned to the seller by the payout/refund paths (never kept as fee). **Underpayment (2026-09-04):** a partial deposit is persisted (`findFundingOutputAny`/`fundedValueAny`) so the seller can Cancel & Refund it; the sweep never auto-cancels or promotes a partial deposit. Wrong address still rejected. EscrowOverpaymentTest + EscrowUnderpaymentTest. |
| E4 | Double spend / RBF bump | **COVERED 2026-09-01** | Sweep re-binds a dropped funding txid to the RBF replacement (exact-deposit address search) before cancelling; EscrowRebindTest + rebindFundingTxId. **Depth re-check added:** `fundingRefundDecision` reverts to FUNDING when a reorg shaves confirmed depth below `required_confirmations` while the address is still funded (EscrowService + EscrowReorgTest). |
| E5 | Wrong chain | COVERED | NETWORK=testnet; explorers testnet4; TestNet3Params vs testnet4 schism documented (works via shared base58/HRP). |
| E6 | Confirmations stall | COVERED | FUNDING+txid sync immediately; sweep promote-to-FUNDED with on-chain deposit check (EscrowService.kt:489-505). |
| E7 | Reorg un-confirms after FUNDED | **COVERED 2026-09-01** | Sweep re-verifies the funding tx before auto-refund: unconfirmed + no address balance → revert to FUNDING (re-verify/cancel path); explorer failure fails closed (skip). EscrowReorgTest + EscrowService.fundingDepositGone. |
| E8 | User pastes invalid txid | COVERED | getTxInfo failure → explicit error (EscrowService.kt:841-846). |
| E9 | Timeout before funding | COVERED | 45min auto-cancel + deposit check (EscrowService.kt:481-543); EscrowTimeoutTest. |
| E10 | Timeout after funding before confs | COVERED | 12h+48h auto-refund (545-568). |
| E11 | One peer SETTLED, other WAITING (desync) | COVERED | EscrowRouter forward-only + terminal acceptance (EscrowRouter.kt:65-111); resume-heal re-publish; EscrowRouterApplyTest. |
| E12 | Manual "I paid" lie without chain evidence | COVERED | markPaid is fiat-side (no chain claim); release gate = seller confirmReceipt ONLY (EscrowService.kt:139-140, 1512-1592). |

## F. Cancel / dispute / abort

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| F1 | Cancel before lock | COVERED | Delete gated OPEN/PAUSED (T13); tombstone. |
| F2 | Cancel after lock before funding | COVERED | Auto-cancel FUNDING (EscrowService.kt:481-543) + offer marked CANCELLED + sync. |
| F3 | Abort after funding (refund path) | COVERED | cancelEscrowRefund (2064-2112) seller-gated; auto-refund (650-664); EscrowRefundSigningTest. |
| F4 | Conflicting abort vs settle in flight | COVERED | Terminal lock: first terminal wins (EscrowRouter.kt:75); status persisted before broadcast. |
| F5 | Dispute evidence bundles | COVERED | LXMF dispute/evidence/resolution; arbitrator_disputes Room v23 (buyer/seller peerIds for resolution delivery); EscrowArbitrationResolutionTest; DisputeRedeliveryGateTest. |
| F6 | User force-close UI; protocol consistent | COVERED | Persisted statuses; resume-heal; PendingDisputeStore retry. |

## G. Anonymity / metadata

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| G1 | No plaintext amounts/assets on wire | **FIXED 2026-09-01** | Offer digest is now a commitment only `{v,id,h}` (RnsOfferDigest.kt); full public subset (incl. fiat_methods + nickname) rides encrypted LXMF via canonicalJson; fetched JSON verified against commitment before ingest (P2POrchestrator). Tests: RnsOfferDigestTest G1-invariant + tamper tests. |
| G2 | Logs do not write full keys | COVERED | No mnemonic/privkey in logs (grep-verified). peerId (public pseudonym) + 12-hex dest prefixes logged. |
| G3 | Screenshot mode / hide amounts | N/A | No screenshot-mode feature. |
| G4 | Same identity reused across trades — linkability | COVERED (documented) | peerId stable across all trades by design; per-trade keys dead code. Documented in DEBUG_MAP §5. |
| G5 | Distinct identities per trade | N/A | Feature removed with Nostr (PATH_NOSTR_TRADE_PREFIX unused). |

## H. UI / UX flows

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| H1 | First-run wizard | COVERED | OnboardingScreen + persisted onboarding_complete gate. |
| H2 | Restore identity from backup / fail backup | COVERED | restoreFromSeedPhrase (IdentityManager.kt:129-149) + RestoreGuard + checksum validation. |
| H3 | Send vs receive from each role | COVERED | peerId role gating (EscrowService.kt:1600-1607); EscrowStepRolesTest. |
| H4 | Background/foreground | COVERED | P2PBackgroundService (WorkManager) + AppForegroundTracker. |
| H5 | Notification when funded while backgrounded | COVERED | NotificationDispatcher + persistent dedup (P2POrchestrator.kt:93-101). |
| H6 | Accessibility: color-only status | COVERED | T23 light-theme contrast + contentDescription audit. |
| H7 | Copy dest hash / QR / corrupt QR | COVERED | InviteScreen QR (zxing), neop2p:// parse, invalid-QR error card. |

## I. Security abuse

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| I1 | Mutated JSON after valid header | COVERED | Json parse guarded everywhere; missing fields default; malformed digest dropped (RnsOfferDigest.kt:51-56). |
| I2 | Sequence rewind / gap / duplicate | COVERED | No sequence numbers — idempotency via status no-downgrade + ciphertext dedup + terminal lock. Replay of old statuses rejected (OfferClaimGate, EscrowRouter). |
| I3 | Cross-flow_id mixup | COVERED | escrow_id/offer_id carried in every payload; party gate (EscrowRouter.kt:145). |
| I4 | Peer claims SETTLED with fake receipt | COVERED | Receipt is advisory; release gate = seller confirmReceipt (E12). |
| I5 | Huge payload DoS | **COVERED 2026-09-01** | Inbound caps: custom data ≤256KB, files ≤512KB (RnsSession), evidence base64 ≤80KB pre-decode (P2POrchestrator). RnsSessionTest oversized-drop tests. |
| I6 | Pathological unicode in handles | **COVERED 2026-09-01** | Nickname length/content capped at write (IdentityManager.sanitizeNickname, 32 chars, control-char strip) + ingest (OfferRouter). OfferRouterIngestValidationTest. |
| I7 | Command injection in shell-outs | N/A | No shell-outs to wallet binaries. |
| I8 | Path traversal in identity/storage paths | COVERED | configDir = context.filesDir.resolve("reticulum") — fixed path, no user input. |
| I9 | Untrusted peer data into eval/exec/SQL | COVERED | Room parameterized; no eval/exec; JSON parsed with kotlinx/org.json only. |
| I10 | Timing: unknown dest vs known | ACCEPTED | send() fails fast locally for unknown dests (RnsSession.kt:256-257); over-the-wire RNS hides source/identity. Only a same-device observer sees it. |

## J. Ops / performance

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| J1 | 100 open ads on one peer | **COVERED 2026-09-01** | RnsLoadTest (30-offer flood ingested) + paced re-announce loop (RnsSession, **2.5s tick**). **Findings:** the fork's path admission drops same-second re-announces to one destination (second-granular announce timebase) AND rate-limits to MAX_RATE_TIMESTAMPS=16/dest/30s — a same-second burst collapses; pacing is mandatory. 2.5s = 12/30s (~25% headroom; 1.5s dropped 8×, 2.0s clean, 2.5s is the app tick). 100 offers cycle in ~4 min. RnsLoadTest asserts zero rate-limit drops at the production cadence. |
| J2 | Long-running soak | **COVERED 2026-09-01** | RnsSoakTest: bounded accelerated-clock soak (chat + offer_status round-trips) with per-iteration fd/heap sampling; a longer soak is the same main with a larger iteration count. |
| J3 | Memory growth / fd leaks | **COVERED 2026-09-01** | Soak/load instrument /proc/self/fd + heap across the window (RnsSoakTest, RnsLoadTest) — seed-level J3. |
| J4 | CPU on announce storms | **COVERED (fork)** | Outbound announce rate limiting in rns-core (MAX_RATE_TIMESTAMPS); ingress limiting hooks exist but TCP interfaces don't implement them — storm test ABSENT. |

---

## Summary

- COVERED: 50 · UNKNOWN: 0 · FAILING: 0 · N/A: 6 · ACCEPTED: 6
- **Remaining unknowns: none.** S30 (resource fault — needs fork seams) and S62 (disk full) documented in the 2026-09-01 analysis as ABSENT.
- **Harness (2026-09-01)**: RnsFaultProxy (TCP relay with kill + delay + jitter) + RnsFaultInjectionTest (2-JVM flap) + RnsLatencyTest (2-JVM 400ms+jitter) + RnsThreePeerTest (1 parent + 2 children) + **RnsLoadTest (30-offer flood, fd/heap instrumented) + RnsSoakTest (accelerated-clock soak)** + RnsTwoProcessFlapServerMain. Port ranges disjoint (20000-39999 / 40000-59999 / 50000-64999), identity seeds unique per test (+7/+13/+23/+29/+31/+37/+41); child mains use a buffered channel collector + type-classified receive (LXMF delivery order is not guaranteed). Full suite: 323 tests, 0 failures (2026-09-02).
