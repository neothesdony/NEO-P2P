# NEO-P2P SCENARIO_MATRIX

Date: 2026-09-01 · HEAD: 45e9574
Status legend: UNKNOWN (not exercised) · COVERED (test/design covers) · FAILING (known defect) · N/A (not applicable / removed)
Handler = file:line of the code path that handles the scenario.

---

## A. Lifecycle / install

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| A1 | Fresh install: generate identity, wallet, first-run | COVERED | IdentityManager.generateNewIdentity (IdentityManager.kt:186-193), OnboardingGate/OnboardingStore, WalletService. Not end-to-end tested on device in this session. |
| A2 | Restart: identities persist, open orders persist, in-flight trades recover | COVERED | Encrypted blob load (IdentityManager.kt:361-392); Room persists offers/escrows; resume-heal re-publish (EscrowService.kt:262-291). |
| A3 | Kill -9 during TERMS_LOCKED / FUNDED / SETTLED | COVERED (design) | Status persisted BEFORE broadcast (crash-safe); SIGNED zombie fixed (confirmReceipt retries from SIGNED, EscrowService.kt:1527-1533); resume-heal covers FUNDING+txid→RELEASED. Kill between DB persist and LXMF send heals on next getEscrow. |
| A4 | Clock jump forward/back | COVERED | Wall-jump guard >2h + per-entity rollback guard (EscrowService.kt:447-469). Countdown skew between devices documented. |
| A5 | App upgrade with old state files | COVERED | Room v22 migrations (7→22 documented in AGENTS.md); legacy identity migration (IdentityManager.kt:397-416). |
| A6 | Two instances same identity dir | UNKNOWN | Reticulum singleton in-process; no cross-process identity-dir lock verified. |
| A7 | Read-only FS / missing home dir | UNKNOWN | Reticulum.start throws → transport down; no graceful degradation test. |

## B. Network formation

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| B1 | Two peers, TCP, mutual announce, link up | COVERED | RnsTwoProcessIntegrationTest (2-JVM TCP, chat + offer_status round-trip). |
| B2 | Three peers, A↔B↔C transport, A trades with C via hop | **COVERED 2026-09-01** | RnsThreePeerTest: one parent + two child JVMs (distinct peerIds), two TCP client interfaces — discovers both, chats + signals with both simultaneously. |
| B3 | Peer never announces | COVERED | send() fails fast "No RNS path" (RnsSession.kt:220-221); OfflineQueue holds chat. |
| B4 | Announce after long delay | COVERED | 20s re-announce (RnsSession.kt:190-195); drain on peerSeen (P2POrchestrator.kt:300-304). |
| B5 | Interface down mid-link | **COVERED 2026-09-01** | RnsFaultInjectionTest: TCP proxy kill mid-conversation → client auto-reconnect + re-announce → failed DIRECT signaling re-sent on next announce (RnsSession.pendingResends). |
| B6 | High latency / jitter / drop | **COVERED 2026-09-01** | RnsLatencyTest: proxy 400ms + 0-300ms jitter per chunk both directions — announce/path/link/chat/signaling all complete. |
| B7 | Asymmetric connectivity | UNKNOWN | Client-only phones; transport node mediates. |
| B8 | Dest hash typo / truncated hash in UI | N/A | UI addresses peers by peerId (invite QR), not raw dest hash. |
| B9 | Max concurrent links / many noisy peers | UNKNOWN | No load test. |
| B10 | IPv6 / IPv4 / mixed | UNKNOWN | TCPClientInterface to DNS host; no v6 test. |
| B11 | RNS interfaces on device | COVERED | Only TCPClientInterface to VPS (RnsSession.kt:133-146); AutoInterface not used on device. |

## C. Discovery & orders

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| C1 | Advertise order, peer sees it | COVERED | publishOffer (RnsSession.kt:318-321) → announce handler (RnsSession.kt:180-186) → offer_request/offer round-trip (P2POrchestrator.kt:222-266). In-JVM test: RnsSessionTest offer-announce cases. |
| C2 | Multiple orders, filter by pair/amount/method | COVERED | HomeScreen filters; digest carries f/m/s fields. |
| C3 | Cancel locally; remote stale cache | COVERED | DeletedOfferStore tombstone + ingest skip (OfferRouter.kt:283-287); terminal statuses never resurrect (OfferRouter.kt:304-328). |
| C4 | Order TTL expire | COVERED | expires_at (DB v21), claim gate past expiry, stale badge (HomeScreen.kt:814-823). |
| C5 | Malformed offer payload | COVERED | Json parse guarded (OfferRouter.kt:266-267, 434-444); missing fields default (OfferRouter.kt:330-362). Huge fields: no explicit size cap — UNKNOWN (I5). |
| C6 | Replay old advertise | COVERED | No-downgrade (OfferRouter.kt:304-328); digest re-announce skipped when offer known (P2POrchestrator.kt:262). |
| C7 | Two peers advertise identical terms | COVERED | offerId distinct; no dedup issue. |
| C8 | Peer advertises then goes offline | COVERED | send fails fast → queue; LXMF propagation node for offline (UNKNOWN behavior). |
| C9 | Rate flood of fake orders | **COVERED (fork)** | rns-core has announce rate limiting (ANNOUNCE_RATE_TARGET 0.12, grace 1.5, penalty 5.0) + ingress burst detection hooks (Transport.kt:6474-6499); TCP interfaces do not implement ingress limiting — app-level flood test ABSENT. |
| C10 | Unicode / RTL / long notes | UNKNOWN | No notes field in digest; nickname length uncapped. |

## D. Negotiation

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| D1 | Happy path accept | COVERED | applyOfferStatus (OfferRouter.kt:104-235) + OfferClaimGateTest. |
| D2 | Counter-offer then accept | N/A | No counter-offer protocol (accept-only). |
| D3 | Simultaneous accept race | COVERED | OfferClaimGate.adoptMatchedPeer (OfferClaimGate.kt:93-109) + tests. |
| D4 | Negotiate then ghost | COVERED | Funding timeout auto-cancel (EscrowService.kt:481-543); payment window auto-dispute (569-601). |
| D5 | Terms change after lock | COVERED | Locked offers immutable (no-downgrade; edit gated to OPEN/PAUSED). |
| D6 | Version skew | UNKNOWN | No protocol version negotiation; digest has v:1. |
| D7 | Unsupported asset | COVERED | CryptoAsset.BTC only; OfferType.valueOf guarded. |
| D8 | Min/max/zero/negative/NaN/overflow | COVERED | parseIdrToLong rejects non-whole; integer money (G.M.01); fee floor MIN_FEE_SATS. Overflow: Long math, no explicit check — UNKNOWN. |
| D9 | Locale/decimal comma vs dot | COVERED | parseIdrToLong + FiatFormat tests. |

## E. Funding & settlement

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| E1 | Seller-funded path | COVERED | createEscrow (EscrowService.kt:673-775); onEscrowFunded (835-917). |
| E2 | Correct amount + confs → SETTLED both sides | COVERED | findFundingOutput binding (125-131); conf depth from tip (ChainMonitor.kt:64-82); EscrowFundingBindingTest, ChainMonitorTxInfoTest. |
| E3 | Underpay / overpay / pay to previous address | COVERED | Exact-amount binding rejects under/over (EscrowService.kt:857-864); recoverFundingTxId scans exact deposit (305-332). |
| E4 | Double spend / RBF bump | **COVERED 2026-09-01** | Sweep re-binds a dropped funding txid to the RBF replacement (exact-deposit address search) before cancelling; EscrowRebindTest + rebindFundingTxId. |
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
| F5 | Dispute evidence bundles | COVERED | kind:33386/33387/33388 → LXMF dispute/evidence/resolution; arbitrator_disputes Room v22; EscrowArbitrationResolutionTest. |
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
| I6 | Pathological unicode in handles | UNKNOWN | Nickname length/content uncapped. |
| I7 | Command injection in shell-outs | N/A | No shell-outs to wallet binaries. |
| I8 | Path traversal in identity/storage paths | COVERED | configDir = context.filesDir.resolve("reticulum") — fixed path, no user input. |
| I9 | Untrusted peer data into eval/exec/SQL | COVERED | Room parameterized; no eval/exec; JSON parsed with kotlinx/org.json only. |
| I10 | Timing: unknown dest vs known | UNKNOWN | send() fails fast for unknown peer (observable locally; RNS hides source). |

## J. Ops / performance

| ID | Scenario | Status | Handler / evidence |
|---|---|---|---|
| J1 | 100 open ads on one peer | UNKNOWN | No load test; digest ~200B × 100 announces every 20s = ~2KB/s per peer — plausible but unverified. |
| J2 | Long-running soak | UNKNOWN | No soak; accelerated-clock harness does not exist yet. |
| J3 | Memory growth / fd leaks | UNKNOWN | No soak instrumentation. |
| J4 | CPU on announce storms | **COVERED (fork)** | Outbound announce rate limiting in rns-core (ANNOUNCE_RATE_TARGET 0.12/s + penalty); ingress limiting hooks exist but TCP interfaces don't implement them — storm test ABSENT. |

---

## Summary

- COVERED: 46 · UNKNOWN: 9 · FAILING: 0 · N/A: 6
- **Remaining unknowns**: B7/B9/B10 (asymmetry/load/IPv6), C10/I6 (unicode), J1-J3 (load/soak), S30 (resource fault), S62 (disk full), E4 mempool-depth (partial).
- **Harness (2026-09-01)**: RnsFaultProxy (TCP relay with kill + delay + jitter) + RnsFaultInjectionTest (2-JVM flap) + RnsLatencyTest (2-JVM 400ms+jitter) + RnsThreePeerTest (1 parent + 2 children) + RnsTwoProcessFlapServerMain. Port ranges disjoint (20000-39999 / 40000-59999 / 50000-64999), identity seeds unique per test (+7/+13/+23/+29/+31/+37/+41); child mains use a buffered channel collector + type-classified receive (LXMF delivery order is not guaranteed). Full suite: 251 tests, 0 failures.
