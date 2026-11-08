package com.neop2p.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one card shape in the app. Depth is a 1dp hairline on a flat surface — the
 * identity never stacks drop shadows. A card that is *live* (a funded escrow, a
 * syncing balance, the primary money action) may carry a single glow; nothing else
 * does.
 */
@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    glow: Boolean = false,
    glowColor: Color = NeoTheme.colors.glow,
    contentPadding: PaddingValues = PaddingValues(NeoTheme.spacing.lg),
    content: @Composable ColumnScope.() -> Unit
) {
    var surface = modifier
    if (glow) surface = surface.neoGlow(glowColor)
    if (onClick != null) {
        surface = surface
            .neoPressScale()
            .clickable(role = Role.Button, onClick = onClick)
    }
    NeoPanel(modifier = surface, contentPadding = contentPadding, content = content)
}

/**
 * A card with no interaction and no glow — the default container for grouped
 * content. Prefer this over [NeoCard] when nothing is clickable: a pressable card
 * that does nothing is a lie.
 */
@Composable
fun NeoPanel(
    modifier: Modifier = Modifier,
    shape: Shape = NeoShapes.medium,
    color: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(NeoTheme.spacing.lg),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(color, shape)
            .border(1.dp, NeoTheme.colors.hairline, shape)
            .padding(contentPadding),
        content = content
    )
}

/**
 * The single allowed glow: a soft radial bloom sitting behind the surface. It is
 * a no-op when the colour is transparent (light mode) or the user asked for
 * reduced motion, so a caller never has to branch on either.
 */
@Composable
fun Modifier.neoGlow(
    color: Color,
    radius: Dp = 18.dp,
    alpha: Float = 0.22f
): Modifier {
    if (alpha <= 0f || color.alpha <= 0f || NeoTheme.reducedMotion) return this
    val glow = color.copy(alpha = alpha)
    return this.drawBehind {
        val r = radius.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow, Color.Transparent),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = r.coerceAtLeast(size.minDimension / 2f + r)
            ),
            radius = r.coerceAtLeast(size.minDimension / 2f + r),
            center = Offset(size.width / 2f, size.height / 2f)
        )
    }
}

/**
 * Press feedback: spring to 0.97 and back. Disabled under reduced motion, where a
 * scale animation is exactly the thing the user turned off.
 */
@Composable
fun Modifier.neoPressScale(enabled: Boolean = true): Modifier {
    if (!enabled || NeoTheme.reducedMotion) return this
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = NeoMotion.snappy,
        label = "neoPressScale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                }
            )
        }
}
