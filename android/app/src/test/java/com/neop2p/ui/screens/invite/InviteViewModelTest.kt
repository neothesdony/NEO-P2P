package com.neop2p.ui.screens.invite

import com.neop2p.ui.screens.invite.InviteViewModel.Companion.parseInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteViewModelTest {

    private val peerId = "12D3KooWAbCdEfGhIjKlMnOpQrStUvWxYz"

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
