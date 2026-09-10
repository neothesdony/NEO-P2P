package com.neop2p.data.p2p.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRouterRejectPayloadTest {

    @Test
    fun `reject payload round-trips through json`() {
        val p = PaymentReceiptRejectPayload(
            reference = "NEO-7F3K2A",
            reason = "JUMLAH_SALAH",
            note = "Total transfer harus termasuk kode unik"
        )
        val parsed = parsePaymentReceiptRejectPayload(p.toJson())
        assertEquals(p, parsed)
    }

    @Test
    fun `reject payload note is optional`() {
        val p = PaymentReceiptRejectPayload(reference = "NEO-7F3K2A", reason = "BELUM_MASUK")
        val parsed = parsePaymentReceiptRejectPayload(p.toJson())
        assertEquals("BELUM_MASUK", parsed?.reason)
        assertEquals("", parsed?.note)
    }

    @Test
    fun `reject payload rejects wrong type`() {
        assertNull(parsePaymentReceiptRejectPayload("""{"type":"payment_receipt","reference":"X","reason":"Y"}"""))
    }

    @Test
    fun `reject payload garbage json yields null`() {
        assertNull(parsePaymentReceiptRejectPayload("{not json"))
        assertNull(parsePaymentReceiptRejectPayload(""))
    }

    @Test
    fun `reject payload escapes quotes in note`() {
        val p = PaymentReceiptRejectPayload("R1", "LAINNYA", "jangan \"jual\" di sini")
        val parsed = parsePaymentReceiptRejectPayload(p.toJson())
        assertEquals("jangan 'jual' di sini", parsed?.note)
    }
}
