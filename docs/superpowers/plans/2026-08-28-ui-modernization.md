# NEO-P2P UI Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modernize NEO-P2P's UI to 2026 Android standards (M3 Expressive motion, bottom navigation, button hierarchy, token-driven theme) without changing any business logic.

**Architecture:** Three-layer change. (1) Theme.kt becomes the single source of truth: expressive motion scheme, custom typography with tabular numerals for money, hairline card borders, and a status-chip color token function. (2) A new shared component layer (`ui/components/`) provides MoneyText, EmptyState, and OnboardingStepIndicator. (3) Navigation is restructured around a 4-tab `AppTab` bottom NavigationBar (Market/Wallet/Trades/Profile) following the Now in Android `TopLevelDestination` pattern, with Settings folded into Profile and the Home top bar reduced to contextual quick-access icons only.

**Tech Stack:** Jetpack Compose, Material 3 (compose-bom 2026.03.00 → material3-android 1.4.0 — verified in gradle cache), Compose Navigation 2.8.5, Kotlin 2.1.0, Hilt, Room.

**Spec:** Analysis + research corpus in `android/neo-p2p-ui/references/modernization-blueprint-2026-08-28.md` and session research (Tangem DeepWiki design system, Now in Android TopLevelDestination, RoboSats single-action-per-state, MostroP2P shadow/spacing tokens, Bithumb structural-depth design review, Bitcoin Design guide).

## Global Constraints

- **DO NOT change any business logic, ViewModel behavior, DB, or protocol code.** This is a UI-only plan. If a change requires touching `data/`, `service/`, or a ViewModel's logic, STOP and ask.
- All colors via `MaterialTheme.colorScheme.*` — never hardcoded `Color(0x...)` outside `Theme.kt`. The status-chip palette (currently duplicated at `EscrowScreen.kt:346-356` and `HistoryScreen.kt:148-158`) moves INTO Theme.kt as one token function — those are the only legit hardcoded hex sites outside the theme.
- All new user-facing text via `stringResource` + entries in BOTH `values/strings.xml` (EN) and `values-in/strings.xml` (ID). Verify with `python scripts/check_strings_parity.py` from the skill.
- Dark theme stays forced (`darkTheme = true`). NO dynamic color, NO light-mode enable, NO third-party UI kits (deliberate — brand hues are semantic: green=buy, cyan=chat, orange=escrow).
- Build/verify commands run from `android/` (`workdir: /home/thesdony/neop2p-btc/android`). Never pipe gradle through tail and trust exit code (`PIPESTATUS` trap).
- Do NOT commit; user commits. Do NOT install to devices unless user says so.
- EscrowScreen.kt is 2008 lines — when editing, always `read_file` the exact region first (line numbers shift as edits apply).

---

### Task 1: Theme.kt — Motion, Typography, Hairline, Status-Chip Tokens

**Files:**
- Modify: `app/src/main/java/com/neop2p/ui/theme/Theme.kt` (entire file rewrite, 126 lines)
- Modify: `app/build.gradle.kts` (add one opt-in compiler arg)
- Verify: `./gradlew :app:compileDebugKotlin`

**Interfaces:**
- Produces (consumed by later tasks):
  - `NeoP2PTheme` now wires `motionScheme = MotionScheme.expressive()` and `typography = NeoTypography`
  - `NeoTypography`: `Typography` with `fontFeatureSettings = "tnum"` on `headlineMedium`, `titleLarge`, `titleMedium`, `bodyLarge`, `bodyMedium`, `labelLarge`, `labelMedium`, `labelSmall` (money roles)
  - `val ColorScheme.escrowStatusColors(status: EscrowStatus): Pair<Color, Color>` — container to content
  - `Modifier.cardHairline(color: Color)` — 1.dp border, 0 shadow wrapper for cards
  - `NeoElevations`: `card = 2.dp`, `raised = 6.dp` (used sparingly; hairline is primary)

- [ ] **Step 1: Add the expressive opt-in compiler arg**

In `app/build.gradle.kts`, the `kotlinOptions.freeCompilerArgs` list (currently `-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi` and `-opt-in=androidx.compose.material3.ExperimentalMaterial3Api`) — add:

```kotlin
"-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
```

- [ ] **Step 2: Rewrite Theme.kt**

Replace the whole file. Keep `DarkColorScheme`, `LightColorScheme`, `buyColor`, `sellColor`, `escrowColor`, `warningColor`, `NeoShapes` EXACTLY as they are today (verified 2026-08-28 — no token drift). Add:

```kotlin
// After the existing color schemes — tabular-nums money typography.
// "tnum" keeps digits from jumping as amounts tick (Bithumb/Muun money-grade pattern).
val NeoTypography = Typography().run {
    val money = fontFeatureSettings("tnum") // FontFeatureSettings is a String: "tnum"
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        titleLarge = titleLarge.copy(fontFeatureSettings = "tnum"),
        titleMedium = titleMedium.copy(fontFeatureSettings = "tnum"),
        bodyLarge = bodyLarge.copy(fontFeatureSettings = "tnum"),
        bodyMedium = bodyMedium.copy(fontFeatureSettings = "tnum"),
        labelLarge = labelLarge.copy(fontFeatureSettings = "tnum"),
        labelMedium = labelMedium.copy(fontFeatureSettings = "tnum"),
        labelSmall = labelSmall.copy(fontFeatureSettings = "tnum")
    )
}

// Structural depth (Bithumb review: 1px borders, not shadows — but M3 cards
// still get a whisper of elevation so the surface ladder reads at a glance).
object NeoElevations {
    val card = 2.dp
    val raised = 6.dp
}

// Hairline card border — Mostro-style named border, kills the
// "flat cards floating on flat background" problem without drop-shadow spam.
fun Modifier.cardHairline(color: Color = Color(0xFF21262D)): Modifier =
    this.then(Modifier.border(1.dp, color, RoundedCornerShape(12.dp)))

// Status chip tokens — the ONE source of truth (kills the duplicated
// 12-hex-pair palette in EscrowScreen.kt:346 and HistoryScreen.kt:148).
fun ColorScheme.escrowStatusColors(status: EscrowStatus): Pair<Color, Color> = when (status) {
    EscrowStatus.FUNDING -> Color(0xFF854D0E) to Color(0xFFFCD34D)
    EscrowStatus.FUNDED -> Color(0xFF065F46) to Color(0xFF6EE7B7)
    EscrowStatus.PAYMENT_PENDING -> Color(0xFF78350F) to Color(0xFFFDE68A)
    EscrowStatus.RECEIPT_SENT -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
    EscrowStatus.SIGNED -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
    EscrowStatus.CONFIRMING -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
    EscrowStatus.RELEASED -> Color(0xFF065F46) to Color(0xFF6EE7B7)
    EscrowStatus.DISPUTED -> Color(0xFF7F1D1D) to Color(0xFFFCA5A5)
    EscrowStatus.RESOLVING -> Color(0xFF581C87) to Color(0xFFC084FC)
    EscrowStatus.CANCELLED -> Color(0xFF78350F) to Color(0xFFFDE68A)
    EscrowStatus.REFUNDED -> Color(0xFF1F2937) to Color(0xFFD1D5DB)
}
```

Imports needed: `androidx.compose.foundation.border`, `androidx.compose.ui.graphics.Color` (already), `androidx.compose.ui.text.font.FontWeight`, `com.neop2p.domain.model.EscrowStatus`.

In `NeoP2PTheme`, wire motion + typography:

```kotlin
@Composable
fun NeoP2PTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NeoTypography,
        shapes = NeoShapes,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
```

- [ ] **Step 3: Verify**

```bash
cd /home/thesdony/neop2p-btc/android
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. If `MotionScheme` or `MaterialTheme(motionScheme=...)` is unresolved, the BOM/material3 version is not 1.4.0 (verified present in `~/.gradle/caches/modules-2/files-2.1/androidx.compose.material3/material3-android/1.4.0` on 2026-08-28) — re-check the cache dir before changing anything.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/neop2p/ui/theme/Theme.kt
git commit -m "feat(ui): M3 Expressive motion, tabular-nums typography, hairline + status-chip tokens"
```

---

### Task 2: Shared components — MoneyText, EmptyState, OnboardingStepIndicator

**Files:**
- Create: `app/src/main/java/com/neop2p/ui/components/MoneyText.kt`
- Create: `app/src/main/java/com/neop2p/ui/components/EmptyState.kt`
- Create: `app/src/main/java/com/neop2p/ui/components/OnboardingStepIndicator.kt`
- Verify: `./gradlew :app:compileDebugKotlin`

**Interfaces:**
- Produces (consumed by Tasks 3, 4, 5):
  - `@Composable fun MoneyText(text: String, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified, modifier: Modifier = Modifier)` — sets `fontFeatureSettings = "tnum"` + `fontFamily = FontFamily.Monospace` on top of the given style (mono for stable money columns; tnum is a no-op for mono but harmless)
  - `@Composable fun BtcAmountText(sats: Long, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified, modifier: Modifier = Modifier)` — calls `formatBtc(sats)` (from `ui/util/BtcFormat.kt`, already exists) then `MoneyText`
  - `@Composable fun NeoEmptyState(icon: ImageVector?, painter: Painter?, title: String, hint: String? = null, modifier: Modifier = Modifier)` — centered icon (36.dp, onSurfaceVariant), title (titleMedium, onSurface), hint (bodyMedium, onSurfaceVariant); no interactive parts
  - `@Composable fun OnboardingStepIndicator(total: Int, current: Int, modifier: Modifier = Modifier)` — 6 circles (10.dp), filled = `primary` (4.dp inner white dot when current), past = `primaryContainer`, future = `surfaceVariant`; `current` is 0-based

- [ ] **Step 1: Create MoneyText.kt**

```kotlin
package com.neop2p.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.neop2p.ui.util.formatBtc

@Composable
fun MoneyText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum"
    )
}

@Composable
fun BtcAmountText(
    sats: Long,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    MoneyText(text = formatBtc(sats), style = style, color = color, modifier = modifier)
}
```

- [ ] **Step 2: Create EmptyState.kt**

```kotlin
package com.neop2p.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun NeoEmptyState(
    icon: ImageVector? = null,
    painter: Painter? = null,
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        } else if (painter != null) {
            Icon(painter, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
```

- [ ] **Step 3: Create OnboardingStepIndicator.kt**

```kotlin
package com.neop2p.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingStepIndicator(total: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(total) { index ->
            val color = when {
                index < current -> MaterialTheme.colorScheme.primaryContainer
                index == current -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(color)
            )
        }
    }
}
```

- [ ] **Step 4: Verify + Commit**

```bash
cd /home/thesdony/neop2p-btc/android
./gradlew :app:compileDebugKotlin
git add app/src/main/java/com/neop2p/ui/components/
git commit -m "feat(ui): shared MoneyText, EmptyState, OnboardingStepIndicator components"
```
Expected: BUILD SUCCESSFUL. (Unused-param lint for `current`/`index` is fine — index is used.)

---

### Task 3: Bottom Navigation shell — AppTab + NavGraph + Home top-bar cleanup

**Files:**
- Create: `app/src/main/java/com/neop2p/ui/components/AppNavigationBar.kt`
- Modify: `app/src/main/java/com/neop2p/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/neop2p/ui/screens/home/HomeScreen.kt` (top bar + signature)
- Modify: `app/src/main/java/com/neop2p/ui/screens/profile/ProfileScreen.kt` (add Settings entry, keep onBack)
- Modify: `app/src/main/java/com/neop2p/ui/screens/wallet/WalletScreen.kt` (signature: `onTabChange` no-op default)
- Modify: `app/src/main/java/com/neop2p/ui/screens/history/HistoryScreen.kt` (same)
- Modify: `app/src/main/res/values/strings.xml` + `values-in/strings.xml` (4 tab labels + 4 cd strings + profile settings entry)
- Verify: `./gradlew :app:compileDebugKotlin`

**Interfaces:**
- Produces:
  - `enum class AppTab(val route: String, @StringRes val labelRes: Int, @StringRes val cdRes: Int, @DrawableRes val iconRes: Int)` with `MARKET("market", R.string.tab_market, R.string.tab_cd_market, R.drawable.ic_storefront)`, `WALLET("wallet", R.string.tab_wallet, R.string.tab_cd_wallet, R.drawable.ic_account_balance)`, `TRADES("trades", R.string.tab_trades, R.string.tab_cd_trades, R.drawable.ic_history)`, `PROFILE("profile", R.string.tab_profile, R.string.tab_cd_profile, R.drawable.ic_person)`
  - `fun AppTab.fromRoute(route: String?): AppTab` — `entries.firstOrNull { route?.startsWith(it.route) == true } ?: MARKET` (startsWith so `trades/...` nested routes still highlight Trades — escrow/chat are pushed on top, see caveat in Step 5)
  - `@Composable fun AppNavigationBar(current: AppTab, onTabSelected: (AppTab) -> Unit, modifier: Modifier = Modifier)`
  - HomeScreen signature: remove `onSettingsClick`, `onWalletClick`, `onHistoryClick`, `onProfileClick`; ADD `onNavigate: (AppTab) -> Unit` (default `{}` for previews)
  - WalletScreen/HistoryScreen/ProfileScreen signatures: ADD `onTabChange: (AppTab) -> Unit = {}` (optional; only Profile uses it for Settings→fold, Wallet/History keep onBack only when pushed)

- [ ] **Step 1: Icons inventory**

Check `app/src/main/res/drawable/` for `ic_storefront` (or a market/swap icon). If missing, reuse an existing icon (`ic_account_balance`, `ic_history`, `ic_person`, `ic_settings` are confirmed present). List actual files:

```bash
ls app/src/main/res/drawable/ | grep -E "ic_(storefront|market|swap|store|account_balance|history|person|settings|chat|lock)"
```

Use the closest available icon for MARKET — if none fits, add a 24dp vector `ic_storefront.xml` (path data from Material Icons: storefront is `M21.9 8.89...` — copy from `material-icons-extended` if present, else use `Icons.Filled.Storefront` via `painterResource` is NOT possible for extended icons — fallback: `Icons.Outlined.Storefront` in AppNavigationBar code instead of a drawable, keeping `iconRes` as nullable and rendering `Icon(painterResource(...))` for drawables or `Icon(Icons...)` for material icons). Simplest safe choice: MARKET uses `R.drawable.ic_history`? NO — Trades owns history. Use `Icons.Filled.Storefront` from `androidx.compose.material.icons.filled.Storefront` (material-icons-extended IS a dependency — confirmed in libs.versions.toml). So: `AppTab` carries `icon: ImageVector` for MARKET/others where extended icons exist, and `painterRes: Int?` fallback for the legacy drawables. To keep it simple and consistent: use extended `ImageVector` icons for ALL four: `Icons.Filled.Storefront`, `Icons.Filled.AccountBalanceWallet` (extended), `Icons.Filled.ReceiptLong` (extended), `Icons.Filled.Person`. All present in material-icons-extended 1.7.x.

- [ ] **Step 2: Create AppNavigationBar.kt**

```kotlin
package com.neop2p.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.neop2p.R

enum class AppTab(
    val route: String,
    @StringRes val labelRes: Int,
    @StringRes val cdRes: Int,
    val icon: ImageVector
) {
    MARKET("market", R.string.tab_market, R.string.tab_cd_market, Icons.Filled.Storefront),
    WALLET("wallet", R.string.tab_wallet, R.string.tab_cd_wallet, Icons.Filled.AccountBalanceWallet),
    TRADES("trades", R.string.tab_trades, R.string.tab_cd_trades, Icons.Filled.ReceiptLong),
    PROFILE("profile", R.string.tab_profile, R.string.tab_cd_profile, Icons.Filled.Person);

    companion object {
        fun fromRoute(route: String?): AppTab =
            entries.firstOrNull { route?.startsWith(it.route) == true } ?: MARKET
    }
}

@Composable
fun AppNavigationBar(
    current: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = stringResource(tab.cdRes)) },
                label = { Text(stringResource(tab.labelRes)) }
            )
        }
    }
}
```

- [ ] **Step 3: Strings (both catalogs)**

`values/strings.xml` (EN) — add:
```xml
<string name="tab_market">Market</string>
<string name="tab_wallet">Wallet</string>
<string name="tab_trades">Trades</string>
<string name="tab_profile">Profile</string>
<string name="tab_cd_market">Market offers</string>
<string name="tab_cd_wallet">Wallet</string>
<string name="tab_cd_trades">Trade history</string>
<string name="tab_cd_profile">Profile and settings</string>
<string name="profile_open_settings">Settings</string>
```
`values-in/strings.xml` (ID) — add:
```xml
<string name="tab_market">Market</string>
<string name="tab_wallet">Dompet</string>
<string name="tab_trades">Transaksi</string>
<string name="tab_profile">Profil</string>
<string name="tab_cd_market">Penawaran pasar</string>
<string name="tab_cd_wallet">Dompet</string>
<string name="tab_cd_trades">Riwayat transaksi</string>
<string name="tab_cd_profile">Profil dan pengaturan</string>
<string name="profile_open_settings">Pengaturan</string>
```

- [ ] **Step 4: Rewire NavGraph**

Replace `composable(Routes.HOME) { ... }` block and add tab destinations + tab-change handling:

```kotlin
// Track current tab from the back stack
val backStackEntry by navController.currentBackStackEntryAsState()
val currentTab = AppTab.fromRoute(backStackEntry?.destination?.route)

fun switchTab(tab: AppTab) {
    val base = tab.route
    val existing = navController.currentBackStackEntry?.destination?.route
    if (existing?.startsWith(base) == true) return
    navController.navigate(base) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
```

Add routes `market`, `trades` (aliases of home/history), keep `home` → redirect to `market` on first composition or simply use `market` as the start destination when identity exists (`Routes.MARKET`). SIMPLEST safe approach (no start-destination churn in MainActivity): keep `Routes.HOME = "home"`, and make `AppTab.MARKET.route = "home"` — wait, no: `AppTab.fromRoute` uses route prefixes, so define:

- `MARKET.route = "home"` — HomeScreen (offer list)
- `WALLET.route = "wallet"` — WalletScreen
- `TRADES.route = "trades"` — NEW alias composable rendering HistoryScreen (keep `history` route too for deep links)
- `PROFILE.route = "profile"` — ProfileScreen

```kotlin
composable(Routes.HOME) {
    HomeScreen(
        onCreateOffer = { navController.navigate(Routes.CREATE_OFFER) },
        onOfferClick = { offerId -> navController.navigate(Routes.offerDetail(offerId)) },
        onChatClick = { offerId, peerId -> navController.navigate(Routes.chat(offerId, peerId)) },
        onEscrowClick = { escrowId -> navController.navigate(Routes.escrow(escrowId)) },
        onNavigate = ::switchTab
    )
}

composable(Routes.WALLET) {
    WalletScreen(
        onBack = { navController.popBackStack() },
        onTabChange = ::switchTab
    )
}

// New alias so the bottom bar's Trades tab is a first-class destination
// (deep-link /history/ keeps working via MainActivity.isKnownRoute).
composable(Routes.TRADES) {
    com.neop2p.ui.screens.history.HistoryScreen(
        onEscrowClick = { escrowId -> navController.navigate(Routes.escrow(escrowId)) },
        onBack = { navController.popBackStack() },
        onTabChange = ::switchTab
    )
}

composable(Routes.PROFILE) {
    ProfileScreen(
        onBack = { navController.popBackStack() },
        onTabChange = ::switchTab
    )
}
```

Add to `Routes`: `const val TRADES = "trades"`. Update `MainActivity.isKnownRoute` to include `Routes.TRADES`. `switchTab` needs `import androidx.navigation.NavGraph.Companion.findStartDestination` and `import androidx.navigation.compose.currentBackStackEntryAsState`. Since `switchTab` is local, hoist the tab state: `var currentTab by remember { mutableStateOf(AppTab.MARKET) }` + `LaunchedEffect(backStackEntry) { currentTab = AppTab.fromRoute(backStackEntry?.destination?.route) }`; pass `currentTab` to `AppNavigationBar`.

Wrap the NavHost with the Scaffold that owns the bottom bar:

```kotlin
Scaffold(
    bottomBar = { AppNavigationBar(current = currentTab, onTabSelected = ::switchTab) }
) { innerPadding ->
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.padding(innerPadding)
    ) { ...all existing composables... }
}
```

CAVEAT (must be documented in a comment): Escrow/Chat/OfferDetail/CreateOffer/Settings/Dispute* are pushed on top of the tabs; while one is open, `currentTab` keeps highlighting the tab it came from (fine), and the bottom bar remains visible on detail screens (acceptable for now — M3 apps often hide it on detail flows; hiding it is a P2 follow-up: pass a `showBottomBar` flag for non-tab routes). If the user objects, the P2 follow-up hides it for `escrow/`, `chat/`, `offer_detail/`, `create_offer`, `settings`.

- [ ] **Step 5: HomeScreen top-bar cleanup**

In `HomeScreen.kt` (currently lines 192-246): delete the 4 `actions` IconButtons (wallet/history/profile/settings) and their callbacks from the signature. Keep the `navigationIcon` Row (chat + escrow quick access — that is contextual, not chrome). New signature:

```kotlin
fun HomeScreen(
    onCreateOffer: () -> Unit,
    onOfferClick: (String) -> Unit,
    onChatClick: (String, String) -> Unit,
    onEscrowClick: (String) -> Unit,
    onNavigate: (AppTab) -> Unit = {},
    modifier: Modifier = Modifier
)
```

Remove now-unused imports (`onSettingsClick` etc. were parameters, not imports — just remove params). HomeViewModel stays untouched.

- [ ] **Step 6: ProfileScreen — Settings entry**

In `ProfileScreen.kt`, add `onTabChange: (AppTab) -> Unit = {}` param. In the profile content (find the section after nickname/attestations — read the file region first), add a settings row that navigates to Settings. ProfileScreen currently has no `onSettingsClick` — add one via NavGraph:

```kotlin
ProfileScreen(
    onBack = { navController.popBackStack() },
    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
    onTabChange = ::switchTab
)
```

In ProfileScreen body, add (reuse existing row styling — read the file for the pattern first):
```kotlin
// Settings entry — folds the gear into Profile per the nav cleanup.
Row(
    modifier = Modifier.fillMaxWidth().clickable { onSettingsClick() }.padding(16.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.width(12.dp))
    Text(stringResource(R.string.profile_open_settings), style = MaterialTheme.typography.bodyLarge)
}
```
Add `onSettingsClick: () -> Unit = {}` to ProfileScreen signature. Also add `onTabChange` to WalletScreen/HistoryScreen signatures (`= {}` default) so NavGraph can pass `::switchTab` — they are tab destinations; their own `onBack` stays for when they were reached as pushed screens (deep links).

- [ ] **Step 7: Verify + Commit**

```bash
cd /home/thesdony/neop2p-btc/android
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. Watch for: `AppTab.fromRoute` unused-param warnings (fine), missing imports for `findStartDestination`, `currentBackStackEntryAsState`, `Icons.Filled.AccountBalanceWallet`/`ReceiptLong`/`Storefront` (all in material-icons-extended — confirmed dependency).

```bash
git add app/src/main/java/com/neop2p/ui/components/AppNavigationBar.kt app/src/main/java/com/neop2p/navigation/NavGraph.kt app/src/main/java/com/neop2p/ui/screens/home/HomeScreen.kt app/src/main/java/com/neop2p/ui/screens/profile/ProfileScreen.kt app/src/main/java/com/neop2p/ui/screens/wallet/WalletScreen.kt app/src/main/java/com/neop2p/ui/screens/history/HistoryScreen.kt app/src/main/java/com/neop2p/MainActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-in/strings.xml
git commit -m "feat(ui): 4-tab bottom navigation shell; settings folds into profile"
```

---

### Task 4: EscrowScreen button hierarchy

**Files:**
- Modify: `app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt` (button sites — line numbers from 2026-08-28 census, re-read before each edit)
- Verify: `./gradlew :app:compileDebugKotlin`

**Interfaces:**
- Consumes: `ColorScheme.escrowStatusColors`, `MaterialTheme.colorScheme.*` (already in file)
- Produces: none (internal restyle only — zero behavioral change)

**Design rule (RoboSats/Mostro single-action-per-state + M3 hierarchy):** each status branch renders AT MOST ONE `Button` (primary). Secondary = `FilledTonalButton`, destructive = `OutlinedButton` with error content color, tertiary/quiet = `TextButton` with error content color. Confirm dialogs keep `Button` (confirm) + `TextButton` (cancel) — untouched.

- [ ] **Step 1: Replace the duplicated chip palette with the token function**

`EscrowScreen.kt:345-357` — replace the entire `when (status)` returning `Color(0x...) to Color(0x...)` with:
```kotlin
val (container, content) = MaterialTheme.colorScheme.escrowStatusColors(status)
```
And `RoundedCornerShape(50)` → `CircleShape` (line 358). Same for `HistoryScreen.kt:148-160` (its chip composable).

- [ ] **Step 2: FUNDING seller branch (lines ~669-767)**

- Keep `Button(onClick = onFundFromWallet, ...)` — primary (has the loading state; keep `height(40.dp)`).
- `Button(onClick = onVerifyFundingTx, ...)` → `FilledTonalButton(onClick = onVerifyFundingTx, ...)` — secondary; keep enabled logic + height.
- `OutlinedButton(onClick = onCancelRefund, ...)` → keep Outlined + error content color (already correct hierarchy — destructive is outlined-error).
- Leave the txid OutlinedTextField + countdown + timeout info untouched.

- [ ] **Step 3: FUNDED / SIGNED branches (lines ~815-857)**

- Buyer `Button(onClick = onMarkPaid, ...)` — keep (primary action).
- Seller `OutlinedButton(onClick = onCancelRefund, ...)` — keep (destructive, error colors).
- No other changes.

- [ ] **Step 4: PAYMENT_PENDING / RECEIPT_SENT branch (lines ~858-938)**

- Buyer `Button(onClick = onOpenReceipt, ...)` — keep (primary).
- Seller RECEIPT_SENT: `Button(onClick = onConfirmReceipt, ...)` → keep (primary — release gate); `OutlinedButton(onClick = onDispute, ...)` → **`TextButton`** with error content color (destructive but SUBORDINATE to the release gate — one primary, one quiet escape):
```kotlin
TextButton(
    onClick = onDispute,
    modifier = Modifier.fillMaxWidth().height(40.dp),
    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
) {
    Text(stringResource(R.string.escrow_dispute))
}
```
- Seller PAYMENT_PENDING `OutlinedButton(onClick = onDispute, ...)` (line ~930) → same TextButton treatment.

- [ ] **Step 5: CONFIRMING seller branch (lines ~950-971)**

- `Button(onClick = onConfirmReceipt, ...)` — keep (primary).
- `OutlinedButton(onClick = onDispute, ...)` → `TextButton` error (as Step 4).
- `OutlinedButton(onClick = onCancelRefund, ...)` → `TextButton` error (quiet escape under the primary gate; same colors as dispute TextButton).

- [ ] **Step 6: DISPUTED / RESOLVING branches (lines ~987-1018)**

- DISPUTED `Button(onClick = onOpenEvidence, ...)` → `FilledTonalButton` (submitting evidence is secondary to the ongoing dispute process).
- RESOLVING `OutlinedButton(onClick = onOpenEvidence, ...)` → keep (view-only action, outline is right).
- Catch-all dispute for FUNDED/SIGNED (lines ~1040-1049): `OutlinedButton(onClick = onDispute, ...)` → `TextButton` error (consistent with the other dispute escapes).

- [ ] **Step 7: Verify hierarchy + commit**

Walk every status with a role in the `when` and confirm exactly ONE `Button` per branch, secondary/escape buttons downgraded. Then:

```bash
cd /home/thesdony/neop2p-btc/android
./gradlew :app:compileDebugKotlin
git add app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt app/src/main/java/com/neop2p/ui/screens/history/HistoryScreen.kt
git commit -m "feat(ui): escrow button hierarchy — one primary per state, tonal secondary, text-error escapes"
```

---

### Task 5: Spacing sweep, wallet amounts, empty states, onboarding indicator

**Files:**
- Modify: `app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt` (6.dp chip padding, 10.dp paddings)
- Modify: `app/src/main/java/com/neop2p/ui/screens/chat/ChatScreen.kt` (6.dp paddings)
- Modify: `app/src/main/java/com/neop2p/ui/screens/wallet/WalletScreen.kt` (%.8f sites → formatBtc; 6.dp if any)
- Modify: `app/src/main/java/com/neop2p/ui/screens/history/HistoryScreen.kt` (empty state → NeoEmptyState)
- Modify: `app/src/main/java/com/neop2p/ui/screens/onboarding/OnboardingScreen.kt` (step indicator)
- Modify: `app/src/main/java/com/neop2p/ui/screens/home/HomeScreen.kt` (local EmptyState → NeoEmptyState)
- Verify: `./gradlew :app:compileDebugKotlin` + `./gradlew :app:testDebugUnitTest`

**Interfaces:**
- Consumes: `NeoEmptyState`, `OnboardingStepIndicator`, `BtcAmountText` from Task 2; `escrowStatusColors` from Task 1.

- [ ] **Step 1: Spacing sweep — exact site list (verified 2026-08-28)**

Replace (grep-verified counts; re-grep before editing since earlier tasks shift lines):
- `EscrowScreen.kt`: `padding(horizontal = 12.dp, vertical = 6.dp)` in EscrowStatusChip (line ~379) → `vertical = 8.dp`. `padding(10.dp)` at ~420 and `padding(10.dp)` at ~541 → `padding(8.dp)`.
- `ChatScreen.kt`: `vertical = 6.dp` at ~202, ~216, ~260 → `vertical = 8.dp`.
- Other 6.dp/10.dp/14.dp/18.dp/30.dp sites: icon sizes `18.dp` (EscrowScreen ~676, ~702, ~721) → `20.dp`; any `14.dp` → `16.dp`; `30.dp` → `32.dp`. Do NOT touch `strokeWidth = 2.dp`, `heightIn(max = 120/160/200/280/360.dp)`, `offset` — those are technical, not spacing.

```bash
grep -rnE "(6|10|14|18|30)\.dp" app/src/main/java/com/neop2p/ui/ --include="*.kt" | grep -vE "heightIn|widthIn|strokeWidth|offset"
```

- [ ] **Step 2: Wallet %.8f → adaptive formatBtc**

`WalletScreen.kt` lines ~170, 180, 354, 434, 436, 445 — replace `"%.8f BTC".format(...)` / `"%.8f".format(...)` / `"-%.8f BTC".format(...)` / `"+%.8f BTC".format(...)` with `formatBtc(sats)` / `formatBtc(-sats)` / `formatBtc(sats)` (prefix sign in the string template around the composable, e.g. `"−" + formatBtc(...)` or keep signs inside by using `formatBtc(sats)` on signed values — formatBtc takes Long, verify it accepts negatives; if not, prefix `-`/`+` in the string and pass abs). Read each site before editing — the surrounding composable may already use `BtcAmountText` from Task 2; prefer `BtcAmountText` where it's a plain display, `formatBtc` where the string is composed with signs/units.

- [ ] **Step 3: Empty states → NeoEmptyState**

- `HomeScreen.kt` private `EmptyState()` (lines ~457-490) → replace its internals with `NeoEmptyState(icon = ..., title = stringResource(R.string.home_no_offers), hint = stringResource(R.string.home_no_offers_hint))` using `Icons.Filled.Inbox` (extended) — then delete the private composable.
- `HistoryScreen.kt` empty branch (line ~68-71: `if (escrows.isEmpty()) { ... stringResource(R.string.history_empty) ... }`) → `NeoEmptyState(icon = Icons.Filled.ReceiptLong, title = stringResource(R.string.history_empty))`.

- [ ] **Step 4: Onboarding step indicator**

`OnboardingScreen.kt`: the root content switches on `state.currentStep` (line ~87). Add above the `when`:
```kotlin
OnboardingStepIndicator(
    total = OnboardingStep.entries.size,
    current = state.currentStep.ordinal,
    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp)
)
```
(read the actual root layout first — it may be a Column or Scaffold; align accordingly.) Keep all 4 CircularProgressIndicators — those are loading states, not the wizard progress.

- [ ] **Step 5: Verify + commit**

```bash
cd /home/thesdony/neop2p-btc/android
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
```
Expected: both pass. (EscrowTimeoutTest is unaffected — no EscrowService changes.)

```bash
git add -A
git commit -m "feat(ui): spacing grid sweep, adaptive BTC amounts, shared empty states, onboarding step dots"
```

---

### Task 6: Full verification + device install (user-gated)

**Files:** none (verification only)

- [ ] **Step 1: Full build + unit tests + lint**

```bash
cd /home/thesdony/neop2p-btc/android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```
All must pass (lint baseline absorbs pre-existing). Do NOT pipe through tail.

- [ ] **Step 2: Copy APK to repo root**

```bash
cp app/build/outputs/apk/debug/app-debug.apk /home/thesdony/neop2p-btc/neop2p-debug.apk
```

- [ ] **Step 3: ASK the user before installing**

User-enforced rule: user drives install + navigation; agent verifies via screencap. Ask: "Build green. Install to OnePlus (e20e943a) + emulator? Navigate to Market/Wallet/Trades/Profile and escrow detail — I'll screencap and check hierarchy." Do NOT run `adb install` or `am start` without explicit approval. After approval: `adb -s e20e943a install -r ...` + `adb -s emulator-5554 install -r ...`, launch, then `exec-out screencap` + vision_analyze per screen, verify: bottom bar visible on tabs, one primary button per escrow state, chips render horizontal, money columns stable.

---

## Self-Review

**Spec coverage:** Every modernization-audit finding maps to a task: elevation/motion → T1; typography/tnum → T1+T2; NavigationBar + top-bar cleanup + settings fold → T3; button hierarchy + chip token → T4; spacing + wallet format + empty states + onboarding → T5. NOT covered (deliberately deferred, per agreed P2): animated countdowns, screen transitions, wallet hero redesign, FAB-expand, hiding bottom bar on detail routes. These stay in the blueprint as follow-ups.

**Placeholder scan:** No TBDs. All code blocks are complete and copyable. Icon availability verified (material-icons-extended is a dependency; the 4 icons exist in it).

**Type consistency:** `AppTab` is defined in Task 3 and consumed by Task 3's own NavGraph + screens — no cross-task signature drift. `NeoEmptyState`/`OnboardingStepIndicator`/`BtcAmountText` defined in Task 2, consumed in Task 5 only. `escrowStatusColors` defined T1, consumed T4. `formatBtc(sats: Long)` already exists in `ui/util/BtcFormat.kt` (verified — the only `.toString()` sci-notation grep hit is its own comment).

**Known risk (documented, accepted):** bottom bar stays visible on pushed detail screens (escrow/chat/offer). P2 follow-up if user dislikes it.
