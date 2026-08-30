# Dispute Flow Fix (P0/P1) — Real Solutions

> **For agents:** Use `superpowers:executing-plans` task-by-task. One commit per task after `./gradlew :app:testDebugUnitTest --console=plain` green. All commands from `android/` workdir, JDK 17 via `~/.gradle/gradle.properties`.

**Goal:** Close 7 double-checked bugs in the dispute path so funds cannot strand, states converge, and arbitration is retry-safe. No new kinds, no DB version bump unless RESOLVING removed (v21->v22 optional, else alias).

**Spec sources:** Double-check 2026-08-30 against `EscrowService.kt:1317/1544/1672/1738`, `P2POrchestrator.kt:437/465/493`, `EscrowRouter.kt:52/68`, `NostrClient.kt:795/857/895`, `DisputeFeedScreen.kt:393/477`, `DisputeEvidenceScreen.kt:339`, `EscrowScreen.kt:2963`, `docs/ARBITRATION.md`, `docs/SECURITY_POSTURE.md`.

**Global constraints**
- Role = `peerId` only (`EscrowRole` `Escrow.kt:98`, Ruling W4). Never pubkey for buyer/seller.
- Relay kinds 33386/33387/33388 only on `custom-minipc.com` (`NostrClient.kt:160`), `verifyEventSignature:372` required.
- Timeouts spec: `FUNDING 45m/30m warn`, `FUNDED 12h+48h grace auto-REFUND`, `PAYMENT 24h+12h grace auto-DISPUTED` (`EscrowService.kt:87/97/108`).
- 2-of-3 P2SH/P2WSH via `assemble2of3Spend:1254` (LEAGACY 220vB/298, SEGWIT 104/176 `BitcoinAddressType.kt:27`).

---

### Task 1: Stranded DISPUTED — publish-then-commit (P0, money safety)

**Problem:** `EscrowScreen.kt:2969` `disputeEscrow()` does ` EscrowService.disputeEscrow() ->DISPUTED+33337:1331` **before** `publishDispute(33386):2985`. If `publishDispute` returns `confirmed.isEmpty():838` the row is already `DISPUTED` locally and shows `UiState.Error:3000` — arbitrator never receives `33386`, stuck.

**Real solution:** Reverse order + idempotent retry. ViewModel builds payload first, publishes `33386`, only on `Result.success` calls `EscrowService.disputeEscrow()`. On publish failure, do not mutate DB. Add a pending-intent retry: store `pending_dispute_<escrowId>` in `SharedPreferences` (or reuse `OfflineQueue`) and `P2POrchestrator.sweepStaleEscrows:555` retries `publishDispute` for any locally `DISPUTED` row lacking a confirmed `33386` ack (tracked via `NostrClient.pendingAcks` or local flag `dispute_published`). `getEscrow:228` heal re-publishes `33337 DISPUTED` already, but `33386` needs its own heal.

**Files**
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt:2963` (reorder, add pending flag)
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt:1317` add `canDisputeFrom(status)` guard (reject `RELEASED/REFUNDED/CANCELLED/ALREADY DISPUTED`), return `Result.failure` without publish
- Modify: `android/app/src/main/java/com/neop2p/service/P2PBackgroundService.kt` or `P2POrchestrator.sweepStaleEscrows` to retry pending disputes every 60s
- Test: `android/app/src/test/java/com/neop2p/data/escrow/EscrowDisputePublishOrderTest.kt` — mock `NostrClient.publishDispute` failure -> `status` stays `FUNDED`, success -> `DISPUTED`

- [ ] Step 1: Add `EscrowService.canDispute(entity)` pure check
- [ ] Step 2: Move `buildDisputeRefundTxHex` + `publishDispute` before `disputeEscrow()` in ViewModel, gate on success
- [ ] Step 3: Add pending-dispute retry in sweep (idempotent: skip if `dispute_published=true`)
- [ ] Step 4: Test + `assembleDebug`

---

### Task 2: Remote dispute guard — fix precedence + cover PAYMENT_PENDING (P0)

**Problem:** `P2POrchestrator.kt:444` `if(local!=null && FUNDED || SIGNED || CONFIRMING)` parses as `(a&&b)||c||d` due to `&&` precedence; misses `FUNDING/PAYMENT_PENDING/RECEIPT_SENT`. Counterparty on `PAYMENT_PENDING` never learns dispute via `33386` (only via `33337` if sweep heals).

**Real solution:** Replace with single rule mirroring `EscrowRouter.applyRemoteStatus:82` (`any non-terminal → DISPUTED`). Pure helper `isDisputable(localStatus)` returns `local!=null && local.status !in TERMINAL && local.status != DISPUTED`. Use parentheses explicitly.

**Files**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt:437-448`
- Test: add `P2POrchestratorDisputeGuardTest` — local `PAYMENT_PENDING` + remote `33386` -> calls `disputeEscrow`, terminal `RELEASED` -> no call

- [ ] Step 1: Extract `fun isDisputable(status:String?):Boolean` (testable)
- [ ] Step 2: Replace buggy `if` with `if(isDisputable(local?.status))`
- [ ] Step 3: Add unit test for all 11 statuses

---

### Task 3: Auto-DISPUTED omits PAYMENT_PENDING (P0)

**Problem:** `EscrowService.kt:535` `when(RECEIPT_SENT/CONFIRMING)` auto-DISPUTEs after `PAYMENT_WINDOW+GRACE`. `PAYMENT_PENDING` (buyer `markPaid:1355` without receipt) falls to `else->{}:566` and never auto-disputes — funds sit `PAYMENT_PENDING` forever if seller stalls.

**Real solution:** Add `PAYMENT_PENDING` to same branch, using `paid_at ?: created_at:541` (already correct fallback). Keep `isSeller`? Auto-DISPUTED is seller-driven timeout but buyer could also trigger; current `isSeller` gate `445` not applied to this branch (intentionally buyer can expire on either device). Keep as-is but include `PAYMENT_PENDING`.

**Files**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt:535` -> `RECEIPT_SENT, CONFIRMING, PAYMENT_PENDING`
- Test: extend `EscrowTimeoutTest.kt:46` — `PAYMENT_PENDING` elapsed `PAYMENT_WINDOW+GRACE+1` -> `DISPUTED`

- [ ] Step 1: One-line fix + test
- [ ] Step 2: Verify `EscrowRouter.applyRemoteStatus` already allows `any->DISPUTED`, so `publishEscrowSync:556` converges

---

### Task 4: Dead RESOLVING — deprecate (P1)

**Problem:** `Escrow.kt:88` `RESOLVING` never written (`grep:328,1735,1757` only reads). UI `EscrowScreen.kt:675/1416`, `HistoryScreen.kt:231`, `Theme.kt:170` render dead branch, `storeArbitrationDecision:1757` accepts it as alias to `DISPUTED`, confusing.

**Real solution (no migration):** Keep enum value for DB compat, mark `@Deprecated("alias to DISPUTED")`, make `storeArbitrationDecision` treat `RESOLVING` as `DISPUTED` (already does), make `applyRemoteStatus:87` accept it, update `SECURITY_POSTURE.md:94` and `ARBITRATION.md` to remove claim. **Alternative v22:** `MIGRATION_21_22: UPDATE escrows SET status='DISPUTED' WHERE status='RESOLVING'` + remove enum value. Choose alias path now (zero migration), schedule v22 removal.

**Files**
- Modify: `android/app/src/main/java/com/neop2p/domain/model/Escrow.kt:88` add `@Deprecated`
- Modify: `docs/SECURITY_POSTURE.md`, `docs/ARBITRATION.md`, `EscrowScreen.kt` comments
- Optional: `AppDatabase.kt:49` `version 22` + `MIGRATION_21_22`

- [ ] Step 1: Annotate + update docs
- [ ] Step 2: (optional) migration + remove UI branches

---

### Task 5: Evidence — ack + persistence (P1)

**Problem:** `DisputeEvidenceScreen.kt:380` `publishEvidence:857` fire-and-forget, `NostrClient.kt:879` no `confirmed` check, `DisputeEvidenceScreen.kt:48` comment says `never published` stale. Arbitrator feed `DisputeFeedScreen.kt:393` in-memory only; reboot loses evidence if relay pruned (<200 or expired).

**Real solution:** 
1. Make `publishEvidence` ack-gated like `publishDispute:838` (`if(confirmed.isEmpty()) return failure`), ViewModel surfaces retry.
2. Arbitrator persists incoming `33387` to `dispute_evidence` via `DisputeEvidenceDao.insert` in `P2POrchestrator.consumeEvidence:465` (currently only notifies `isArb`). Feed then `collect` from `dao.observeEvidenceForEscrow` merged with relay flow, so restart replays from DB + relay last-200.
3. Fix docs: `SECURITY_POSTURE.md:96` change to `stored in SQLCipher AND published to relay (base64, public)`.

**Files**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/NostrClient.kt:857-887`
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt:465` add `evidenceDao` + `isArb` insert
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/DisputeEvidenceScreen.kt:374-386` handle `isFailure` -> `error`
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/DisputeFeedScreen.kt:442` merge DB + relay

- [ ] Step 1: Ack-gate publishEvidence
- [ ] Step 2: Persist arbitrator evidence
- [ ] Step 3: Update docs/comments + test `DisputeEvidenceAckTest`

---

### Task 6: Dispute auth + status guard (P1)

**Problem:** `EscrowService.disputeEscrow:1317` has no status guard (can `DISPUTED` a `RELEASED` would revert) and no `opened_by` check; `P2POrchestrator.consumeDisputes` trusts any `33386` signed by any Nostr key, not just `buyer/seller`.

**Real solution:** 
- Service: `if(current in TERMINAL || current==DISPUTED) return failure("already terminal/disputed")`.
- Orchestrator: `val openedBy = obj["opened_by"]?.content ?: return; if(openedBy != buyerPeerId && openedBy != sellerPeerId) return` (drop spam). Optionally verify `event.pubkey` corresponds to `IdentityManager` trade key for that peer (defer: needs peer->pubkey mapping).

**Files**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt:1317-1339`
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt:440-450`

- [ ] Step 1: Add guards + tests `disputeEscrowGuardTest`

---

### Task 7: Fee/docs alignment (P2)

**Problem:** `SECURITY_POSTURE.md:91` `0.3% (3/1000)` vs `NeoP2PConfig`/`AGENTS.md:51` `0.5% (5/1000)`. Code uses `5/1000`.

**Real solution:** Update `SECURITY_POSTURE.md:91` to `0.5% (5/1000)` + fix `G.M.01` example. Add CI check `grep FEE_NUM` vs docs.

**Files**
- Modify: `docs/SECURITY_POSTURE.md:91`

---

### Task 8: Verification (all tasks)

- `./gradlew :app:testDebugUnitTest` — expect new `EscrowDisputePublishOrderTest`, `P2POrchestratorDisputeGuardTest`, `EscrowTimeoutTest` (PAYMENT_PENDING), `DisputeEvidenceAckTest` green; existing `EscrowRouterApplyTest:27/44/60` still pass (DISPUTED->RELEASED/REFUNDED).
- `./gradlew :app:assembleDebug` — lint baseline unaffected, check `NostrClientTest` still verifies `buildSignedEvent` NIP-01.
- Manual: two-device trade `FUNDED -> seller dispute` -> `33386` visible in log `NostrClient:845` on both, counterparty status flips without `33337`; kill after `markPaid` (PAYMENT_PENDING) -> 60s sweep auto-DISPUTEs; arbitrator feed survives reboot.
- Docs: `ARBITRATION.md:10` table add `refund_tx_hex`, `DisputeEvidenceScreen.kt:48` fix comment, `CHANGELOG.md` entry.

**Rollback:** All changes backward-compatible: `33386` extra fields `deposit_sats/funding_script_type` already optional, `RESOLVING` alias keeps old rows readable, `33387` ack failure only adds error path.
