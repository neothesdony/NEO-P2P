package com.neop2p.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-5.3 (2026-09-15): the money-CTA tag strings are a stable contract for the
 * instrumented/Maestro flows. Pin the exact set so a rename cannot silently
 * break a flow, and prove they are unique (a duplicate tag makes
 * `onNodeWithTag` ambiguous).
 */
class TestTagsTest {

    @Test
    fun `all tags are unique and non blank`() {
        assertTrue(TestTags.all.none { it.isBlank() })
        assertEquals(TestTags.all.size, TestTags.all.toSet().size)
    }

    @Test
    fun `money cta tags keep their wire names`() {
        assertEquals(
            listOf(
                "create_offer_submit",
                "accept_offer",
                "fund_escrow",
                "verify_funding",
                "mark_paid",
                "confirm_receipt",
                "reject_receipt",
                "open_dispute",
                "recover_via_cltv",
                "share_payment_details",
                "invite_identity_hash",
                "wallet_send",
            ),
            TestTags.all,
        )
    }
}
