package com.neop2p.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BentoStatsTest {

    @Test
    fun three_stats_maximum() {
        assertTrue(bentoStats(PortfolioHeader(2, 500_000, 3)).size <= 3)
    }

    @Test
    fun zero_portfolio_is_omitted() {
        assertTrue(bentoStats(PortfolioHeader()).isEmpty())
    }

    @Test
    fun unread_leads_when_present() {
        val stats = bentoStats(PortfolioHeader(openTrades = 2, lockedSats = 1, unreadTotal = 3))
        assertEquals("unread", stats.first().label)
        assertTrue(stats.first().accent)
    }

    @Test
    fun a_partial_portfolio_shows_only_its_real_numbers() {
        val stats = bentoStats(PortfolioHeader(openTrades = 1))
        assertEquals(1, stats.size)
        assertEquals("open", stats.single().label)
    }

    @Test
    fun locked_sats_are_rendered_as_btc() {
        val stats = bentoStats(PortfolioHeader(lockedSats = 150_000_000))
        // 1.5 BTC at the >= 1 BTC precision step.
        assertEquals("1.5000", stats.single().value)
    }
}
