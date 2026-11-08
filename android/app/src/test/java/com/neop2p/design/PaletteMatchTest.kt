package com.neop2p.design

import com.neop2p.ui.design.PaletteGroup
import com.neop2p.ui.design.PaletteItem
import com.neop2p.ui.design.paletteMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteMatchTest {

    private val items = listOf(
        PaletteItem("o1", PaletteGroup.OFFERS, "BCA 0.0005 BTC", "Rp 750.000.000"),
        PaletteItem("t1", PaletteGroup.TRADES, "Escrow · Andi", "FUNDED"),
        PaletteItem("a1", PaletteGroup.ACTIONS, "Buat Penawaran", ""),
    )

    @Test
    fun empty_query_lists_everything() {
        assertEquals(3, paletteMatches("", items).size)
    }

    @Test
    fun empty_query_is_grouped_offers_then_trades_then_actions() {
        val groups = paletteMatches("", items).map { it.group }
        assertEquals(listOf(PaletteGroup.OFFERS, PaletteGroup.TRADES, PaletteGroup.ACTIONS), groups)
    }

    @Test
    fun subsequence_match_finds_bca() {
        assertEquals("o1", paletteMatches("bca", items).first().id)
    }

    @Test
    fun trade_query_returns_the_trade() {
        assertEquals("t1", paletteMatches("esc", items).first().id)
    }

    @Test
    fun matching_is_case_insensitive() {
        assertEquals("o1", paletteMatches("BCA", items).first().id)
        assertEquals("o1", paletteMatches("bca", items).first().id)
    }

    @Test
    fun a_word_prefix_outranks_a_mid_word_hit() {
        val candidates = listOf(
            PaletteItem("x", PaletteGroup.ACTIONS, "Rekonsiliasi", ""),
            PaletteItem("y", PaletteGroup.ACTIONS, "Cek saldo", ""),
        )
        // "cek" is a word prefix of item y; it also appears inside "Rekonsiliasi"
        // but not at a word boundary, so y must come first.
        assertEquals("y", paletteMatches("cek", candidates).first().id)
    }

    @Test
    fun subtitle_matches_too() {
        assertEquals("t1", paletteMatches("funded", items).first().id)
    }

    @Test
    fun a_query_that_matches_nothing_returns_nothing() {
        assertTrue(paletteMatches("zzzz", items).isEmpty())
    }

    @Test
    fun the_subsequence_must_be_in_order() {
        // "abc" is a subsequence of "aXbXc" but not of "cba".
        val ordered = listOf(PaletteItem("s", PaletteGroup.ACTIONS, "aXbXc", ""))
        assertEquals(1, paletteMatches("abc", ordered).size)
        assertTrue(paletteMatches("cba", ordered).isEmpty())
    }
}
