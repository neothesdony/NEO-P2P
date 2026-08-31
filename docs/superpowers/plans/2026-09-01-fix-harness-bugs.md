# Fix the Two Production Bugs the Harness Found (RnsLoadTest, 2026-09-01)

> Goal: close the two defects the load test surfaced while hardening the matrix unknowns — (A) cross-process offer digests are dropped/deferred behind an unbounded identity-resolution gap, and (B) the app's offer-feed announce cadence sat right against the fork's per-destination announce semantics with no observability. App-side fixes + a fork-redundancy removal; no new protocol, no Room bump.

**Spec sources:** `RnsSession.kt:92-116,245-270,304-315,655-715` (identity maps, defer-flush, handler registration, stop), `RnsSession.kt:694-715` (handleOfferAnnounce deferral), `Transport.kt:3610-3619` (fork remembers every valid announce), `Transport.kt:3961-3972` (MAX_RATE_TIMESTAMPS=16/30s rebroadcast limit), `Transport.kt:3676` (same-second path admission), `Destination.kt:1551-1555` (second-granular announce timebase), `Identity.kt:291-322,355-381,545-559` (recall/remember + validate_announce already remembers), `WallClock.kt` (`nowSeconds()` = `nowMs()/1000`), `RnsOfferFloodServerMain.kt` (2000ms pacing passed; 1500ms hit `Rate limiting announce` 8×), `RnsLoadTest.kt`, `RnsSoakTest.kt`, `SCENARIO_MATRIX.md` J1/C8 rows.

**Global constraints**
- RNS announce semantics match Python — same-second same-dest dedup and the 16/30s-per-dest rebroadcast cap are **protocol, not fork bugs**. No rns-core change to the admission rule; the app must stay inside the limits by pacing.
- No new LXMF wire types, no Room schema change, no new dependencies.
- All tests run from `android/` (`./gradlew :app:testDebugUnitTest`), JDK 21 pinned in `~/.gradle/gradle.properties`. Fork jars are consumed from `~/.m2` (mavenLocal), so rns-core changes (if any) require re-publish — **prefer app-side fixes**.
- Update `SCENARIO_MATRIX.md` rows as tasks land.

---

## Root-cause inventory

### Bug A — cross-process offer digests dropped / deferred behind an unbounded gap

| # | Symptom | Grounded cause |
|---|---------|----------------|
| A1 | App `lxmf.delivery` handler calls `Identity.remember` with a **zeroed `packetHash`** (`RnsSession.kt:248-254`) | Redundant: the fork already remembers every valid announce before dispatching handlers (`Transport.kt:3610`). The app's call **overwrites** the fork's real `packetHash` in the shared `knownDestinations` (same destHash key, `Identity.kt:375`) and pollutes `saveKnownDestinations` persistence. Benign for `recall` (which only uses `publicKey`, `Identity.kt:293-300`) but wrong hygiene and hides the fork's own mechanism. |
| A2 | `neop2p/offers` digest arriving before its peer's `lxmf.delivery` announce is deferred, then flushed on delivery (`RnsSession.kt:694-715`) | The peerId (libp2p) rides only in the delivery announce's appData; a digest's identity-hash cannot resolve to a peerId until the delivery announce lands. Order-dependence is inherent — but the deferral buffer `pendingOfferAnnouncesByIdentityHash` (`RnsSession.kt:98-101`) is **unbounded**: a hostile peer that announces offers under an identity that never delivers a delivery-announce grows it without limit (memory DoS) — the exact class J1 was meant to close. |
| A3 | `send`/`sendSignaling` depend on `Identity.recall(destHash)` (`RnsSession.kt:328,598-599`) | Works only because the app's remember (A1) populated `knownDestinations`. Once A1 is removed, the fork's remember (`Transport.kt:3610`) must be the sole source — it is (runs before handlers), but this must be asserted by a test. |

### Bug B — offer-feed cadence sat against fork announce semantics; no observability

| # | Symptom | Grounded cause |
|---|---------|----------------|
| B1 | A same-second burst of announces to ONE destination collapses | `Transport.kt:3676` admits a re-announce only when `announceEmitted > pathTimebase`; the emission timebase is second-granular (`Destination.kt:1551` → `WallClock.nowSeconds()` = ms/1000). Two announces to one dest in the same second: the second is dropped. Matches Python RNS. |
| B2 | More than ~16 announces per dest per 30s are silently dropped | `MAX_RATE_TIMESTAMPS=16`/30s on the rebroadcast path (`Transport.kt:3961-3972`) caps one destination at ~1 announce/2s through a transport node. The load test confirmed: 1500ms pacing → 8× `Rate limiting announce`; 2000ms → 0×. |
| B3 | Old "100 offers × 20s ≈ 2KB/s" model wrong; no way to know announces are being dropped | Production's paced loop (10s, one digest/tick, `RnsSession.kt:280-300`) is already inside the limits, but a full cycle over N offers is N×10s (100 offers ≈ 17 min) and nothing logs when the set is large enough to approach the ceiling. The one-shot `publishOffer` at create/edit (`CreateOfferScreen.kt:827-834,939-946`) is now redundant with the paced loop but kept for immediacy — worth a decision, not a silent duplicate. |

---

## Task 1 (P0) — Bug A1/A3: remove the app's redundant identity remember; rely on the fork's

**Problem:** A1 — the app clobbers the fork's real remember with a zeroed packetHash.

**Design:** delete the manual `Identity.remember` block from the `lxmf.delivery` handler (`RnsSession.kt:246-259`). The fork's `processAnnounce` already remembers every valid announce (any aspect) with the correct `packet.packetHash` (`Transport.kt:3610-3615`) BEFORE handlers dispatch, so `Identity.recall(destHash)` for outbound sends keeps working. The handler keeps receiving `announcedIdentity` — seed `peerIdByIdentityHash` from it (identity hash → peerId) as today, without calling `remember`.

**Files:**
- Modify: `RnsSession.kt` (handler: drop `Identity.remember` + zeroed packetHash; keep `handlePeerAnnounce(destHash, announcedIdentity, appData)` and the identity-hash seeding).
- Add: `RnsSessionTest` case — `Identity.recall(peerDestHash)` returns the peer after only the fork-side announce path (via `handlePeerAnnounce`), proving recall no longer depends on the app's remember.

**Accept:**
- No app code path calls `Identity.remember`.
- `RnsSessionTest` `registerPeer` path still resolves `Identity.recall` (fork remember covers it).
- Full suite green.

---

## Task 2 (P0) — Bug A2: bound the deferral buffer (hostile-identity memory DoS)

**Problem:** A2 — `pendingOfferAnnouncesByIdentityHash` is unbounded.

**Design:** bound the deferral in `RnsSession`:
- Per identity: max `MAX_PENDING_OFFERS_PER_IDENTITY = 32` digests, dedup by digest (a re-announced digest doesn't re-add).
- Total: max `MAX_PENDING_OFFER_IDENTITIES = 64` identities; evict oldest identity (by first-deferral time) when exceeded.
- On exceeding either bound: log a warning (`[RnsSession] deferral cap hit …`) and drop the new digest — a peer whose identity never delivers is hostile/absent and must not grow memory.
- Keep the flush-on-delivery (delivery announce removes + emits), which is correct and now safe.

**Files:**
- Modify: `RnsSession.kt` (bounded deferral map, constants, eviction).
- Add: `RnsSessionTest` case — offer digests from an unknown identity: first ≤32 defer, the 33rd drops; >64 identities → oldest evicted; a delivery announce flushes the held set for that identity.
- Modify: `OfferRouterIngestValidationTest` untouched (separate concern).

**Accept:**
- Deferral map size is provably ≤ 64 identities × 32 digests regardless of hostile input.
- A legitimate digest-before-delivery-announce still flushes once the delivery announce lands (existing behavior preserved).

---

## Task 3 (P1) — Bug B: tighten paced cadence + add announce-drop observability

**Problem:** B1/B2/B3 — cadence safe but slow; no visibility.

**Design (app-side only; protocol semantics stay):**
1. **Cadence 10s → 2500ms** (`OFFER_REANNOUNCE_INTERVAL_MS`): 12 announces/30s per dest — 25% headroom under the 16/30s ceiling against scheduling jitter (1500ms→8 drops, 2000ms→0 drops; 2500ms is comfortably inside). 100 offers now cycle in ~4 min instead of ~17 min. Keep the delivery re-announce at 20s (separate dest, unrelated).
2. **Drop the redundant one-shot `publishOffer` at create/edit** (`CreateOfferScreen.kt:827-834,939-946`): the paced loop announces a newly `track`ed digest within 2.5s. Keep `trackOfferDigest` (which already triggers the loop). This removes the duplicate-announce-in-a-30s-window and the same-second collision risk with a tick (both carried the same digest; peers skip re-fetch anyway — but the dedup is now structural).
3. **Observability:** the paced loop already counts `pacedOfferReannounces` (test seam). Add a warning log when the tracked set is large enough that a full cycle would approach the ceiling — i.e. when `set.size * intervalMs` leaves `< 30s / 16 * 1000 * 2` per-dest headroom — so a future cadence change can't silently lose announces. No app-visible counter needed; log only.
4. **Stale-set note (documented, not changed):** the rehydrate loop (`P2POrchestrator.rehydrateOfferReannounce`, 60s) can lag an edit/delete by up to 60s, so the paced loop may announce a just-deleted offer once. Harmless — `DeletedOfferStore` tombstone blocks ingest resurrection.

**Files:**
- Modify: `RnsSession.kt` (constant 2500ms, headroom warning), `CreateOfferScreen.kt` (drop the two one-shot `publishOffer` calls, keep `trackOfferDigest`).
- Modify: `RnsLoadTest`/`RnsOfferFloodServerMain` — pace the flood at 2500ms and assert **no** `Rate limiting announce` in the child output (proves the cadence stays inside the fork limits); assert the parent's headroom warning never logs.
- Modify: `RnsSessionTest` paced-reannounce case (tick is test-injected, unaffected).

**Accept:**
- 40-offer flood at 2500ms cadence: all 40 digests delivered, zero rate-limit drops.
- A single-offer create shows on the feed within ~2.5s (paced), no one-shot.
- Warning fires only when the tracked set would exceed the ceiling (log-verified in test).

---

## Task 4 (P2) — docs + matrix

- `SCENARIO_MATRIX.md` J1: replace the "~2KB/s per peer" estimate with the verified semantics (second-granular same-dest dedup + 16/30s rebroadcast cap; paced 2500ms cadence; full-cycle 100 offers ≈ 4 min). Mark C8 note that the paced loop is the discovery path (already COVERED — add the cadence number).
- `CHANGELOG.md`: 1.0.25 entry (identity-remember cleanup, bounded deferral, cadence, one-shot removal).
- `DEBUG_MAP.md` §offer-feed if it mentions the one-shot create announce.

**Files:** `SCENARIO_MATRIX.md`, `CHANGELOG.md`, `DEBUG_MAP.md`.

**Accept:** matrix J1/C8 and changelog reflect the corrected model; grep shows no remaining `publishOffer(` at create/edit call sites.

---

## Execution order

1. Task 1 (remove redundant remember) → run `RnsSessionTest`
2. Task 2 (bound deferral) → add hostile-flood test
3. Task 3 (cadence + one-shot removal + observability) → re-run `RnsLoadTest`
4. Task 4 (docs)
5. Full `./gradlew :app:testDebugUnitTest` + `./gradlew :app:lintDebug`

Branch: `harness-bugfix-identity-and-announce` (from main). Status: PLAN — awaiting user confirmation before code.

Verify: `./gradlew :app:testDebugUnitTest` (from `android/`); full suite 266+ tests, 0 failures; lint baseline unchanged.
