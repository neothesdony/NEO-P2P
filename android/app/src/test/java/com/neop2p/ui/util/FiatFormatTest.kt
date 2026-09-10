package com.neop2p.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FiatFormatTest {

    @Test
    fun `formats whole rupiah with dot thousands`() {
        assertEquals("Rp 1.250.000", formatIdr(1_250_000L))
    }

    @Test
    fun `handles small amounts`() {
        assertEquals("Rp 15.000", formatIdr(15_000L))
    }

    @Test
    fun `handles zero`() {
        assertEquals("Rp 0", formatIdr(0L))
    }

    @Test
    fun `never uses comma thousands`() {
        assertFalse(formatIdr(1_250_000L).contains(","))
    }

    @Test
    fun `handles millions`() {
        assertEquals("Rp 10.000.000", formatIdr(10_000_000L))
    }

    @Test
    fun `price per btc without currency symbol`() {
        assertEquals("1.250.000", formatIdrNoCurrency(1_250_000.0))
    }

    @Test
    fun `no currency symbol on small price`() {
        assertEquals("15.000", formatIdrNoCurrency(15_000.0))
    }

    // ── uniquePaymentCode (Indodax/Flip 3-digit suffix convention) ──

    @Test
    fun `code derives from escrow id digits`() {
        assertEquals(432, uniquePaymentCode("escrow_123432", 1_250_000L))
    }

    @Test
    fun `code falls back to fiat amount tail when id has no digits`() {
        assertEquals(0, uniquePaymentCode("escrow_abc", 1_250_000L))
        assertEquals(432, uniquePaymentCode("escrow_abc", 1_250_432L))
    }

    @Test
    fun `code is deterministic across devices`() {
        val id = "escrow_987654"
        val amount = 2_500_000L
        assertEquals(uniquePaymentCode(id, amount), uniquePaymentCode(id, amount))
        // Both devices derive the SAME amount to transfer.
        assertEquals(2_500_654L, amount + uniquePaymentCode(id, amount))
    }

    @Test
    fun `code is always three digits or less`() {
        for (i in 0 until 200) {
            val code = uniquePaymentCode("escrow_${i}_abc", i.toLong() * 1_000_000)
            assert(code in 0..999) { "code $code out of range" }
        }
    }

    @Test
    fun `duration formats mm ss then hh mm`() {
        assertEquals("02:14", formatDurationShort(134_000L))
        assertEquals("59:59", formatDurationShort(3_599_000L))
        assertEquals("1:00", formatDurationShort(3_600_000L))
        assertEquals("00:00", formatDurationShort(-5_000L))
    }
}
