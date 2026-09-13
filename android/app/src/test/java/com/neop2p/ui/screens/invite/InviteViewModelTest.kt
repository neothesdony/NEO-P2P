package com.neop2p.ui.screens.invite

import com.neop2p.ui.screens.invite.InviteViewModel.Companion.parseInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteViewModelTest {

    private val peerId = "12D3KooWAbCdEfGhIjKlMnOpQrStUvWxYz"

    // The RNS identity hash is a 16-byte truncated hash → 32 hex chars
    // (RnsConstants.TRUNCATED_HASH_BYTES), matching the `neop2p.identity`
    // binding announce from PeerBinding/RnsSession.
    private val identityHash = "00112233445566778899aabbccddeeff"

    @Test
    fun `parses identity hash fragment from invite link`() {
        val parsed = parseInvite("neop2p://peer/$peerId#$identityHash")
        assertEquals(peerId, parsed!!.first)
        assertEquals(identityHash, parsed.second)
    }

    @Test
    fun `bare peer id has no identity hash`() {
        assertNull(parseInvite(peerId)!!.second)
    }

    @Test
    fun `rejects a non-hex identity hash`() {
        assertNull(parseInvite("neop2p://peer/$peerId#zzzz"))
    }

    @Test
    fun `rejects an identity hash of the wrong length`() {
        assertNull(parseInvite("neop2p://peer/$peerId#abcd"))
    }

    @Test
    fun `rejects a 64-hex identity hash because the RNS identity hash is 16 bytes`() {
        assertNull(parseInvite("neop2p://peer/$peerId#$identityHash$identityHash"))
    }

    @Test
    fun `parses full invite link`() {
        val parsed = parseInvite("neop2p://peer/$peerId")
        assertEquals(peerId, parsed!!.first)
    }

    @Test
    fun `parses bare peer id`() {
        val parsed = parseInvite(peerId)
        assertEquals(peerId, parsed!!.first)
    }

    @Test
    fun `strips query params`() {
        val parsed = parseInvite("neop2p://peer/$peerId?utm_source=wa")
        assertEquals(peerId, parsed!!.first)
    }

    @Test
    fun `rejects too-short id`() {
        assertNull(parseInvite("neop2p://peer/abc"))
    }

    @Test
    fun `rejects non-alphanumeric id`() {
        assertNull(parseInvite("neop2p://peer/12D3KooW-abc"))
    }
}
