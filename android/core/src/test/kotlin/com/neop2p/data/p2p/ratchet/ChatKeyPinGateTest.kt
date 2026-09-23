package com.neop2p.data.p2p.ratchet

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatKeyPinGateTest {

    private val ed = ByteArray(32) { 1 }
    private val ik = ByteArray(32) { 2 }
    private val ratchet = ByteArray(32) { 3 }

    @Test
    fun `first contact (no stored keys) is allowed`() {
        assertEquals(
            ChatKeyPinGate.Verdict.ALLOW,
            ChatKeyPinGate.verdict(null, ed, null, ik, null, ratchet)
        )
    }

    @Test
    fun `matching pinned keys are allowed`() {
        assertEquals(
            ChatKeyPinGate.Verdict.ALLOW,
            ChatKeyPinGate.verdict(ed, ed, ik, ik, ratchet, ratchet)
        )
    }

    @Test
    fun `a changed identity key is refused`() {
        assertEquals(
            ChatKeyPinGate.Verdict.KEY_CHANGED,
            ChatKeyPinGate.verdict(ed, ByteArray(32) { 9 }, ik, ik, ratchet, ratchet)
        )
    }

    @Test
    fun `a changed ratchet key is refused`() {
        assertEquals(
            ChatKeyPinGate.Verdict.KEY_CHANGED,
            ChatKeyPinGate.verdict(ed, ed, ik, ik, ratchet, ByteArray(32) { 9 })
        )
    }

    @Test
    fun `a legacy row with null pins is allowed to rebind`() {
        assertEquals(
            ChatKeyPinGate.Verdict.ALLOW,
            ChatKeyPinGate.verdict(null, ed, null, ik, null, ratchet)
        )
    }
}
