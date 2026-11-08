package com.neop2p.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class NeoToastKind { INFO, SUCCESS, WARNING, ERROR, LOADING }

data class NeoToastMessage(
    val id: Long,
    val text: String,
    val kind: NeoToastKind,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

/**
 * Toast queue. Held by the nav host so a screen can surface a transient outcome
 * without owning a snackbar host; a `LOADING` toast can be [update]d into `SUCCESS`
 * in place, which is what makes "sending… → sent" read as one event.
 */
class NeoToastState {
    internal val messages: SnapshotStateList<NeoToastMessage> = mutableStateListOf()
    private var nextId = 1L

    fun show(
        text: String,
        kind: NeoToastKind = NeoToastKind.INFO,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ): Long {
        val id = nextId++
        messages.add(NeoToastMessage(id, text, kind, actionLabel, onAction))
        return id
    }

    fun update(id: Long, kind: NeoToastKind, text: String? = null) {
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = messages[index]
        messages[index] = current.copy(kind = kind, text = text ?: current.text)
    }

    fun dismiss(id: Long) {
        messages.removeAll { it.id == id }
    }

    fun dismissAll() = messages.clear()
}

/**
 * Renders the queue at the top of the screen. Each toast slides down from a 6dp
 * offset with a scale-in, auto-dismisses after 4 s (never while `LOADING`), and can
 * be flicked upward to dismiss.
 */
@Composable
fun NeoToastHost(state: NeoToastState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NeoTheme.spacing.lg, vertical = NeoTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(NeoTheme.spacing.sm)
    ) {
        state.messages.forEach { message ->
            key(message.id) {
                ToastItem(state = state, message = message)
            }
        }
    }
}

@Composable
private fun ToastItem(state: NeoToastState, message: NeoToastMessage) {
    var visible = remember { false }
    LaunchedEffect(message.id) { visible = true }
    LaunchedEffect(message.id, message.kind) {
        if (message.kind != NeoToastKind.LOADING) {
            delay(TOAST_DISMISS_MS)
            state.dismiss(message.id)
        }
    }

    val colors = when (message.kind) {
        NeoToastKind.SUCCESS -> NeoTheme.colors.status.confirmed
        NeoToastKind.WARNING -> NeoTheme.colors.status.actionRequired
        NeoToastKind.ERROR -> NeoTheme.colors.status.danger
        NeoToastKind.INFO, NeoToastKind.LOADING -> NeoTheme.colors.status.progress
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(NeoMotion.BASE_MS)) { -it } +
            fadeIn(tween(NeoMotion.BASE_MS)) +
            scaleIn(tween(NeoMotion.BASE_MS), initialScale = 0.96f),
        exit = slideOutVertically(tween(NeoMotion.FAST_MS)) { -it } +
            fadeOut(tween(NeoMotion.FAST_MS)),
        label = "toast"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(NeoShapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = NeoTheme.spacing.md, vertical = NeoTheme.spacing.md)
                .pointerInput(message.id) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -8f) state.dismiss(message.id)
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (message.kind == NeoToastKind.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = colors.content
                )
            } else {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(NeoPillShape)
                        .background(colors.content)
                )
            }
            Spacer(Modifier.width(NeoTheme.spacing.sm))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (message.actionLabel != null && message.onAction != null) {
                val label = message.actionLabel
                val action = message.onAction
                Spacer(Modifier.width(NeoTheme.spacing.sm))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.content,
                    modifier = Modifier
                        .clip(NeoShapes.small)
                        .clickable {
                            action()
                            state.dismiss(message.id)
                        }
                        .padding(horizontal = NeoTheme.spacing.sm, vertical = NeoTheme.spacing.xs)
                )
            }
        }
    }
}

/** Long enough to read one short line, short enough to not sit on the CTA. */
private const val TOAST_DISMISS_MS = 4_000L
