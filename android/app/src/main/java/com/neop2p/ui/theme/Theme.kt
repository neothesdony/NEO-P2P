package com.neop2p.ui.theme

import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.neop2p.domain.model.EscrowStatus

// ─── NEO-P2P "Neo Grid" theme ────────────────────────────────────────────
// Token architecture inspired by MostroP2P (working P2P trading app) but
// rebuilt around NEO-P2P brand hues (design-system/design-tokens.json):
//   primary   #00E676  neon green  → buy / active trades
//   secondary #00BCD4  cyan        → chat / network / messaging
//   tertiary  #FF9800  orange      → escrow / warnings
// Improvements over Mostro: true neon primary (no olive), no purple clash,
// M3 tonal surface ladder, semantic buy/sell colors, complete light scheme.
//
// Modernization (2026-08-28): M3 Expressive motion scheme (Android 16
// visual language), tabular-nums typography for money (Bithumb/Muun
// money-grade pattern — digits never jump), hairline card borders
// (Bithumb review: structural depth via 1px borders, not drop shadows),
// and the single source of truth for escrow status chip colors.

private val DarkColorScheme = darkColorScheme(
    // Brand
    primary = Color(0xFF00E676),            // neon green — buy, active trades
    onPrimary = Color(0xFF04170C),          // near-black green for text on primary
    primaryContainer = Color(0xFF0E4D33),   // deep green container (chips, badges)
    onPrimaryContainer = Color(0xFFB6F5D8),
    inversePrimary = Color(0xFF00C853),
    // Chat / network
    secondary = Color(0xFF00BCD4),          // cyan — messaging, network nodes
    onSecondary = Color(0xFF00252B),
    secondaryContainer = Color(0xFF0A3A44),
    onSecondaryContainer = Color(0xFFB8F6F7),
    // Escrow / warnings
    tertiary = Color(0xFFFF9800),           // orange — escrow, premium, warnings
    onTertiary = Color(0xFF3A1700),
    tertiaryContainer = Color(0xFF4A2400),
    onTertiaryContainer = Color(0xFFFFE0B2),
    // Surfaces (Mostro-style layered ladder, token-aligned)
    background = Color(0xFF0D1117),          // token: background.dark
    onBackground = Color(0xFFC9D1D9),
    surface = Color(0xFF161B22),             // token: surface.dark — cards
    onSurface = Color(0xFFC9D1D9),
    surfaceVariant = Color(0xFF21262D),      // token: surfaceVariant.dark
    onSurfaceVariant = Color(0xFF8B949E),
    surfaceContainerLowest = Color(0xFF0D1117),
    surfaceContainerLow = Color(0xFF131A22),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF21262D),
    surfaceContainerHighest = Color(0xFF2A313D),
    // Feedback
    error = Color(0xFFFF5252),
    onError = Color(0xFF3D0000),
    errorContainer = Color(0xFF5C1010),
    onErrorContainer = Color(0xFFFFB9B6),
    outline = Color(0xFF30363D),             // token: outline.dark
    outlineVariant = Color(0xFF21262D),
    scrim = Color(0xFF000000)
)

private val LightColorScheme = lightColorScheme(
    // Brand — darkened for WCAG AA on white (onPrimary is White):
    //   primary #007A41 (5.6:1), secondary #00707A (5.8:1), tertiary #B33A00 (6.0:1)
    primary = Color(0xFF007A41),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F6CA),
    onPrimaryContainer = Color(0xFF003300),
    secondary = Color(0xFF00707A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2EBF2),
    onSecondaryContainer = Color(0xFF00333D),
    tertiary = Color(0xFFB33A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0B2),
    onTertiaryContainer = Color(0xFF3E1700),
    // Surfaces
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = Color(0xFF424242),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFECEEF2),
    surfaceContainerHighest = Color(0xFFE4E7EC),
    // Feedback
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFFE0E0E0),
    outlineVariant = Color(0xFFEDEFF2),
    scrim = Color(0xFF000000)
)

// Semantic trading colors — buy/sell/escrow so screens never mix up roles.
// Buy = primary green, Sell = danger red, Escrow = tertiary orange.
// sellColor is scheme-aware: #FF6B6B passes on dark surfaces but fails WCAG
// AA on white (~2.8:1), so light mode gets a darker red (#C62828, ~5.6:1).
val androidx.compose.material3.ColorScheme.buyColor: Color get() = primary
val androidx.compose.material3.ColorScheme.sellColor: Color
    get() = if (background.luminance() > 0.5f) Color(0xFFC62828) else Color(0xFFFF6B6B)
val androidx.compose.material3.ColorScheme.escrowColor: Color get() = tertiary
val androidx.compose.material3.ColorScheme.warningColor: Color get() = tertiary

// Token-aligned shapes (design-system/design-tokens.json shape section)
private val NeoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

// Tabular-nums money typography — "tnum" keeps digits from jumping as
// amounts tick (Bithumb/Muun money-grade pattern). Applied to every role
// that renders amounts or dense data; display faces stay proportional.
val NeoTypography = Typography().run {
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

// Named elevations — Mostro-style. Hairlines do the structural work in
// dark mode (Bithumb review: 1px borders, not drop-shadow spam); these
// values are reserved for the few surfaces that genuinely float.
object NeoElevations {
    val card = 2.dp
    val raised = 6.dp
}

// Hairline card border — kills "flat cards on flat background" without
// shadow spam. Default color matches the dark surfaceVariant/outlineVariant.
fun Modifier.cardHairline(color: Color = Color(0xFF21262D)): Modifier =
    this.then(Modifier.border(1.dp, color, RoundedCornerShape(12.dp)))

// Status chip tokens — the ONE source of truth for escrow status chip
// colors (kills the duplicated 12-hex-pair palette that lived in
// EscrowScreen.kt:346 and HistoryScreen.kt:148). Mostro-style bg/text pairs.
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
        content = content
    )
}
