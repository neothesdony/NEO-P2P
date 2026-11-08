package com.neop2p.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neop2p.ui.design.NeoMotion
import com.neop2p.ui.design.NeoPillShape
import com.neop2p.ui.design.NeoTheme

/**
 * Shared empty-state block: centered icon + title + optional hint.
 * Replaces per-screen hand-rolled empty states (HomeScreen, HistoryScreen).
 * No interactive parts — a display-only state.
 */
@Composable
fun NeoEmptyState(
    icon: ImageVector? = null,
    painter: Painter? = null,
    title: String,
    hint: String? = null,
    actions: (@Composable () -> Unit)? = null,
    accent: Boolean = true,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = NeoMotion.fadeIn + NeoMotion.slideUp,
        label = "emptyState"
    ) {
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val glyphTint = if (accent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            if (icon != null || painter != null) {
                // The glyph sits in a soft ring so an empty screen has one point of
                // colour without competing with the message.
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(NeoPillShape)
                        .background(glyphTint.copy(alpha = if (accent) 0.10f else 0.06f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = glyphTint,
                            modifier = Modifier.size(32.dp)
                        )
                    } else if (painter != null) {
                        Icon(
                            painter,
                            contentDescription = null,
                            tint = glyphTint,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(NeoTheme.spacing.md))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            if (hint != null) {
                Spacer(Modifier.height(NeoTheme.spacing.xs))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (actions != null) {
                Spacer(Modifier.height(NeoTheme.spacing.lg))
                actions()
            }
        }
    }
}
