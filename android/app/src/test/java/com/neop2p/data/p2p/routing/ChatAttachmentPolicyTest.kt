package com.neop2p.data.p2p.routing

import com.neop2p.data.p2p.RnsSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentPolicyTest {
    @Test
    fun `rejects empty and over-cap`() {
        assertFalse(ChatAttachmentPolicy.allows(0))
        assertFalse(ChatAttachmentPolicy.allows(ChatAttachmentPolicy.MAX_BYTES + 1))
    }

    @Test
    fun `accepts at the cap`() {
        assertTrue(ChatAttachmentPolicy.allows(1))
        assertTrue(ChatAttachmentPolicy.allows(ChatAttachmentPolicy.MAX_BYTES))
    }

    @Test
    fun `cap-sized payload fits the inbound gate once wrapped`() {
        val wrapped = ChatFileEnvelope.wrap(ByteArray(ChatAttachmentPolicy.MAX_BYTES))
        assertTrue(wrapped.size <= RnsSession.MAX_INBOUND_FILE_BYTES)
    }
}
