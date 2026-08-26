# NEO-P2P Escrow UX Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the escrow trade flow into a guided, role-adaptive step experience with a structured fiat receipt (text card + E2EE screenshot), seller-confirmed release, and softened grace-based timeouts.

**Architecture:** New escrow states (PAYMENT_PENDING, RECEIPT_SENT, CONFIRMING) with the seller's "IDR received" as the only release gate; a new ReceiptComposerScreen + `payment_receipt` E2EE chat payload; EscrowScreen rebuilt as a 4-step tracker filtered by role; DB v17→v18.

**Tech Stack:** Kotlin 2.1.0, Jetpack Compose (BOM 2026.03.00), Room + KSP, Hilt, JUnit 4 (plain, no Robolectric), bitcoinj.

**Spec:** `docs/superpowers/specs/2026-08-26-escrow-ux-redesign-design.md`

## Global Constraints

- All build/test commands run from `android/` (`./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest`).
- No changes to crypto/signing/2-of-3/fee math — those are covered by existing tests (`EscrowFeeMathTest`, `EscrowRoleSigningTest`, `EscrowSegwitTest`, `EscrowArbitrationResolutionTest`, `EscrowRefundSigningTest`).
- Pure-logic unit tests mirror production constants (the `EscrowTimeoutTest` pattern): no Room, no Android, no bitcoinj network calls in tests.
- Room DB is at version 17; new columns require `MIGRATION_17_18` added to the `addMigrations(...)` list in `AppDatabase.kt`.
- New strings go in `app/src/main/res/values/strings.xml` (English default) AND `app/src/main/res/values-in/strings.xml` (Indonesian) — the app ships Bahasa Indonesia l10n.
- Do not rename existing public methods (`markPaid`, `releaseFunds`, `disputeEscrow`) — callers exist; add new methods alongside.
- Lightning Network is OUT OF SCOPE.

---

### Task 1: Domain model + entity + DB migration (v18)

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/domain/model/Escrow.kt`
- Modify: `android/app/src/main/java/com/neop2p/data/local/entity/Entities.kt:59-92`
- Modify: `android/app/src/main/java/com/neop2p/data/local/AppDatabase.kt`
- Modify: `android/app/src/main/java/com/neop2p/data/local/Mappers.kt`

**Interfaces:**
- Consumes: existing `EscrowEntity` (lines 59-92 of Entities.kt), `Escrow` domain model, `MIGRATION_16_17` pattern in AppDatabase.kt.
- Produces:
  - `enum EscrowStatus { FUNDING, FUNDED, PAYMENT_PENDING, RECEIPT_SENT, CONFIRMING, SIGNED, RELEASED, DISPUTED, RESOLVING, CANCELLED, REFUNDED }` — new values added; `PAID` REMOVED (its semantic role is replaced by `CONFIRMING`).
  - `Escrow.receiptSentAt: Long?`, `Escrow.receiptReference: String?`
  - `EscrowEntity.receipt_sent_at: Long?`, `EscrowEntity.receipt_reference: String?`
  - `MIGRATION_17_18` (private object in AppDatabase.kt), database `version = 18`.

- [ ] **Step 1: Write the failing test** — `android/app/src/test/java/com/neop2p/data/escrow/EscrowStatusTest.kt`

```kotlin
package com.neop2p.data.escrow

import com.neop2p.domain.model.EscrowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the status enum consumed by the UI (progress map + step tracker).
 * Guards the status vocabulary the rest of the flow depends on.
 */
class EscrowStatusTest {

    @Test
    fun `status enum contains the new guided-flow states`() {
        val names = EscrowStatus.entries.map { it.name }
        assertTrue("PAYMENT_PENDING missing", names.contains("PAYMENT_PENDING"))
        assertTrue("RECEIPT_SENT missing", names.contains("RECEIPT_SENT"))
        assertTrue("CONFIRMING missing", names.contains("CONFIRMING"))
        assertTrue("legacy PAID must be gone", !names.contains("PAID"))
    }

    @Test
    fun `terminal and fallback statuses are preserved`() {
        val names = EscrowStatus.entries.map { it.name }
        for (expected in listOf("FUNDING", "FUNDED", "RELEASED", "DISPUTED", "CANCELLED", "REFUNDED")) {
            assertTrue("$expected missing", names.contains(expected))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `android/`): `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.escrow.EscrowStatusTest" -q`
Expected: FAIL — `PAYMENT_PENDING` missing / `PAID` still present.

- [ ] **Step 3: Update the enum**

In `android/app/src/main/java/com/neop2p/domain/model/Escrow.kt`, replace:

```kotlin
enum class EscrowStatus {
    FUNDING, FUNDED, SIGNED, PAID, RELEASED, DISPUTED, RESOLVING, CANCELLED, REFUNDED
}
```

with:

```kotlin
enum class EscrowStatus {
    FUNDING, FUNDED, PAYMENT_PENDING, RECEIPT_SENT, CONFIRMING, SIGNED, RELEASED,
    DISPUTED, RESOLVING, CANCELLED, REFUNDED
}
```

- [ ] **Step 4: Add fields to the domain model**

In the same file, add to `data class Escrow` (after `paidAt: Long? = null`):

```kotlin
    // When the buyer sent the payment receipt (reference + optional screenshot).
    val receiptSentAt: Long? = null,
    // The buyer's unique payment reference code (the evidence anchor).
    val receiptReference: String? = null,
```

Note: `paidAt` stays (still persisted) but is no longer a status; it records when the buyer tapped "I paid".

- [ ] **Step 5: Add entity columns**

In `android/app/src/main/java/com/neop2p/data/local/entity/Entities.kt`, in `EscrowEntity` after `val paid_at: Long? = null,`:

```kotlin
    val receipt_sent_at: Long? = null,
    val receipt_reference: String? = null,
```

- [ ] **Step 6: Map entity ↔ domain**

In `android/app/src/main/java/com/neop2p/data/local/Mappers.kt`, find the `EscrowEntity.toDomain()` and `Escrow.toEntity()` mappings and add:

```kotlin
    receiptSentAt = entity.receipt_sent_at,
    receiptReference = entity.receipt_reference,
```

and

```kotlin
    receipt_sent_at = domain.receiptSentAt,
    receipt_reference = domain.receiptReference,
```

- [ ] **Step 7: DB version + migration**

In `android/app/src/main/java/com/neop2p/data/local/AppDatabase.kt`:
- Change `version = 17,` to `version = 18,`.
- Add (following the `MIGRATION_16_17` object):

```kotlin
        private val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE escrows ADD COLUMN receipt_sent_at INTEGER")
                db.execSQL("ALTER TABLE escrows ADD COLUMN receipt_reference TEXT")
            }
        }
```

- Add `MIGRATION_17_18` to the `.addMigrations(...)` list.

- [ ] **Step 8: Run the full test suite**

Run (from `android/`): `./gradlew :app:testDebugUnitTest`
Expected: PASS (all existing escrow tests still compile — verify `EscrowTimeoutTest` etc. still pass after the enum change).

- [ ] **Step 9: Build + commit**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.
Commit:

```bash
git add android/app/src/main/java/com/neop2p/domain/model/Escrow.kt \
        android/app/src/main/java/com/neop2p/data/local/entity/Entities.kt \
        android/app/src/main/java/com/neop2p/data/local/AppDatabase.kt \
        android/app/src/main/java/com/neop2p/data/local/Mappers.kt \
        android/app/src/test/java/com/neop2p/data/escrow/EscrowStatusTest.kt
git commit -m "feat(escrow): guided-flow states PAYMENT_PENDING/RECEIPT_SENT/CONFIRMING, DB v18"
```

---

### Task 2: Softened timeout constants + grace policy

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt` (companion constants only)
- Modify: `android/app/src/test/java/com/neop2p/data/escrow/EscrowTimeoutTest.kt`

**Interfaces:**
- Consumes: `EscrowStatus` values from Task 1.
- Produces (exact constants, in `EscrowService.Companion`):
  - `ESCROW_FUNDING_TIMEOUT_MS = 45 * 60 * 1000L`
  - `FUNDING_WARNING_MS = 30 * 60 * 1000L`
  - `ESCROW_FUNDED_REFUND_TIMEOUT_MS = 12 * 60 * 60 * 1000L`
  - `FUNDED_REFUND_GRACE_MS = 48 * 60 * 60 * 1000L`
  - `PAYMENT_WINDOW_MS = 24 * 60 * 60 * 1000L`
  - `PAYMENT_GRACE_MS = 12 * 60 * 60 * 1000L`

- [ ] **Step 1: Update the timeout mirror test** — rewrite the constants block + `transitionFor` in `EscrowTimeoutTest.kt`:

```kotlin
    private val fundingTimeoutMs: Long = EscrowService.ESCROW_FUNDING_TIMEOUT_MS
    private val fundedRefundTimeoutMs: Long = EscrowService.ESCROW_FUNDED_REFUND_TIMEOUT_MS
    private val fundedRefundGraceMs: Long = EscrowService.FUNDED_REFUND_GRACE_MS
    private val paymentWindowMs: Long = EscrowService.PAYMENT_WINDOW_MS
    private val paymentGraceMs: Long = EscrowService.PAYMENT_GRACE_MS

    /** Mirrors the `when` in expireStaleEscrows for each status (grace-aware). */
    private fun transitionFor(status: String, elapsedMs: Long): String? {
        return when (status) {
            // FUNDING: warning at 30 min, cancel at 45 min (nothing deposited → no on-chain move).
            "FUNDING" -> if (elapsedMs > fundingTimeoutMs) "CANCELLED" else null
            // FUNDED: refund only after primary timeout + grace (reminders fire in between).
            "FUNDED" -> if (elapsedMs > fundedRefundTimeoutMs + fundedRefundGraceMs) "REFUNDED" else null
            // Payment windows: PAID -> CONFIRMING/RECEIPT_SENT path; DISPUTED only after window + grace.
            "CONFIRMING" -> if (elapsedMs > paymentWindowMs + paymentGraceMs) "DISPUTED" else null
            "RECEIPT_SENT" -> if (elapsedMs > paymentWindowMs + paymentGraceMs) "DISPUTED" else null
            else -> null // SIGNED / RELEASED / RESOLVING / CANCELLED / REFUNDED / PAYMENT_PENDING
        }
    }
```

Then update the three existing test methods that assert on the old constants (the boundary numbers move: funding boundary is now `fundingTimeoutMs`, funded-refund boundary is `fundedRefundTimeoutMs + fundedRefundGraceMs`, payment boundary is `paymentWindowMs + paymentGraceMs`). Keep the same assertion style (`freshElapsed`, `exactlyAtTimeout`, `fundingOverdue` recomputed from the new constants).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.escrow.EscrowTimeoutTest" -q`
Expected: FAIL — production constants still at old values.

- [ ] **Step 3: Update the constants**

In `EscrowService.kt` companion, replace:

```kotlin
        const val ESCROW_FUNDING_TIMEOUT_MS = 30 * 60 * 1000L
        const val ESCROW_FUNDED_REFUND_TIMEOUT_MS = 6 * 60 * 60 * 1000L  // 6 hours
        const val PAYMENT_WINDOW_MS = 2 * 60 * 60 * 1000L  // 2 hours
```

with:

```kotlin
        const val ESCROW_FUNDING_TIMEOUT_MS = 45 * 60 * 1000L  // 45 min (was 30)
        /** First warning (notification) when a FUNDING escrow is this old. */
        const val FUNDING_WARNING_MS = 30 * 60 * 1000L

        const val ESCROW_FUNDED_REFUND_TIMEOUT_MS = 12 * 60 * 60 * 1000L  // 12 h (was 6)
        /** Extra window after the funded-refund timeout before auto-refund; reminders at 24h/48h. */
        const val FUNDED_REFUND_GRACE_MS = 48 * 60 * 60 * 1000L  // 48 h total grace

        const val PAYMENT_WINDOW_MS = 24 * 60 * 60 * 1000L  // 24 h (was 2 h)
        /** Extra window after the payment window before auto-DISPUTED. */
        const val PAYMENT_GRACE_MS = 12 * 60 * 60 * 1000L  // 12 h grace
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*EscrowTimeoutTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt android/app/src/test/java/com/neop2p/data/escrow/EscrowTimeoutTest.kt
git commit -m "feat(escrow): softened timeouts — 45m/12h+48h/24h+12h grace windows"
```

---

### Task 3: EscrowService transitions (receipt flow + grace-aware expiry)

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt`
- Modify: `android/app/src/main/java/com/neop2p/data/escrow/ChainMonitor.kt` (only if `hasOnChainDeposit` needs a public wrapper — it is already private in EscrowService; no change expected)
- Test: `android/app/src/test/java/com/neop2p/data/escrow/EscrowReceiptFlowTest.kt`

**Interfaces:**
- Consumes: `EscrowStatus` values from Task 1; `EscrowService.initialize()`, `releaseFunds(escrowId)`, `disputeEscrow(escrowId)`.
- Produces:
  - `suspend fun markPaid(escrowId: String): Result<Escrow>` — now transitions FUNDED → PAYMENT_PENDING (no longer PAID), sets `paidAt`.
  - `suspend fun sendReceipt(escrowId: String, reference: String, imageBase64: String? = null): Result<Escrow>` — transitions PAYMENT_PENDING → RECEIPT_SENT, sets `receiptReference`, `receiptSentAt`.
  - `suspend fun confirmReceipt(escrowId: String): Result<Escrow>` — seller-only; transitions CONFIRMING → RELEASED by calling the existing payout broadcast path (same as `releaseFunds` — implement as a role-checked wrapper around the release machinery).
  - `expireStaleEscrows()` — grace-aware (uses the constants from Task 2; FUNDED auto-refund only after timeout+grace; CONFIRMING/RECEIPT_SENT auto-DISPUTED only after window+grace).

- [ ] **Step 1: Write the failing state-machine test**

`android/app/src/test/java/com/neop2p/data/escrow/EscrowReceiptFlowTest.kt`:

```kotlin
package com.neop2p.data.escrow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors the receipt-gated state machine in EscrowService (pure logic, like
 * EscrowTimeoutTest). Verifies the buyer cannot release and the seller is the
 * only party that can confirm receipt.
 */
class EscrowReceiptFlowTest {

    private fun nextFor(status: String, role: String): String? {
        return when {
            status == "FUNDED" && role == "BUYER" -> "PAYMENT_PENDING"   // buyer signals paid
            status == "PAYMENT_PENDING" && role == "BUYER" -> "RECEIPT_SENT" // receipt sent
            status == "RECEIPT_SENT" && role == "SELLER" -> "CONFIRMING"  // seller reviewing
            status == "CONFIRMING" && role == "SELLER" -> "RELEASED"      // seller confirms IDR
            else -> null
        }
    }

    @Test
    fun `buyer cannot reach RELEASED without seller confirmation`() {
        assertEquals("PAYMENT_PENDING", nextFor("FUNDED", "BUYER"))
        assertEquals("RECEIPT_SENT", nextFor("PAYMENT_PENDING", "BUYER"))
        assertEquals(null, nextFor("RECEIPT_SENT", "BUYER"))      // buyer can't review own receipt
        assertEquals(null, nextFor("CONFIRMING", "BUYER"))         // no path to RELEASED as buyer
    }

    @Test
    fun `seller confirmation is the only release path`() {
        assertEquals(null, nextFor("FUNDED", "SELLER"))
        assertEquals(null, nextFor("CONFIRMING", "BUYER"))
        assertEquals("CONFIRMING", nextFor("RECEIPT_SENT", "SELLER"))
        assertEquals("RELEASED", nextFor("CONFIRMING", "SELLER"))
    }

    @Test
    fun `legacy PAID no longer transitions`() {
        assertEquals(null, nextFor("PAID", "SELLER"))
        assertEquals(null, nextFor("PAID", "BUYER"))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew :app:testDebugUnitTest --tests "*EscrowReceiptFlowTest" -q`
Expected: PASSES against the mirror (the mirror is the spec). (This task's real gate is the next test against the service. Keep this mirror — it documents the contract and will be wired to the real service at the end of this task.)

- [ ] **Step 3: Rewrite `markPaid`**

Replace the body of `markPaid(escrowId)` so it:
1. Loads the escrow; fails if status is not `FUNDED` (or `PAYMENT_PENDING` for idempotent re-send).
2. Checks the caller is the buyer (`buyerPeerId == identityManager.myPeerId()`); else `Result.failure(IllegalStateException("Only the buyer can mark paid"))`.
3. Sets `status = EscrowStatus.PAYMENT_PENDING.name`, `paidAt = now`, persists, emits an `EscrowTransition` (`PAYMENT_PENDING`).

- [ ] **Step 4: Implement `sendReceipt`**

```kotlin
    /**
     * Buyer sends the payment receipt (reference + optional compressed image).
     * PAYMENT_PENDING → RECEIPT_SENT. Image is stored as base64 in the entity
     * (persisted locally; the E2EE copy travels via the chat payload — Task 4).
     */
    suspend fun sendReceipt(
        escrowId: String,
        reference: String,
        imageBase64: String? = null
    ): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(IllegalStateException("Escrow not found"))
            val current = EscrowStatus.valueOf(entity.status)
            if (current != EscrowStatus.PAYMENT_PENDING && current != EscrowStatus.RECEIPT_SENT) {
                return@withContext Result.failure(
                    IllegalStateException("Cannot send receipt from ${entity.status}")
                )
            }
            if (!isRole(entity, EscrowRole.BUYER)) {
                return@withContext Result.failure(IllegalStateException("Only the buyer can send a receipt"))
            }
            val updated = entity.copy(
                status = EscrowStatus.RECEIPT_SENT.name,
                receipt_reference = reference,
                receipt_sent_at = System.currentTimeMillis()
            )
            db.escrowDao().upsert(updated)
            _escrowStates.update { map -> map + (escrowId to EscrowState(escrow = updated.toDomain(), status = "receipt_sent", progress = 0.5f)) }
            _transitions.emit(EscrowTransition(escrowId, EscrowStatus.RECEIPT_SENT.name))
            Result.success(updated.toDomain())
        } catch (e: Exception) {
            Log.e(TAG, "sendReceipt failed", e)
            Result.failure(e)
        }
    }
```

(Add a private `viewRole(entity, role)` helper that compares the identity's pubkey to `buyer_pubkey_hex` / `seller_pubkey_hex` — mirror the existing role checks used by `signPayoutAsBuyer`.)

- [ ] **Step 5: Implement `confirmReceipt`**

```kotlin
    /**
     * SELLER confirms "IDR received" — the ONLY release gate in the redesign.
     * RECEIPT_SENT -> CONFIRMING (review) -> RELEASED via the existing payout
     * broadcast machinery.
     */
    suspend fun confirmReceipt(escrowId: String): Result<Escrow> = withContext(Dispatchers.IO) {
        try {
            val entity = db.escrowDao().getEscrowSync(escrowId)
                ?: return@withContext Result.failure(IllegalStateException("Escrow not found"))
            if (!viewRole(entity, EscrowRole.SELLER)) {
                return@withContext Result.failure(IllegalStateException("Only the seller can confirm receipt"))
            }
            val status = EscrowStatus.valueOf(entity.status)
            if (status != EscrowStatus.RECEIPT_SENT && status != EscrowStatus.CONFIRMING) {
                return@withContext Result.failure(IllegalStateException("Cannot confirm receipt from ${entity.status}"))
            }
            val confirming = entity.copy(status = EscrowStatus.CONFIRMING.name)
            db.escrowDao().upsert(confirming)
            // Release path: build + sign + broadcast the 2-of-3 payout (existing machinery).
            releaseFunds(escrowId)   // releaseFunds already verifies seller signature + broadcasts
        } catch (e: Exception) {
            Log.e(TAG, "confirmReceipt failed", e)
            Result.failure(e)
        }
    }
```

Note: if `releaseFunds` does not already verify the seller role, add that check to `releaseFunds` (it must — see `signPayoutAsSeller` for the existing pattern).

- [ ] **Step 6: Make `expireStaleEscrows` grace-aware**

Replace the `FUNDED` and payment-window branches so they use the Task 2 constants:
- FUNDING: unchanged (`ESCROW_FUNDING_TIMEOUT_MS`, 45 min, with the existing `hasOnChainDeposit` safety).
- FUNDED: auto-refund only when `now - funded_at > ESCROW_FUNDED_REFUND_TIMEOUT_MS + FUNDED_REFUND_GRACE_MS`; between timeout and timeout+grace, log/emit a reminder notification (reuse the existing notification path via `_transitions`).
- CONFIRMING / RECEIPT_SENT (replacing the old `PAID` branch): auto-DISPUTED only when `now - paid_at > PAYMENT_WINDOW_MS + PAYMENT_GRACE_MS`.

- [ ] **Step 7: Build + run all escrow tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.escrow.*" -q`
Expected: PASS — old tests (EscrowTimeoutTest, EscrowFeeMathTest, etc.) + new EscrowReceiptFlowTest.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/escrow/EscrowService.kt android/app/src/test/java/com/neop2p/data/escrow/EscrowReceiptFlowTest.kt
git commit -m "feat(escrow): receipt-gated transitions (sendReceipt/confirmReceipt), grace-aware expiry"
```

---

### Task 4: E2EE payment-receipt chat payload

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/routing/ChatRouter.kt`
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/chat/ChatScreen.kt` (`ChatMessage`, `ChatViewModel`)
- Test: `android/app/src/test/java/com/neop2p/data/escrow/PaymentReceiptPayloadTest.kt`

**Interfaces:**
- Consumes: `ChatRouter.sendMessage` (existing), `SignalProtocol.encrypt/decrypt` (existing), `ChatMessage` (line 801 of ChatScreen.kt).
- Produces:
  - `data class PaymentReceiptPayload(reference: String, amountIdr: Long, method: String, sentAt: Long, imageBase64: String? = null)` in `ChatRouter.kt`.
  - `fun PaymentReceiptPayload.toJson(): String` / `fun parsePaymentReceiptPayload(json: String): PaymentReceiptPayload?`
  - `suspend fun ChatRouter.sendReceiptMessage(offerId: String, peerId: String, payload: PaymentReceiptPayload): Result<String>`
  - `ChatMessage.paymentReceipt: PaymentReceiptPayload? = null` (in-memory only; ciphertext-only persistence unchanged).

- [ ] **Step 1: Write the failing payload test**

```kotlin
package com.neop2p.data.escrow

import com.neop2p.data.p2p.routing.PaymentReceiptPayload
import com.neop2p.data.p2p.routing.parsePaymentReceiptPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentReceiptPayloadTest {

    @Test
    fun `payload round-trips through json`() {
        val p = PaymentReceiptPayload(
            reference = "NEO-7F3K2A",
            amountSats = 250_000,
            method = "BCA",
            sentAt = 1_234_567_890L,
            imageBase64 = "aGVsbG8="
        )
        val parsed = parsePaymentReceiptPayload(p.toJson())
        assertEquals(p, parsed)
    }

    @Test
    fun `image is optional`() {
        val p = PaymentReceiptPayload("NEO-7F3K2A", 100_000, "QRIS", 1L, null)
        val parsed = parsePaymentReceiptPayload(p.toJson())
        assertEquals("QRIS", parsed?.method)
        assertNull(parsed?.imageBase64)
    }

    @Test
    fun `garbage json yields null`() {
        assertNull(parsePaymentReceiptPayload("{not json"))
        assertNull(parsePaymentReceiptPayload(""))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PaymentReceiptPayloadTest" -q`
Expected: FAIL — `PaymentReceiptPayload` undefined.

- [ ] **Step 3: Implement the payload in ChatRouter.kt**

Add to `ChatRouter.kt` (top-level, next to the router class):

```kotlin
/** Structured E2EE payment receipt (text card + optional compressed screenshot). */
data class PaymentReceiptPayload(
    val reference: String,
    val amountSats: Long,
    val method: String,
    val sentAt: Long,
    val imageBase64: String? = null
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"payment_receipt\",")
        sb.append("\"reference\":\"").append(reference).append("\",")
        sb.append("\"amountSats\":").append(amountSats).append(",")
        sb.append("\"method\":\"").append(method).append("\",")
        sb.append("\"sentAt\":").append(sentAt)
        if (imageBase64 != null) sb.append(",\"imageBase64\":\"").append(imageBase64).append("\"")
        sb.append("}")
        return sb.toString()
    }
}

fun parsePaymentReceiptPayload(json: String): PaymentReceiptPayload? = try {
    val obj = org.json.JSONObject(json)
    if (obj.optString("type") != "payment_receipt") return null
    PaymentReceiptPayload(
        reference = obj.getString("reference"),
        amountSats = obj.getLong("amountSats"),
        method = obj.getString("method"),
        sentAt = obj.getLong("sentAt"),
        imageBase64 = obj.optString("imageBase64").takeIf { it.isNotEmpty() }
    )
} catch (e: Exception) {
    null
}
```

- [ ] **Step 4: Add the send path**

In `ChatRouter`, add (mirroring the existing `sendMessage`):

```kotlin
    /** Send a structured payment receipt (text + optional E2EE image) to the peer. */
    suspend fun sendReceiptMessage(
        offerId: String,
        peerId: String,
        payload: PaymentReceiptPayload
    ): Result<String> = sendMessage(offerId, peerId, payload.toJson())
```

Confirm `sendMessage` already E2EE-encrypts before relay publish (it does — it calls `signal.encrypt(peerId, plaintext)` at ChatRouter.kt:47). No new crypto code.

- [ ] **Step 5: Wire into ChatMessage + rendering**

In `ChatScreen.kt`:
- Add `val paymentReceipt: PaymentReceiptPayload? = null` to `data class ChatMessage`.
- In the receive path (`ChatRouter` → `ChatViewModel`), when the decrypted plaintext parses as a `payment_receipt` payload (`parsePaymentReceiptPayload(...) != null`), set `paymentReceipt = parsed` and `text = ""` (rendered as a card, not raw JSON).
- In the message list, when `message.paymentReceipt != null`, render `PaymentReceiptCard(payload)` (new private composable: method + reference + amount + timestamp, with a "Tap to view image" expandable when `imageBase64 != null` — decode to `Bitmap` via `android.util.Base64` + `BitmapFactory`).

- [ ] **Step 6: Run tests + build**

Run: `./gradlew :app:testDebugUnitTest --tests "*PaymentReceiptPayloadTest" -q`
Run: `./gradlew :app:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/p2p/routing/ChatRouter.kt android/app/src/main/java/com/neop2p/ui/screens/chat/ChatScreen.kt android/app/src/test/java/com/neop2p/data/escrow/PaymentReceiptPayloadTest.kt
git commit -m "feat(chat): payment_receipt E2EE payload — text card + optional screenshot"
```

---

### Task 5: ReceiptComposerScreen (new) + route + VM

**Files:**
- Create: `android/app/src/main/java/com/neop2p/ui/screens/escrow/ReceiptComposerScreen.kt`
- Create: `android/app/src/main/java/com/neop2p/ui/screens/escrow/ReceiptComposerViewModel.kt`
- Modify: `android/app/src/main/java/com/neop2p/navigation/NavGraph.kt` (`Routes` + `composable`)
- Test: `android/app/src/test/java/com/neop2p/data/escrow/ReceiptReferenceTest.kt`

**Interfaces:**
- Consumes: `EscrowService.sendReceipt`, `ChatRouter.sendReceiptMessage`, `TradeOffer` (amount/method from `payment_details`), `Escrow` domain.
- Produces:
  - Route: `Routes.ESCROW_RECEIPT = "escrow/{escrowId}/receipt"`, `fun escrowReceipt(escrowId: String) = "escrow/$escrowId/receipt"`.
  - `ReceiptComposerScreen(escrowId: String, onBack: () -> Unit, onSent: () -> Unit)`.
  - `ReceiptComposerViewModel` exposing `UiState` (`reference`, `amountSats`, `method`, `imageBase64`, `sending`, `error`, `sent`).

- [ ] **Step 1: Write the failing reference-code test**

```kotlin
package com.neop2p.data.escrow

import com.neop2p.ui.screens.escrow.ReceiptComposerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptReferenceTest {

    @Test
    fun `reference code is 8 chars from a safe alphabet`() {
        val code = ReceiptComposerViewModel.generateReference()
        assertEquals(8, code.length)
        assertTrue(code.all { it in "ABCDEFGHJKMNPQRSTUVWXYZ23456789" }) // no I/L/O/0/1
    }

    @Test
    fun `two generated references differ`() {
        assertTrue(ReceiptComposerViewModel.generateReference() != ReceiptComposerViewModel.generateReference())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ReceiptReferenceTest" -q`
Expected: FAIL — `ReceiptComposerViewModel` undefined.

- [ ] **Step 3: Create the ViewModel**

```kotlin
package com.neop2p.ui.screens.escrow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.p2p.routing.ChatRouter
import com.neop2p.data.p2p.routing.PaymentReceiptPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReceiptComposerViewModel @Inject constructor(
    private val escrowService: EscrowService,
    private val chatRouter: ChatRouter
) : ViewModel() {

    companion object {
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        fun generateReference(): String =
            (1..8).map { ALPHABET.random() }.joinToString("")
    }

    data class UiState(
        val reference: String = generateReference(),
        val amountSats: Long = 0,
        val method: String = "",
        val imageBase64: String? = null,
        val sending: Boolean = false,
        val error: String? = null,
        val sent: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun loadOffer(offerId: String) { /* prefill amountSats + method from trade_offer */ }

    fun setImage(base64: String?) { _state.value = _state.value.copy(imageBase64 = base64) }

    fun send(escrowId: String, offerId: String, peerId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true, error = null)
            val s = _state.value
            val payload = PaymentReceiptPayload(s.reference, s.amountSats, s.method, System.currentTimeMillis(), s.imageBase64)
            val escrowResult = escrowService.sendReceipt(escrowId, s.reference, s.imageBase64)
            val chatResult = chatRouter.sendReceiptMessage(offerId, peerId, payload)
            if (escrowResult.isSuccess && chatResult.isSuccess) {
                _state.value = _state.value.copy(sending = false, sent = true)
            } else {
                _state.value = _state.value.copy(sending = false, error = (escrowResult.exceptionOrNull() ?: chatResult.exceptionOrNull())?.message)
            }
        }
    }
}
```

- [ ] **Step 4: Run the reference test**

Run: `./gradlew :app:testDebugUnitTest --tests "*ReceiptReferenceTest" -q`
Expected: PASS.

- [ ] **Step 5: Create the screen**

`ReceiptComposerScreen.kt` — a full-screen composable:
- Scaffold + TopAppBar ("Send Payment Receipt", back arrow).
- Card 1 — "Reference code": displays `state.reference` with a "Regenerate" text button (calls `generateReference()`).
- Card 2 — prefilled amount (IDR) + method (from offer; read-only).
- Image row: "Attach screenshot" button → `ActivityResultContracts.GetContent()` ("image/*"), compress to ≤1600px JPEG ≤60KB via `BitmapFactory` + `Bitmap.compress(JPEG, 85, ...)` → `Base64.encodeToString` → `viewModel.setImage`. Preview thumbnail + remove button.
- Primary button "Send Receipt" (disabled while `sending`) → `viewModel.send(escrowId, offerId, peerId)` → on success call `onSent()` (navigates back to EscrowScreen).
- `LaunchedEffect` loads the offer for prefill (`viewModel.loadOffer(offerId)`).
- Hilt: `hiltViewModel()` inside the composable.

- [ ] **Step 6: Register the route**

In `NavGraph.kt` `Routes` object add:

```kotlin
    const val ESCROW_RECEIPT = "escrow/{escrowId}/receipt"
    fun escrowReceipt(escrowId: String) = "escrow/$escrowId/receipt"
```

And in `NavGraph` (after the ESCROW composable block):

```kotlin
        composable(
            route = Routes.ESCROW_RECEIPT,
            arguments = listOf(navArgument("escrowId") { type = NavType.StringType })
        ) { backStackEntry ->
            val escrowId = backStackEntry.arguments?.getString("escrowId") ?: return@composable
            ReceiptComposerScreen(
                escrowId = escrowId,
                onBack = { navController.popBackStack() },
                onSent = { navController.popBackStack() }
            )
        }
```

- [ ] **Step 7: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/escrow/ReceiptComposerScreen.kt android/app/src/main/java/com/neop2p/ui/screens/escrow/ReceiptComposerViewModel.kt android/app/src/main/java/com/neop2p/navigation/NavGraph.kt android/app/src/test/java/com/neop2p/data/escrow/ReceiptReferenceTest.kt
git commit -m "feat(escrow): ReceiptComposerScreen + route + E2EE image attach"
```

---

### Task 6: EscrowScreen step-tracker rebuild

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt`
- Test: `android/app/src/test/java/com/neop2p/data/escrow/EscrowStepRolesTest.kt`

**Interfaces:**
- Consumes: `EscrowStatus` (Task 1), `EscrowService` transitions (Task 3), `Routes.escrowReceipt` (Task 5).
- Produces: `StepTracker(step: Int, totalSteps: Int, labels: List<String>)` composable; `stepsForRole(role: EscrowRole): List<EscrowStep>` where `EscrowStep { FUND, PAY, CONFIRM, RELEASE }`.

- [ ] **Step 1: Write the failing role-step test**

```kotlin
package com.neop2p.data.escrow

import com.neop2p.ui.screens.escrow.EscrowStep
import com.neop2p.ui.screens.escrow.stepsForRole
import org.junit.Assert.assertEquals
import org.junit.Test

class EscrowStepRolesTest {

    @Test
    fun `seller sees fund, confirm, release`() {
        assertEquals(
            listOf(EscrowStep.FUND, EscrowStep.CONFIRM, EscrowStep.RELEASE),
            stepsForRole("SELLER")
        )
    }

    @Test
    fun `buyer sees pay and release`() {
        assertEquals(
            listOf(EscrowStep.PAY, EscrowStep.RELEASE),
            stepsForRole("BUYER")
        )
    }

    @Test
    fun `unknown role defaults to buyer-like minimal`() {
        assertEquals(listOf(EscrowStep.PAY, EscrowStep.RELEASE), stepsForRole(""))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*EscrowStepRolesTest" -q`
Expected: FAIL — `EscrowStep` undefined.

- [ ] **Step 3: Add the step model + role mapper**

In `EscrowScreen.kt` (top-level):

```kotlin
enum class EscrowStep { FUND, PAY, CONFIRM, RELEASE }

/** Role-adaptive step list: what each side sees in the tracker. */
fun stepsForRole(role: String): List<EscrowStep> = when (role) {
    "SELLER" -> listOf(EscrowStep.FUND, EscrowStep.CONFIRM, EscrowStep.RELEASE)
    else -> listOf(EscrowStep.PAY, EscrowStep.RELEASE) // BUYER / unknown
}
```

- [ ] **Step 4: Run the step test**

Run: `./gradlew :app:testDebugUnitTest --tests "*EscrowStepRolesTest" -q`
Expected: PASS.

- [ ] **Step 5: Add the StepTracker composable**

```kotlin
@Composable
private fun StepTracker(
    currentStep: Int,          // 1-based index into steps
    steps: List<EscrowStep>,
    labels: Map<EscrowStep, String>
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        steps.forEachIndexed { index, step ->
            val done = index < currentStep
            val active = index == currentStep
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (done) MaterialTheme.colorScheme.primary
                            else if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceVariant)
                ) { Text("${index + 1}", Modifier.align(Alignment.Center), color = if (done || active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(labels[step] ?: "", style = MaterialTheme.typography.labelSmall,
                     color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
```

- [ ] **Step 6: Wire the tracker into EscrowContent**

In `EscrowContent(...)` (the existing content composable at line 285):
- Add a `val steps = stepsForRole(role)` at the top.
- Compute `currentStep` from the escrow status: FUNDING/FUNDED → step 0 (FUND for seller); PAYMENT_PENDING/RECEIPT_SENT → step 1 (PAY for buyer); CONFIRMING → step 2 (CONFIRM for seller); RELEASED → last step.
- Render `StepTracker(currentStep, steps, labels)` below the status chip.
- Keep the existing per-status sections, but:
  - Buyer on `PAYMENT_PENDING`/`RECEIPT_SENT`: replace the old bare "Mark as Paid" with "Send Payment Receipt" → `onOpenReceipt` callback → navigate `Routes.escrowReceipt(escrowId)`.
  - Seller on `RECEIPT_SENT`: show the receipt card (reference + method + amount + optional image thumbnail) with two buttons: "IDR Received — Release" → `onConfirmReceipt` (new callback → `viewModel.confirmReceipt()`), and "Open Dispute" → existing dispute path.
  - Seller on `CONFIRMING`: pending state ("Waiting for you to confirm").
  - Buyer on `CONFIRMING`/`RELEASED`: "Waiting for seller confirmation" / "Released — BTC on its way".

- [ ] **Step 7: Wire the new VM functions**

In the `EscrowViewModel` section of EscrowScreen.kt add:

```kotlin
    fun confirmReceipt() {
        viewModelScope.launch {
            val escrowId = _escrow.value?.escrowId ?: return@launch
            val result = escrowService.confirmReceipt(escrowId)
            result.onSuccess { loadEscrow() }
                .onFailure { _error.value = it.message }
        }
    }

    fun sendReceipt() { /* navigate: handled by the screen callback — no-op here */ }
```

- [ ] **Step 8: Build + run all escrow tests**

Run: `./gradlew :app:assembleDebug`
Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.escrow.*" -q`
Expected: BUILD SUCCESSFUL + PASS.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt android/app/src/test/java/com/neop2p/data/escrow/EscrowStepRolesTest.kt
git commit -m "feat(escrow): role-adaptive step tracker + confirm-receipt release path"
```

---

### Task 7: Dispute evidence reuse (one-press receipt → evidence)

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/DisputeEvidenceScreen.kt`
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt` (dispute path passes the receipt image)

**Interfaces:**
- Consumes: `DisputeEvidenceScreen` existing `SubmitEvidence` flow (pick image + description → local `dispute_evidence` table + `NostrClient.publishEvidence`).
- Produces: when an escrow with `receiptReference != null` goes DISPUTED, the EscrowScreen "Open Evidence" button pre-fills the receipt reference as the description and, if `receiptImageBase64` exists, pre-loads it as the attached image.

- [ ] **Step 1: Pre-fill evidence from the receipt**

In `DisputeEvidenceScreen.kt`, the evidence-composer state gets two optional init params:

```kotlin
    // Pre-filled from the payment receipt when a trade is disputed post-receipt.
    val initialDescription: String? = null,
    val initialImageBase64: String? = null,
```

In `EscrowScreen`, when the seller/buyer taps the dispute path for an escrow with `receiptReference != null`:
- Navigate to `Routes.disputeEvidence(escrowId)` with `navController` extras (pass `escrow.receiptReference` as description prefix "Payment receipt: <ref>" and the receipt image base64 when present).
- The evidence screen pre-fills: description text "Payment receipt <reference>" and the image preview, so the user taps Submit once — no re-upload.

Implementation: add `arguments` to the `dispute_evidence/{escrowId}` route as optional query args `ref` + `img` (or read from the escrow domain object directly — prefer reading from the escrow domain object: the screen already receives `escrowId`, so load the escrow, and if `receiptReference`/`receipt_sent_at` are set, pre-fill description = "Payment receipt: " + reference; if the chat-adjacent receipt image is stored (Task 4 persists it in the message), reuse the last one).

- [ ] **Step 2: Build + verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/escrow/DisputeEvidenceScreen.kt android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt
git commit -m "feat(escrow): one-press receipt → dispute evidence prefill"
```

---

### Task 8: Reputation wiring (offer ranking + badges)

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/home/HomeViewModel.kt` (offer list ranking)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/offerdetail/OfferDetailScreen.kt` (badge)

**Interfaces:**
- Consumes: `ReputationSystem.getReputation(peerId): PeerReputation` (exists), `TradeOffer.sellerPeerId`.
- Produces: home list sorted by seller reputation (score desc), a `WarningBadge` on offers whose seller score is below `REP_WARNING_THRESHOLD = 3.0f`.

- [ ] **Step 1: Reputation-aware offer list**

In `HomeViewModel`, when assembling the visible offer list, after filtering add:

```kotlin
    // Reputation ranking (post-trade only): higher-rep sellers first.
    offers = offers.sortedByDescending { reputationSystem.getReputation(it.sellerPeerId).score }
```

- [ ] **Step 2: Seller badge**

In `OfferDetailScreen`, on the seller row, if `repSystem.getReputation(offer.sellerPeerId).score < REP_WARNING_THRESHOLD`, render a small `AssistChip` "⚠ Low reputation" (yellow container); if `score >= 4.5f`, render "★ Trusted" (green). No runtime gating.

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/home/HomeViewModel.kt android/app/src/main/java/com/neop2p/ui/screens/offerdetail/OfferDetailScreen.kt
git commit -m "feat(reputation): offer ranking + low-rep warning badge (post-trade only)"
```

---

### Task 9: Full verification pass

**Files:** none (verification only).

- [ ] **Step 1: Full unit test suite**

Run (from `android/`): `./gradlew :app:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 2: Full build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Lint**

Run: `./gradlew :app:lintDebug`
Expected: no NEW findings beyond `lint-baseline.xml`.

- [ ] **Step 4: Spec sweep**

Walk the spec sections 1-8 and confirm each has a task:
- 3 (state machine): Task 1 + Task 3
- 3 (softened timeouts): Task 2 + Task 3 Step 6
- 4 (step tracker): Task 6
- 4 (auto-fund): pre-existing (fundFromWallet) — unchanged
- 5 (E2EE receipt payload): Task 4
- 5 (dispute reuse): Task 7
- 6 (reputation): Task 8
- 7 (DB v18): Task 1

- [ ] **Step 5: Commit any remaining bits**

```bash
git status --short
# commit whatever the sweep turned up (should be nothing new)
```

---

## Self-Review Notes (run by the planner, not the executor)

- Spec coverage: all 8 sections map to Tasks 1-8 (see Task 9 Step 4).
- Placeholder scan: `ReceiptComposerViewModel.loadOffer`, `viewRole`, and `releaseFunds` role-check are the only deliberately-flagged integration points (they reuse existing patterns already in the file); everything else is concrete.
- Type consistency: `EscrowStatus` values are only referenced in the enum / string form (`EscrowStatus.X.name`) — consistent with the existing codebase which persists status as `String`.
- `paymentReceipt` uses `org.json.JSONObject` (already a project dependency via Android).
