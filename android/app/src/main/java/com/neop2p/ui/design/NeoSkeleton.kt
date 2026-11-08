package com.neop2p.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A loading placeholder. Under reduced motion the shimmer stops at a fixed alpha —
 * an infinite pulse is exactly what that setting exists to remove.
 */
@Composable
fun NeoSkeleton(width: Dp?, height: Dp, modifier: Modifier = Modifier) {
    val reduced = NeoTheme.reducedMotion
    val alpha = if (reduced) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val animated by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "skeletonAlpha"
        )
        animated
    }

    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    androidx.compose.foundation.layout.Box(
        modifier = sized
            .height(height)
            .clip(NeoShapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha))
    )
}

/** A whole offer-card-shaped placeholder, used while the feed loads. */
@Composable
fun NeoSkeletonCard(modifier: Modifier = Modifier) {
    NeoPanel(modifier = modifier) {
        NeoSkeleton(width = 40.dp, height = 40.dp)
        Spacer(Modifier.height(NeoTheme.spacing.md))
        NeoSkeleton(width = 120.dp, height = 12.dp)
        Spacer(Modifier.height(NeoTheme.spacing.sm))
        NeoSkeleton(width = 200.dp, height = 20.dp)
        Spacer(Modifier.height(NeoTheme.spacing.sm))
        NeoSkeleton(width = null, height = 12.dp)
    }
}
