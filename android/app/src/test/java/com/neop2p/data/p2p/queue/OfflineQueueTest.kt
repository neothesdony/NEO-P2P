package com.neop2p.data.p2p.queue

import com.neop2p.data.local.dao.PendingMessageDao
import com.neop2p.data.local.entity.PendingMessageEntity
import com.neop2p.data.p2p.protocol.AppMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePendingDao : PendingMessageDao {
    val store = MutableStateFlow<Map<String, PendingMessageEntity>>(emptyMap())
    override suspend fun insert(entity: PendingMessageEntity) {
        store.value = store.value + (entity.message_id to entity)
    }
    override fun pendingFor(peerId: String): Flow<List<PendingMessageEntity>> =
        store.map { m -> m.values.filter { it.to_peer_id == peerId } }
    override suspend fun delete(messageId: String) {
        store.value = store.value - messageId
    }
    override suspend fun deleteFor(peerId: String) {
        store.value = store.value.filterValues { it.to_peer_id != peerId }
    }
    override suspend fun deleteChatFor(peerId: String) {
        store.value = store.value.filterValues { !(it.to_peer_id == peerId && it.type == "chat") }
    }
}

class OfflineQueueTest {

    @Test
    fun `send_enqueues_for_peer`() = kotlinx.coroutines.runBlocking {
        val dao = FakePendingDao()
        val queue = OfflineQueue(dao)
        queue.send("peerB", AppMessage.Chat("peerB", "offer1", byteArrayOf(1)))
        val entry = dao.store.value.values.firstOrNull()
        assertTrue("pending entry stored for peerB", entry != null)
        assertEquals("peerB", entry!!.to_peer_id)
        assertEquals("chat", entry.type)
    }

    @Test
    fun `drain_delivers_and_removes_pending`() = kotlinx.coroutines.runBlocking {
        val dao = FakePendingDao()
        val queue = OfflineQueue(dao)
        queue.send("peerB", AppMessage.Chat("peerB", "offer1", byteArrayOf(1)))
        val delivered = mutableListOf<AppMessage>()
        queue.drainFor("peerB") { msg ->
            delivered += msg
            true
        }
        assertEquals(1, delivered.size)
        assertTrue("pending entry removed after successful delivery", dao.store.value.isEmpty())
    }

    @Test
    fun `drain_keeps_pending_when_delivery_fails`() = kotlinx.coroutines.runBlocking {
        val dao = FakePendingDao()
        val queue = OfflineQueue(dao)
        queue.send("peerB", AppMessage.Chat("peerB", "offer1", byteArrayOf(1)))
        queue.drainFor("peerB") { false }
        assertTrue("pending entry retained when delivery fails", dao.store.value.isNotEmpty())
    }
}
