package com.neop2p.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically

// ─── NEO-P2P expressive motion tokens ────────────────────────────────────
// Hand-rolled port of the Material 3 Expressive motion scheme values
// (Google's Android 16 design language).
//
// WHY hand-rolled: the public `MotionScheme.expressive()` API is INTERNAL
// in material3-android 1.4.0 (the version pinned by every stable BOM
// through 2026.08.00 — verified against Google Maven). The public API
// only exists in 1.5.0-alpha24+ (alpha). These tokens deliver the same
// feel through the stable compose animation APIs, and the one-line
// `motionScheme = MotionScheme.expressive()` swap becomes possible the
// day a stable material3 exposes it.
//
// Usage: pass as the `animationSpec` of animate*AsState / AnimatedVisibility
// / AnimatedContent, e.g.
//   val color by animateColorAsState(target, animationSpec = NeoMotion.emphasized)

object NeoMotion {
    // Emphasized spring — the expressive "pop": medium stiffness with a
    // medium-bouncy damping ratio. Use for chips, badges, countdown flips.
    val emphasized = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    // Standard spring — quiet, no bounce. Use for cards and panels.
    val standard = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    // Emphasized decelerate easing (M3 expressive token).
    val emphasizedEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // Screen/content transitions with the expressive feel.
    val fadeIn = fadeIn(animationSpec = tween(220, easing = emphasizedEase))
    val fadeOut = fadeOut(animationSpec = tween(160, easing = emphasizedEase))
    val slideUp = slideInVertically(
        animationSpec = tween(220, easing = emphasizedEase),
        initialOffsetY = { it / 16 }
    )
}
