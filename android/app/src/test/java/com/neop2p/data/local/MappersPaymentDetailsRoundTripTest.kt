package com.neop2p.data.local

import com.neop2p.domain.model.PaymentDetails
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersPaymentDetailsRoundTripTest {

    @Test
    fun `qris string survives room json round trip`() {
        val details = mapOf(
            "qris" to PaymentDetails(accountNumber = "123", accountHolder = "Budi", qrisString = "0002010102112660"),
            "bca" to PaymentDetails(accountNumber = "8831", accountHolder = "Siti")
        )
        val round = parsePaymentDetails(toPaymentDetailsJson(details))
        assertEquals("0002010102112660", round["qris"]?.qrisString)
        assertEquals("8831", round["bca"]?.accountNumber)
        assertEquals("", round["bca"]?.qrisString)
    }

    @Test
    fun `quotes in holder survive room json round trip`() {
        val details = mapOf("bca" to PaymentDetails(accountNumber = "1", accountHolder = "D'Angelo \"Dee\" Smith"))
        val round = parsePaymentDetails(toPaymentDetailsJson(details))
        assertEquals("D'Angelo \"Dee\" Smith", round["bca"]?.accountHolder)
    }
}
