package com.neop2p.data.p2p

import network.reticulum.lxmf.DeliveryMethod
import network.reticulum.lxmf.MessageState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDeliveryStatusTest {

    @Test
    fun `mapper maps delivered regardless of method`() {
        assertEquals(ChatDeliveryStatus.DELIVERED, ChatDeliveryStatusMapper.map(MessageState.DELIVERED, DeliveryMethod.DIRECT))
        assertEquals(ChatDeliveryStatus.DELIVERED, ChatDeliveryStatusMapper.map(MessageState.DELIVERED, DeliveryMethod.PROPAGATED))
    }

    @Test
    fun `mapper maps sent-propagated to propagated`() {
        assertEquals(ChatDeliveryStatus.PROPAGATED, ChatDeliveryStatusMapper.map(MessageState.SENT, DeliveryMethod.PROPAGATED))
        assertEquals(ChatDeliveryStatus.SENT, ChatDeliveryStatusMapper.map(MessageState.SENT, DeliveryMethod.DIRECT))
    }

    @Test
    fun `mapper maps terminal failures`() {
        assertEquals(ChatDeliveryStatus.FAILED, ChatDeliveryStatusMapper.map(MessageState.FAILED, null))
        assertEquals(ChatDeliveryStatus.FAILED, ChatDeliveryStatusMapper.map(MessageState.REJECTED, null))
        assertEquals(ChatDeliveryStatus.FAILED, ChatDeliveryStatusMapper.map(MessageState.CANCELLED, null))
    }

    @Test
    fun `reducer never downgrades delivered or propagated`() {
        assertEquals(ChatDeliveryStatus.DELIVERED, ChatDeliveryStatusReducer.apply(ChatDeliveryStatus.DELIVERED, ChatDeliveryStatus.SENT))
        assertEquals(ChatDeliveryStatus.DELIVERED, ChatDeliveryStatusReducer.apply(ChatDeliveryStatus.DELIVERED, ChatDeliveryStatus.FAILED))
        assertEquals(ChatDeliveryStatus.PROPAGATED, ChatDeliveryStatusReducer.apply(ChatDeliveryStatus.PROPAGATED, ChatDeliveryStatus.SENT))
    }

    @Test
    fun `reducer allows failed to recover to delivered`() {
        assertEquals(ChatDeliveryStatus.DELIVERED, ChatDeliveryStatusReducer.apply(ChatDeliveryStatus.FAILED, ChatDeliveryStatus.DELIVERED))
    }

    @Test
    fun `token is deterministic and 32 hex chars`() {
        val a = ChatDeliveryToken.of(byteArrayOf(1, 2, 3))
        val b = ChatDeliveryToken.of(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(32, a.length)
        assert(a.all { it in "0123456789abcdef" })
    }
}
