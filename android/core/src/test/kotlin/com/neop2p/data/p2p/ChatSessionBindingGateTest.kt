package com.neop2p.data.p2p

import com.neop2p.data.p2p.ChatSessionBindingGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSessionBindingGateTest {

    private val verified = "00112233445566778899aabbccddeeff"

    private fun verdict(
        verifiedForSender: Boolean = true,
        verifiedIdentityHash: String? = verified,
        expectedInviteHash: String? = null,
        storedSessionHash: String? = null,
    ) = ChatSessionBindingGate.verdict(
        verifiedForSender, verifiedIdentityHash, expectedInviteHash, storedSessionHash
    )

    @Test
    fun `verified peer with no expectations is allowed`() {
        assertEquals(Verdict.ALLOW, verdict())
    }

    @Test
    fun `unverified sender is rejected before any other check`() {
        assertEquals(Verdict.UNVERIFIED, verdict(verifiedForSender = false, expectedInviteHash = "ffff"))
        assertEquals(Verdict.UNVERIFIED, verdict(verifiedIdentityHash = null))
        assertEquals(Verdict.UNVERIFIED, verdict(verifiedIdentityHash = ""))
    }

    @Test
    fun `invite hash must match the verified identity`() {
        assertEquals(Verdict.INVITE_MISMATCH, verdict(expectedInviteHash = "ffffffffffffffffffffffffffffffff"))
        assertEquals(Verdict.ALLOW, verdict(expectedInviteHash = verified))
    }

    @Test
    fun `invite hash comparison is case insensitive`() {
        assertEquals(Verdict.ALLOW, verdict(expectedInviteHash = verified.uppercase()))
    }

    @Test
    fun `stored session hash must match the verified identity`() {
        assertEquals(Verdict.IDENTITY_CHANGED, verdict(storedSessionHash = "ffffffffffffffffffffffffffffffff"))
        assertEquals(Verdict.ALLOW, verdict(storedSessionHash = verified))
    }

    @Test
    fun `invite mismatch takes precedence over identity change`() {
        assertEquals(Verdict.INVITE_MISMATCH, verdict(expectedInviteHash = "aabb", storedSessionHash = "ccdd"))
    }
}
