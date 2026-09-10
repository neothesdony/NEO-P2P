package com.neop2p.ui.screens.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySearchTest {

    // escrowId "escrow_o_345987654": digits = 345987654, last 3 = 654 → kode unik = 654.
    private val escrowId = "escrow_o_345987654"
    private val offerId = "offer_abc123"
    private val reference = "REF-XYZ"

    @Test
    fun emptyQueryMatchesEverything() {
        val result = historyMatchesSearch(escrowId, offerId, reference, 10_000L, "  ")
        assertTrue(result)
    }

    @Test
    fun escrowIdSubstringMatch() {
        assertTrue(historyMatchesSearch(escrowId, offerId, reference, 10_000L, "escrow_o"))
    }

    @Test
    fun offerIdSubstringMatch() {
        assertTrue(historyMatchesSearch(escrowId, offerId, reference, 10_000L, "abc123"))
    }

    @Test
    fun receiptReferenceMatchIsCaseInsensitive() {
        assertTrue(historyMatchesSearch(escrowId, offerId, reference, 10_000L, "ref-xyz"))
    }

    @Test
    fun kodeUnikQueryMatchesComputedCode() {
        // q = "654" == uniquePaymentCode(escrowId, ...) → match even though
        // "654" is NOT a substring of "escrow_o_345987654".
        assertTrue(historyMatchesSearch(escrowId, offerId, reference, 10_000L, "654"))
    }

    @Test
    fun kodeUnikQueryDoesNotSubstringMatchWrongRow() {
        // q = "345" IS a substring of escrowId, but the computed code is 654.
        // Old behavior (escrowId.contains) matched — regression test for the fix.
        assertFalse(historyMatchesSearch(escrowId, offerId, reference, 10_000L, "345"))
    }

    @Test
    fun kodeUnikFallsBackToFiatWhenEscrowIdHasNoDigits() {
        // No digits in escrowId → uniquePaymentCode falls back to (fiatAmount % 1000).
        assertEquals(
            456,
            com.neop2p.ui.util.uniquePaymentCode("escrow_no_digits", 10_456L)
        )
        assertTrue(historyMatchesSearch("escrow_no_digits", offerId, reference, 10_456L, "456"))
    }

    @Test
    fun nonDigitShortQueryStillSubstringMatches() {
        // "abc" is 3 chars but not all digits → substring path, not kode-unik path.
        assertTrue(historyMatchesSearch(escrowId, offerId, "abc", 10_000L, "abc"))
    }
}
