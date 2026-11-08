package com.neop2p.ui.design

/** Palette sections, in display order. There is no PEERS group: nothing routes to a
 * peer profile — the only peer surface is a chat, which is a TRADE. */
enum class PaletteGroup { OFFERS, TRADES, ACTIONS }

data class PaletteItem(
    val id: String,
    val group: PaletteGroup,
    val title: String,
    val subtitle: String
)

/**
 * Ranked, case-insensitive matching for the command palette.
 *
 * Four tiers, best first: whole-title prefix, word prefix (`bca` beats `abc` inside
 * a word), substring anywhere, then an in-order subsequence (`bsc` → "BCA Sepi…").
 * An empty query returns everything grouped as the palette displays it.
 */
fun paletteMatches(query: String, items: List<PaletteItem>): List<PaletteItem> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return items.sortedBy { it.group.ordinal }
    }
    return items
        .mapNotNull { item ->
            val score = score(trimmed.lowercase(), item)
            if (score == null) null else item to score
        }
        .sortedWith(compareBy({ it.second }, { it.first.group.ordinal }))
        .map { it.first }
}

private fun score(query: String, item: PaletteItem): Int? {
    val title = item.title.lowercase()
    val subtitle = item.subtitle.lowercase()

    if (title.startsWith(query)) return 0
    if (hasWordPrefix(title, query)) return 1
    if (title.contains(query)) return 2
    if (subtitle.contains(query)) return 3
    if (isSubsequence(query, title) || isSubsequence(query, subtitle)) return 4
    return null
}

/** True when [query] starts at a word boundary (` `, `·`, `/`, `-`, start). */
private fun hasWordPrefix(text: String, query: String): Boolean {
    var index = text.indexOf(query)
    while (index >= 0) {
        val atBoundary = index == 0 || !text[index - 1].isLetterOrDigit()
        if (atBoundary) return true
        index = text.indexOf(query, index + 1)
    }
    return false
}

private fun isSubsequence(query: String, text: String): Boolean {
    var cursor = 0
    for (char in text) {
        if (cursor < query.length && char == query[cursor]) cursor++
        if (cursor == query.length) return true
    }
    return query.isEmpty()
}
