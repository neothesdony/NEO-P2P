package com.neop2p.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A pill. `labelMedium`, 32dp tall. Selected chips fill with the accent at 12% and
 * take accent text — the same treatment in both schemes, because the accent is
 * already AA-safe per scheme.
 */
@Composable
fun NeoChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: Painter? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    val container = if (selected) accent.copy(alpha = 0.12f) else Color.Transparent
    val content = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (selected) accent.copy(alpha = 0.4f) else NeoTheme.colors.hairline

    var surface = modifier
        .height(32.dp)
        .clip(NeoPillShape)
        .background(container, NeoPillShape)
        .border(1.dp, border, NeoPillShape)
        .padding(horizontal = NeoTheme.spacing.md)
    if (onClick != null) {
        // The pill is 32dp tall by design; the touch target is still 48dp, so a
        // thumb can hit it without the chip dominating the row.
        surface = surface
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onClick)
    }

    Row(
        modifier = surface,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leading != null) {
            Icon(
                painter = leading,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(NeoTheme.spacing.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The only interactive chip: a single-select filter strip. */
@Composable
fun NeoFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NeoChip(text = text, modifier = modifier, selected = selected, onClick = onClick)
}

/**
 * A read-only status pill in one of the seven status families. Colour changes
 * animate with the emphasized spring so a status flip reads as a transition rather
 * than a jump.
 */
@Composable
fun NeoStatusChip(
    text: String,
    colors: NeoStatusColors,
    modifier: Modifier = Modifier
) {
    val container by animateColorAsState(
        colors.container, animationSpec = NeoMotion.emphasizedColor, label = "chipContainer"
    )
    val content by animateColorAsState(
        colors.content, animationSpec = NeoMotion.emphasizedColor, label = "chipContent"
    )
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(NeoPillShape)
            .background(container, NeoPillShape)
            .padding(horizontal = NeoTheme.spacing.md)
            .semantics { stateDescription = text },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
