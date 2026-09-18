package com.neop2p.data.p2p.routing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFileEnvelopeTest {
    @Test
    fun `round trips`() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val wrapped = ChatFileEnvelope.wrap(payload)
        assertTrue(ChatFileEnvelope.isWrapped(wrapped))
        assertArrayEquals(payload, ChatFileEnvelope.unwrap(wrapped))
    }

    @Test
    fun `rejects short and foreign bytes`() {
        assertNull(ChatFileEnvelope.unwrap(byteArrayOf(1, 2, 3)))
        assertNull(ChatFileEnvelope.unwrap("plaintext file".toByteArray()))
        assertFalse(ChatFileEnvelope.isWrapped("plaintext".toByteArray()))
    }

    @Test
    fun `header is not counted as payload`() {
        val payload = byteArrayOf(9, 9)
        val wrapped = ChatFileEnvelope.wrap(payload)
        assertTrue(wrapped.size > payload.size)
    }
}
