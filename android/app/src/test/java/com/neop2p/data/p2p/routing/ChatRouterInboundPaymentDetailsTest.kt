package com.neop2p.data.p2p.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression guard for e220267: `persistInboundPaymentDetails` must refuse a
 * payment-details envelope from a peer who is not a party to the offer, so a
 * verified-but-unrelated peer cannot overwrite `trade_offers.payment_details`
 * for an arbitrary offerId and redirect the buyer's fiat. Only the offer
 * creator (seller) or the matched buyer may write into a trade's thread.
 *
 * [inboundPaymentDetailsToPersist] is the pure decision the persist path
 * delegates to, so removing the party gate fails these tests.
 */
class ChatRouterInboundPaymentDetailsTest {

    private val payload =
        """{"type":"payment_details","methods":{"bca":{"accountNumber":"8830001245","accountHolder":"Siti","qrisString":""}}}"""

    @Test
    fun `third party is refused`() {
        assertNull(inboundPaymentDetailsToPersist("seller", "buyer", payload, "stranger"))
    }

    @Test
    fun `creator seller is accepted`() {
        val parsed = inboundPaymentDetailsToPersist("seller", "buyer", payload, "seller")
        assertEquals("8830001245", parsed?.get("bca")?.accountNumber)
        assertEquals("Siti", parsed?.get("bca")?.accountHolder)
    }

    @Test
    fun `matched buyer is accepted`() {
        val parsed = inboundPaymentDetailsToPersist("seller", "buyer", payload, "buyer")
        assertEquals("8830001245", parsed?.get("bca")?.accountNumber)
    }

    @Test
    fun `blank or null parties fail closed`() {
        assertNull(inboundPaymentDetailsToPersist("seller", "buyer", payload, ""))
        assertNull(inboundPaymentDetailsToPersist(null, null, payload, "stranger"))
    }

    @Test
    fun `non payment-details payload is refused`() {
        assertNull(
            inboundPaymentDetailsToPersist(
                "seller", "buyer",
                """{"type":"payment_receipt","reference":"x"}""",
                "seller"
            )
        )
    }

    @Test
    fun `empty methods map is refused`() {
        assertNull(
            inboundPaymentDetailsToPersist(
                "seller", "buyer",
                """{"type":"payment_details","methods":{}}""",
                "seller"
            )
        )
    }
}
