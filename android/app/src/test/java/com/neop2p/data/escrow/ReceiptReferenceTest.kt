package com.neop2p.data.escrow

import com.neop2p.ui.screens.escrow.ReceiptComposerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptReferenceTest {

    @Test
    fun `reference code is 8 chars from a safe alphabet`() {
        val code = ReceiptComposerViewModel.generateReference()
        assertEquals(8, code.length)
        assertTrue(code.all { it in "ABCDEFGHJKMNPQRSTUVWXYZ23456789" }) // no I/L/O/0/1
    }

    @Test
    fun `two generated references differ`() {
        assertTrue(ReceiptComposerViewModel.generateReference() != ReceiptComposerViewModel.generateReference())
    }
}
