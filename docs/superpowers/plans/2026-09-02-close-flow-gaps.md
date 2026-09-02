# Close Flow-Analysis Gaps — Dead Trade Room, Buyer Pending Dead-End, Missing Funding Warning, Sweep/Announce Coupling

> Goal: fix the concrete flow gaps found by the 2026-09-02 flow analysis (dead `TRADE_ROOM` destination, buyer's escrow-pending poll falling into a dead-end `Error`, the unused 30-min `FUNDING_WARNING_MS` ladder rung, and the two 60s sweep loops sharing one tick so re-announce pacing can starve behind on-chain calls). Five small, testable code fixes + one documented-as-accepted. No protocol or schema changes.

**Spec sources:** `NavGraph.kt:43,279,318-329`, `EscrowScreen.kt:2622-2631,378-411`, `EscrowService.kt:152-154,594-829`, `P2POrchestrator.kt:680-751`, `EscrowRouter.kt:65-111`, `HomeScreen.kt:1010-1122`, `WalletWatcher.kt:38-40`.

**Global constraints**
- Integer-only money (G.M.01). No `Double` round-trip on money paths.
- No Room schema change, no new LXMF wire types, no new dependencies.
- Router status rules stay forward-only + no-downgrade; nothing here touches the escrow state machine's transitions.
- All tests run from `android/` (`./gradlew :app:testDebugUnitTest`), JDK 21 pinned in `~/.gradle/gradle.properties`.

---

## Root-cause inventory

| # | Cell | Status today | Grounded cause |
|---|------|--------------|----------------|
| 0 | Dead nav destination | real | `Routes.TRADE_ROOM = "trade/{offerId}"` is registered (`NavGraph.kt:279,318`) but **nothing ever navigates to it** — `tradeRoom()` helper has zero call sites; Home routes chat via `Routes.chat` (`NavGraph.kt:127-128`) and escrow via `Routes.escrow`. `TradeRoomScreen` is reachable only by a hand-typed deep link (and `isKnownRoute` in `MainActivity.kt:137` doesn't even whitelist it). Dead code + an undocumented navigation affordance. |
| 1 | Buyer escrow dead-end | real | `EscrowViewModel.loadEscrow` polls `getEscrow` every 5s × 30 attempts (2.5 min) then hard-falls to `UiState.Error("Escrow not found")` (`EscrowScreen.kt:2622-2631`). The buyer's row only arrives via LXMF `escrow_status` sync from the seller's device; a seller that is offline at accept time leaves the buyer staring at an error with only a retry button that re-runs the same 2.5-min poll. The `Pending` state + `EscrowPendingScreen` (`EscrowScreen.kt:378-411`) exist but are abandoned on the 30th miss. |
| 2 | Missing funding warning | real | `FUNDING_WARNING_MS = 30 min` is defined (`EscrowService.kt:154`) and the timeout ladder has `refund_grace_reminder` (`:772-778`) + `payment_grace_reminder` (`:805-811`) via `emitOnce` — but **no FUNDING branch ever emits** `"funding_warning"`; the ladder's first rung is dead. A seller funding slowly gets zero heads-up before the 45-min auto-cancel. |
| 3 | Sweep/announce coupling | real | `sweepStaleEscrows()` (60s tick, `P2POrchestrator.kt:717`) and `rehydrateOfferReannounce()` (same 60s tick, `:748`) share `ESCROW_SWEEP_INTERVAL_MS`. The sweep does on-chain calls (`expireStaleEscrows` → `hasOnChainDeposit` 3×retry with 700ms backoffs, `EscrowService.kt:837-855`) — a slow sweep delays the re-announce set refresh, so a newly created/edited offer can wait minutes to enter the paced announce cycle. No jitter either, so two 60s phases can beat. |
| 4 | `TRADE_ROOM` intent | UNKNOWN → accept | `TradeRoomScreen` appears to be a legacy aggregate screen (offer → chat + escrow). Since Home already provides chat/escrow quick-access targets (`HomeScreen.kt:1158-1187`), the room adds nothing; removing the destination is the fix. |
| 5 | Role-key separation | UNKNOWN → accept | `buyerPubKeyHex == sellerPubKeyHex` in the single-key model (`OfferDetailScreen.kt:1065-1071`, `createSellerEscrow:1161-1167`); roles are peerId-gated (`EscrowService.kt:1831-1838`). Documented, accepted for the demo phase — see `IDENTITY_REWRITE.md` scope. No code this round. |

---

## Task 1 (P0) — Delete the dead `TRADE_ROOM` destination

**Problem:** #0. `trade/{offerId}` is registered but unreachable and unwhitelisted; `TradeRoomScreen` is a legacy aggregate with no navigation entry.

**Design:**
- Remove `Routes.TRADE_ROOM` const + `tradeRoom()` helper (`NavGraph.kt:43,62`).
- Remove the `composable(Routes.TRADE_ROOM)` block (`NavGraph.kt:318-329`).
- Delete `ui/screens/trade/TradeRoomScreen.kt` (its `TradeRoomViewModel` has no other consumers — verified single-file).
- `MainActivity.isKnownRoute` (`MainActivity.kt:137-148`) already excludes it — no change.

**Files:** `navigation/NavGraph.kt`, delete `ui/screens/trade/TradeRoomScreen.kt`.

**Accept:** grep shows zero references to `TRADE_ROOM` / `tradeRoom(` / `TradeRoomScreen`; `./gradlew :app:assembleDebug` and `:app:lintDebug` pass; navigation between Home ↔ OfferDetail ↔ Chat ↔ Escrow unchanged.

---

## Task 2 (P0) — Buyer escrow row: stay `Pending`, never dead-end into `Error`

**Problem:** #1. The 30×5s poll then `Error("Escrow not found")` is a UX dead-end for the legitimate case "seller offline, row not yet synced".

**Design:**
- In `EscrowViewModel.loadEscrow` (`EscrowScreen.kt:2622-2631`): replace the 30-attempt hard fail with an **open-ended bounded wait** — keep polling at a backoff (5s → 15s, cap) while the row is absent, and **remain in `UiState.Pending`** instead of flipping to `Error`.
- Stop the wait the moment any of these lands (all already exist):
  - the escrow row appears in Room (`getEscrow != null`),
  - an `escrowService.transitions` event for this escrowId fires (already collected at `:2593-2597`),
  - the user taps Retry (existing `EscrowPendingScreen` button, `:378-411`).
- Give the Pending screen an honest timeout line: after ~2 min add a hint "seller belum online / row akan muncul saat seller sinkron" (reuse/adapt `escrow_pending_hint` string; add one new string only if the hint text is insufficient).
- The poll must be cancellable when the ViewModel is cleared (guard `viewModelScope`).

**Files:** `EscrowScreen.kt` (`loadEscrow` + `EscrowPendingScreen` hint), `res/values*/strings.xml` (only if a new hint string is needed).

**Accept:** unit-testable via the existing pattern — a fake that returns null for `getEscrow` for N polls: state stays `Pending` (never `Error`) across >30 iterations; state flips to `Success` the poll after the row appears; Retry re-triggers a fresh poll immediately. (Pure logic extracted as `PendingWaitPolicy` if the loop is hard to test inline — see Task 2a.)

---

## Task 2a (P2, do only if Task 2 makes the loop untestable) — Extract the pending-wait decision

**Problem:** the poll loop is embedded in the ViewModel with `delay()` calls; direct unit testing needs a seam.

**Design:** extract a pure `PendingRowWaiter` (or `PendingWaitPolicy`) in `data/escrow/` returning `WAIT / CHECK / GIVE_UP_HINT` given (attempt count, row present), with the backoff schedule as data. ViewModel calls it each tick. Mirrors the `EscrowRouter.applyRemoteStatus` pure-function test style.

**Files:** new `data/escrow/PendingRowWaiter.kt` + test.

**Accept:** unit test covers attempt 0→n: backoff caps at 15s, row-appears terminates, no unbounded `Error` transition.

---

## Task 3 (P1) — Fire the 30-min FUNDING warning (complete the timeout ladder)

**Problem:** #2. `FUNDING_WARNING_MS` is dead; a FUNDING escrow approaching the 45-min auto-cancel gives the seller no heads-up.

**Design:** in `expireStaleEscrows`'s FUNDING branch (`EscrowService.kt:636-719`), before the timeout decision, add the mirror of the other two reminders:

```kotlin
else if (now - entity.created_at > FUNDING_WARNING_MS) {
    emitOnce("funding_warning", entity.escrow_id) {
        _transitions.emit(EscrowTransition(entity.escrow_id, "funding_warning"))
    }
}
```

- `emitOnce` already dedups per process (`:572-578`).
- The orchestrator's notification mapper (`P2POrchestrator.kt:394-436`) gains a `"funding_warning"` case → reuse/extend `notif_escrow_*` strings (add `notif_escrow_funding_warning_title/body` EN + ID).
- `HomeScreen` snackbar mapper (`HomeScreen.kt:186-191`) — decision: **exclude** `funding_warning` from snackbars (notifications only), or add it; pick notifications-only to avoid banner noise.

**Files:** `EscrowService.kt`, `P2POrchestrator.kt`, `res/values*/strings.xml`.

**Accept:** unit test (extend the stale-escrow test): FUNDING escrow aged 31 min emits `funding_warning` exactly once across two sweeps; aged 44 min emits warning once and is NOT cancelled; aged 46 min with no deposit → CANCELLED (unchanged); notification mapping test covers the new key.

---

## Task 4 (P1) — Decouple re-announce sync from the on-chain sweep; add jitter

**Problem:** #3. Two 60s loops share one interval; the sweep's on-chain retries can delay the offer re-announce set, and phase-locking can beat.

**Design:**
- Give `rehydrateOfferReannounce` its own interval constant (`OFFER_REANNOUNCE_SYNC_INTERVAL_MS = 15_000L` — the set only needs to track edits/matches; the *paced announce tick* itself lives in `RnsSession.offerReannounceIntervalMs` and is untouched).
- Start the re-announce sync with `initialDelay = 0` (it currently shares the sweep's first-run-at-0 anyway; keep that) — the change is only the cadence.
- Add ±10% jitter to both loop delays (`sweep`: `60_000 ± 6s`, re-announce sync: `15_000 ± 1.5s`) so two peers' sweeps don't phase-lock into simultaneous on-chain request bursts (politeness to Mempool/Blockstream).

**Files:** `P2POrchestrator.kt` (`sweepStaleEscrows`, `rehydrateOfferReannounce`, constants), `NeoP2PConfig.kt` (optional: expose the sync interval).

**Accept:** code review + `assembleDebug`/`lintDebug`; behavioral assertion via the existing `RnsSession` pacing tests (unchanged) plus a log-line check that re-announce sync runs ≥3× per sweep interval in a short instrumented run. No protocol change — offer feed behavior identical, just fresher.

---

## Task 5 (P2, deferred) — Role-key separation design note (documented as accepted this round)

**Problem:** #5. `buyerPubKeyHex == sellerPubKeyHex` — the 2-of-3 is effectively 1-of-2 keys + arbitrator; role separation is peerId-based only.

**Decision:** **no code this round.** Document in `docs/SECURITY_POSTURE.md` (or the existing accepted-risk section) that:
- In the single-key model a device holding the mnemonic can fill both role slots (`releaseFunds` requires 2 distinct keys, so a *single remote* peer can never release alone; both parties must cooperate — the current property).
- True separation requires per-role derivation (`m/44'/0'/0'/0/1` buyer / `.../0/2` seller style) — tracked under `IDENTITY_REWRITE.md` scope; escrow `buyer_pubkey_hex`/`seller_pubkey_hex` columns already carry two slots, so the schema supports it with no migration.

**Files:** `docs/SECURITY_POSTURE.md` (or `CRITICAL.md`).

**Accept:** doc-only; no behavior change.

---

## Documented as accepted (no code)

- **`TRADE_ROOM` removal risk:** none — no whitelist entry, no call sites (verified by grep).
- **Pending poll energy:** an open-ended 5s→15s Room poll for a foreground screen is negligible (single row read); background battery impact is zero (the sweep already runs regardless).
- **Notification-only funding warning:** grace reminders are notification-only today (`P2POrchestrator.kt:414-419`); keeping `funding_warning` consistent (notification-only, snackbar excluded) is the accepted choice to avoid home-screen banner noise.
- **Role-key separation:** deferred per above; current 2-distinct-key property documented.

---

## Verification

```bash
cd android
./gradlew :app:testDebugUnitTest   # new tests: pending-wait policy, funding-warning emit, notification mapping
./gradlew :app:assembleDebug
./gradlew :app:lintDebug           # baseline unchanged
```
