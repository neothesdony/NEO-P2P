# Dispute Feed Empty — Fix-All Plan

> Goal: make a created dispute reliably appear in `DisputeFeed` (arbitrator) regardless of timing, relay, or reboot. Closes the 6 kill-points found in 2026-08-31 investigation. No new Nostr kinds, no fee change, Room bump only if `RESOLVING` removed (opt. v22).

**Spec sources:** `DisputeFeedScreen.kt:393/410/461`, `NostrClient.kt:239/295/513/795/954`, `EscrowScreen.kt:2963`, `EscrowService.kt:1319/1554/1682/1748`, `P2POrchestrator.kt:451/484/552`, `EscrowRouter.kt:52/68`, `NeoP2PConfig.kt:54/68`, `IdentityManager.kt:54/539`, `NavGraph.kt:52/312`, `SettingsScreen.kt:316`, `docs/ARBITRATION.md:10`, `2026-08-30-dispute-flow-fix.md`.

**Global constraints**
- Kinds `33386/33387/33388` only on `custom-minipc.com` `NostrClient.kt:160`, all events `verifyEventSignature:372`.
- Role gate = `peerId` only `Escrow.kt:102 Ruling W4`.
- Timeouts: `FUNDING 45m/30m`, `FUNDED 12h+48h`, `PAYMENT 24h+12h` `EscrowService.kt:87/97/108`.
- 2-of-3 via `assemble2of3Spend:1254`.

---

### Root cause inventory (why feed stays `Success(empty)` `strings.xml:691`)

| # | Kill point | File:line | Symptom in feed |
|---|------------|-----------|-----------------|
| 1 | Publish never confirmed | `NostrClient.kt:837-841` `if(confirmed.isEmpty()) failure`, `publishToConnectedRelays:958-959 empty if no isConnected/scope/null/timeout 5s` | Opener shows `Error "No relay confirmed"` `EscrowScreen.kt:2991`, no `33386` on relay, `NostrClient:845 Dispute published` never logs |
| 2 | Subscription missing | `NostrClient.kt:239,295` arbitration REQ only if `isCustomRelay` | All custom relays `isConnected=false` (Settings relay list, `NostrClient:232 Connected`) → no `Received dispute:521` ever |
| 3 | Signature dropped | `handleNostrMessage:372-374` `Rejected invalid signature` before `KIND_DISPUTE:513` | `publishDispute:831` signed with `getNostrKeyPair()` mismatch → silent drop |
| 4 | Not arbitrator | `DisputeFeedViewModel:414-421` `getArbitratorPubKeyHex()==ARBITRATOR_PUBKEY` `NeoP2PConfig:54` | Feed shows `Error "This identity is not the arbitrator"` not `empty`; user misreads as empty |
| 5 | In-memory loss | `DisputeFeedScreen.kt:393-398` `LinkedHashMap` no DB, `P2POrchestrator.consumeEvidence:532 isArb notify` only, feed not merged | Reboot/relay prune (<200 or expired) → empty again, `refresh():404` is no-op (`Loading`→`publishState` same maps) |
| 6 | Local-vs-relay confusion | `EscrowService.disputeEscrow:1339` writes `33337` DISPUTED, feed ignores `33337` (`EscrowRouter`) | Local row `DISPUTED` but feed empty — expected (feed needs `33386`) |

---

### Task 0 — Observability (P0, 0.5d, unblock debugging)
**Problem:** no UI to distinguish 1/2/4.

- Add `DisputeFeedViewModel` fields `isArb:Boolean`, `relayConnected:Int`, `lastSyncAt:Long`, `lastError:String?`; surface in top bar.
- `DisputeFeedScreen.kt:50` header: `if(!isArb) Error`, else subtitle `Relays 2/4 · synced 10s ago` from `nostrClient.relays:69` flow.
- Log `NostrClient:521 Received dispute`, `838 No relay confirmed`, `372 Rejected` already exist — add `DisputeFeedViewModel:461 publishState` log `disputes.size`.
- **Files:** `DisputeFeedScreen.kt:361/393`, `NostrClient.kt:66`
- **Accept:** arbitrator sees relay count; non-arbitrator sees explicit error, not empty.

### Task 1 — Fix `refresh()` + add DB-merge persistence (P0, 1.5d)
**Problem:** 5 + `refresh` no-op.

- Create `ArbitratorDisputeEntity` (`dispute_id=escrowId PK`, `opened_by`, `reason`, `redeem`, `psbt_hex`, `refund_hex`, `deposit`, `scriptType`, `sellerRefund`, `received_at`) Room `version 21→22` + `MIGRATION_21_22`.
- `NostrClient.handleNostrMessage:513` **and** `P2POrchestrator.consumeDisputes` both upsert the entity (so arbitrator without escrow row still persists).
- `DisputeFeedViewModel.collect()` merges `disputeDao.observeAll()` (DB) + `nostrClient.disputes` (live) → `disputes` map seeded from DB before live collect; `evidenceMap` merges `DisputeEvidenceDao` (already persisted by `P2POrchestrator:513`) + `nostrClient.evidence`.
- Fix `refresh():404` to `nostrClient.reconnect()` or `disputeDao` reload, not just `publishState`.
- Keep `limit 200` REQ; DB survives prune.
- **Files:** `AppDatabase.kt:22,133`, `ArbitratorDisputeDao.kt` (new), `DisputeFeedScreen.kt:396/442/461`, `NostrClient.kt:520`, `P2POrchestrator.kt:454`
- **Accept:** kill app after dispute → reopen feed shows same dispute without relay; `adb shell dumpsys` DB row exists.

### Task 2 — Retry for ack-gated `33386` (P0, 1d) — extends 2026-08-30 Task1
**Problem:** 1 — `publishToConnectedRelays` empty = lost dispute.

- Already fixed order `EscrowScreen.kt:2969 publishDispute before disputeEscrow`. Add pending retry:
  - On `publishDispute isFailure`, write `SharedPreferences pending_dispute_<escrowId>=json(payload)`.
  - `P2POrchestrator.sweepStaleEscrows:614` (60s) retries `publishDispute` for pending keys; on `confirmed not empty` deletes key and calls `disputeEscrow` if still disputable (`isDisputableStatus:441`).
  - `publishToConnectedRelays` log `W Relay X did not ack` already `973` — keep 5s timeout but retry 3× with 1s backoff.
- **Files:** `EscrowScreen.kt:2979`, `P2POrchestrator.kt:451/614`, `EscrowService.kt:1319`
- **Accept:** airplane on → dispute → `Error not opened` → airplane off → 60s later `Received dispute` both devices.

### Task 3 — Relay health & subscription guard (P1, 0.5d)
**Problem:** 2.

- `NostrClient.connectWithBackoff:216` already exp-backoff `1s→60s jitter`. Add `arbitration` REQ resubscribe on reconnect (already in `232` block, but not after `EOSE`). Add `onRelayConnected` callback to re-emit pending disputes.
- Show in feed: if `connectedCustom==0` banner `No custom relay connected — disputes cannot propagate` (instead of empty).
- **Files:** `NostrClient.kt:239/295`, `DisputeFeedScreen.kt:81` `Loading/Error/Success`
- **Accept:** kill `relay1.custom-minipc.com` → banner appears; restart relay → disputes replay.

### Task 4 — Per-card busy + sorting (P1, 0.5d)
**Problem:** global `busy:381` blocks all cards.

- Change `resolve:483 if(_busy.value) return` to per-escrow `busySet:Set<String>`; `busy` derived `busySet.isNotEmpty()`. Keep `_busyEscrowId` for spinner.
- Sort `publishState:465 disputes.values.sortedByDescending { it.openedAt }`.
- Surface `openedAt` in `DisputeCard:213` (`SimpleDateFormat`) and `deposit/sellerRefund` for audit.
- **Files:** `DisputeFeedScreen.kt:381/483/465/205`

### Task 5 — Signature/auth logging (P2, 0.5d)
**Problem:** 3 silent drop.

- In `handleNostrMessage:372` log `pubkey` + `id` on reject; in `publishDispute:831` log `effectivePub` prefix.
- In `P2POrchestrator.consumeDisputes:463` already drops `openedBy not party` with `Log.w` — add same drop metric to feed (show `filtered:2` in header for arbitrator debug).
- **Files:** `NostrClient.kt:372/831`, `P2POrchestrator.kt:463`

### Task 6 — Dead `RESOLVING` alias (P2, 0.5d)
- Annotate `Escrow.kt:90 @Deprecated("alias to DISPUTED")`, keep `storeArbitrationDecision:1767` handling, update `SECURITY_POSTURE.md:94`. Optional `MIGRATION_21_22 UPDATE escrows SET status='DISPUTED' WHERE status='RESOLVING'` if bumping anyway for Task1.
- **Files:** `Escrow.kt:90`, `docs/SECURITY_POSTURE.md`, `AppDatabase.kt`

---

### Verification
- Unit: `DisputeFeedMergeTest` (DB seeded → VM shows), `PublishRetryTest` (no relay → pending → connected → published), `IsDisputableTest` (11 statuses), `AckGateTest` (`confirmed.isEmpty→failure`).
- Instrumented: `EscrowScreenDisputePublishOrderTest` (mock `publishDispute failure→status stays FUNDED`).
- Manual two-device: A (seller) `FUNDING→FUNDED` (`onEscrowFunded` needs 1 conf), B `PAYMENT_PENDING` → A `Dispute` (airplane off: success → both DISPUTED + arbitrator feed shows card; airplane on: pending → feed empty → airplane off → 60s → feed appears). Reboot arbitrator → feed still shows.
- Commands (from `android/`): `env -u ANDROID_PREFS_ROOT ./gradlew :app:testDebugUnitTest --console=plain` green; `assembleDebug` no lint baseline change.

**Rollback:** DB merge additive (new table), retry flag deletable; no kind change, no fee change; old builds ignore extra DB table.
