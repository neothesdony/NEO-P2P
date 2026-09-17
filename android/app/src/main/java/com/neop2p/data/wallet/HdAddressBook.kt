package com.neop2p.data.wallet

/**
 * BIP-44 HD wallet pointer math (P0.2). Pure — no Android, no network.
 *
 * Mirrors BlueWallet's external (`/0`) + internal (`/1`) pointer model:
 * `next_free_address_index` / `next_free_change_address_index` plus
 * `gap_limit = 20`, and the chunked-discovery window `c < next + gap_limit`.
 *
 * Address reuse is a privacy regression, not a fund-loss one, but the gap
 * window IS a fund-visibility contract: every address the wallet might have
 * received on must stay inside the scan set ([scanSet]) until the pointer
 * advances past it.
 */

/** Standard BIP-44 gap limit: 20 consecutive unused addresses. */
const val GAP_LIMIT = 20

/**
 * HD pointers for one identity.
 *
 * @param nextExternal next unused external (`/0`) index — receive rotation.
 * @param nextChange next unused internal (`/1`) index — change rotation.
 * @param reserved indices handed out by the receive flow, excluded from
 *   [pickReceiveIndex] until released or observed used (P0.7).
 */
data class HdPointers(
    val nextExternal: Int = 0,
    val nextChange: Int = 0,
    val reserved: Set<Int> = emptySet()
)

/**
 * The address indices to scan for [next]: `0 until next + gap` — the upper
 * bound is EXCLUSIVE, matching BlueWallet's `c < next + gap_limit`. With
 * `next = 0` and the default gap this is exactly `0..19`.
 */
fun scanSet(next: Int, gap: Int = GAP_LIMIT): List<Int> =
    if (next < 0 || gap <= 0) emptyList() else (0 until next + gap).toList()

/**
 * Move each pointer to just past the highest used index in its chain. Never
 * moves a pointer backwards (a re-org or a re-scan must not re-issue a used
 * address). A chain with no used indices leaves its pointer unchanged.
 */
fun advance(p: HdPointers, usedExternal: Set<Int>, usedChange: Set<Int>): HdPointers {
    val nextExternal = maxOf(p.nextExternal, (usedExternal.maxOrNull() ?: -1) + 1)
    val nextChange = maxOf(p.nextChange, (usedChange.maxOrNull() ?: -1) + 1)
    return if (nextExternal == p.nextExternal && nextChange == p.nextChange) {
        p
    } else {
        p.copy(nextExternal = nextExternal, nextChange = nextChange)
    }
}

/**
 * Lowest receive index at or above [HdPointers.nextExternal] that is not
 * currently reserved by an in-flight receive flow. Never returns an index below
 * `nextExternal`.
 */
fun pickReceiveIndex(p: HdPointers): Int {
    var index = p.nextExternal
    while (index in p.reserved) index++
    return index
}

/** The next change index. The pointer only advances write-ahead at send time (P0.6). */
fun pickChangeIndex(p: HdPointers): Int = p.nextChange

/** Reserve [index] for the receive flow (idempotent). */
fun reserve(p: HdPointers, index: Int): HdPointers =
    if (index in p.reserved) p else p.copy(reserved = p.reserved + index)

/** Release a reserved [index] (idempotent). */
fun release(p: HdPointers, index: Int): HdPointers =
    if (index !in p.reserved) p else p.copy(reserved = p.reserved - index)

/**
 * (P0.8) The used indices that fall inside the current scan [window]. Anything
 * outside the window is ignored — a partial/failed scan must never slide the
 * pointer. Pure, so the advance policy is testable with a fake activity set.
 */
fun usedIndices(active: Set<Int>, window: List<Int>): Set<Int> {
    val inWindow = window.toSet()
    return active.filterTo(LinkedHashSet()) { it in inWindow }
}
