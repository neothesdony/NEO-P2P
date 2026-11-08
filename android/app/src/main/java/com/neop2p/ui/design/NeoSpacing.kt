package com.neop2p.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The 4dp grid, addressed by meaning rather than by step. Screens use these names
 * so a density change is one edit, not a hunt for `12.dp` literals.
 */
data class NeoSpacing(
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val gutter: Dp,
    val sectionGap: Dp,
    val screenPadding: Dp
) {
    companion object {
        val Default = NeoSpacing(
            xxs = 2.dp,
            xs = 4.dp,
            sm = 8.dp,
            md = 12.dp,
            lg = 16.dp,
            xl = 24.dp,
            xxl = 32.dp,
            gutter = 16.dp,
            sectionGap = 24.dp,
            screenPadding = 20.dp
        )
    }
}

/** Hero surfaces `extraLarge`, cards `medium`, inputs/buttons `small`, chips `pill`. */
val NeoShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Chips, avatars and status pills. */
val NeoPillShape = RoundedCornerShape(999.dp)

/**
 * Cards are 0dp + a hairline. These are the only surfaces that genuinely float,
 * because in dark mode a shadow is nearly invisible anyway — the hairline carries
 * the depth.
 */
object NeoElevation {
    val card = 0.dp
    val raised = 6.dp
    val overlay = 16.dp
    val modal = 24.dp
}
