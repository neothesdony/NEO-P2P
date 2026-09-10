package com.neop2p.data.p2p.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matched-offer notification entitlement (2026-09-06): only the offer
 * creator (seller) and the matched peer (buyer) may be notified of a
 * MATCHED event — a third-party observer must not receive a notification
 * whose tap opens the locked offer's details (NotificationDispatcher
 * content intent → offer_detail route).
 */
class OfferRouterNotificationGateTest {

    @Test
    fun `creator gets notified of foreign matcher`() {
        assertTrue(shouldNotifyMatched("seller", "buyer", "seller"))
    }

    @Test
    fun `matched peer gets notified`() {
        assertTrue(shouldNotifyMatched("seller", "buyer", "buyer"))
    }

    @Test
    fun `third-party observer is never notified`() {
        assertFalse(shouldNotifyMatched("seller", "buyer", "stranger"))
    }

    @Test
    fun `identity locked blank myPeerId never notifies`() {
        assertFalse(shouldNotifyMatched("seller", "buyer", ""))
    }

    @Test
    fun `self-match is never notified`() {
        assertFalse(shouldNotifyMatched("seller", "seller", "seller"))
    }

    @Test
    fun `missing creator or matcher never notifies`() {
        assertFalse(shouldNotifyMatched(null, "buyer", "seller"))
        assertFalse(shouldNotifyMatched("seller", null, "seller"))
        assertFalse(shouldNotifyMatched("seller", "", "buyer"))
    }

    @Test
    fun `peer ids match case-insensitively`() {
        assertTrue(shouldNotifyMatched("SELLER", "buyer", "seller"))
    }
}
