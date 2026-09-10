package com.neop2p.data.p2p.routing

import com.neop2p.domain.model.PaymentDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRouterPaymentDetailsPayloadTest {

    @Test
    fun `qris string round-trips through payload`() {
        val details = mapOf(
            "qris" to PaymentDetails(accountNumber = "123", accountHolder = "Budi", qrisString = "00020101021126600009ID.CO.QRIS.WWW0118936009162030563")
        )
        val payload = paymentDetailsPayload(details)
        val parsed = parsePaymentDetailsPayload(payload)
        assertEquals(details["qris"]?.qrisString, parsed?.get("qris")?.qrisString)
    }

    @Test
    fun `bank details round-trip without qris`() {
        val details = mapOf(
            "bca" to PaymentDetails(accountNumber = "8830001245", accountHolder = "Siti Amanah"),
            "qris" to PaymentDetails(accountNumber = "", accountHolder = "", qrisString = "0002010102")
        )
        val parsed = parsePaymentDetailsPayload(paymentDetailsPayload(details))
        assertEquals("8830001245", parsed?.get("bca")?.accountNumber)
        assertEquals("Siti Amanah", parsed?.get("bca")?.accountHolder)
        assertEquals("0002010102", parsed?.get("qris")?.qrisString)
    }

    @Test
    fun `payload escapes quotes in account holder`() {
        val details = mapOf("bca" to PaymentDetails(accountNumber = "1", accountHolder = "D'Angelo \"Dee\" Smith"))
        val parsed = parsePaymentDetailsPayload(paymentDetailsPayload(details))
        assertEquals("D'Angelo \"Dee\" Smith", parsed?.get("bca")?.accountHolder)
    }

    @Test
    fun `payload rejects wrong type and garbage`() {
        assertNull(parsePaymentDetailsPayload("""{"type":"payment_receipt","methods":{}}"""))
        assertNull(parsePaymentDetailsPayload("{not json"))
        assertNull(parsePaymentDetailsPayload(""))
    }

    @Test
    fun `payload with no methods parses to empty map`() {
        val parsed = parsePaymentDetailsPayload("""{"type":"payment_details","methods":{}}""")
        assertTrue(parsed != null && parsed.isEmpty())
    }
}
