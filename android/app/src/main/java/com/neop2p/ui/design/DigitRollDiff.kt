package com.neop2p.ui.design

/**
 * One character of a rendered amount. [changed] is true when the character at this
 * right-aligned position differs from the previous value (or did not exist before) —
 * the caller uses it to decide what to roll.
 */
data class DigitSlot(val char: Char, val changed: Boolean)

/**
 * Right-aligned character diff between two formatted amounts.
 *
 * Right alignment is the whole point: `999 → 1.000` must not report "9,9,9 vs
 * 1,.,0" — the decimals are what the eye tracks, so the comparison is anchored to
 * the decimal end where a satoshi actually changes.
 */
fun digitSlots(previous: String, next: String): List<DigitSlot> {
    val shift = next.length - previous.length
    return next.mapIndexed { index, char ->
        val previousIndex = index - shift
        val before = if (previousIndex in previous.indices) previous[previousIndex] else null
        DigitSlot(char = char, changed = before != char)
    }
}
