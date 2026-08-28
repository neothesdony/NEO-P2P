# NEO-P2P Debug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the seven gaps found in the Flow-1 debug pass + deep check: the SIGNED zombie escrow state, non-durable onboarding gate, timeout-constant drift, missing seed recovery UI, unguarded restore, uncapped dispute evidence, and three small hygiene items.

**Architecture:** All fixes are local-state or pure-logic changes — no new transports, no backend, no schema migration (Room DB stays at version 21). Pure decision logic is extracted into JVM-testable objects mirroring the existing `EscrowRouterApplyTest` / `EscrowTimeoutTest` / `OfferClaimGateTest` pattern; Android-bound code (KeyStore, BitmapFactory, BiometricPrompt) is verified by assemble + manual device steps.

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.3, plain JUnit 4 (no Robolectric), Room 21, Hilt, Compose. JDK 17 pinned machine-wide via `~/.gradle/gradle.properties` (do NOT touch).

**Spec:** `AGENTS.md` (root + `android/AGENTS.md`), `docs/SECURITY_POSTURE.md`, and the debug-pass findings from 2026-08-28 (SIGNED zombie, onboarding gate, timeout drift, seed recovery, restore guard, evidence cap, hygiene).

## Global Constraints

- All Gradle commands run from `android/` (the root repo is intentionally not a Gradle project).
- No new dependencies. No Room schema change (DB stays v21). No new transports/backend.
- Money is never floating point; IDR integer rupiah, BTC satoshi.
- Every user-facing error keeps a Bahasa Indonesia string + machine reason code (`ErrorCodes`).
- New UI strings must be added to BOTH `app/src/main/res/values/strings.xml` (EN) and `app/src/main/res/values-in/strings.xml` (ID).
- Never log seeds, mnemonics, private keys, or full payment account numbers.
- Test command: `./gradlew :app:testDebugUnitTest` (from `android/`). Build: `./gradlew :app:assembleDebug`.
- Follow existing patterns: pure logic mirrored in test files named after user-visible scenarios; `EscrowRouterApplyTest` style for router rules, `EscrowTimeoutTest` style for sweep rules.

---

### Task 1: Fix the SIGNED zombie escrow state (P1 — money safety)

**Problem:** `generatePayoutTransaction()` persists `status = SIGNED` (EscrowService.kt:861) and is only called from `confirmReceipt` (EscrowService.kt:1384), which then re-fetches and sets CONFIRMING. A process kill between those two upserts leaves the escrow SIGNED forever:
- `EscrowRouter.applyRemoteStatus` forward order (EscrowRouter.kt:88-95) has no SIGNED → `li == -1` → the buyer's PAYMENT_PENDING/RECEIPT_SENT events are REJECTED → split-brain (seller SIGNED, buyer PAYMENT_PENDING).
- `expireStaleEscrows` (EscrowService.kt:400-510) has no SIGNED branch → no auto-refund, no auto-dispute.
- `getEscrow` resume-heal (EscrowService.kt:228-233) does not re-publish SIGNED → no heal.
- `confirmReceipt` status gate (EscrowService.kt:1360) rejects SIGNED → the seller cannot retry; only exit is manual dispute.

**Files:**
- Modify: `app/src/main/java/com/neop2p/data/p2p/routing/EscrowRouter.kt:88-95` (order list) and `:102-112` (ALLOWED_REMOTE)
- Modify: `app/src/main/java/com/neop2p/data/escrow/EscrowService.kt:400-510` (sweep `when`), `:228-233` (resume-heal condition), `:1360` (confirmReceipt gate)
- Test: `app/src/test/java/com/neop2p/data/p2p/routing/EscrowRouterApplyTest.kt`
- Test: `app/src/test/java/com/neop2p/data/escrow/EscrowTimeoutTest.kt`

**Interfaces:**
- Consumes: `EscrowRouter.applyRemoteStatus(localStatus: String?, remoteStatus: String): String?` (pure, existing)
- Consumes: `EscrowService.expireStaleEscrows()` sweep `when(status)` branches (existing)
- Produces: SIGNED accepted as a forward state everywhere FUNDED is; SIGNED auto-refunds when stalled past `ESCROW_FUNDED_REFUND_TIMEOUT_MS + FUNDED_REFUND_GRACE_MS` measured from `funded_at ?: created_at`

- [ ] **Step 1: Write the failing router test**

Add to `EscrowRouterApplyTest.kt`:

```kotlin
@Test
fun `signed is a forward state between funded and payment pending`() {
    assertEquals("SIGNED", EscrowRouter.applyRemoteStatus("FUNDED", "SIGNED"))
    assertEquals("PAYMENT_PENDING", EscrowRouter.applyRemoteStatus("SIGNED", "PAYMENT_PENDING"))
    assertEquals("RECEIPT_SENT", EscrowRouter.applyRemoteStatus("SIGNED", "RECEIPT_SENT"))
    assertEquals("CONFIRMING", EscrowRouter.applyRemoteStatus("SIGNED", "CONFIRMING"))
    assertNull(EscrowRouter.applyRemoteStatus("SIGNED", "FUNDED"))
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.p2p.routing.EscrowRouterApplyTest" --console=plain`
Expected: FAIL — `applyRemoteStatus("FUNDED", "SIGNED")` returns null (SIGNED not in ALLOWED_REMOTE).

- [ ] **Step 3: Fix the router**

In `EscrowRouter.kt`, add `EscrowStatus.SIGNED.name` to `ALLOWED_REMOTE` (after FUNDED) and insert `EscrowStatus.SIGNED.name` into the forward `order` list between FUNDED and PAYMENT_PENDING:

```kotlin
val order = listOf(
    EscrowStatus.FUNDING.name,
    EscrowStatus.FUNDED.name,
    EscrowStatus.SIGNED.name,
    EscrowStatus.PAYMENT_PENDING.name,
    EscrowStatus.RECEIPT_SENT.name,
    EscrowStatus.CONFIRMING.name,
    EscrowStatus.RELEASED.name
)
```

- [ ] **Step 4: Write the failing sweep test**

In `EscrowTimeoutTest.kt`, change the `transitionFor` mirror so SIGNED behaves like FUNDED (stalled → auto-REFUND after timeout + grace):

```kotlin
// FUNDED: refund only after primary timeout + grace (reminders fire in between).
// SIGNED: same — the payout was generated but the trade stalled; the deposit
// is confirmed on-chain, so the seller gets the same auto-refund window.
"FUNDED", "SIGNED" -> if (elapsedMs > fundedRefundTimeoutMs + fundedRefundGraceMs) "REFUNDED" else null
```

Remove `"SIGNED"` from the never-expired list in `non-funding and non-funded statuses are never expired` (line 118) and add:

```kotlin
@Test
fun `signed escrow auto-refunds like funded when stalled past timeout plus grace`() {
    assertEquals(null, transitionFor("SIGNED", fundedRefundTimeoutMs + 1))
    assertEquals("REFUNDED", transitionFor("SIGNED", fundedRefundTimeoutMs + fundedRefundGraceMs + 1))
}
```

- [ ] **Step 5: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.escrow.EscrowTimeoutTest" --console=plain`
Expected: FAIL — `transitionFor("SIGNED", ...)` returns null (no SIGNED branch in the mirror).

- [ ] **Step 6: Fix the sweep + heal + confirmReceipt**

In `EscrowService.kt`:

1. Sweep: change `EscrowStatus.FUNDED -> {` to `EscrowStatus.FUNDED, EscrowStatus.SIGNED -> {` (the branch body already measures from `funded_at ?: created_at` and gates on `isSeller`).
2. Resume-heal in `getEscrow` (line ~228): add `esc.status == EscrowStatus.SIGNED ||` to the re-publish condition.
3. `confirmReceipt` status gate (line ~1360): allow retry from SIGNED:

```kotlin
if (status != EscrowStatus.RECEIPT_SENT && status != EscrowStatus.CONFIRMING &&
    status != EscrowStatus.SIGNED
) {
    return@withContext Result.failure(
        IllegalStateException("Cannot confirm receipt from ${entity.status}")
    )
}
```

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS (all existing + new tests).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/neop2p/data/p2p/routing/EscrowRouter.kt app/src/main/java/com/neop2p/data/escrow/EscrowService.kt app/src/test/java/com/neop2p/data/p2p/routing/EscrowRouterApplyTest.kt app/src/test/java/com/neop2p/data/escrow/EscrowTimeoutTest.kt
git commit -m "fix(escrow): SIGNED is a forward state — router, sweep, heal, confirmReceipt retry (zombie stuck-funds path)"
```

---

### Task 2: Persist the onboarding-complete gate (P1 — seed backup durability)

**Problem:** `MainActivity.kt:74` keys the start destination on `hasIdentity()` alone. `completeOnboarding()` (OnboardingScreen.kt:857) only flips a Compose step. A process kill between identity generation and seed verification → next launch goes straight to HOME with the seed never backed up. Seed loss = wallet loss.

**Files:**
- Create: `app/src/main/java/com/neop2p/data/local/OnboardingStore.kt`
- Create: `app/src/main/java/com/neop2p/data/local/OnboardingGate.kt`
- Modify: `app/src/main/java/com/neop2p/MainActivity.kt:74`
- Modify: `app/src/main/java/com/neop2p/ui/screens/onboarding/OnboardingScreen.kt:857-859` (completeOnboarding)
- Test: `app/src/test/java/com/neop2p/data/local/OnboardingGateTest.kt`

**Interfaces:**
- Produces: `OnboardingStore(context).isComplete(): Boolean` / `markComplete()`
- Produces: `OnboardingGate.shouldShowOnboarding(hasIdentity: Boolean, onboardingComplete: Boolean): Boolean`
- Consumes: `IdentityManager.hasIdentity(): Boolean` (existing)

- [ ] **Step 1: Write the failing test**

Create `OnboardingGateTest.kt`:

```kotlin
package com.neop2p.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGateTest {

    @Test
    fun `first_launch_kill_after_generate_does_not_skip_seed_backup`() {
        // Identity exists but the user never finished backup/verify:
        // the app MUST return to onboarding, not home.
        assertTrue(OnboardingGate.shouldShowOnboarding(hasIdentity = true, onboardingComplete = false))
    }

    @Test
    fun `completed onboarding with identity goes home`() {
        assertFalse(OnboardingGate.shouldShowOnboarding(hasIdentity = true, onboardingComplete = true))
    }

    @Test
    fun `no identity always shows onboarding`() {
        assertTrue(OnboardingGate.shouldShowOnboarding(hasIdentity = false, onboardingComplete = true))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.local.OnboardingGateTest" --console=plain`
Expected: FAIL — `OnboardingGate` not defined.

- [ ] **Step 3: Implement the gate + store**

`OnboardingGate.kt`:

```kotlin
package com.neop2p.data.local

/**
 * Pure start-destination policy. The seed-backup step is the ONLY durable
 * proof that the user can recover their identity; an identity that exists
 * but was never backed up must return to onboarding (a kill between
 * generate and verify must not skip the backup screen).
 */
object OnboardingGate {
    fun shouldShowOnboarding(hasIdentity: Boolean, onboardingComplete: Boolean): Boolean =
        !(hasIdentity && onboardingComplete)
}
```

`OnboardingStore.kt`:

```kotlin
package com.neop2p.data.local

import android.content.Context

/** Durable "user finished backup + verify" flag. Plain prefs — not secret. */
class OnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences("neop2p_onboarding", Context.MODE_PRIVATE)
    fun isComplete(): Boolean = prefs.getBoolean("complete", false)
    fun markComplete() {
        prefs.edit().putBoolean("complete", true).apply()
    }
}
```

- [ ] **Step 4: Wire the gate**

`MainActivity.kt:74`:

```kotlin
startDestination = if (com.neop2p.data.local.OnboardingGate.shouldShowOnboarding(
        identityManager.hasIdentity(),
        com.neop2p.data.local.OnboardingStore(applicationContext).isComplete()
    )) {
    Routes.ONBOARDING
} else {
    Routes.HOME
},
```

`OnboardingScreen.kt` `completeOnboarding()`:

```kotlin
fun completeOnboarding() {
    // Durable: a kill after this point may go straight to HOME, so the
    // backup+verify steps must have been completed before this is called.
    com.neop2p.data.local.OnboardingStore(context).markComplete()
    _uiState.update { it.copy(currentStep = OnboardingStep.FINISH) }
}
```

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS.

- [ ] **Step 6: Device verification**

Install debug build. Generate identity → force-stop the app → relaunch. Expected: lands on BACKUP_SEED (not HOME). Complete backup + verify → force-stop → relaunch. Expected: HOME.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/neop2p/data/local/OnboardingStore.kt app/src/main/java/com/neop2p/data/local/OnboardingGate.kt app/src/main/java/com/neop2p/MainActivity.kt app/src/main/java/com/neop2p/ui/screens/onboarding/OnboardingScreen.kt app/src/test/java/com/neop2p/data/local/OnboardingGateTest.kt
git commit -m "fix(onboarding): durable completion flag — kill after generate returns to backup step"
```

---

### Task 3: Revert escrow timeout constants to product spec (P2 — spec drift)

**Problem:** Code constants are 2× the documented spec with `// 2x for test` comments: `ESCROW_FUNDING_TIMEOUT_MS` 90 min vs spec 45, `FUNDING_WARNING_MS` 60 vs 30, `ESCROW_FUNDED_REFUND_TIMEOUT_MS` 24 h vs 12, `FUNDED_REFUND_GRACE_MS` 96 h vs 48, `PAYMENT_WINDOW_MS` 48 h vs 24, `PAYMENT_GRACE_MS` 24 h vs 12. AGENTS.md and the p2p-escrow-dispute-resolution skill both document the spec values; nothing in the test suite requires the doubling (EscrowTimeoutTest asserts the doubled values only because the constants were doubled). Decision: revert to spec. (If the user prefers keeping 2×, skip this task and update the docs instead — confirm before executing.)

**Files:**
- Modify: `app/src/main/java/com/neop2p/data/escrow/EscrowService.kt:80,82,90,92,101,103`
- Modify: `app/src/test/java/com/neop2p/data/escrow/EscrowTimeoutTest.kt:124-126,132` (+ comments at 40-47)
- Modify: `app/src/main/java/com/neop2p/NeoP2PConfig.kt` if any timeout constants live there (grep first; currently they live in EscrowService)

**Interfaces:**
- Consumes: `EscrowService.ESCROW_FUNDING_TIMEOUT_MS` etc. (existing constants, referenced by sweep + tests)
- Produces: spec-value constants; `EscrowTimeoutTest` asserts the spec values

- [ ] **Step 1: Update the failing test assertions first**

In `EscrowTimeoutTest.kt`:

```kotlin
@Test
fun `the funding timeout constant is forty five minutes`() {
    assertEquals(45L * 60L * 1000L, fundingTimeoutMs)
}
```

and line 132: `assertEquals(12L * 60L * 60L * 1000L, fundedRefundTimeoutMs)`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.escrow.EscrowTimeoutTest" --console=plain`
Expected: FAIL — constants are still 2×.

- [ ] **Step 3: Revert the constants**

In `EscrowService.kt`:

```kotlin
const val ESCROW_FUNDING_TIMEOUT_MS = 45 * 60 * 1000L  // 45 min
const val FUNDING_WARNING_MS = 30 * 60 * 1000L  // 30 min
const val ESCROW_FUNDED_REFUND_TIMEOUT_MS = 12 * 60 * 60 * 1000L  // 12 h
const val FUNDED_REFUND_GRACE_MS = 48 * 60 * 60 * 1000L  // 48 h grace
const val PAYMENT_WINDOW_MS = 24 * 60 * 60 * 1000L  // 24 h
const val PAYMENT_GRACE_MS = 12 * 60 * 60 * 1000L  // 12 h grace
```

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/neop2p/data/escrow/EscrowService.kt app/src/test/java/com/neop2p/data/escrow/EscrowTimeoutTest.kt
git commit -m "fix(escrow): revert timeout constants to product spec (45/30/12+48/24+12) — remove 2x test hack"
```

---

### Task 4: Settings seed recovery view, auth-gated (P2 — seed loss recovery)

**Problem:** After onboarding there is no way to ever see the seed phrase again. Combined with Task 2, a user who skipped backup (pre-fix) or lost their paper copy is permanently locked out of their funds.

**Files:**
- Modify: `app/src/main/java/com/neop2p/ui/screens/settings/SettingsScreen.kt` (add row + dialog + BiometricPrompt)
- Modify: `app/src/main/java/com/neop2p/ui/screens/settings/SettingsScreen.kt:625-780` (SettingsViewModel — add `seedPhrase()`)
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-in/strings.xml` (new strings)
- Test: none feasible without Robolectric (BiometricPrompt + KeyStore) — device verification only

**Interfaces:**
- Consumes: `IdentityManager.getOrCreateIdentity().seedPhrase: List<String>` (existing)
- Consumes: BiometricPrompt pattern from `CreateOfferScreen.kt:74-98` (existing pattern: `onAuthenticationSucceeded` → retry pending op)
- Produces: `SettingsViewModel.seedPhrase(): List<String>`

- [ ] **Step 1: Add the ViewModel method**

In `SettingsViewModel` (it already injects `identityManager` — verify at line 625):

```kotlin
fun seedPhrase(): List<String> = identityManager.getOrCreateIdentity().seedPhrase
```

- [ ] **Step 2: Add the UI row + auth gate + dialog**

In `SettingsScreen.kt`, inside the Privacy section (near the reset/destroy card, ~line 507), add a card "Lihat frasa pemulihan" / "View recovery phrase" that:
1. Launches a `BiometricPrompt` (same callback pattern as `CreateOfferScreen.kt:74-98` — `onAuthenticationSucceeded` proceeds, `onAuthenticationError` shows a snackbar).
2. On success, shows an `AlertDialog` with the 12 words (masked by default, toggle to reveal), a copy button, and a warning that anyone with the phrase controls the funds.
3. Copy uses the same clipboard pattern as onboarding; the dialog must NOT auto-dismiss on copy.

New strings (both locales):
- `settings_show_seed` — "Lihat frasa pemulihan" / "View recovery phrase"
- `settings_seed_dialog_title` — "Frasa pemulihan" / "Recovery phrase"
- `settings_seed_dialog_warning` — "Siapa pun dengan frasa ini dapat mengakses dana Anda. Jangan bagikan." / "Anyone with this phrase can access your funds. Never share it."
- `settings_seed_copy` — "Salin" / "Copy"
- `settings_seed_hide` — "Sembunyikan" / "Hide"
- `settings_seed_auth_required` — "Buka kunci perangkat untuk melihat frasa" / "Unlock your device to view the phrase"

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Device verification**

Settings → Lihat frasa pemulihan → device auth prompt → dialog shows 12 words matching the onboarding phrase. Copy works. Mask toggle works.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/neop2p/ui/screens/settings/SettingsScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-in/strings.xml
git commit -m "feat(settings): auth-gated recovery phrase view (seed loss recovery)"
```

---

### Task 5: Guard restore against clobbering a live identity (P2)

**Problem:** `IdentityManager.restoreFromSeedPhrase` (IdentityManager.kt:125) overwrites any existing identity with zero confirmation. Today it is only reachable from onboarding (no identity), but the service method has no guard — a future deep-link or bug could silently replace a live identity. Exception: the KeyPermanentlyInvalidated path (lock-screen change) MUST still allow restore, because the stored identity is unreadable there.

**Files:**
- Create: `app/src/main/java/com/neop2p/data/p2p/RestoreGuard.kt`
- Modify: `app/src/main/java/com/neop2p/data/p2p/IdentityManager.kt:125-136`
- Test: `app/src/test/java/com/neop2p/data/p2p/RestoreGuardTest.kt`

**Interfaces:**
- Produces: `RestoreGuard.allowRestore(existingLoadable: Boolean, force: Boolean): Boolean`
- Consumes: `IdentityManager.loadIdentityFromStorage(): Identity?` (private, existing — throws `IdentityLockedException` when the KeyStore key is auth-gated/invalidated)
- Produces: `restoreFromSeedPhrase(seedPhrase: List<String>, force: Boolean = false): Identity` (signature change is additive, default false — existing callers unaffected)

- [ ] **Step 1: Write the failing test**

Create `RestoreGuardTest.kt`:

```kotlin
package com.neop2p.data.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGuardTest {

    @Test
    fun `restore is blocked when a loadable identity exists`() {
        assertFalse(RestoreGuard.allowRestore(existingLoadable = true, force = false))
    }

    @Test
    fun `restore is allowed on a fresh device`() {
        assertTrue(RestoreGuard.allowRestore(existingLoadable = false, force = false))
    }

    @Test
    fun `restore is allowed when the stored identity is locked or invalidated`() {
        // KeyPermanentlyInvalidated / auth-gated: loadIdentityFromStorage throws
        // IdentityLockedException → not loadable → restore is the ONLY recovery.
        assertTrue(RestoreGuard.allowRestore(existingLoadable = false, force = false))
    }

    @Test
    fun `force overrides the guard`() {
        assertTrue(RestoreGuard.allowRestore(existingLoadable = true, force = true))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.data.p2p.RestoreGuardTest" --console=plain`
Expected: FAIL — `RestoreGuard` not defined.

- [ ] **Step 3: Implement the guard**

`RestoreGuard.kt`:

```kotlin
package com.neop2p.data.p2p

/**
 * Restore policy: a loadable identity must never be silently overwritten.
 * A locked/invalidated identity (KeyStore auth-gated or lock-screen change)
 * is NOT loadable — restore is the only recovery, so it stays allowed.
 */
object RestoreGuard {
    fun allowRestore(existingLoadable: Boolean, force: Boolean): Boolean =
        force || !existingLoadable
}
```

- [ ] **Step 4: Wire the guard into IdentityManager**

`IdentityManager.restoreFromSeedPhrase`:

```kotlin
fun restoreFromSeedPhrase(seedPhrase: List<String>, force: Boolean = false): Identity {
    if (!RestoreGuard.allowRestore(
            existingLoadable = runCatching { loadIdentityFromStorage() != null }.getOrDefault(false),
            force = force
        )
    ) {
        throw IllegalStateException(
            "An identity already exists on this device. Restore would overwrite it."
        )
    }
    // Validate checksum
    if (!validateBip39Checksum(seedPhrase)) {
        throw IllegalArgumentException("Invalid BIP-39 checksum")
    }
    // ... existing body unchanged
}
```

Note: `loadIdentityFromStorage()` does not touch `cachedIdentity` (only `getOrCreateIdentity` assigns it), so the pre-check cannot poison the cache.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:testDebugUnitTest --console=plain`
Expected: PASS (existing `IdentityManagerTest` restore paths still pass — they run against a fresh prefs state).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/neop2p/data/p2p/RestoreGuard.kt app/src/main/java/com/neop2p/data/p2p/IdentityManager.kt app/src/test/java/com/neop2p/data/p2p/RestoreGuardTest.kt
git commit -m "fix(identity): restore refuses to clobber a loadable identity (locked-key path still allowed)"
```

---

### Task 6: Cap dispute evidence image size (P3 — relay event size)

**Problem:** `DisputeEvidenceViewModel.submitEvidence` (DisputeEvidenceScreen.kt:339-390) reads the picked image raw and publishes it base64 to the relay (kind:33387). A 10 MB photo → multi-MB Nostr event → relay rejection or feed bloat. The receipt composer already has the right compressor (`compressReceiptImage`, ReceiptComposerScreen.kt:318-345, ≤1600px / ≤60KB JPEG) — extract and reuse it.

**Files:**
- Create: `app/src/main/java/com/neop2p/ui/util/ImageCompressor.kt`
- Modify: `app/src/main/java/com/neop2p/ui/screens/escrow/ReceiptComposerScreen.kt:318-345` (delegate to shared util)
- Modify: `app/src/main/java/com/neop2p/ui/screens/escrow/DisputeEvidenceScreen.kt:339-390` (compress before store + publish)
- Test: none feasible without Robolectric (BitmapFactory) — device verification only

**Interfaces:**
- Produces: `ImageCompressor.compressToBytes(resolver: ContentResolver, uri: Uri): ByteArray?` (≤1600px edge, ≤60KB JPEG, quality 85→30 loop)
- Produces: `ImageCompressor.compressToBase64(resolver: ContentResolver, uri: Uri): String?`
- Consumes: existing constants `MAX_IMAGE_EDGE = 1600`, `MAX_IMAGE_BYTES = 60 * 1024` (moved into ImageCompressor)

- [ ] **Step 1: Extract the compressor**

Create `ImageCompressor.kt` with the exact body of `compressReceiptImage` (ReceiptComposerScreen.kt:318-345) as `compressToBytes`, plus a `compressToBase64` wrapper. Move `MAX_IMAGE_EDGE` / `MAX_IMAGE_BYTES` constants into it.

- [ ] **Step 2: Delegate from ReceiptComposerScreen**

Replace the private `compressReceiptImage` body with a call to `ImageCompressor.compressToBase64(resolver, uri)` (keep the private wrapper so the call site at line 80 is unchanged).

- [ ] **Step 3: Compress in DisputeEvidenceViewModel**

In `submitEvidence`, replace the raw read:

```kotlin
val bytes = ImageCompressor.compressToBytes(context.contentResolver, uri)
if (bytes == null || bytes.isEmpty()) {
    _error.value = context.getString(R.string.escrow_evidence_attach_failed, "empty or unreadable image")
    return@launch
}
```

(store `bytes` in the entity, publish `Base64.encodeToString(bytes, Base64.NO_WRAP)` — both already use the `bytes` variable, so only the read changes.)

- [ ] **Step 4: Build + device verification**

Run: `./gradlew :app:assembleDebug --console=plain` → BUILD SUCCESSFUL.
Device: open dispute evidence, pick a large photo → evidence stores ≤60KB; relay event `image_base64_len` in the exported bundle shows ≤60KB.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/neop2p/ui/util/ImageCompressor.kt app/src/main/java/com/neop2p/ui/screens/escrow/ReceiptComposerScreen.kt app/src/main/java/com/neop2p/ui/screens/escrow/DisputeEvidenceScreen.kt
git commit -m "fix(dispute): cap evidence images at 1600px/60KB via shared compressor (relay event size)"
```

---

### Task 7: Hygiene — clipboard auto-clear, dead code, locked-identity notification (P3)

**Files:**
- Modify: `app/src/main/java/com/neop2p/ui/screens/onboarding/OnboardingScreen.kt:76-81` (CopySeed handler)
- Modify: `app/src/main/java/com/neop2p/data/p2p/IdentityManager.kt:338` (remove `identity_version` write)
- Modify: `app/src/main/java/com/neop2p/service/P2PBackgroundService.kt:58-70` (catch IdentityLockedException → notify)
- Modify: `app/src/main/java/com/neop2p/service/NotificationDispatcher.kt` (add `notifyIdentityLocked()`)
- Modify: `app/src/main/res/values/strings.xml` + `values-in/strings.xml`
- Test: none feasible without Robolectric — device verification only

**Interfaces:**
- Consumes: `NotificationDispatcher` existing channel plumbing (id 1001 = FGS; use a new id 1002 for the locked prompt)
- Produces: `NotificationDispatcher.notifyIdentityLocked()`

- [ ] **Step 1: Clipboard auto-clear**

In the `CopySeed` event handler (OnboardingScreen.kt:76-81), after `setPrimaryClip`, schedule a clear that only fires if the clipboard still holds OUR seed:

```kotlin
val clip = ClipData.newPlainText("NEO-P2P seed phrase", ev.seed)
clipboard.setPrimaryClip(clip)
// Auto-clear after 60s so the phrase does not linger on the system
// clipboard (other apps can read it). Only clear if it is still OUR
// phrase — never clobber something the user copied later.
android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
    val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    if (current == ev.seed) {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}, 60_000L)
```

- [ ] **Step 2: Remove dead `identity_version` write**

Delete the `.putInt("identity_version", 2)` line in `saveIdentityToStorage` (IdentityManager.kt:338).

- [ ] **Step 3: Locked-identity notification**

In `P2PBackgroundService.onStartCommand`'s launch block, catch `IdentityLockedException` and notify:

```kotlin
scope.launch {
    try {
        orchestrator.start()
        Log.d(TAG, "P2P background service started")
    } catch (e: com.neop2p.data.p2p.IdentityLockedException) {
        Log.w(TAG, "Identity locked — P2P paused until unlock: ${e.message}")
        notificationDispatcher.notifyIdentityLocked()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start P2P service", e)
    }
}
```

Inject `notificationDispatcher` into the service (Hilt field injection, same as `orchestrator`).

In `NotificationDispatcher`, add:

```kotlin
fun notifyIdentityLocked() {
    // Distinct id from the FGS (1001) so the prompt is actionable.
    notify(1002, "neop2p_connections", ...) // title/body from strings below
}
```

New strings (both locales):
- `notif_identity_locked_title` — "Identitas terkunci" / "Identity locked"
- `notif_identity_locked_body` — "Buka aplikasi dan buka kunci perangkat untuk melanjutkan P2P" / "Open the app and unlock your device to resume P2P"

- [ ] **Step 4: Build + device verification**

Run: `./gradlew :app:assembleDebug --console=plain` → BUILD SUCCESSFUL.
Device: copy seed during onboarding → wait 60s → clipboard empty. Lock screen change → restart app → locked notification appears; opening the app shows the restore path.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/neop2p/ui/screens/onboarding/OnboardingScreen.kt app/src/main/java/com/neop2p/data/p2p/IdentityManager.kt app/src/main/java/com/neop2p/service/P2PBackgroundService.kt app/src/main/java/com/neop2p/service/NotificationDispatcher.kt app/src/main/res/values/strings.xml app/src/main/res/values-in/strings.xml
git commit -m "chore(hygiene): seed clipboard auto-clear, drop dead identity_version, locked-identity notification"
```

---

## Self-Review

**Spec coverage:**
- SIGNED zombie (deep-check finding #1) → Task 1 (router + sweep + heal + confirmReceipt retry, 4 code sites, 2 test files). ✓
- Onboarding gate (G1) → Task 2 (durable flag + pure gate + test + device steps). ✓
- Timeout drift (deep-check finding #2) → Task 3 (revert to spec, test assertions updated). ✓
- Seed recovery (G3) → Task 4 (auth-gated settings view). ✓
- Restore guard (G2) → Task 5 (pure guard + locked-key exception preserved). ✓
- Evidence cap (deep-check finding) → Task 6 (shared compressor). ✓
- Clipboard / dead code / locked notification (G4/G5/G6) → Task 7. ✓
- Not in scope (deliberately): identity-spoofing gap (needs NIP-26/kind:0 design — separate plan), reorg handling (needs ChainMonitor design — separate plan), battery-optimization request (needs product decision on Play policy), neop2p:// intent-filter (needs manifest + nav design).

**Placeholder scan:** No TBD/TODO; every code step has exact code. Task 4 and Task 6-7 are UI/Android-bound and state device verification instead of JVM tests — consistent with the repo's plain-JUnit constraint (no Robolectric).

**Type consistency:** `OnboardingGate.shouldShowOnboarding(Boolean, Boolean): Boolean` used identically in test + MainActivity. `RestoreGuard.allowRestore(Boolean, Boolean): Boolean` identical in test + IdentityManager. `ImageCompressor.compressToBytes/compressToBase64` names consistent across both call sites. `EscrowRouter.applyRemoteStatus` and `EscrowService` constants unchanged in signature. `restoreFromSeedPhrase(seedPhrase, force = false)` additive — existing callers (OnboardingViewModel:733) compile unchanged.

**Dependency order:** Task 1 and 2 are independent (either first). Task 3 independent. Tasks 4-7 independent of 1-3. No task depends on another's output except Task 6 reusing the compressor body that Task 6 itself extracts.
