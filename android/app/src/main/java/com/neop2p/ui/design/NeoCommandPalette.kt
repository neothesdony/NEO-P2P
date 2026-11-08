package com.neop2p.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neop2p.R

/**
 * A cmdk-style overlay: one search field over a grouped, fuzzy-matched list. Up /
 * down moves the highlight, Enter runs it, Escape dismisses.
 *
 * There is no PEERS section because no peer destination exists — offering one would
 * be a dead end dressed as navigation.
 */
@Composable
fun NeoCommandPalette(
    items: List<PaletteItem>,
    onSelect: (PaletteItem) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableIntStateOf(0) }
    val results = remember(query, items) { paletteMatches(query, items) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) { highlighted = 0 }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NeoTheme.colors.scrimStrong)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(NeoMotion.SLOW_MS)) +
                    scaleIn(tween(NeoMotion.SLOW_MS), initialScale = 0.98f),
                label = "palette"
            ) {
                Surface(
                    modifier = Modifier
                        .padding(top = NeoTheme.spacing.xxl)
                        .fillMaxWidth()
                        .widthIn(max = 560.dp),
                    shape = NeoShapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 0.dp
                ) {
                    Column(Modifier.padding(NeoTheme.spacing.lg)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            if (results.isNotEmpty()) {
                                                highlighted = (highlighted + 1) % results.size
                                            }
                                            true
                                        }

                                        Key.DirectionUp -> {
                                            if (results.isNotEmpty()) {
                                                highlighted = (highlighted - 1 + results.size) % results.size
                                            }
                                            true
                                        }

                                        Key.Enter -> {
                                            results.getOrNull(highlighted)?.let(onSelect)
                                            true
                                        }

                                        Key.Escape -> {
                                            onDismiss()
                                            true
                                        }

                                        else -> false
                                    }
                                }
                        ) {
                            NeoSearchField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = stringResource(R.string.palette_search_hint)
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .padding(top = NeoTheme.spacing.sm)
                        ) {
                            results.forEachIndexed { index, item ->
                                val previousGroup = results.getOrNull(index - 1)?.group
                                if (previousGroup != item.group) {
                                    item {
                                        Text(
                                            text = stringResource(groupTitleRes(item.group)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(
                                                top = NeoTheme.spacing.md,
                                                bottom = NeoTheme.spacing.xs
                                            )
                                        )
                                    }
                                }
                                item(key = item.id) {
                                    PaletteRow(
                                        item = item,
                                        highlighted = index == highlighted,
                                        onClick = { onSelect(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(item: PaletteItem, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                NeoShapes.small
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NeoTheme.spacing.md, vertical = NeoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun groupTitleRes(group: PaletteGroup): Int = when (group) {
    PaletteGroup.OFFERS -> R.string.palette_group_offers
    PaletteGroup.TRADES -> R.string.palette_group_trades
    PaletteGroup.ACTIONS -> R.string.palette_group_actions
}
