package com.neop2p.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neop2p.domain.model.EscrowStatus

/**
 * Which progress treatment a status gets. A linear spine only tells the truth for a
 * status that is *on* the happy path; a dispute or a finished trade is not a step,
 * so it gets a band instead of a parked dot.
 */
enum class NeoBandKind { SPINE, DISPUTE, TERMINAL }

@Suppress("DEPRECATION") // EscrowStatus.RESOLVING is a DB-compat alias for DISPUTED
fun escrowBandFor(status: EscrowStatus): NeoBandKind = when (status) {
    EscrowStatus.DISPUTED, EscrowStatus.RESOLVING -> NeoBandKind.DISPUTE
    EscrowStatus.CANCELLED, EscrowStatus.REFUNDED, EscrowStatus.RELEASED -> NeoBandKind.TERMINAL
    else -> NeoBandKind.SPINE
}

/**
 * Horizontal step rail. Done = filled accent, current = ringed accent node, future =
 * hairline. Labels are passed in rather than derived, so the kit stays free of
 * screen-domain types.
 */
@Composable
fun NeoStepSpine(
    labels: List<String>,
    currentIndex: Int,
    modifier: Modifier = Modifier
) {
    if (labels.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    val hairline = NeoTheme.colors.hairline

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NeoTheme.spacing.lg, vertical = NeoTheme.spacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        labels.forEachIndexed { index, label ->
            val done = index < currentIndex
            val active = index == currentIndex
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Rail segment — the line the nodes sit on.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (index <= currentIndex) accent else hairline)
                )
                Spacer(Modifier.height(NeoTheme.spacing.sm))
                Box(
                    Modifier
                        .size(if (active) 14.dp else 10.dp)
                        .clip(NeoPillShape)
                        .background(
                            when {
                                done -> accent
                                active -> accent.copy(alpha = 0.25f)
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = when {
                                done || active -> accent
                                else -> hairline
                            },
                            shape = NeoPillShape
                        )
                )
                Spacer(Modifier.height(NeoTheme.spacing.xs))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done || active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Replaces the spine when the trade has left the happy path: disputes and terminal
 * outcomes are a state, not a position on a line.
 */
@Composable
fun NeoTimelineBand(
    title: String,
    body: String,
    colors: NeoStatusColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NeoShapes.small)
            .background(colors.container)
            .padding(NeoTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NeoTheme.spacing.xs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.content
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = colors.content
        )
    }
}

/**
 * A live-status dot. It pulses only while something is actually moving: terminal
 * statuses pass `pulse = false`, and reduced motion turns the pulse off entirely
 * (the dot stays, it just stops breathing).
 */
@Composable
fun NeoStatusDot(
    colors: NeoStatusColors,
    pulse: Boolean,
    modifier: Modifier = Modifier,
    periodMs: Int = 2400
) {
    val reduced = NeoTheme.reducedMotion
    val alpha = if (pulse && !reduced) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMs, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "statusPulseAlpha"
        )
        animated
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(10.dp)
            .clip(NeoPillShape)
            .background(colors.content.copy(alpha = alpha))
    )
}
