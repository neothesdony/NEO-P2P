package com.neop2p.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Two stable hues (0–359) derived from the peerId. Stable matters more than unique:
 * the same peer must look the same on every device and every launch, because the
 * avatar is a recognition aid for a human verifying a trade partner.
 */
fun avatarSeed(peerId: String): Pair<Int, Int> {
    val hash = peerId.hashCode()
    val first = abs(hash) % 360
    // A fixed arc keeps the gradient readable; a second independent hash can land
    // one degree away and produce a flat disc.
    val second = (first + 42) % 360
    return first to second
}

@Composable
fun NeoAvatar(
    peerId: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val (first, second) = avatarSeed(peerId)
    val initial = label.trim().take(1).uppercase().ifEmpty { "?" }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(hsl(first), hsl(second))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelLarge,
            color = Color.Black.copy(alpha = 0.72f)
        )
    }
}

/** HSL → RGB at a fixed saturation/lightness pair that keeps black text readable. */
private fun hsl(hue: Int, saturation: Float = 0.62f, lightness: Float = 0.62f): Color {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val h = hue / 60f
    val x = c * (1f - abs(h % 2f - 1f))
    val (r1, g1, b1) = when {
        h < 1f -> Triple(c, x, 0f)
        h < 2f -> Triple(x, c, 0f)
        h < 3f -> Triple(0f, c, x)
        h < 4f -> Triple(0f, x, c)
        h < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = lightness - c / 2f
    return Color(r1 + m, g1 + m, b1 + m)
}
