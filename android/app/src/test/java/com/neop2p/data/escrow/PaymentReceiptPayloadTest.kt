package com.neop2p.data.escrow

import com.neop2p.data.p2p.routing.PaymentReceiptPayload
import com.neop2p.data.p2p.routing.parsePaymentReceiptPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentReceiptPayloadTest {

    @Test
    fun `payload round-trips through json`() {
        val p = PaymentReceiptPayload(
            reference = "NEO-7F3K2A",
            amountSats = 250_000,
            method = "BCA",
            sentAt = 1_234_567_890L,
            imageBase64 = "aGVsbG8="
        )
        val parsed = parsePaymentReceiptPayload(p.toJson())
        assertEquals(p, parsed)
    }

    @Test
    fun `image is optional`() {
        val p = PaymentReceiptPayload("NEO-7F3K2A", 100_000, "QRIS", 1L, null)
        val parsed = parsePaymentReceiptPayload(p.toJson())
        assertEquals("QRIS", parsed?.method)
        assertNull(parsed?.imageBase64)
    }

    @Test
    fun `garbage json yields null`() {
        assertNull(parsePaymentReceiptPayload("{not json"))
        assertNull(parsePaymentReceiptPayload(""))
    }
}
