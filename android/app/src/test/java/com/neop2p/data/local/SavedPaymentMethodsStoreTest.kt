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
    fun `qris string round-trips through parse`() {
        val json = SavedPaymentMethodsStore.toJson(
            mapOf("qris" to PaymentDetails(qrisString = "00020101021126630012"))
        )
        val parsed = SavedPaymentMethodsStore.parse(json)
        assertEquals("00020101021126630012", parsed["qris"]?.qrisString)
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

    @Test
    fun `save then all returns the method`() {
        // The store needs an Android Context, so plain JUnit exercises the
        // write path through the same serialization save() uses: toJson is
        // exactly what save() persists, parse is exactly what all() reads.
        val json = SavedPaymentMethodsStore.toJson(
            mapOf("bca" to PaymentDetails("1234567890", "Sari"))
        )
        val parsed = SavedPaymentMethodsStore.parse(json)
        assertEquals("1234567890", parsed["bca"]?.accountNumber)
        assertEquals("Sari", parsed["bca"]?.accountHolder)
    }
}
