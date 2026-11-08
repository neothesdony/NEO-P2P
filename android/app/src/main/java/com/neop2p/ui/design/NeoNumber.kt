package com.neop2p.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.neop2p.ui.util.formatBtc

enum class NeoMoneyRole { HERO, LARGE, MEDIUM, SMALL }

/**
 * Renders an amount as individual slots so a changing digit rolls instead of the
 * whole string snapping. Every slot reserves the width of its own glyph, so a value
 * that gains a digit never reflows the row.
 *
 * Under reduced motion the roll degrades to a cross-fade — same information, no
 * vertical travel.
 */
@Composable
fun DigitRoll(
    value: Long,
    format: (Long) -> String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val text = format(value)
    var previous by remember { mutableStateOf(text) }
    val slots = remember(text, previous) { digitSlots(previous, text) }

    val reduced = NeoTheme.reducedMotion
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(text, style) {
        slots.map { measurer.measure(it.char.toString(), style).size.width }
    }

    Row(modifier = modifier) {
        slots.forEachIndexed { index, slot ->
            val width = with(density) { widths[index].toDp() }
            Box(Modifier.width(width)) {
                AnimatedContent(
                    targetState = slot.char,
                    transitionSpec = {
                        if (reduced) {
                            fadeIn(tween(NeoMotion.FAST_MS)) togetherWith
                                fadeOut(tween(NeoMotion.FAST_MS))
                        } else {
                            (slideInVertically(tween(NeoMotion.FAST_MS)) { it } +
                                fadeIn(tween(NeoMotion.FAST_MS))) togetherWith
                                (slideOutVertically(tween(NeoMotion.FAST_MS)) { -it } +
                                    fadeOut(tween(NeoMotion.FAST_MS)))
                        }
                    },
                    label = "digit"
                ) { char ->
                    Text(text = char.toString(), style = style, color = color, maxLines = 1)
                }
            }
        }
    }

    // Advance the baseline only after this frame has been laid out, so the roll
    // animates from the value the user was looking at.
    LaunchedEffect(text) { previous = text }
}

/**
 * The app's money display. Picks the numeric role, steps the size down when the
 * string would overflow (34 → 30 → 26 → 24 for HERO, proportionally for the others)
 * and never wraps a number onto a second line.
 */
@Composable
fun NeoMoney(
    sats: Long,
    modifier: Modifier = Modifier,
    role: NeoMoneyRole = NeoMoneyRole.LARGE,
    color: Color = Color.Unspecified,
    animate: Boolean = true
) {
    val base = when (role) {
        NeoMoneyRole.HERO -> NeoTheme.type.moneyHero
        NeoMoneyRole.LARGE -> NeoTheme.type.moneyLarge
        NeoMoneyRole.MEDIUM -> NeoTheme.type.moneyMedium
        NeoMoneyRole.SMALL -> NeoTheme.type.moneySmall
    }
    val text = formatBtc(sats)

    BoxWithConstraints(modifier = modifier) {
        val measurer = rememberTextMeasurer()
        val steps = remember(base) {
            val size = base.fontSize.value
            listOf(size, size - 4f, size - 8f, size - 10f)
                .filter { it >= 11f }
                .distinct()
        }
        val available = constraints.maxWidth
        val fitted = remember(text, base, available, steps) {
            steps.firstOrNull { step ->
                measurer.measure(text, base.copy(fontSize = step.sp)).size.width <= available
            }?.let { step -> base.copy(fontSize = step.sp, lineHeight = (step + 4f).sp) }
                ?: base.copy(fontSize = steps.last().sp, lineHeight = (steps.last() + 4f).sp)
        }

        if (animate && !NeoTheme.reducedMotion) {
            DigitRoll(value = sats, format = ::formatBtc, style = fitted, color = color)
        } else {
            val contentColor = if (color == Color.Unspecified) LocalContentColor.current else color
            BasicText(
                text = text,
                style = fitted,
                color = { contentColor },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
