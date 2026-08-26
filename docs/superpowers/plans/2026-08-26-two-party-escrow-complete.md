# Two-Party Escrow Complete (P1/P2/P3 + U1-U4 + Missing Flows) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the escrow happy path genuinely two-party: verify funding on-chain, gate release in the service, sync escrow lifecycle over the relay, collect the buyer's BTC address, give the buyer an escrow UI, and add seller decline — plus the copy/dead-code sweep.

**Architecture:** Add a shared escrow-status relay event (kind:33337) that carries the escrow row's mutable fields so both devices converge on one DB row (idempotent, no-downgrade, role-verified). Bind `onEscrowFunded` to real tx outputs (address + amount + vout), persist the vout, and use it for payout/refund inputs. Move the release gate into `EscrowService` (UI can no longer bypass it). Collect the buyer payout address at accept time and carry it in the escrow event.

**Tech Stack:** Kotlin 2.1.0, Room (DB v18→v19, SQLCipher), bitcoinj 0.16.2, Nostr relay events (kinds 33333/33336/33386-33388 existing; **new kind 33337**), JUnit 4 plain-JVM tests.

**Spec:** Review findings from 2026-08-26 session (P1 funding binding, P1 release gate, P2 2-party sync, P2 buyer address, P2 timelock lie, P2 stale copy, P3 dead code, UI#1 address field, UI#2 role-aware release, UI#3 buyer entry, UI#4 copy, flows 1-6).

## Global Constraints

- All Gradle commands run from `android/` (root project is intentionally empty). Run bare, never piped: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain`.
- JDK 17 pinned via user-level `~/.gradle/gradle.properties`; on `AndroidLocationsException` run `env -u ANDROID_PREFS_ROOT ./gradlew ...`.
- Room DB is **v18 today; this plan bumps to v19** (adds `escrows.funding_vout`, `escrows.buyer_btc_address`, `trade_offers.btc_receive_address`). Update `AGENTS.md` + `docs/SECURITY_POSTURE.md` in the final task.
- Relays: subscribe kind:33337 ONLY on custom relays (same as offers; never on public fallback relays).
- Escrow status vocabulary (no legacy `PAID`): FUNDING → FUNDED → PAYMENT_PENDING → RECEIPT_SENT → CONFIRMING → RELEASED (+ DISPUTED/RESOLVING/CANCELLED/REFUNDED).
- PeerId-based role determination only (`identityManager.myPeerId()` vs `buyer_peer_id`/`seller_peer_id`). Never pubkey-based for roles.
- Timeout constants (do not touch): `ESCROW_FUNDING_TIMEOUT_MS`=45m, `FUNDING_WARNING_MS`=30m, `ESCROW_FUNDED_REFUND_TIMEOUT_MS`=12h, `FUNDED_REFUND_GRACE_MS`=48h, `PAYMENT_WINDOW_MS`=24h, `PAYMENT_GRACE_MS`=12h.
- One commit per task, only after `:app:testDebugUnitTest` green. Commit message convention: `fix(escrow): ...` / `feat(escrow): ...` / `fix(l10n): ...` / `docs: ...`.

---

### Task 1: ChainMonitor — parse tx outputs (pure, testable)

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/ChainMonitor.kt` (add after `getTxInfo`, ~line 162)
- Test: `android/app/src/test/java/com/neop2p/data/escrow/ChainMonitorTxOutputsTest.kt`

**Interfaces:**
- Produces: `data class TxOutput(val scriptPubkeyAddress: String?, val valueSats: Long, val index: Int)` and `fun parseTxOutputs(json: String): List<TxOutput>` (pure) plus `suspend fun getTxOutputs(txid: String): Result<List<TxOutput>>` (uses `apiGet("/tx/$txid")`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

class ChainMonitorTxOutputsTest {
    private val sample = """{
        "txid": "aa",
        "vout": [
            {"scriptpubkey_address": "tb1qescrowfunding", "value": 123000},
            {"scriptpubkey_address": "tb1qchange", "value": 5000}
        ]
    }"""

    @Test
    fun `parses outputs with address value and index`() {
        val outputs = ChainMonitor.parseTxOutputs(sample)
        assertEquals(2, outputs.size)
        assertEquals(123000L, outputs[0].valueSats)
        assertEquals("tb1qescrowfunding", outputs[0].scriptPubkeyAddress)
        assertEquals(0, outputs[0].index)
    }

    @Test
    fun `missing fields become null zero`() {
        val outputs = ChainMonitor.parseTxOutputs("""{"vout":[{"value":1}]}""")
        assertEquals(1, outputs.size)
        assertEquals(null, outputs[0].scriptPubkeyAddress)
        assertEquals(1L, outputs[0].valueSats)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.escrow.ChainMonitorTxOutputsTest" --console=plain`
Expected: FAIL — `parseTxOutputs` unresolved.

- [ ] **Step 3: Implement**

In `ChainMonitor.kt` companion, add:

```kotlin
data class TxOutput(val scriptPubkeyAddress: String?, val valueSats: Long, val index: Int)

companion object {
    /** Pure parser for Mempool/Esplora /tx/{txid} vout JSON. */
    fun parseTxOutputs(json: String): List<TxOutput> {
        val obj = Json.parseToJsonElement(json).jsonObject
        val vout = obj["vout"]?.jsonArray ?: return emptyList()
        return vout.mapIndexed { i, el ->
            val o = el.jsonObject
            TxOutput(
                scriptPubkeyAddress = o["scriptpubkey_address"]?.jsonPrimitive?.content,
                valueSats = o["value"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                index = i
            )
        }
    }
}
```

And in the class (after `getTxInfo`):

```kotlin
suspend fun getTxOutputs(txid: String): Result<List<TxOutput>> = try {
    Result.success(parseTxOutputs(apiGet("/tx/$txid")))
} catch (e: Exception) {
    Log.e(TAG, "Failed to get tx outputs: ${e.message}")
    Result.failure(e)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.escrow.ChainMonitorTxOutputsTest" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/escrow/ChainMonitor.kt android/app/src/test/java/com/neop2p/data/escrow/ChainMonitorTxOutputsTest.kt
git commit -m "feat(escrow): parse funding tx outputs (pure, testable)"
```

---

### Task 2: DB v19 — `funding_vout`, `buyer_btc_address`, offer `btc_receive_address`

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/local/entity/Entities.kt` (EscrowEntity + TradeOfferEntity)
- Modify: `android/app/src/main/java/com/neop2p/data/local/AppDatabase.kt` (version 18→19 + MIGRATION_18_19)
- Modify: `android/app/src/main/java/com/neop2p/data/local/Mappers.kt` (toDomain/toEntity for new fields)
- Test: none (Room migrations exercised at runtime; compile-only here)

**Interfaces:**
- Produces: `EscrowEntity.funding_vout: Long = 0`, `EscrowEntity.buyer_btc_address: String? = null`, `TradeOfferEntity.btc_receive_address: String? = null`; `Escrow.fundingVout: Long`, `Escrow.buyerBtcAddress: String?`, `TradeOffer.btcReceiveAddress` already in domain model.

- [ ] **Step 1: Read current Entities.kt + AppDatabase.kt + Mappers.kt** and note exact column list of `escrows` and `trade_offers`.

- [ ] **Step 2: Add fields to entities**

In `EscrowEntity` add `@ColumnInfo(name = "funding_vout") val fundingVout: Long = 0L` and `@ColumnInfo(name = "buyer_btc_address") val buyerBtcAddress: String? = null`. In `TradeOfferEntity` add `@ColumnInfo(name = "btc_receive_address") val btcReceiveAddress: String? = null`.

- [ ] **Step 3: Bump DB version and write migration**

In `AppDatabase.kt` set `version = 19` and add:

```kotlin
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE escrows ADD COLUMN funding_vout INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE escrows ADD COLUMN buyer_btc_address TEXT")
        db.execSQL("ALTER TABLE trade_offers ADD COLUMN btc_receive_address TEXT")
    }
}
```

Add `MIGRATION_18_19` to the `addMigrations(...)` list.

- [ ] **Step 4: Update `Mappers.kt`** so `toDomain()`/`toEntity()` carry the three new fields both directions.

- [ ] **Step 5: Build + commit**

Run: `./gradlew :app:assembleDebug --console=plain` — expected SUCCESS.
```bash
git add android/app/src/main/java/com/neop2p/data/local/
git commit -m "feat(db): v19 — funding_vout, buyer_btc_address, offer btc_receive_address"
```

---

### Task 3: P1 — verify funding binds to the escrow address/amount, capture vout

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt` (`onEscrowFunded` :478-524)
- Test: `android/app/src/test/java/com/neop2p/data/escrow/EscrowFundingBindingTest.kt` (pure)

**Interfaces:**
- Consumes: `ChainMonitor.getTxOutputs(txid): Result<List<TxOutput>>`
- Produces: `fun findFundingOutput(outputs: List<TxOutput>, address: String?, amountSats: Long): Int?` (companion, pure); `onEscrowFunded` now stores `funding_vout` and refuses mismatches.

- [ ] **Step 1: Write failing test**

```kotlin
package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EscrowFundingBindingTest {

    private val outputs = listOf(
        ChainMonitor.TxOutput("tb1qchange", 5000L, 0),
        ChainMonitor.TxOutput("tb1qescrow", 123000L, 1)
    )

    @Test
    fun `finds the vout paying escrow address with exact amount`() {
        assertEquals(1, EscrowService.findFundingOutput(outputs, "tb1qescrow", 123000L))
    }

    @Test
    fun `null when address mismatch`() {
        assertNull(EscrowService.findFundingOutput(outputs, "tb1qother", 123000L))
    }

    @Test
    fun `null when amount mismatch`() {
        assertNull(EscrowService.findFundingOutput(outputs, "tb1qescrow", 999L))
    }

    @Test
    fun `null on empty outputs`() {
        assertNull(EscrowService.findFundingOutput(emptyList(), "tb1qescrow", 123000L))
    }
}
```

- [ ] **Step 2: Run, verify FAIL** — `findFundingOutput` unresolved.

- [ ] **Step 3: Implement**

In `EscrowService` companion add:

```kotlin
/** Return the vout index whose output pays [address] exactly [amountSats], or null. */
fun findFundingOutput(outputs: List<ChainMonitor.TxOutput>, address: String?, amountSats: Long): Int? =
    outputs.firstOrNull { o -> o.scriptPubkeyAddress.equals(address, ignoreCase = true) && o.valueSats == amountSats }?.index
```

Rewrite `onEscrowFunded` between the confirmations check and the FUNDED update:

```kotlin
val outputs = chainMonitor.getTxOutputs(fundingTxId).getOrElse {
    return@withContext Result.failure(Exception("Cannot fetch funding tx outputs: ${it.message}"))
}
val vout = findFundingOutput(outputs, entity.funding_address, entity.deposit_amount_sats)
    ?: return@withContext Result.failure(
        Exception("Funding tx does not pay the escrow address ${entity.funding_address} the deposit amount ${entity.deposit_amount_sats} sats")
    )
```

And in the `updated` copy add `funding_vout = vout.toLong()`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.escrow.EscrowFundingBindingTest" --console=plain`
Expected: PASS (all 4).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt android/app/src/test/java/com/neop2p/data/escrow/EscrowFundingBindingTest.kt
git commit -m "fix(escrow): P1 funding verification binds address+amount, stores real vout"
```

---

### Task 4: P1 — use `fundingVout` in payout + refund (no more hardcoded 0)

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt` (`generatePayoutTransaction` :531, `buildRefundTx` :1600)

**Interfaces:**
- Consumes: `Escrow.fundingVout`
- Produces: unchanged signatures; both tx builders use the stored vout.

- [ ] **Step 1: `generatePayoutTransaction`** — replace default param `fundingOutputIndex: Int = 0` with `fundingOutputIndex: Int = escrow.fundingVout.toInt()` (compute before use; keep the parameter for callers that still pass it, default now derived).

- [ ] **Step 2: `buildRefundTx`** — replace `tx.addInput(Sha256Hash.wrap(fundingTxId), 0L, ...)` with `... , escrow.fundingVout, ...`.

- [ ] **Step 3: Verify callers still compile** — `EscrowViewModel.confirmPayout` (:1364) passes no vout → now defaults to the stored one. 

- [ ] **Step 4: Run**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: all existing escrow tests still pass (Segwit/RefundSigning/Arbitration rely on vout 0 — unchanged for old rows since `funding_vout` defaults to 0).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt
git commit -m "fix(escrow): payout/refund spend stored funding_vout, not hardcoded 0"
```

---

### Task 5: P2 — service-level release gate; confirmReceipt self-generates the payout

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt` (`releaseFunds` :714, `confirmReceipt` :1029)
- Test: `android/app/src/test/java/com/neop2p/data/escrow/EscrowReleaseGateTest.kt` (pure mirror, style of `EscrowReceiptFlowTest`)

**Interfaces:**
- Produces: `releaseFunds` refuses unless status ∈ {RECEIPT_SENT, CONFIRMING}; `confirmReceipt` calls a new private `ensurePayoutTx(entity)` that generates the unsigned payout if `psbt_unsigned` is null.

- [ ] **Step 1: Write failing test (mirror)**

```kotlin
package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

class EscrowReleaseGateTest {

    private fun canRelease(status: String): Boolean = when (status) {
        "RECEIPT_SENT", "CONFIRMING" -> true
        else -> false
    }

    @Test
    fun `only receipt states allow release`() {
        assertEquals(false, canRelease("FUNDED"))
        assertEquals(false, canRelease("SIGNED"))
        assertEquals(false, canRelease("PAYMENT_PENDING"))
        assertEquals(true, canRelease("RECEIPT_SENT"))
        assertEquals(true, canRelease("CONFIRMING"))
    }
}
```

- [ ] **Step 2: Run, verify fail** (mirror test passes trivially — keep the real gate test below)

Actually — the mirror passes immediately; the real guard is behavioral. Add to the same file a second test using the service-level contract via a pure `canReleaseFromStatus(status: String): Boolean` in the companion that `releaseFunds` uses:

```kotlin
@Test
fun `release gate mirrors service guard`() {
    assertEquals("FUNDED cannot release", false, EscrowService.canReleaseFromStatus("FUNDED"))
    assertEquals("SIGNED cannot release", false, EscrowService.canReleaseFromStatus("SIGNED"))
}
```

- [ ] **Step 3: Implement**

In `EscrowService` companion:

```kotlin
fun canReleaseFromStatus(status: String): Boolean =
    status == EscrowStatus.RECEIPT_SENT.name || status == EscrowStatus.CONFIRMING.name
```

At the top of `releaseFunds` body:

```kotlin
val currentStatus = EscrowStatus.valueOf(entity.status)
if (!canReleaseFromStatus(entity.status)) {
    return@withContext Result.failure(
        IllegalStateException("Release requires a confirmed receipt (current: ${entity.status})")
    )
}
```

Rewrite the top of `confirmReceipt` so it always has a payout tx before `releaseFunds`:

```kotlin
if (entity.psbt_unsigned == null) {
    val escrow = entity.toDomain()
    val fundingTxId = escrow.fundingTxId
        ?: return@withContext Result.failure(IllegalStateException("No funding tx recorded"))
    val buyerAddr = escrow.buyerBtcAddress?.takeIf { it.isNotBlank() }
        ?: escrow.fundingAddress
        ?: return@withContext Result.failure(IllegalStateException("No buyer payout address"))
    val gen = generatePayoutTransaction(
        escrowId = escrow.escrowId,
        fundingTxId = fundingTxId,
        fundingOutputIndex = escrow.fundingVout.toInt(),
        buyerAddressStr = buyerAddr
    )
    if (gen.isFailure) return@withContext Result.failure(
        gen.exceptionOrNull() ?: Exception("Could not build payout tx")
    )
}
```

- [ ] **Step 4: Run all escrow tests**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: all pass. (Existing `confirmPayout` UI still calls `releaseFunds` directly — it now fails for FUNDED/SIGNED; Task 8 removes those UI paths.)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt android/app/src/test/java/com/neop2p/data/escrow/EscrowReleaseGateTest.kt
git commit -m "fix(escrow): P2 release gate in service; confirmReceipt self-builds payout"
```

---

### Task 6: U#4 + P2/P3 — copy sweep + dead code removal

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt` (`disputeEscrow` error copy :918-919; delete `getFeeSummary` :1628-1633, `estimateRefundNetworkFee` :1318-1319)
- Modify: `android/app/src/main/res/values/strings.xml` (`escrow_dispute_timelock` :207, `escrow_timeout_info` :205)

- [ ] **Step 1: `disputeEscrow`** — replace the `error = "Dispute triggered — 7-day timelock started"` with `error = "Dispute opened — awaiting arbitrator review"`.

- [ ] **Step 2: strings.xml**

Replace:
```xml
<string name="escrow_dispute_timelock">Dispute in progress - 7-day timelock active</string>
```
with:
```xml
<string name="escrow_dispute_timelock">Dispute in progress - arbitrator review</string>
```

Replace:
```xml
<string name="escrow_timeout_info">Unfunded escrows auto-cancel after 30 minutes; funded-but-stalled escrows auto-refund after 6 hours.</string>
```
with:
```xml
<string name="escrow_timeout_info">Unfunded escrows auto-cancel after 45 minutes; funded-but-stalled escrows auto-refund after 12 hours plus a 48-hour grace period.</string>
```

- [ ] **Step 3: Delete dead code**

Delete `getFeeSummary()` + nested `FeeSummary` + `estimateRefundNetworkFee()` from `EscrowService`. Verify zero callers first:

```bash
grep -rn "getFeeSummary\|estimateRefundNetworkFee" android/app/src/main/java/ | grep -v "fun getFeeSummary\|fun estimateRefundNetworkFee"
```

- [ ] **Step 4: Run lint + tests**

Run: `./gradlew :app:lintDebug :app:testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL (lint baseline unchanged).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt android/app/src/main/res/values/strings.xml
git commit -m "fix(escrow): remove false 7-day timelock claim, fix timeout copy, drop dead code"
```

---

### Task 7: New kind:33337 escrow status + NostrClient plumbing

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/NostrClient.kt` (constants :42-50, subscription :244-260, new publish + flow, handler near :435)

**Interfaces:**
- Produces: `const val KIND_ESCROW_STATUS = 33337`; `val escrowStatusEvents: SharedFlow<JsonObject>`; `suspend fun publishEscrowStatus(escrowId: String, status: String, escrowJson: String): Result<String>` (content = JSON with all mutable escrow fields, signed with the identity key pair).

- [ ] **Step 1: Add kind constant** next to `KIND_OFFER_STATUS` in companion.

- [ ] **Step 2: Add flow + subscription**

```kotlin
private val _escrowStatusEvents = MutableSharedFlow<JsonObject>(replay = 100)
val escrowStatusEvents: SharedFlow<JsonObject> = _escrowStatusEvents.asSharedFlow()
```

In the subscription block that currently subscribes `KIND_OFFER_STATUS` (near :244), extend the custom-relay kinds array to include `KIND_ESCROW_STATUS`.

- [ ] **Step 3: Route inbound to the flow**

In the `when (kind)` dispatch (near :339 for offers/:442 for disputes), add:

```kotlin
KIND_ESCROW_STATUS -> {
    scope?.launch { _escrowStatusEvents.emit(event) }
}
```

- [ ] **Step 4: Implement `publishEscrowStatus`**

```kotlin
suspend fun publishEscrowStatus(
    escrowId: String,
    status: String,
    fields: Map<String, String> = emptyMap()
): Result<String> = withContext(Dispatchers.IO) {
    try {
        val kp = identityManager.getNostrKeyPair()
        val content = buildJsonObject {
            put("escrow_id", escrowId)
            put("status", status)
            put("ts", System.currentTimeMillis())
            fields.forEach { (k, v) -> put(k, v) }
        }.toString()
        val event = NostrEventSigner.buildSignedEvent(
            kind = KIND_ESCROW_STATUS, content = content,
            pubkey = kp.publicKeyHex, privateKeyHex = kp.privateKeyHex
        )
        publishToConnectedRelays(event)
        Result.success(event["id"]?.jsonPrimitive?.content ?: "")
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

- [ ] **Step 5: Build + commit**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.
```bash
git add android/app/src/main/java/com/neop2p/data/p2p/NostrClient.kt
git commit -m "feat(p2p): kind:33337 escrow status events (publish + subscribe)"
```

---

### Task 8: EscrowRouter — remote escrow row ingestion

**Files:**
- Create: `android/app/src/main/java/com/neop2p/data/p2p/routing/EscrowRouter.kt`
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt` (start + collect)

**Interfaces:**
- Consumes: `NostrClient.escrowStatusEvents`, `EscrowDao`, `EscrowService` (upsert path), `OfferDao`
- Produces: `class EscrowRouter @Inject constructor(...) { fun startListening(scope: CoroutineScope) }` — idempotent (guarded `started`), single collector.

Rules (same philosophy as OfferRouter):
- Ignore events for escrows where neither `buyer_peer_id` nor `seller_peer_id` equals `identityManager.myPeerId()`.
- Never downgrade a terminal status (RELEASED/REFUNDED/CANCELLED/DISPUTED stay).
- Only these transitions apply from remote: FUNDING→FUNDED (fills `funding_tx_id`, `funded_at`), PAYMENT_PENDING (fills `paid_at`), RECEIPT_SENT (fills `receipt_reference`, `receipt_sent_at`), CONFIRMING, DISPUTED.
- Keep local `psbt_unsigned`, signatures, `arbitrator_*` — never overwritten by remote.
- Upsert the row, then emit `EscrowTransition` so the orchestrator notifies.

- [ ] **Step 1: Write the router** (full file ~180 lines) following OfferRouter structure. Include a pure `fun applyRemoteStatus(local: EscrowEntity?, remoteStatus: String): String?` that returns null when the transition is disallowed (no downgrade, terminal locked) — unit-testable.

- [ ] **Step 2: Unit test `EscrowRouterApplyTest.kt`** for: FUNDING→FUNDED ok; RELEASED→anything null; FUNDED→SIGNED? (only from local, remote SIGNED ignored); unknown peer ignored; receipt fields preserved.

- [ ] **Step 3: Wire into `P2POrchestrator.start()`** — inject `EscrowRouter`, call `escrowRouter.startListening(scope)` after `offerRouter.startListening(scope)` (:93).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/p2p/routing/EscrowRouter.kt android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt android/app/src/test/java/com/neop2p/data/p2p/routing/EscrowRouterApplyTest.kt
git commit -m "feat(escrow): remote escrow status ingestion (kind:33337)"
```

---

### Task 9: Emit kind:33337 from every service transition

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt` (createEscrow, onEscrowFunded, markPaid, sendReceipt, confirmReceipt/releaseFunds, disputeEscrow, resolveDispute/storeArbitrationDecision)
- Modify: `android/app/src/main/java/com/neop2p/di/AppModule.kt` — inject `NostrClient` into `EscrowService` (currently absent)

**Interfaces:**
- Consumes: `NostrClient.publishEscrowStatus`
- Produces: every status mutation now publishes the event with the mutable fields (address, amounts, buyer_btc_address, funding_tx_id, receipt_reference…). Best-effort: failures logged, never block the local transition.

- [ ] **Step 1: DI** — add `nostrClient: NostrClient` to `EscrowService` constructor; update any test instantiations (search `EscrowService(` in tests — none construct it directly, all are pure mirrors; verify).

- [ ] **Step 2: Private helper**

```kotlin
private fun escrowStatusFields(entity: EscrowEntity): Map<String, String> = buildMap {
    put("offer_id", entity.offer_id ?: "")
    put("buyer_peer_id", entity.buyer_peer_id ?: "")
    put("seller_peer_id", entity.seller_peer_id ?: "")
    put("funding_address", entity.funding_address ?: "")
    put("buyer_btc_address", entity.buyer_btc_address ?: "")
    entity.funding_tx_id?.let { put("funding_tx_id", it) }
    entity.funded_at?.let { put("funded_at", it.toString()) }
    entity.paid_at?.let { put("paid_at", it.toString()) }
    entity.receipt_reference?.let { put("receipt_reference", it) }
    entity.receipt_sent_at?.let { put("receipt_sent_at", it.toString()) }
    put("deposit_sats", entity.deposit_amount_sats.toString())
    put("trade_sats", entity.trade_amount_sats.toString())
}
```

- [ ] **Step 3: Emit after each upsert** — in `createEscrow` (status FUNDING), `onEscrowFunded` (FUNDED), `markPaid` (PAYMENT_PENDING), `sendReceipt` (RECEIPT_SENT), `confirmReceipt` (CONFIRMING), `releaseFunds` (RELEASED), `disputeEscrow` (DISPUTED), `storeArbitrationResolution` (RELEASED/REFUNDED). Pattern:

```kotlin
runCatching { nostrClient.publishEscrowStatus(escrowId, "FUNDED", escrowStatusFields(updated)) }
```

- [ ] **Step 4: Run tests + commit**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS.
```bash
git add android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt android/app/src/main/java/com/neop2p/di/AppModule.kt
git commit -m "feat(escrow): publish kind:33337 on every lifecycle transition"
```

---

### Task 10: U1 — buyer BTC address collection at accept + payout use

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/offerdetail/OfferDetailScreen.kt` (accept dialog + `acceptOffer` + `createSellerEscrow`)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt` (`buyerAddressFor` :1238 — read `escrow.buyerBtcAddress` first)
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt` (`createEscrow` — accept `buyerBtcAddress` param, persist)

**Interfaces:**
- Produces: `acceptOffer(offer, onAccepted)` gains `buyerBtcAddress: String`; `createEscrow(..., buyerBtcAddress: String? = null)`; `Escrow.buyerBtcAddress` populated on the buyer's device and propagated via kind:33337 fields so the seller's escrow row gets it too (Task 8 preserves it — add `buyer_btc_address` to the apply rules).

- [ ] **Step 1: Service** — `createEscrow` param `buyerBtcAddress: String? = null` stored on the entity.

- [ ] **Step 2: OfferDetail dialog** — when the acting role is BUYER (accepting a SELL offer), the accept dialog shows a required text field "Alamat BTC tujuan (payout)" pre-validated with `Address.fromString` in try/catch; disable Accept until valid. Pass value into `acceptOffer`.

- [ ] **Step 3: `acceptOffer`** — BUY role: `escrowService.createEscrow(..., buyerBtcAddress = buyerBtcAddress)`; SELL role: no change (seller gets it via sync event).

- [ ] **Step 4: `buyerAddressFor`** — order: `escrow.buyerBtcAddress` → `offer.btcReceiveAddress` → `escrow.fundingAddress`.

- [ ] **Step 5: Build + manual E2E script**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.
```bash
git add android/app/src/main/java/com/neop2p/
git commit -m "feat(escrow): buyer BTC address collected at accept and used in payout"
```

---

### Task 11: U2 — role-aware release UI (remove FUNDED/SIGNED release buttons)

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt` (FUNDED branch :634-654, SIGNED branch :655-672, `confirmPayout` :1356 — deprecate)

**Interfaces:**
- Produces: no UI path calls `releaseFunds` except the RECEIPT_SENT/CONFIRMING seller button; `confirmPayout()`/`releaseFunds()` VM methods deleted; a new `onSellerWaitHint` text per role.

- [ ] **Step 1: FUNDED branch** — replace the Release button with role text: seller sees "Waiting for buyer to pay…"; buyer sees "Mark IDR payment sent" button (`onMarkPaid`).

- [ ] **Step 2: SIGNED branch** — same: buyer gets markPaid; seller gets wait text (release impossible pre-receipt).

- [ ] **Step 3: Delete `confirmPayout()` and `releaseFunds()` from `EscrowViewModel`; remove their callback wiring (:107-116); keep `onConfirmReceipt` as the only release wiring.

- [ ] **Step 4: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain`
Expected: green.
```bash
git add android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt
git commit -m "fix(escrow): U2 role-aware release — no release button before receipt"
```

---

### Task 12: U3 — buyer-side escrow status + entry (waiting-for-funding card, chat banner, nav)

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/chat/ChatScreen.kt` (add `EscrowStatusBanner` above the composer when `escrowDao.observeEscrowByOfferId` returns non-null — shows status + "Open escrow" button navigating to `Routes.escrow(escrowId)`)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt` — when the escrow doesn't exist yet (buyer), render `WaitingCard("Menunggu penjual membuat escrow…")` + auto-retry `loadEscrow()` every 5s until the row appears (remote event created it via Task 8).
- Modify: `android/app/src/main/java/com/neop2p/navigation/NavGraph.kt` — OfferDetail `onEscrowCreated` already navigates when escrowId non-null; add: buyer accept navigates to chat, chat banner opens escrow.

- [ ] **Step 1: Chat banner** — in `ChatViewModel`, expose `escrowStatus: StateFlow<String?>` from the existing `observeEscrowFunding`; render a Material3 `Card` above the message list with status + `EscrowScreen` link.

- [ ] **Step 2: EscrowScreen pending** — in `loadEscrow()`, when `getEscrow(escrowId) == null` show pending state + retry loop (5s delay, bounded 30 tries, then "escrow tidak ditemukan").

- [ ] **Step 3: Build + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain`
Expected: green.
```bash
git add android/app/src/main/java/com/neop2p/ui/screens/chat/ChatScreen.kt android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt android/app/src/main/java/com/neop2p/navigation/NavGraph.kt
git commit -m "feat(escrow): U3 buyer-side escrow status + waiting card + chat banner"
```

---

### Task 13: U4 — seller decline for MATCHED offers

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/offerdetail/OfferDetailScreen.kt` (add Decline button in MATCHED seller view)
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/NostrClient.kt` (`publishOfferStatus` already exists — use it)
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/routing/OfferRouter.kt` — allow `MATCHED/ESCROWED → OPEN` decline when matched peer == event author, clear `matched_peer_id`

- [ ] **Step 1: OfferDetail seller view** — for `MATCHED` status add `OutlinedButton("Tolak tawaran")` → confirm dialog → `offerDao.updateStatus(offerId, OPEN)` + `publishOfferStatus(offerId, OPEN, null)`.

- [ ] **Step 2: OfferRouter** — extend `effectiveStatus` logic: allow MATCHED/ESCROWED → OPEN only when the remote event's author equals the local matched_peer_id (decline by the buyer is not allowed; only the creator/accept flow un-locks). Implementation: the existing status flow already preserves `matched_peer_id`; add rule: if `existingStatus == MATCHED/ESCROWED && parsedStatus == OPEN && matchedPeerId matches event author` → apply; else keep.

- [ ] **Step 3: Build + test + commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain`
Expected: green.
```bash
git add android/app/src/main/java/com/neop2p/ui/screens/offerdetail/OfferDetailScreen.kt android/app/src/main/java/com/neop2p/data/p2p/routing/OfferRouter.kt
git commit -m "feat(offers): U4 seller decline for matched offers (back to OPEN)"
```

---

### Task 14: Docs + final E2E verification

**Files:**
- Modify: `AGENTS.md` (root + `android/AGENTS.md`) — DB v19, kind:33337, funding binding, release gate, new UI
- Modify: `docs/SECURITY_POSTURE.md`, `docs/ARBITRATION.md`, `ROADMAP.md` — drop the timelock claim, note 2-party flows

- [ ] **Step 1: Update docs** — DB version 18→19; add `funding_vout`; escrow sync kinds; release gate.

- [ ] **Step 2: Full verification**

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

Expected: BUILD SUCCESSFUL; lint baseline unchanged (66→0 findings).

- [ ] **Step 3: Live 2-party smoke (device + emulator, user-driven)**

Per user memory: OnePlus 7 e20e943a (seller), emulator-5554 (buyer), package `com.neop2p.app.debug`; user drives UI, assistant verifies via screencap/logcat. Scenario:
1. Seller creates SELL offer with payment details.
2. Buyer accepts, enters BTC address.
3. Seller sees escrow, funds via "Send from my wallet to escrow".
4. Verify both devices show FUNDED (kind:33337 synced).
5. Buyer markPaid + send receipt; seller confirmReceipt → payout broadcast (real testnet4).

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md android/AGENTS.md docs/ ROADMAP.md
git commit -m "docs: two-party escrow — DB v19, kind:33337 sync, release gate, buyer address"
```

---

## Self-Review (done inline)

- **Spec coverage:** P1 funding binding → Task 1-3; P1 release gate → Task 5 + UI Task 11; P2 sync → Tasks 7-9; P2 buyer address → Tasks 2,10; P2 timelock → Task 6; P3 dead code → Task 6; U1 address field → Task 10; U2 release UI → Task 11; U3 buyer entry → Task 12; U4 copy → Task 6 (strings) + Task 12 (banner); seller decline → Task 13. All findings mapped.
- **Placeholder scan:** none; every step has concrete code or exact commands.
- **Type consistency:** `Escrow.fundingVout: Long`, `TxOutput(scriptPubkeyAddress, valueSats, index)`, `canReleaseFromStatus(status: String)`, `publishEscrowStatus(escrowId, status, fields)` used consistently across tasks.
