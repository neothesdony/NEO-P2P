# Trade Hub + Invite Deep Link + Notification Rationale — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the three highest-impact flow problems found in the app-flow analysis: (1) give every accepted trade a single "trade hub" destination that shows the next action, pay card, and escrow/chat shortcuts; (2) make `neop2p://peer/<id>` invite links work as real system deep links; (3) show a one-time rationale before the POST_NOTIFICATIONS prompt.

**Architecture:** The trade hub is a status + navigation hub (NOT an embedding of the full Escrow/Chat screens — those keep their dialogs and ViewModels; the hub shows the step tracker, next-action bar, pay instruction card, and one-tap shortcuts to the real screens). Pure decision logic is extracted into plain functions (`resolveTradeRoom`, `parseInvite`, `notifRationaleDecision`) so it is unit-testable with plain JUnit 4 like the rest of the project. The invite deep link reuses the existing notification deep-link machinery in `MainActivity` (route-string navigation + `onNavControllerReady` replay).

**Tech Stack:** Kotlin 2.3.0, Jetpack Compose (Material 3), Navigation Compose, Hilt, Room, plain JUnit 4 (no Robolectric/Mockito).

**Spec:** `docs/FLOW_ANALYSIS.md` — sections 11 (issues #1, #5, #7), 12 (R1, R2, R3), 13 (QA cases 6, 10, 12).

## Global Constraints

- All Gradle commands run from `android/` (the root repo is NOT a Gradle project). Build: `./gradlew :app:assembleDebug`. Tests: `./gradlew :app:testDebugUnitTest`. Lint: `./gradlew :app:lintDebug`.
- Tests are plain JUnit 4 + `kotlinx-coroutines-test` only. No Robolectric, no Mockito, no Android framework in unit tests. Test pure functions; verify Compose changes by building + manual QA.
- No new dependencies. No changes to root Gradle files, `libs.versions.toml`, or `app/build.gradle.kts` (except nothing — none needed).
- Every new user-facing string MUST be added to BOTH `app/src/main/res/values/strings.xml` (EN) and `app/src/main/res/values-in/strings.xml` (ID).
- Money stays integer-only (sats/Long). This plan touches no money math.
- Follow existing patterns: `StateFlow` + `collectAsStateWithLifecycle`, `hiltViewModel()`, `CenterAlignedTopAppBar`, `NeoP2PTheme` wrapper, `popBackStack` for back.
- Do not change escrow/offer service logic, routers, or the Room schema. This plan is UI + entry-point only.
- Commit after every task with a Conventional Commits message.

---

### Task 1: Expose escrow composables for reuse

**Files:**
- Modify: `app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt` (4 visibility changes)

**Interfaces:**
- Consumes: nothing new.
- Produces: `internal fun EscrowStatusChip(status: EscrowStatus, fundingTxId: String, modifier: Modifier = Modifier)`, `internal fun StepTracker(currentStep: Int, steps: List<EscrowStep>, labels: Map<EscrowStep, String>)`, `internal fun NextActionBar(escrow: Escrow, isRole: EscrowRole, fiatAmount: Long, modifier: Modifier = Modifier)`, `internal fun PayInstructionCard(fiatAmount: Long, escrowId: String, methods: Set<String>, paymentDetails: Map<String, PaymentDetails>, modifier: Modifier = Modifier)` — all currently `private` in `EscrowScreen.kt`, consumed by Task 4's trade hub.

- [ ] **Step 1: Change `private` to `internal` on the four composables**

In `app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt`, change the declaration of each of these four functions from `private fun` to `internal fun`:

1. `private fun EscrowStatusChip(` (line ~493)
2. `private fun StepTracker(` (line ~454)
3. `private fun NextActionBar(` (line ~1829)
4. `private fun PayInstructionCard(` (line ~1547)

Do NOT change their bodies, parameters, or call sites inside `EscrowScreen.kt`.

- [ ] **Step 2: Build to verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no visibility errors; the four composables are still used by `EscrowScreen` itself).

- [ ] **Step 3: Run existing tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all tests pass (no behavior changed).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt
git commit -m "refactor: expose escrow composables for trade hub reuse"
```

---

### Task 2: TradeRoomData + resolveTradeRoom pure logic

**Files:**
- Create: `app/src/main/java/com/neop2p/ui/screens/trade/TradeRoomData.kt`
- Test: `app/src/test/java/com/neop2p/ui/screens/trade/TradeRoomDataTest.kt`

**Interfaces:**
- Consumes: `com.neop2p.domain.model.TradeOffer`, `Escrow`, `EscrowRole`, `PaymentDetails` (all exist).
- Produces: `data class TradeRoomData(offer: TradeOffer, escrow: Escrow?, role: EscrowRole, peerId: String, paymentDetails: Map<String, PaymentDetails>, fiatAmount: Long)` and `fun resolveTradeRoom(offer: TradeOffer, escrow: Escrow?, myPeerId: String): TradeRoomData` — consumed by Task 3's ViewModel and Task 4's hub UI.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/neop2p/ui/screens/trade/TradeRoomDataTest.kt`:

```kotlin
package com.neop2p.ui.screens.trade

import com.neop2p.domain.model.Escrow
import com.neop2p.domain.model.EscrowRole
import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.TradeOffer
import org.junit.Assert.assertEquals
import org.junit.Test

class TradeRoomDataTest {

    private fun offer(creator: String, matched: String? = null, fiat: Long = 1_000_000L) = TradeOffer(
        offerId = "offer_1",
        creatorPeerId = creator,
        type = OfferType.SELL,
        fiatAmount = fiat,
        cryptoAmountSats = 100_000L,
        pricePerUnit = 10_000_000.0,
        fiatMethods = listOf("bca"),
        matchedPeerId = matched
    )

    private fun escrow(buyer: String, seller: String) = Escrow(
        escrowId = "escrow_1",
        offerId = "offer_1",
        depositAmountSats = 100_500L,
        tradeAmountSats = 100_000L,
        feeAmountSats = 500L,
        buyerPeerId = buyer,
        sellerPeerId = seller
    )

    @Test
    fun `buyer role when my id is the escrow buyer`() {
        val data = resolveTradeRoom(offer("seller"), escrow("buyer", "seller"), "buyer")
        assertEquals(EscrowRole.BUYER, data.role)
    }

    @Test
    fun `seller role when my id is the escrow seller`() {
        val data = resolveTradeRoom(offer("seller"), escrow("buyer", "seller"), "seller")
        assertEquals(EscrowRole.SELLER, data.role)
    }

    @Test
    fun `unknown role when no escrow yet`() {
        val data = resolveTradeRoom(offer("seller"), null, "buyer")
        assertEquals(EscrowRole.UNKNOWN, data.role)
    }

    @Test
    fun `creator chats with the matched peer`() {
        val data = resolveTradeRoom(offer("seller", matched = "buyer"), null, "seller")
        assertEquals("buyer", data.peerId)
    }

    @Test
    fun `taker chats with the creator`() {
        val data = resolveTradeRoom(offer("seller", matched = "buyer"), null, "buyer")
        assertEquals("seller", data.peerId)
    }

    @Test
    fun `payment details and fiat amount pass through`() {
        val o = offer("seller", matched = "buyer", fiat = 2_500_000L)
        val data = resolveTradeRoom(o, null, "buyer")
        assertEquals(2_500_000L, data.fiatAmount)
        assertEquals(o.paymentDetails, data.paymentDetails)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.ui.screens.trade.TradeRoomDataTest"`
Expected: FAIL — `resolveTradeRoom` unresolved reference.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/neop2p/ui/screens/trade/TradeRoomData.kt`:

```kotlin
package com.neop2p.ui.screens.trade

import com.neop2p.domain.model.Escrow
import com.neop2p.domain.model.EscrowRole
import com.neop2p.domain.model.PaymentDetails
import com.neop2p.domain.model.TradeOffer

/**
 * Everything the trade hub needs to render, derived from the offer + escrow
 * rows and the current identity. Pure so it is unit-testable without Android.
 */
data class TradeRoomData(
    val offer: TradeOffer,
    val escrow: Escrow?,
    val role: EscrowRole,
    val peerId: String,
    val paymentDetails: Map<String, PaymentDetails>,
    val fiatAmount: Long
)

/**
 * Resolve the hub state for [myPeerId]:
 *  - role comes from the escrow's buyer/seller peer ids (UNKNOWN before the
 *    escrow row exists — e.g. the buyer of a SELL offer pre-sync);
 *  - chat target: the offer creator talks to the matched peer, the taker
 *    talks to the creator.
 *
 * NOTE (single-key model): role resolution is BUYER-FIRST, matching
 * EscrowService.roleFor (EscrowService.kt:1831-1838). In the single-key demo
 * one device holds BOTH role peerIds, so the seller's own device resolves
 * BUYER and shows the buyer view — identical to the escrow screen today.
 * This is existing, known behavior; the hub must NOT "fix" it or the two
 * screens would disagree. QA should expect the seller side to look like the
 * buyer side on a single-key device.
 */
fun resolveTradeRoom(offer: TradeOffer, escrow: Escrow?, myPeerId: String): TradeRoomData {
    val role = when (myPeerId) {
        escrow?.buyerPeerId -> EscrowRole.BUYER
        escrow?.sellerPeerId -> EscrowRole.SELLER
        else -> EscrowRole.UNKNOWN
    }
    val peerId = when {
        offer.creatorPeerId == myPeerId -> offer.matchedPeerId ?: ""
        else -> offer.creatorPeerId
    }
    return TradeRoomData(
        offer = offer,
        escrow = escrow,
        role = role,
        peerId = peerId,
        paymentDetails = offer.paymentDetails,
        fiatAmount = offer.fiatAmount
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.ui.screens.trade.TradeRoomDataTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/trade/TradeRoomData.kt android/app/src/test/java/com/neop2p/ui/screens/trade/TradeRoomDataTest.kt
git commit -m "feat: add trade hub state resolution logic"
```

---

### Task 3: Rework TradeRoomViewModel to observe the escrow live

**Files:**
- Modify: `app/src/main/java/com/neop2p/ui/screens/trade/TradeRoomScreen.kt` (ViewModel section only, lines ~108-143)

**Interfaces:**
- Consumes: `resolveTradeRoom`/`TradeRoomData` from Task 2; `OfferDao.getOfferSync`, `EscrowDao.getEscrowByOfferId`, `EscrowDao.observeEscrowByOfferId`, `IdentityManager.getOrCreateIdentity` (all exist); `com.neop2p.data.local.toDomain` (exists in `Mappers.kt`).
- Produces: `sealed class State { Loading; Error(message: String); Ready(data: TradeRoomData) }` and `fun load(offerId: String)` — consumed by Task 4's hub UI.

- [ ] **Step 1: Replace the ViewModel**

In `app/src/main/java/com/neop2p/ui/screens/trade/TradeRoomScreen.kt`, replace the entire `TradeRoomViewModel` class (currently lines ~108-143) with:

```kotlin
@HiltViewModel
class TradeRoomViewModel @Inject constructor(
    private val offerDao: OfferDao,
    private val escrowDao: EscrowDao,
    private val identityManager: com.neop2p.data.p2p.IdentityManager
) : ViewModel() {
    sealed class State {
        object Loading : State()
        data class Error(val message: String) : State()
        data class Ready(val data: TradeRoomData) : State()
    }
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(offerId: String) {
        _state.value = State.Loading
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val offer = offerDao.getOfferSync(offerId)
                if (offer == null) {
                    _state.value = State.Error("Offer not found")
                    return@launch
                }
                val myId = runCatching { identityManager.getOrCreateIdentity().peerId }.getOrDefault("")
                val domain = offer.toDomain()
                _state.value = State.Ready(
                    resolveTradeRoom(domain, escrowDao.getEscrowByOfferId(offerId)?.toDomain(), myId)
                )
                // Live observers run OUTSIDE the try/catch: a Room flow that
                // throws (e.g. closed DB) must not flip the hub to Error
                // permanently — the initial load already succeeded.
                runCatching {
                    escrowDao.observeEscrowByOfferId(offerId).collect { entity ->
                        val current = (_state.value as? State.Ready)?.data ?: return@collect
                        _state.value = State.Ready(resolveTradeRoom(current.offer, entity?.toDomain(), myId))
                    }
                }
                // Live: the OFFER row too — the seller's bank details arrive via
                // E2EE chat AFTER the hub loaded (auto-share at FUNDED), and
                // ChatRouter persists them into the offer row. Without this
                // observer the buyer's pay card would stay empty of rails until
                // they leave and re-enter the hub.
                runCatching {
                    offerDao.getOffer(offerId).collect { entity ->
                        val current = (_state.value as? State.Ready)?.data ?: return@collect
                        if (entity == null) return@collect
                        val fresh = entity.toDomain()
                        _state.value = State.Ready(
                            resolveTradeRoom(fresh, current.escrow, myId)
                        )
                    }
                }
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Load failed")
            }
        }
    }
}
```

Add these imports to the file (keep the existing ones):

```kotlin
import com.neop2p.data.local.toDomain
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.dao.OfferDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
```

Remove the now-unused `com.neop2p.data.local.dao.EscrowDao`/`OfferDao`/`IdentityManager` imports if they were only used by the old ViewModel (the file's current imports are `EscrowDao`, `OfferDao`, `IdentityManager` — keep them, they are still used).

- [ ] **Step 2: Build to verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (The old `State.Ready(escrowId, peerId)` shape is gone; the screen composable still references it and will fail to compile — that is expected and fixed in Task 4. If you want a green build now, temporarily keep the old screen body; the plan's Task 4 replaces it.)

- [ ] **Step 3: Run existing tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all existing tests pass (Task 2's `TradeRoomDataTest` included).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/trade/TradeRoomScreen.kt
git commit -m "feat: rework trade room view model to observe escrow live"
```

---

### Task 4: Trade hub UI

**Files:**
- Modify: `app/src/main/java/com/neop2p/ui/screens/trade/TradeRoomScreen.kt` (replace the whole file)

**Interfaces:**
- Consumes: `TradeRoomViewModel`/`TradeRoomData` (Task 3), `EscrowStatusChip`/`StepTracker`/`NextActionBar`/`PayInstructionCard` (Task 1), `EscrowStep`/`stepsForRole`/`currentStepFor` (already public in `com.neop2p.ui.screens.escrow`), `EscrowStatus`/`EscrowRole` (domain), existing strings `trade_room_*`, `escrow_step_*`, `escrow_open_receipt`, `chat_locked_until_funded`, `chat_with_peer`, `general_back`, `general_retry`.
- Produces: `fun TradeRoomScreen(offerId: String, onBack: () -> Unit, onOpenEscrow: (String) -> Unit, onOpenChat: (String, String) -> Unit, onOpenReceipt: (String) -> Unit, viewModel: TradeRoomViewModel = hiltViewModel())` — consumed by Task 5's NavGraph wiring.

- [ ] **Step 1: Replace the whole file**

Replace the entire contents of `app/src/main/java/com/neop2p/ui/screens/trade/TradeRoomScreen.kt` with:

```kotlin
package com.neop2p.ui.screens.trade

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neop2p.R
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toDomain
import com.neop2p.domain.model.Escrow
import com.neop2p.domain.model.EscrowRole
import com.neop2p.domain.model.EscrowStatus
import com.neop2p.ui.screens.escrow.EscrowStatusChip
import com.neop2p.ui.screens.escrow.EscrowStep
import com.neop2p.ui.screens.escrow.NextActionBar
import com.neop2p.ui.screens.escrow.PayInstructionCard
import com.neop2p.ui.screens.escrow.StepTracker
import com.neop2p.ui.screens.escrow.currentStepFor
import com.neop2p.ui.screens.escrow.stepsForRole
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Trade hub: the single destination a user lands on after accepting an offer
 * (and the natural home for a live trade). Shows the escrow status header
 * (chip + role-adaptive step tracker + next-action bar with countdown), the
 * buyer's pay instruction card inline, a "chat locked until funded" tooltip,
 * and one-tap shortcuts to the full Escrow and Chat screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeRoomScreen(
    offerId: String,
    onBack: () -> Unit,
    onOpenEscrow: (String) -> Unit,
    onOpenChat: (String, String) -> Unit,
    onOpenReceipt: (String) -> Unit,
    viewModel: TradeRoomViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) } // 0 = Escrow, 1 = Chat
    LaunchedEffect(offerId) { viewModel.load(offerId) }

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.trade_room_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(id = R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.general_back))
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                when (val s = state) {
                    is TradeRoomViewModel.State.Loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    is TradeRoomViewModel.State.Error -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.message)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { viewModel.load(offerId) }) {
                                Text(stringResource(R.string.general_retry))
                            }
                        }
                    }
                    is TradeRoomViewModel.State.Ready -> {
                        val data = s.data
                        val esc = data.escrow

                        // Status header: chip + step tracker + next action.
                        if (esc != null) {
                            EscrowStatusChip(
                                status = esc.status,
                                fundingTxId = esc.fundingTxId.orEmpty(),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            val roleSteps = stepsForRole(data.role.name)
                            StepTracker(
                                currentStep = currentStepFor(esc.status, roleSteps),
                                steps = roleSteps,
                                labels = mapOf(
                                    EscrowStep.FUND to stringResource(R.string.escrow_step_fund),
                                    EscrowStep.PAY to stringResource(R.string.escrow_step_pay),
                                    EscrowStep.CONFIRM to stringResource(R.string.escrow_step_confirm),
                                    EscrowStep.RELEASE to stringResource(R.string.escrow_step_release)
                                )
                            )
                            NextActionBar(escrow = esc, isRole = data.role, fiatAmount = data.fiatAmount)
                        }

                        // Buyer: pay instruction card inline (exact IDR + unique code).
                        if (esc != null && data.role == EscrowRole.BUYER &&
                            esc.status in setOf(
                                EscrowStatus.FUNDED, EscrowStatus.SIGNED,
                                EscrowStatus.PAYMENT_PENDING, EscrowStatus.RECEIPT_SENT,
                                EscrowStatus.CONFIRMING
                            )
                        ) {
                            PayInstructionCard(
                                fiatAmount = data.fiatAmount,
                                escrowId = esc.escrowId,
                                methods = data.paymentDetails.keys,
                                paymentDetails = data.paymentDetails,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }

                        // Chat-locked tooltip: explains why the chat is unavailable.
                        if (esc == null || esc.status == EscrowStatus.FUNDING) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Lock, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.chat_locked_until_funded),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.trade_room_tab_escrow)) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.trade_room_tab_chat)) })
                        }
                        when (tab) {
                            0 -> EscrowTabContent(data, onOpenEscrow, onOpenReceipt)
                            else -> ChatTabContent(data, offerId, onOpenChat)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EscrowTabContent(
    data: TradeRoomData,
    onOpenEscrow: (String) -> Unit,
    onOpenReceipt: (String) -> Unit
) {
    val esc = data.escrow
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (esc == null) {
            Text(stringResource(R.string.trade_room_no_escrow), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.trade_room_no_escrow_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Button(onClick = { onOpenEscrow(esc.escrowId) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.trade_room_open_escrow))
            }
            if (data.role == EscrowRole.BUYER &&
                esc.status in setOf(EscrowStatus.PAYMENT_PENDING, EscrowStatus.RECEIPT_SENT)
            ) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onOpenReceipt(esc.escrowId) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.escrow_open_receipt))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.trade_room_escrow_id, esc.escrowId.take(8)),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ChatTabContent(
    data: TradeRoomData,
    offerId: String,
    onOpenChat: (String, String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (data.peerId.isBlank()) {
            Text(stringResource(R.string.trade_room_no_peer), style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = { onOpenChat(offerId, data.peerId) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.chat_with_peer))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.trade_room_peer_id, data.peerId.take(12)),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@HiltViewModel
class TradeRoomViewModel @Inject constructor(
    private val offerDao: OfferDao,
    private val escrowDao: EscrowDao,
    private val identityManager: com.neop2p.data.p2p.IdentityManager
) : ViewModel() {
    sealed class State {
        object Loading : State()
        data class Error(val message: String) : State()
        data class Ready(val data: TradeRoomData) : State()
    }
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(offerId: String) {
        _state.value = State.Loading
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val offer = offerDao.getOfferSync(offerId)
                if (offer == null) {
                    _state.value = State.Error("Offer not found")
                    return@launch
                }
                val myId = runCatching { identityManager.getOrCreateIdentity().peerId }.getOrDefault("")
                val domain = offer.toDomain()
                _state.value = State.Ready(
                    resolveTradeRoom(domain, escrowDao.getEscrowByOfferId(offerId)?.toDomain(), myId)
                )
                // Live observers run OUTSIDE the try/catch: a Room flow that
                // throws (e.g. closed DB) must not flip the hub to Error
                // permanently — the initial load already succeeded.
                runCatching {
                    escrowDao.observeEscrowByOfferId(offerId).collect { entity ->
                        val current = (_state.value as? State.Ready)?.data ?: return@collect
                        _state.value = State.Ready(resolveTradeRoom(current.offer, entity?.toDomain(), myId))
                    }
                }
                // Live: the OFFER row too — the seller's bank details arrive via
                // E2EE chat AFTER the hub loaded (auto-share at FUNDED), and
                // ChatRouter persists them into the offer row. Without this
                // observer the buyer's pay card would stay empty of rails until
                // they leave and re-enter the hub.
                runCatching {
                    offerDao.getOffer(offerId).collect { entity ->
                        val current = (_state.value as? State.Ready)?.data ?: return@collect
                        if (entity == null) return@collect
                        val fresh = entity.toDomain()
                        _state.value = State.Ready(
                            resolveTradeRoom(fresh, current.escrow, myId)
                        )
                    }
                }
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Load failed")
            }
        }
    }
}
```

Note: `TradeRoomData` and `resolveTradeRoom` live in `TradeRoomData.kt` (same package `com.neop2p.ui.screens.trade`), so no import is needed for them.

- [ ] **Step 2: Build to verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (The NavGraph still calls the old 3-arg `TradeRoomScreen` — that breaks in Task 5; if you want a green build now, temporarily add default values `onOpenEscrow: (String) -> Unit = {}`, `onOpenChat: (String, String) -> Unit = { _, _ -> }`, `onOpenReceipt: (String) -> Unit = {}` — Task 5 removes the defaults.)

- [ ] **Step 3: Run tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all pass (including `TradeRoomDataTest`).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/trade/TradeRoomScreen.kt
git commit -m "feat: build trade hub screen with status header and shortcuts"
```

---

### Task 5: Wire navigation — post-accept lands on the hub; remove dead params

**Files:**
- Modify: `app/src/main/java/com/neop2p/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/neop2p/ui/screens/offerdetail/OfferDetailScreen.kt`
- Modify: `app/src/main/java/com/neop2p/MainActivity.kt`
- Modify: `app/src/main/java/com/neop2p/ui/screens/wallet/WalletScreen.kt`
- Modify: `app/src/main/java/com/neop2p/ui/screens/profile/ProfileScreen.kt`
- Modify: `app/src/main/java/com/neop2p/ui/screens/history/HistoryScreen.kt`

**Interfaces:**
- Consumes: `TradeRoomScreen` from Task 4; `Routes.TRADE_ROOM`/`Routes.tradeRoom(offerId)` (exist).
- Produces: post-accept navigation → `trade/{offerId}`; `trade/` added to `MainActivity.isKnownRoute`; `onEscrowCreated` removed from `OfferDetailScreen`; dead `onTabChange` params removed from Wallet/Profile/History.

- [ ] **Step 1: OfferDetailScreen — replace `onEscrowCreated` with `onTradeStarted`**

In `app/src/main/java/com/neop2p/ui/screens/offerdetail/OfferDetailScreen.kt`:

1. Change the signature (line ~51-57):

```kotlin
fun OfferDetailScreen(
    offerId: String,
    onBack: () -> Unit,
    onChatClick: (String, String) -> Unit,
    onTradeStarted: (String) -> Unit = {},
    onEdit: () -> Unit = {}
)
```

(remove `onEscrowCreated: (String) -> Unit`).

2. In the accept-flow callback (the `onAccepted` lambda, ~line 206-231), replace the `Proceed` branch:

```kotlin
is OfferDetailViewModel.AcceptOutcome.Proceed -> {
    onTradeStarted(it.offerId)
}
```

3. In the `onCreateEscrow` wiring (~line 146-150), replace:

```kotlin
onCreateEscrow = { offer ->
    viewModel.createSellerEscrow(offer) { escrowId ->
        if (escrowId != null) onTradeStarted(offer.offerId)
    }
},
```

(On escrow-creation failure the seller stays on offer detail, where the error is already surfaced via `_uiState`.)

- [ ] **Step 2: NavGraph — route post-accept to the hub, update the hub call, drop dead params**

In `app/src/main/java/com/neop2p/navigation/NavGraph.kt`:

1. In the `OFFER_DETAIL` composable (~line 162-172), replace:

```kotlin
onEscrowCreated = { escrowId ->
    navController.navigate(Routes.escrow(escrowId))
},
```

with:

```kotlin
onTradeStarted = { offerId ->
    navController.navigate(Routes.tradeRoom(offerId))
},
```

2. In the `TRADE_ROOM` composable (~line 318-329), replace the `TradeRoomScreen` call with:

```kotlin
com.neop2p.ui.screens.trade.TradeRoomScreen(
    offerId = offerId,
    onBack = { navController.popBackStack() },
    onOpenEscrow = { escrowId -> navController.navigate(Routes.escrow(escrowId)) },
    onOpenChat = { oid, pid -> navController.navigate(Routes.chat(oid, pid)) },
    onOpenReceipt = { eid -> navController.navigate(Routes.escrowReceipt(eid)) }
)
```

3. Remove the dead `onTabChange` wiring:
   - `WALLET` composable (~line 270-275): remove `onTabChange = ::switchTab`.
   - `PROFILE` composable (~line 255-262): remove `onTabChange = ::switchTab`.
   - `TRADES` composable (~line 279-287): remove `onTabChange = ::switchTab`.

- [ ] **Step 3: Remove the dead `onTabChange` params from the three screens**

- `app/src/main/java/com/neop2p/ui/screens/wallet/WalletScreen.kt`: remove the `onTabChange: (com.neop2p.ui.components.AppTab) -> Unit = {},` parameter from `WalletScreen` (line ~64).
- `app/src/main/java/com/neop2p/ui/screens/profile/ProfileScreen.kt`: remove the `onTabChange: (com.neop2p.ui.components.AppTab) -> Unit = {},` parameter from `ProfileScreen` (line ~51).
- `app/src/main/java/com/neop2p/ui/screens/history/HistoryScreen.kt`: remove the `onTabChange: (com.neop2p.ui.components.AppTab) -> Unit = {},` parameter from `HistoryScreen` (line ~51).

Do NOT touch `HomeScreen`'s `onNavigate` — it is used by the portfolio header.

- [ ] **Step 4: MainActivity — whitelist the trade route**

In `app/src/main/java/com/neop2p/MainActivity.kt`, in `isKnownRoute` (line ~137-148), add after the `route.startsWith("escrow/")` line:

```kotlin
route.startsWith("trade/") ||
```

- [ ] **Step 5: Build + tests**

Run: `./gradlew :app:assembleDebug` then `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/neop2p/navigation/NavGraph.kt android/app/src/main/java/com/neop2p/ui/screens/offerdetail/OfferDetailScreen.kt android/app/src/main/java/com/neop2p/MainActivity.kt android/app/src/main/java/com/neop2p/ui/screens/wallet/WalletScreen.kt android/app/src/main/java/com/neop2p/ui/screens/profile/ProfileScreen.kt android/app/src/main/java/com/neop2p/ui/screens/history/HistoryScreen.kt
git commit -m "feat: route post-accept trades to the trade hub; drop dead nav params"
```

---

### Task 6: Invite links as system deep links

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/neop2p/MainActivity.kt`
- Test: `app/src/test/java/com/neop2p/ui/screens/invite/InviteViewModelTest.kt`

**Interfaces:**
- Consumes: `InviteViewModel.parseInvite(raw: String): Pair<String, String?>?` (exists, companion fun), `PeerRegistry.recordPeerSeen(peerId: String, authenticated: Boolean = false, multiaddrs: List<String> = emptyList())` (exists), `IdentityManager.myPeerId()` (exists).
- Produces: `neop2p://peer/<id>` handled on cold + warm start; `parseInvite` covered by tests.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/neop2p/ui/screens/invite/InviteViewModelTest.kt`:

```kotlin
package com.neop2p.ui.screens.invite

import com.neop2p.ui.screens.invite.InviteViewModel.Companion.parseInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteViewModelTest {

    private val peerId = "12D3KooWAbCdEfGhIjKlMnOpQrStUvWxYz"

    @Test
    fun `parses full invite link`() {
        val parsed = parseInvite("neop2p://peer/$peerId")
        assertEquals(peerId, parsed!!.first)
    }

    @Test
    fun `parses bare peer id`() {
        val parsed = parseInvite(peerId)
        assertEquals(peerId, parsed!!.first)
    }

    @Test
    fun `strips query params`() {
        val parsed = parseInvite("neop2p://peer/$peerId?utm_source=wa")
        assertEquals(peerId, parsed!!.first)
    }

    @Test
    fun `rejects too-short id`() {
        assertNull(parseInvite("neop2p://peer/abc"))
    }

    @Test
    fun `rejects non-alphanumeric id`() {
        assertNull(parseInvite("neop2p://peer/12D3KooW-abc"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.ui.screens.invite.InviteViewModelTest"`
Expected: FAIL — test class not found (no test file existed before).

- [ ] **Step 3: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.ui.screens.invite.InviteViewModelTest"`
Expected: PASS (5 tests) — `parseInvite` already implements this behavior; the test locks it in.

- [ ] **Step 4: Manifest — register the invite scheme**

In `app/src/main/AndroidManifest.xml`, inside the existing `<activity android:name=".MainActivity" ...>` element, add a second intent-filter after the existing MAIN/LAUNCHER filter:

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="neop2p" android:host="peer" />
</intent-filter>
```

- [ ] **Step 4b: Add the toast string (EN + ID)**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="invite_deep_link_added">Peer %1$s… added</string>
```

In `app/src/main/res/values-in/strings.xml`, add:

```xml
<string name="invite_deep_link_added">Peer %1$s… ditambahkan</string>
```

- [ ] **Step 5: MainActivity — consume invite intents**

In `app/src/main/java/com/neop2p/MainActivity.kt`:

1. Add the injection (next to `identityManager`):

```kotlin
@Inject
lateinit var peerRegistry: com.neop2p.data.p2p.store.PeerRegistry
```

2. Add a field to remember the start destination (next to `pendingIntent`):

```kotlin
private var startDestination: String = Routes.ONBOARDING
```

3. In `onCreate`, set it where the start destination is computed (line ~74-82):

```kotlin
startDestination = if (com.neop2p.data.local.OnboardingGate.shouldShowOnboarding(
        identityManager.hasIdentity(),
        com.neop2p.data.local.OnboardingStore(applicationContext).isComplete()
    )
) {
    Routes.ONBOARDING
} else {
    Routes.HOME
}
```

4. In the `onNavControllerReady` callback (line ~83-89), also consume invite intents:

```kotlin
onNavControllerReady = { controller ->
    navController = controller
    consumeNotificationIntent(pendingIntent ?: intent)
    consumeInviteIntent(pendingIntent ?: intent)
    pendingIntent = null
}
```

5. In `onNewIntent` (line ~96-104), also consume invite intents:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (navController != null) {
        consumeNotificationIntent(intent)
        consumeInviteIntent(intent)
    } else {
        pendingIntent = intent
    }
}
```

6. Add the consumer method (next to `consumeNotificationIntent`):

```kotlin
/**
 * Handle a `neop2p://peer/<peerId>` invite link (tapped in WhatsApp, a
 * browser, or a QR scanner). Records the peer locally and lands on Home.
 * Ignored when the user has not finished onboarding (no identity yet) or
 * the link is malformed / self-referential.
 */
private fun consumeInviteIntent(intent: Intent?) {
    if (intent == null) return
    val data = intent.data ?: return
    if (data.scheme != "neop2p" || data.host != "peer") return
    if (startDestination != Routes.HOME) {
        Log.w(TAG, "Ignoring invite before onboarding: $data")
        return
    }
    val parsed = com.neop2p.ui.screens.invite.InviteViewModel.parseInvite(data.toString())
    if (parsed == null) {
        Log.w(TAG, "Ignoring malformed invite: $data")
        return
    }
    val peerId = parsed.first
    if (peerId == runCatching { identityManager.myPeerId() }.getOrNull()) {
        Log.w(TAG, "Ignoring self invite: $peerId")
        return
    }
    peerRegistry.recordPeerSeen(peerId)
    android.widget.Toast.makeText(
        this,
        getString(com.neop2p.R.string.invite_deep_link_added, peerId.take(12)),
        android.widget.Toast.LENGTH_SHORT
    ).show()
    val controller = navController ?: return
    if (controller.currentDestination?.route != Routes.HOME) {
        controller.navigate(Routes.HOME) {
            popUpTo(Routes.HOME) { inclusive = true }
        }
    }
}
```

- [ ] **Step 6: Build + tests**

Run: `./gradlew :app:assembleDebug` then `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (including the new `InviteViewModelTest`).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml android/app/src/main/java/com/neop2p/MainActivity.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-in/strings.xml android/app/src/test/java/com/neop2p/ui/screens/invite/InviteViewModelTest.kt
git commit -m "feat: handle neop2p invite links as system deep links"
```

---

### Task 7: One-time notification permission rationale

**Files:**
- Create: `app/src/main/java/com/neop2p/ui/screens/home/NotifRationale.kt`
- Test: `app/src/test/java/com/neop2p/ui/screens/home/NotifRationaleTest.kt`
- Modify: `app/src/main/java/com/neop2p/ui/screens/home/HomeScreen.kt`
- Modify: `app/src/main/res/values/strings.xml` and `app/src/main/res/values-in/strings.xml`

**Interfaces:**
- Consumes: nothing new.
- Produces: `enum class NotifRationaleDecision { NONE, SHOW_RATIONALE, REQUEST }` and `fun notifRationaleDecision(hasPermission: Boolean, rationaleShown: Boolean): NotifRationaleDecision` — consumed by `HomeScreen`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/neop2p/ui/screens/home/NotifRationaleTest.kt`:

```kotlin
package com.neop2p.ui.screens.home

import com.neop2p.ui.screens.home.NotifRationaleDecision
import com.neop2p.ui.screens.home.notifRationaleDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class NotifRationaleTest {

    @Test
    fun `no decision when permission already granted`() {
        assertEquals(NotifRationaleDecision.NONE, notifRationaleDecision(hasPermission = true, rationaleShown = false))
    }

    @Test
    fun `show rationale on first run`() {
        assertEquals(NotifRationaleDecision.SHOW_RATIONALE, notifRationaleDecision(hasPermission = false, rationaleShown = false))
    }

    @Test
    fun `request directly once rationale has been shown`() {
        assertEquals(NotifRationaleDecision.REQUEST, notifRationaleDecision(hasPermission = false, rationaleShown = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.ui.screens.home.NotifRationaleTest"`
Expected: FAIL — `notifRationaleDecision` unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/neop2p/ui/screens/home/NotifRationale.kt`:

```kotlin
package com.neop2p.ui.screens.home

/** What the Home screen should do about the POST_NOTIFICATIONS permission. */
enum class NotifRationaleDecision { NONE, SHOW_RATIONALE, REQUEST }

/**
 * One-time rationale gate: the system prompt is only shown after the user
 * has seen (and dismissed) the in-app explanation, so a reflexive denial
 * on first launch is less likely. Pure for unit testing.
 */
fun notifRationaleDecision(hasPermission: Boolean, rationaleShown: Boolean): NotifRationaleDecision = when {
    hasPermission -> NotifRationaleDecision.NONE
    !rationaleShown -> NotifRationaleDecision.SHOW_RATIONALE
    else -> NotifRationaleDecision.REQUEST
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.neop2p.ui.screens.home.NotifRationaleTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Add the strings (EN + ID)**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="notif_rationale_title">Allow notifications?</string>
<string name="notif_rationale_body">NEO-P2P needs notifications to tell you when your offer is matched, the escrow is funded, or the seller confirms payment. No ads, no tracking — messages stay on your device.</string>
<string name="notif_rationale_allow">Allow</string>
<string name="notif_rationale_later">Not now</string>
```

In `app/src/main/res/values-in/strings.xml`, add:

```xml
<string name="notif_rationale_title">Izinkan notifikasi?</string>
<string name="notif_rationale_body">NEO-P2P membutuhkan notifikasi untuk memberi tahu saat tawaran Anda cocok, escrow didanai, atau penjual mengonfirmasi pembayaran. Tanpa iklan, tanpa pelacakan — pesan tetap di perangkat Anda.</string>
<string name="notif_rationale_allow">Izinkan</string>
<string name="notif_rationale_later">Nanti</string>
```

- [ ] **Step 6: HomeScreen — gate the permission request behind the rationale**

In `app/src/main/java/com/neop2p/ui/screens/home/HomeScreen.kt`, replace the `LaunchedEffect(Unit)` block (lines ~167-179) with:

```kotlin
var showNotifRationale by remember { mutableStateOf(false) }
val notifRationalePrefs = remember {
    context.getSharedPreferences("neop2p_notif_rationale", android.content.Context.MODE_PRIVATE)
}
LaunchedEffect(Unit) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        when (notifRationaleDecision(
            hasPermission = hasNotifPermission(),
            rationaleShown = notifRationalePrefs.getBoolean("shown", false)
        )) {
            NotifRationaleDecision.SHOW_RATIONALE -> showNotifRationale = true
            NotifRationaleDecision.REQUEST -> notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            NotifRationaleDecision.NONE -> {}
        }
    }
    val svcIntent = android.content.Intent(context, com.neop2p.service.P2PBackgroundService::class.java)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        context.startForegroundService(svcIntent)
    } else {
        context.startService(svcIntent)
    }
}
```

Then add the rationale dialog at the end of the `NeoP2PTheme { ... }` block (after the `Scaffold`, before the closing brace of `NeoP2PTheme`):

```kotlin
if (showNotifRationale) {
    AlertDialog(
        onDismissRequest = { showNotifRationale = false },
        title = { Text(stringResource(R.string.notif_rationale_title)) },
        text = { Text(stringResource(R.string.notif_rationale_body)) },
        confirmButton = {
            TextButton(onClick = {
                showNotifRationale = false
                notifRationalePrefs.edit().putBoolean("shown", true).apply()
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }) {
                Text(stringResource(R.string.notif_rationale_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                showNotifRationale = false
                notifRationalePrefs.edit().putBoolean("shown", true).apply()
            }) {
                Text(stringResource(R.string.notif_rationale_later))
            }
        }
    )
}
```

Add the import at the top of the file (with the other `com.neop2p.ui.screens.home` imports — it is the same package, so no import is needed for `NotifRationaleDecision`/`notifRationaleDecision`; they resolve automatically).

- [ ] **Step 7: Build + tests**

Run: `./gradlew :app:assembleDebug` then `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/home/NotifRationale.kt android/app/src/test/java/com/neop2p/ui/screens/home/NotifRationaleTest.kt android/app/src/main/java/com/neop2p/ui/screens/home/HomeScreen.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values-in/strings.xml
git commit -m "feat: show one-time rationale before notification permission prompt"
```

---

## Final verification

- [ ] Run `./gradlew :app:assembleDebug` from `android/` — BUILD SUCCESSFUL.
- [ ] Run `./gradlew :app:testDebugUnitTest` from `android/` — all tests pass (existing + `TradeRoomDataTest` + `InviteViewModelTest` + `NotifRationaleTest`).
- [ ] Run `./gradlew :app:lintDebug` from `android/` — no new lint issues beyond the baseline.
- [ ] Manual QA (device/emulator):
  - Accept a SELL offer as the buyer → lands on the trade hub; the pay card + next action appear once the escrow row syncs (live-collect re-renders — allow a few seconds; the locked-chat tooltip shows meanwhile).
  - Accept a BUY offer as the seller → lands on the trade hub, funding next action visible. NOTE (single-key model): role resolution is buyer-first, matching `EscrowService.roleFor` — on a single-key device the seller's hub shows the buyer view, same as the escrow screen today. This is expected, not a regression.
  - Seller's bank details arrive via chat after FUNDED → the hub's pay card updates WITHOUT leaving and re-entering (offer-row observer).
  - Tap "Buka Eskro" / "Buka Obrolan" from the hub → correct screens; Back returns to the hub.
  - `adb shell am start -a android.intent.action.VIEW -d "neop2p://peer/12D3KooWAbCdEfGhIjKlMnOpQrStUvWxYz"` → app opens on Home with a localized "Peer added" toast (after onboarding).
  - Fresh install → rationale dialog appears before the system notification prompt; "Nanti" → banner shows; second launch → system prompt directly.

## Self-review notes

- **Spec coverage:** R1 (trade hub) → Tasks 1-5; R2 (invite deep link) → Task 6; R3 (notification rationale) → Task 7. Flow-analysis issues #5 (dead TradeRoom route) and #6 (dead `onTabChange` params) are fixed in Task 5. Issue #7 (premature permission prompt) → Task 7. QA cases 6, 10, 12 from section 13 map to Tasks 5, 7, 6.
- **Deviations from the spec (deliberate):** the hub is a status + navigation hub, not an embedding of the full Escrow/Chat screens — embedding would require re-scoping `EscrowViewModel` (its `escrowId` comes from the nav-arg `savedStateHandle`) and duplicating the money-action dialogs; the hub instead reuses the real screens via one-tap navigation and surfaces the next action + pay card inline. The receipt composer stays a pushed route (its draft machinery is route-scoped). Notification deep links still go to the full Escrow/Chat screens (the hub is the post-accept destination, not a replacement for deep-link targets).
- **Review fixes applied (2026-09-02 review):**
  - Task 2: `resolveTradeRoom` documents buyer-first role resolution (matches `EscrowService.roleFor`); the single-key seller device shows the buyer view — consistent with the escrow screen, QA expects it.
  - Task 3 + Task 4: the ViewModel now observes the OFFER row too (payment details arrive via E2EE chat after load — the pay card would otherwise stay empty until re-entry), and both live collectors are wrapped in `runCatching` so a Room flow failure cannot permanently flip the hub to Error.
  - Task 4: `PayInstructionCard` receives `methods = data.paymentDetails.keys` (matches the escrow screen's own call at EscrowScreen.kt:818) instead of `fiatMethods`.
  - Task 6: the "Peer added" toast uses a new `invite_deep_link_added` string in EN + ID (string-parity gate); the test file is a lock-in test (Step 2's "failure" is class-not-found, not a red test — intentional, `parseInvite` already implements the behavior).
  - QA checklist: notes the pre-sync pay-card delay, the single-key buyer-view expectation, and the live pay-card update.
- **Known minor items (deliberate, not fixed):** the hub's receipt button gates on PAYMENT_PENDING/RECEIPT_SENT while the header pay card renders through CONFIRMING (matches the escrow screen's own gating split); the hub shows the pay card inline AND the full escrow screen also shows it (hub = summary, deliberate); `TabRow` tab state uses `remember` not `rememberSaveable` (resets on config change — acceptable for a hub); the chat button may show "No peer yet" for a few seconds after accept until the `offer_status` sync lands; `consumeInviteIntent`'s `popUpTo(HOME){inclusive}` is harmless when already on HOME.
- **Placeholder scan:** no TBDs; every step has concrete code or exact edits.
- **Type consistency:** `resolveTradeRoom(offer, escrow, myPeerId): TradeRoomData` defined in Task 2 and used identically in Tasks 3-4; `TradeRoomScreen(offerId, onBack, onOpenEscrow, onOpenChat, onOpenReceipt, viewModel)` defined in Task 4 and wired in Task 5; `notifRationaleDecision(hasPermission, rationaleShown)` defined and used in Task 7; `parseInvite` used in Task 6 exactly as it exists.
