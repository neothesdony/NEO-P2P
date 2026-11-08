package com.neop2p.ui.design

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.neop2p.R

/**
 * The three bundled families. Fonts ship as static weights in `res/font` — there is
 * no runtime download and no `FontFamily.Monospace` fallback anywhere in the UI
 * (OEM mono faces resolve differently on every device).
 *
 * Static instances were generated from the upstream variable families at the exact
 * weights below and restricted to the Latin + currency + punctuation range; any
 * glyph outside that range falls through Android's system fallback chain, so a
 * nickname in another script still renders.
 */
object NeoFonts {
    /** Display and headline voice — brand only, never for numbers. */
    val spaceGrotesk: FontFamily = FontFamily(
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_bold, FontWeight.Bold)
    )

    /** Every body, label and UI string. */
    val inter: FontFamily = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold)
    )

    /** Money, hashes, addresses, fingerprints — true tabular figures. */
    val jetBrainsMono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium)
    )
}
