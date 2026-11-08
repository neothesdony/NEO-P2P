package com.neop2p.ui.design

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Numeric roles live outside [Typography] on purpose: a number a user might
 * transcribe or verify (amount, address, txid, fingerprint, reference) always
 * renders in JetBrains Mono with tabular figures, including the largest figure on
 * a screen. Space Grotesk display roles are reserved for non-numeric hero text.
 */
data class NeoType(
    val moneyHero: TextStyle,
    val moneyLarge: TextStyle,
    val moneyMedium: TextStyle,
    val moneySmall: TextStyle,
    val mono: TextStyle,
    val monoMicro: TextStyle
) {
    companion object {
        /** Material-only fallback for kit components composed outside NeoP2PTheme. */
        fun defaultFrom(@Suppress("UNUSED_PARAMETER") typography: Typography): NeoType = NeoType(
            moneyHero = money(34, 38, FontWeight.Medium),
            moneyLarge = money(24, 28, FontWeight.Medium),
            moneyMedium = money(18, 22, FontWeight.Medium),
            moneySmall = money(14, 18, FontWeight.Normal),
            mono = money(13, 18, FontWeight.Normal),
            monoMicro = money(11, 14, FontWeight.Normal)
        )

        private fun money(size: Int, line: Int, weight: FontWeight) = TextStyle(
            fontFamily = NeoFonts.jetBrainsMono,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = line.sp,
            letterSpacing = 0.sp,
            fontFeatureSettings = "tnum"
        )
    }
}

/** Null outside [NeoP2PTheme] — consumers fall back to [NeoType.defaultFrom]. */
val LocalNeoType = staticCompositionLocalOf<NeoType?> { null }

/**
 * The M3 scale, re-cut on the bundled families (spec §5.2). Display and headline
 * roles are Space Grotesk; everything else is Inter. `tnum` is set on the roles
 * that can carry a figure inline, so a tick cannot nudge the surrounding text.
 */
fun neoTypography(): Typography {
    fun display(size: Int, line: Int, weight: FontWeight, tracking: Float) = TextStyle(
        fontFamily = NeoFonts.spaceGrotesk,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.sp
    )

    fun text(
        size: Int,
        line: Int,
        weight: FontWeight,
        tracking: Float = 0f,
        tabular: Boolean = false
    ) = TextStyle(
        fontFamily = NeoFonts.inter,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.sp,
        fontFeatureSettings = if (tabular) "tnum" else null
    )

    return Typography(
        displayLarge = display(40, 44, FontWeight.Bold, -0.6f),
        displayMedium = display(34, 40, FontWeight.Bold, -0.34f),
        displaySmall = display(32, 38, FontWeight.Bold, -0.32f),
        headlineLarge = display(28, 34, FontWeight.Bold, -0.14f),
        headlineMedium = display(24, 30, FontWeight.Bold, -0.12f),
        headlineSmall = text(21, 26, FontWeight.SemiBold, -0.04f),
        titleLarge = text(20, 26, FontWeight.SemiBold),
        titleMedium = text(16, 22, FontWeight.SemiBold, tabular = true),
        titleSmall = text(14, 20, FontWeight.Medium, tabular = true),
        bodyLarge = text(16, 24, FontWeight.Normal, tabular = true),
        bodyMedium = text(14, 20, FontWeight.Normal, tabular = true),
        bodySmall = text(12, 16, FontWeight.Normal),
        labelLarge = text(14, 20, FontWeight.Medium, 0.01f, tabular = true),
        labelMedium = text(12, 16, FontWeight.Medium, 0.02f, tabular = true),
        labelSmall = text(11, 14, FontWeight.Medium, 0.04f, tabular = true)
    )
}
