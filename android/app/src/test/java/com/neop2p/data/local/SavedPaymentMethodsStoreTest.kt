package com.neop2p.data.local

import com.neop2p.domain.model.PaymentDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPaymentMethodsStoreTest {

    @Test
    fun `json round-trips through parse`() {
        val json = SavedPaymentMethodsStore.toJson(
            mapOf(
                "bca" to PaymentDetails("1234567890", "Budi Santoso"),
                "qris" to PaymentDetails("08123456789", "Budi Santoso")
            )
        )
        val parsed = SavedPaymentMethodsStore.parse(json)
        assertEquals(2, parsed.size)
        assertEquals("1234567890", parsed["bca"]?.accountNumber)
        assertEquals("Budi Santoso", parsed["bca"]?.accountHolder)
        assertEquals("08123456789", parsed["qris"]?.accountNumber)
    }

    @Test
    fun `empty and garbage json yield empty map`() {
        assertTrue(SavedPaymentMethodsStore.parse("").isEmpty())
        assertTrue(SavedPaymentMethodsStore.parse("{not json").isEmpty())
        assertTrue(SavedPaymentMethodsStore.parse("{}").isEmpty())
    }

    @Test
    fun `missing fields default to empty strings`() {
        val parsed = SavedPaymentMethodsStore.parse("""{"bca":{"accountNumber":"123"}}""")
        assertEquals("123", parsed["bca"]?.accountNumber)
        assertEquals("", parsed["bca"]?.accountHolder)
    }
}
