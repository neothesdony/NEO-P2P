package com.neop2p.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.neop2p.domain.model.EscrowStatus

// ─── Neon Vault colour ───────────────────────────────────────────────────
// Source of truth: design-system/design-tokens.json + spec §5.1. Nothing else in
// the app may define a colour; every screen resolves through NeoTheme.colors or
// MaterialTheme.colorScheme.
//
// Depth is carried by contrast + a 1px hairline (#242938 dark / #E4E7EE light),
// never by drop shadows. Shadows are reserved for genuinely floating layers.

/** Dark is the primary scheme — the ink base the identity was drawn on. */
val darkScheme: ColorScheme = darkColorScheme(
    // Brand — emerald signature
    primary = Color(0xFF00E676),
    onPrimary = Color(0xFF04170C),
    primaryContainer = Color(0xFF0B3D28),
    onPrimaryContainer = Color(0xFF9DF5C9),
    // Network — cyan
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF00252B),
    secondaryContainer = Color(0xFF0A2F3A),
    onSecondaryContainer = Color(0xFFA8F0FA),
    // Escrow — amber
    tertiary = Color(0xFFF5A524),
    onTertiary = Color(0xFF2A1600),
    tertiaryContainer = Color(0xFF3D2A0B),
    onTertiaryContainer = Color(0xFFFFDFA6),
    // Ink base
    background = Color(0xFF07080B),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF13161F),
    onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF181C28),
    onSurfaceVariant = Color(0xFF9AA3B8),
    surfaceContainerLowest = Color(0xFF07080B),
    surfaceContainerLow = Color(0xFF0E1017),
    surfaceContainer = Color(0xFF13161F),
    surfaceContainerHigh = Color(0xFF181C28),
    surfaceContainerHighest = Color(0xFF1E2330),
    // Feedback
    error = Color(0xFFFF5C5C),
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF3D1114),
    onErrorContainer = Color(0xFFFFB9B9),
    outline = Color(0xFF333A4D),
    outlineVariant = Color(0xFF242938),
    scrim = Color(0xFF000000)
)

/** Light is a shipping path, not a best effort — every value is AA on white. */
val lightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF00784A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7F2E4),
    onPrimaryContainer = Color(0xFF00402A),
    secondary = Color(0xFF00707A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6EFF2),
    onSecondaryContainer = Color(0xFF00444B),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFDECD6),
    onTertiaryContainer = Color(0xFF5A2A00),
    background = Color(0xFFF6F7FA),
    onBackground = Color(0xFF111318),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111318),
    surfaceVariant = Color(0xFFEFF1F6),
    onSurfaceVariant = Color(0xFF5A6173),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F7FA),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFF1F6),
    surfaceContainerHighest = Color(0xFFE7EAF0),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE4E4),
    onErrorContainer = Color(0xFF6B0F0F),
    outline = Color(0xFFC9CFDB),
    outlineVariant = Color(0xFFE4E7EE),
    scrim = Color(0xFF000000)
)

/** A `(container, content)` pair — the same shape the old chip palette used. */
data class NeoStatusColors(val container: Color, val content: Color)

/**
 * Seven families, not eleven hues: escrow statuses stay distinguishable without
 * inventing a colour per state. Violet ([arbitration]) is the only permitted
 * fourth hue and exists for disputes alone.
 */
data class NeoStatusPalette(
    val neutral: NeoStatusColors,
    val progress: NeoStatusColors,
    val actionRequired: NeoStatusColors,
    val waiting: NeoStatusColors,
    val confirmed: NeoStatusColors,
    val danger: NeoStatusColors,
    val arbitration: NeoStatusColors
)

/**
 * Everything the identity adds on top of the M3 scheme. Held in a CompositionLocal
 * so kit components work under a bare `MaterialTheme { }` by falling back to
 * [defaultFrom].
 */
data class NeoColors(
    val buy: Color,
    val sell: Color,
    val escrow: Color,
    val warning: Color,
    val network: Color,
    val glow: Color,
    val scrimStrong: Color,
    val hairline: Color,
    val status: NeoStatusPalette,
    val isLight: Boolean
) {
    companion object {
        fun defaultFrom(cs: ColorScheme): NeoColors {
            val light = cs.background.luminance() > 0.5f
            return NeoColors(
                buy = cs.primary,
                sell = if (light) Color(0xFFC62828) else Color(0xFFFF6B6B),
                escrow = cs.tertiary,
                warning = cs.tertiary,
                network = cs.secondary,
                // Light mode glow is a flat 0 alpha — it reads as dirt on white and
                // the reduced-motion path relies on this being harmless.
                glow = if (light) Color.Transparent else cs.primary.copy(alpha = 0.22f),
                scrimStrong = Color.Black.copy(alpha = if (light) 0.56f else 0.72f),
                hairline = cs.outlineVariant,
                status = if (light) lightStatusPalette else darkStatusPalette,
                isLight = light
            )
        }
    }
}

private val darkStatusPalette = NeoStatusPalette(
    neutral = NeoStatusColors(container = Color(0xFF1E2330), content = Color(0xFF9AA3B8)),
    progress = NeoStatusColors(container = Color(0xFF0A2F3A), content = Color(0xFFA8F0FA)),
    actionRequired = NeoStatusColors(container = Color(0xFF3D2A0B), content = Color(0xFFFFDFA6)),
    waiting = NeoStatusColors(container = Color(0xFF0C333F), content = Color(0xFF86DCEC)),
    confirmed = NeoStatusColors(container = Color(0xFF0B3D28), content = Color(0xFF9DF5C9)),
    danger = NeoStatusColors(container = Color(0xFF3D1114), content = Color(0xFFFFB9B9)),
    arbitration = NeoStatusColors(container = Color(0xFF241A3D), content = Color(0xFFA78BFA))
)

private val lightStatusPalette = NeoStatusPalette(
    neutral = NeoStatusColors(container = Color(0xFFE7EAF0), content = Color(0xFF5A6173)),
    progress = NeoStatusColors(container = Color(0xFFDDF4F8), content = Color(0xFF00707A)),
    actionRequired = NeoStatusColors(container = Color(0xFFFDF0DC), content = Color(0xFFA64A06)),
    waiting = NeoStatusColors(container = Color(0xFFE4F6FA), content = Color(0xFF146C7A)),
    confirmed = NeoStatusColors(container = Color(0xFFDDF6EA), content = Color(0xFF00784A)),
    danger = NeoStatusColors(container = Color(0xFFFBE4E4), content = Color(0xFFC62828)),
    arbitration = NeoStatusColors(container = Color(0xFFEDE6F9), content = Color(0xFF6D28D9))
)

/**
 * `RESOLVING` is `@Deprecated` on the model (DB-compat alias for `DISPUTED`) but
 * rows still carry it, so the branch stays — the suppression documents why.
 */
@Suppress("DEPRECATION")
fun NeoStatusPalette.forStatus(status: EscrowStatus): NeoStatusColors = when (status) {
    EscrowStatus.FUNDING -> actionRequired
    EscrowStatus.PAYMENT_PENDING -> actionRequired
    EscrowStatus.RECEIPT_SENT -> waiting
    EscrowStatus.SIGNED -> progress
    EscrowStatus.FUNDED,
    EscrowStatus.CONFIRMING,
    EscrowStatus.RELEASED -> confirmed
    EscrowStatus.DISPUTED -> danger
    EscrowStatus.RESOLVING -> arbitration
    EscrowStatus.CANCELLED,
    EscrowStatus.REFUNDED -> neutral
}

/**
 * Null outside [NeoP2PTheme] — kit components fall back to
 * [NeoColors.defaultFrom] so smoke tests can compose them under a bare theme.
 */
val LocalNeoColors = staticCompositionLocalOf<NeoColors?> { null }
