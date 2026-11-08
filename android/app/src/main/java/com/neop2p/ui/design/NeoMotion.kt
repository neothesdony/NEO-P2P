package com.neop2p.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ─── Neon Vault motion ───────────────────────────────────────────────────
// Hand-rolled because `MotionScheme.expressive()` is @Internal in material3
// 1.4.0 (the version the pinned BOM resolves). These tokens deliver the same
// feel through the stable compose animation APIs.
//
// Every member of the previous `com.neop2p.ui.theme.NeoMotion` object is present
// here — `NavGraph` and `EscrowScreen` already call `fadeIn`, `fadeOut`,
// `slideUp`, `emphasized`, `standard`, `emphasizedColor`, `standardColor` and
// `emphasizedEase`, so dropping one breaks the build the moment the old file goes.

object NeoMotion {
    const val INSTANT_MS = 90
    const val FAST_MS = 140
    const val BASE_MS = 200
    const val SLOW_MS = 280
    const val DELIBERATE_MS = 360

    /** Entering. */
    val standardEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Leaving — accelerates away instead of easing out. */
    val exitEase = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Hero reveals and success confirmations. */
    val emphasizedEase = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Chips, badges, counters — medium bounce. */
    val emphasized = spring<Float>(
        dampingRatio = 0.75f,
        stiffness = 500f
    )

    /** Cards and panels — quiet, no bounce. */
    val standard = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Press feedback — high stiffness, 0.7 damping. */
    val snappy = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = 1200f
    )

    /** Slow, damped settle for panels that must not feel springy. */
    val gentle = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 220f
    )

    // Color-typed variants: animateColorAsState needs AnimationSpec<Color>, and
    // spring<Float> is not a spring<Color> (invariant generics).
    val emphasizedColor = spring<Color>(dampingRatio = 0.75f, stiffness = 500f)
    val standardColor = spring<Color>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Screen/content transitions. */
    val fadeIn = fadeIn(animationSpec = tween(220, easing = emphasizedEase))
    val fadeOut = fadeOut(animationSpec = tween(160, easing = emphasizedEase))
    val slideUp = slideInVertically(
        animationSpec = tween(220, easing = emphasizedEase),
        initialOffsetY = { it / 16 }
    )
}

/**
 * Set at the activity level from `Settings.Global.ANIMATOR_DURATION_SCALE == 0`.
 * When true every signature motion degrades to a cross-fade or a static state —
 * it must never change layout or information.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
fun reducedMotion(): Boolean = LocalReducedMotion.current
