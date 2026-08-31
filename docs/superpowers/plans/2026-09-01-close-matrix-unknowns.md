# Close Remaining Matrix Unknowns — Offer Re-announce, Field/Nickname Caps, E4 Depth, Load/Soak Harness

> Goal: close the UNKNOWN/partial cells of `SCENARIO_MATRIX.md` (2026-09-01) that are real gaps — and, while grounding them in code, fix the one **undocumented defect** found: the offer feed is announced **once per create/edit and never re-announced**. Four code fixes + one test harness. Items that are inherent or accepted risk are documented, not coded.

**Spec sources:** `SCENARIO_MATRIX.md:18-19,31-33,45-48,61-63,129-137`, `RnsSession.kt:226-231,327-357`, `RnsTransport.kt:58-101,126-129`, `CreateOfferScreen.kt:827,938`, `OfferRouter.kt:266-362`, `P2POrchestrator.kt:230-283`, `IdentityManager.kt:175-181`, `ChainMonitor.kt:226-246`, `EscrowService.kt:617-659`, `Models.kt:29-35`, `OnboardingScreen.kt:338`, `RnsOfferDigest.kt:33-43`, `TransportConstants.kt:136-142` (rns-core fork).

**Global constraints**
- Integer-only money (G.M.01). No `Double` round-trip on money paths.
- No Room schema change, no new LXMF wire types, no new dependencies.
- `SCENARIO_MATRIX.md` legend must be updated per row as tasks land (COVERED / FAILING / documented-as-accepted).
- All tests run from `android/` (`./gradlew :app:testDebugUnitTest`), JDK 21 pinned in `~/.gradle/gradle.properties`.

---

## Root-cause inventory

| # | Cell | Status today | Grounded cause |
|---|------|--------------|----------------|
| 0 | C8/J1 | **FAILING (undocumented)** | Offer announce is **one-shot**: `publishOffer` fires `offersDest.announce` once (`RnsSession.kt:354-357`); the 20s re-announce loop only re-announces `deliveryDest` (`RnsSession.kt:226-231`). A peer that joins after an offer was created never discovers it; a cold-started seller with open offers re-announces nothing. Matrix's J1 bandwidth math (~2KB/s per peer) is also wrong — there is no periodic offer announce to measure. |
| 1 | C5 | UNKNOWN → real | LXMF byte caps (I5) bound the *container* but offer **field magnitudes are unclamped** at ingest: `fiat_amount`/`crypto_amount_sats` → `Long`, `price_per_unit` → `Double`, `fiat_methods` cardinality unbounded (`OfferRouter.kt:336-342`). A hostile peer can inject absurd amounts into the feed. |
| 2 | D8 | UNKNOWN → low | Money is integer-only (`Models.kt:35`); the only overflow-prone multiply is `cryptoAmountSats × priceIdr`, bounded by user input today. Clamping ingest fields (Task 2) makes the Long math safe by construction. |
| 3 | C10/I6 | UNKNOWN → real | `OnboardingScreen.kt:338` caps *input* at 32 chars but `IdentityManager.updateNickname` (`IdentityManager.kt:175-181`) and the Profile edit dialog have **no cap**; inbound nicknames flow unvalidated from `offerJson["nickname"]` (`OfferRouter.kt:379`) into `PeerEntity`. |
| 4 | E4 (depth) | partial | `getTxInfo` derives depth from tip; when tip fails, confirmed → 1 conf (`ChainMonitor.kt:64-82,226-246`). The auto-refund sweep only re-verifies *full* unconfirm+gone (`fundingDepositGone`, `EscrowService.kt:642`). A reorg dropping depth **below `required_confirmations` (default 1, can be >1) while the address is still funded** → `fundingDepositGone=false` → auto-refund proceeds against a reorged input. Depth re-check missing. |
| 5 | J1/J2/J3 | UNKNOWN → harness | No load test, no soak harness, no fd/memory instrumentation. rns-core throttles announces (`ANNOUNCE_RATE_TARGET=0.12/s`, `PENALTY=5.0`, `TransportConstants.kt:136-142`) — a 100-offer burst will hit it; scope (global vs per-destination) unmeasured. |
| 6 | A6/A7/B7/B10/D6/I10 | UNKNOWN → accept | Documented as accepted risk (below). No code. |

---

## Task 1 (P0) — Fix one-shot offer feed: paced periodic re-announce

**Problem:** #0. Offers are announced once at create/edit and never re-announced; the matrix's J1 estimate is void and discovery is broken for offline-then-online peers.

**Design:** add a paced offer re-announcer in `RnsSession`, cycling the caller's open offers through the `neop2p/offers` destination at a rate the rns-core limiter tolerates (target ≤0.10/s sustained; the J1 test measures the real ceiling).

- Add `RnsSession.reannounceOffers(digests: List<String>)`-style entry: re-announce **at most one** offer digest per tick (round-robin through the caller's open offers). One digest per announce (appData ~300B cap makes batching impossible).
- Extend the existing 20s loop (`RnsSession.kt:226-231`): keep the delivery re-announce on its current cadence; add a **separate paced offers loop** (tick default 10s → 100 offers ≈ 17 min full cycle). Configurable tick in `NeoP2PConfig`.
- `RnsTransport` gains `suspend fun reannounceOffers(digests: List<String>)`; caller (`P2POrchestrator`, new coroutine) reads open `OPEN`/`PAUSED` offers from the DAO and feeds the list. Cold start re-announces all open offers once (paced), fixing the "seller restarts, nobody sees their offers" case.
- On digest re-announce, peers already holding the offer skip re-fetch (`P2POrchestrator.kt:277` `getOfferSync != null`); the commitment-verify path (`:259-263`) is untouched.

**Files:**
- Modify: `RnsSession.kt` (loop + paced offers re-announce), `RnsTransport.kt` (delegate), `P2POrchestrator.kt` (feed the offers list from the DAO), `NeoP2PConfig.kt` (tick constant).

**Accept:**
- Two-process test: peer A creates an offer; peer B starts **after** the announce → B discovers and ingests A's offer within one offers cycle (proves the one-shot defect is gone).
- Cold-start test: A restarts with an open offer in the DB → B re-discovers it without A editing.
- No rns-core rate-limit penalty observed while re-announcing 100 offers at the paced tick (Task 5 measures).

---

## Task 2 (P0) — C5/D8: field-level offer clamps at ingest

**Problem:** #1/#2. Ingest accepts unbounded magnitudes/cardinality.

- In `OfferRouter.ingestOfferEvent` (`OfferRouter.kt:330-362`): after parse, validate before `offerDao.upsert`:
  - `cryptoAmountSats` in `[MIN_OFFER_SATS, MAX_OFFER_SATS]` (add constants to `NeoP2PConfig`, e.g. 1 000…1e8 sats) — else drop the offer.
  - `fiatAmount` in `[1, 1_000_000_000]` IDR — else drop.
  - `pricePerUnit` finite and `> 0`, within `[1.0, 1e9]` — else drop.
  - `fiat_methods` cardinality ≤ 8 and each entry length ≤ 64 chars — else drop.
  - Drop = log + `return` (never persist a hostile offer, same pattern as `deletedOfferStore` skip at `:283`).
- Shared `validateOfferFields(offerJson): Boolean` so the LXMF path (`ingestRnsOffer`, `:434`) uses the identical gate. **This closes D8 by construction**: the Long money math can no longer receive inputs large enough to overflow.

**Files:** `OfferRouter.kt`, `NeoP2PConfig.kt`, new `OfferRouterIngestTest`.

**Accept:** unit test feeds offers with `crypto_amount_sats=Long.MAX_VALUE`, `fiat_amount=-1`, 100-element `fiat_methods`, 10 KB method string, `price_per_unit=NaN` → all dropped, feed row count unchanged; a valid offer ingests.

---

## Task 3 (P1) — C10/I6: nickname capped at write AND at ingest

**Problem:** #3.

- `IdentityManager.updateNickname` (`IdentityManager.kt:175-181`): clamp to `MAX_NICKNAME_LENGTH = 32` (matches onboarding's input cap), trim. Callers (`ProfileScreen.kt:631`, onboarding) need no change — the cap is enforced at the single write point.
- Ingest side (`OfferRouter.kt:379`): clamp inbound nickname to 32 chars and strip control chars (`\n`, `\r`, NUL…) before `PeerEntity.nickname`, so a hostile peer can't plant a bidi/RTL overflow or CRLF into the feed.
- Optionally cap the Profile edit dialog `onValueChange` at 32 for UX symmetry (no behavior change).

**Files:** `IdentityManager.kt`, `OfferRouter.kt`, `ProfileScreen.kt` (optional), `IdentityManagerTest`/`OfferRouterIngestTest`.

**Accept:** `updateNickname` with a 300-char + control-char string stores exactly 32 chars; ingest of an offer with a 500-char nickname persists ≤32 chars with control chars stripped.

---

## Task 4 (P1) — E4: depth re-check before auto-refund

**Problem:** #4.

- In the sweep (`EscrowService.kt:639-655`), extend the re-verify gate: for a `FUNDED`/`SIGNED` escrow past the refund window, before `autoRefundEscrow`:
  - fetch `txInfo`; if `txInfo.confirmed` **and** `txInfo.confirmations < entity.required_confirmations` (reorg shaved the depth below the gate while the address still holds the deposit) → **revert to `FUNDING`** (same re-verify/cancel path as the deposit-gone branch at `:642-652`) instead of refunding.
  - Keep `fundingDepositGone` as the full-unconfirm case; add the depth case alongside.
- Explorer failure already fails closed (skip), unchanged.

**Files:** `EscrowService.kt`, `EscrowReorgTest` (extend with a "depth < required but address funded" case).

**Accept:** unit test: `required_confirmations=3`, funding tx depth drops to 1, address balance non-zero, past refund window → status reverts to `FUNDING`, no refund tx broadcast; with depth ≥ 3 → refund proceeds.

---

## Task 5 (P2) — J1/J2/J3: load + soak harness

**Problem:** #5.

- **`RnsLoadTest`** (extend the 3-JVM harness pattern from `RnsThreePeerTest.kt`): one child announces N=100 offers; the parent must ingest all 100 within the paced-cycle bound. Instrument:
  - Sustained announce rate the fork actually permits (global vs per-destination limiter scope — log when `ANNOUNCE_RATE_PENALTY` engages),
  - Feed ingest completeness + latency-per-offer (digest → `offer_request` → `offer` → verify → upsert),
  - fd count via `/proc/self/fd` and heap delta before/after (J3 seed).
- **`RnsSoakMain`** (accelerated-clock harness): loop the two-process chat + offer_status + escrow_status round-trips with jitter for a bounded window; assert monotonic fd count, bounded heap growth, no exception accumulation. Runs in CI as a plain unit test with a short accelerated window; a longer soak is a manual `main`.
- Wire the fd/heap sampling into `RnsSessionTest`'s existing `@After` teardown if cheap, else keep to the load/soak mains.

**Files:** `RnsLoadTest.kt`, `RnsSoakMain.kt`, reuse `RnsFaultProxy.kt`/`RnsTwoProcessServerMain.kt`.

**Accept:** 100-offer load passes within the cycle bound with no penalty storm; soak main shows flat fd count and bounded heap over its window; J3 row moved to COVERED (with the instrumentation noted as seed-level).

---

## Documented as accepted (no code)

Update `SCENARIO_MATRIX.md` rows to a new **ACCEPTED** status (add to legend):

- **A6** (cross-process identity-dir lock): `configDir` is per-app `filesDir`; in-process guard is `AtomicBoolean` (`Reticulum.kt:271`). Two processes sharing a dir requires two installs with copied config — out of threat model.
- **A7** (read-only FS): `RnsTransport.start()` propagates failure; `P2POrchestrator.sweepStaleEscrows` retries every 60s (`P2POrchestrator.kt:654-658`). Transport-down app remains usable. A graceful-degradation UI is deferred.
- **B7** (asymmetric): client-only phones over the VPS transport node is the shipped architecture; the 3-JVM test (`RnsThreePeerTest`) already exercises transport-mediated routing without NAT.
- **B10** (IPv6): `TCPClientInterface` resolves the DNS host; a literal-v6 test is low value vs. cost. Noted.
- **D6** (version skew): digest carries `v:1`; a mismatched `v` drops the digest cleanly (`RnsOfferDigest.kt:82`). Negotiation deferred until a second wire version exists.
- **I10** (timing): `send()` fails fast locally for unknown dests (`RnsSession.kt:256-257`); over-the-wire RNS hides source/identity. Only a same-device observer sees it. Accepted.

---

## Execution order

1. Task 1 (defect fix, P0) → `:app:testDebugUnitTest`
2. Task 2 (clamps, P0) → new ingest tests
3. Task 3 (nickname, P1)
4. Task 4 (E4 depth, P1) → extend reorg test
5. Task 5 (harness, P2) → `RnsLoadTest` + `RnsSoakMain`
6. Update `SCENARIO_MATRIX.md` (rows + legend + summary count) + `CHANGELOG.md`

Branch: `matrix-unknowns-close` (from main). Status: PLAN — awaiting user confirmation before code.

Verify: `./gradlew :app:testDebugUnitTest` (from `android/`), then `./gradlew :app:lintDebug`; full suite stays 251+ tests, 0 failures.
