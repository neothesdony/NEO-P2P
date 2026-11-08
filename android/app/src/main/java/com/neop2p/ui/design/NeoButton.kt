package com.neop2p.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Ranks the call to action: one PRIMARY per screen, everything else is quieter. */
enum class NeoButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * The only button in the app. 52dp tall, `small` radius, `labelLarge`.
 *
 * [loading] disables the button and swaps the label for a spinner — the label is
 * kept in the semantics tree so a test that finds the button by text still can.
 */
@Composable
fun NeoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: NeoButtonVariant = NeoButtonVariant.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: Painter? = null,
    testTag: String? = null
) {
    val colors = NeoTheme.colors
    val contentColor = when (variant) {
        NeoButtonVariant.PRIMARY -> MaterialTheme.colorScheme.onPrimary
        NeoButtonVariant.SECONDARY, NeoButtonVariant.GHOST -> MaterialTheme.colorScheme.onSurface
        NeoButtonVariant.DANGER -> MaterialTheme.colorScheme.onError
    }
    val containerColor = when (variant) {
        NeoButtonVariant.PRIMARY -> MaterialTheme.colorScheme.primary
        NeoButtonVariant.DANGER -> MaterialTheme.colorScheme.error
        NeoButtonVariant.SECONDARY, NeoButtonVariant.GHOST -> Color.Transparent
    }

    var surface = modifier
        .height(52.dp)
        .let {
            if (variant == NeoButtonVariant.SECONDARY) {
                it.border(BorderStroke(1.dp, colors.hairline), NeoShapes.small)
            } else {
                it
            }
        }
    if (testTag != null) surface = surface.testTag(testTag)

    Button(
        onClick = onClick,
        modifier = surface,
        enabled = enabled && !loading,
        shape = NeoShapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.38f),
            disabledContentColor = contentColor.copy(alpha = 0.38f)
        ),
        contentPadding = ButtonDefaults.ContentPadding
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
            Spacer(Modifier.width(NeoTheme.spacing.sm))
        } else if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor
            )
            Spacer(Modifier.width(NeoTheme.spacing.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Icon-only affordance — top-bar actions, list row trailing controls. */
@Composable
fun NeoIconButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { this.contentDescription = contentDescription }
    ) {
        Icon(painter = icon, contentDescription = contentDescription, tint = tint)
    }
}

/**
 * The one floating action. Kept for the offer-creation entry point; anything that
 * merely navigates belongs in the bottom bar instead.
 */
@Composable
fun NeoFab(
    text: String,
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = NeoShapes.small,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
            defaultElevation = NeoElevation.raised
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier.padding(vertical = NeoTheme.spacing.xs)
        ) {
            Icon(painter = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(NeoTheme.spacing.sm))
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
