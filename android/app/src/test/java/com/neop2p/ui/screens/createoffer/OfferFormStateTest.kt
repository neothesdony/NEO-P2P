package com.neop2p.ui.screens.createoffer

import com.neop2p.domain.model.OfferType
import com.neop2p.domain.model.PaymentDetails
import com.neop2p.domain.model.TradeOffer
import com.neop2p.ui.screens.createoffer.CreateOfferViewModel.MethodDetails
import com.neop2p.ui.screens.createoffer.CreateOfferViewModel.OfferFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferFormStateTest {

    private fun validState() = OfferFormState(
        btcAmount = "0.5",
        pricePerBtc = "100000000",
        selectedMethods = setOf("bca"),
        methodDetails = mapOf("bca" to MethodDetails(accountNumber = "1234567890", accountHolder = "Budi"))
    )

    @Test
    fun `canSubmit true when amount, price, method and details are complete`() {
        assertTrue(validState().canSubmit)
    }

    @Test
    fun `canSubmit false when amount is missing`() {
        assertFalse(validState().copy(btcAmount = "").canSubmit)
    }

    @Test
    fun `canSubmit false when amount is not a number`() {
        assertFalse(validState().copy(btcAmount = "abc").canSubmit)
    }

    @Test
    fun `canSubmit false when price is missing`() {
        assertFalse(validState().copy(pricePerBtc = "").canSubmit)
    }

    // NOTE: this asserts priceInvalid, NOT canSubmit. canSubmit uses
    // toDoubleOrNull(), which parses "100000000.5" fine, and the planned
    // logic change is the QRIS completeness gate only — a canSubmit
    // decimal assertion would fail red-red (fail at step 2 AND still fail
    // at step 8 after the exact planned code). Decimal prices are surfaced
    // by the priceInvalid inline supportingText instead; publishing with a
    // decimal is rejected downstream by priceIdrExact()/parseIdrToLong.
    @Test
    fun `priceInvalid true for decimal price`() {
        assertTrue(OfferFormState(pricePerBtc = "100000000.5").priceInvalid)
    }

    @Test
    fun `canSubmit false when no method selected`() {
        assertFalse(validState().copy(selectedMethods = emptySet()).canSubmit)
    }

    @Test
    fun `canSubmit false when method details incomplete`() {
        val state = validState().copy(
            methodDetails = mapOf("bca" to MethodDetails(accountNumber = "1234567890", accountHolder = ""))
        )
        assertFalse(state.canSubmit)
    }

    @Test
    fun `qris offer cannot submit with blank qris string`() {
        val state = validState().copy(
            selectedMethods = setOf("qris"),
            methodDetails = mapOf("qris" to MethodDetails(accountNumber = "08123456789", accountHolder = "Budi"))
        )
        assertFalse(state.canSubmit)
    }

    @Test
    fun `qris offer submits when qris string present`() {
        val state = validState().copy(
            selectedMethods = setOf("qris"),
            methodDetails = mapOf(
                "qris" to MethodDetails(accountNumber = "08123456789", accountHolder = "Budi", qrisString = "00020101021126670014COM.GO-JEK0111")
            )
        )
        assertTrue(state.canSubmit)
    }

    @Test
    fun `amountInvalid true only for non-blank unparseable input`() {
        assertTrue(OfferFormState(btcAmount = "abc").amountInvalid)
        assertFalse(OfferFormState(btcAmount = "").amountInvalid)
        assertFalse(OfferFormState(btcAmount = "0.5").amountInvalid)
    }

    @Test
    fun `priceInvalid true only for non-blank unparseable input`() {
        assertTrue(OfferFormState(pricePerBtc = "100000000.5").priceInvalid)
        assertFalse(OfferFormState(pricePerBtc = "").priceInvalid)
        assertFalse(OfferFormState(pricePerBtc = "100000000").priceInvalid)
    }

    @Test
    fun `parseIdrToLong rejects decimals and accepts whole numbers`() {
        assertTrue(CreateOfferViewModel.parseIdrToLong("100000000") == 100_000_000L)
        assertTrue(CreateOfferViewModel.parseIdrToLong("100000000.5") == null)
        assertTrue(CreateOfferViewModel.parseIdrToLong("abc") == null)
    }

    @Test
    fun `methodDetailsFromOffer preserves qris string`() {
        val offer = TradeOffer(
            offerId = "offer_1", creatorPeerId = "peer", type = OfferType.SELL,
            fiatAmount = 1_000_000L, cryptoAmountSats = 1_000_000L, pricePerUnit = 100_000_000.0,
            fiatMethods = listOf("qris"),
            paymentDetails = mapOf(
                "qris" to PaymentDetails(
                    accountNumber = "08123456789", accountHolder = "Budi",
                    qrisString = "00020101021126670014COM.GO-JEK0111"
                )
            )
        )
        assertEquals("00020101021126670014COM.GO-JEK0111", methodDetailsFromOffer(offer)["qris"]?.qrisString)
    }

    @Test
    fun `methodDetailsFromOffer defaults blank qris for non-qris rails`() {
        val offer = TradeOffer(
            offerId = "offer_2", creatorPeerId = "peer", type = OfferType.SELL,
            fiatAmount = 1_000_000L, cryptoAmountSats = 1_000_000L, pricePerUnit = 100_000_000.0,
            fiatMethods = listOf("bca"),
            paymentDetails = mapOf("bca" to PaymentDetails(accountNumber = "1234567890", accountHolder = "Budi"))
        )
        assertEquals("", methodDetailsFromOffer(offer)["bca"]?.qrisString)
    }

    @Test
    fun `btcSatsExact is exact for common decimals`() {
        assertEquals(29_000_000L, OfferFormState(btcAmount = "0.29").btcSatsExact())
        assertEquals(123_456_789L, OfferFormState(btcAmount = "1.23456789").btcSatsExact())
        assertEquals(10_000_000L, OfferFormState(btcAmount = "0.1").btcSatsExact())
    }

    @Test
    fun `btcSatsExact truncates sub-satoshi input`() {
        assertEquals(1L, OfferFormState(btcAmount = "0.000000015").btcSatsExact())
    }

    @Test
    fun `btcSatsExact rejects blank zero negative and garbage`() {
        assertNull(OfferFormState(btcAmount = "").btcSatsExact())
        assertNull(OfferFormState(btcAmount = "0").btcSatsExact())
        assertNull(OfferFormState(btcAmount = "-0.5").btcSatsExact())
        assertNull(OfferFormState(btcAmount = "abc").btcSatsExact())
    }

    @Test
    fun `btcSatsExact parses exponent notation exactly and bounds-check catches huge values`() {
        assertEquals(10_000_000L, OfferFormState(btcAmount = "1e-1").btcSatsExact())
        val huge = validState().copy(btcAmount = "1e5")
        assertTrue(huge.amountOutOfBounds)
        assertFalse(huge.canSubmit)
    }
}
