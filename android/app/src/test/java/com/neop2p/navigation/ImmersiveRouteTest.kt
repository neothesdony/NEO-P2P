package com.neop2p.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom bar is chrome for the four top-level destinations. A money flow
 * (escrow, offer detail, chat) owns the whole screen — a stray tab bar under a Fund
 * CTA is how a user taps "Wallet" mid-trade by accident.
 */
class ImmersiveRouteTest {

    @Test
    fun immersive_and_fullscreen_routes_hide_the_bar() {
        listOf(
            "offer_detail/x",
            "escrow/x",
            "create_offer",
            "edit_offer/x",
            "chat/x/y",
            "trade/x",
            "dispute_evidence/x",
            "escrow/x/receipt",
            "onboarding",
            "terms"
        ).forEach { assertFalse(it, showsBottomBar(it)) }
    }

    @Test
    fun top_level_and_settings_routes_keep_the_bar() {
        listOf("home", "wallet", "trades", "profile", "settings", "history", "chats", "invite")
            .forEach { assertTrue(it, showsBottomBar(it)) }
    }

    @Test
    fun navigation_route_patterns_hide_the_bar_too() {
        // The back stack reports the *pattern*, not the filled path, so the
        // predicate must work on "escrow/{escrowId}" as well as "escrow/abc".
        listOf(
            Routes.ESCROW,
            Routes.ESCROW_RECEIPT,
            Routes.OFFER_DETAIL,
            Routes.EDIT_OFFER,
            Routes.CHAT,
            Routes.TRADE_ROOM,
            Routes.DISPUTE_EVIDENCE,
            Routes.ONBOARDING,
            Routes.TERMS
        ).forEach { assertFalse(it, showsBottomBar(it)) }
    }

    @Test
    fun a_null_route_keeps_the_bar() {
        assertTrue(showsBottomBar(null))
    }
}
