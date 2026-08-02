package com.neop2p.data.p2p.store

import com.neop2p.data.local.dao.SessionDao
import com.neop2p.data.local.entity.SessionEntity
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SessionStore

/**
 * Room/SQLCipher-backed SessionStore for Signal Protocol.
 */
class SqlCipherSessionStore(
    private val sessionDao: SessionDao
) : SessionStore {

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val entity = kotlinx.coroutines.runBlocking {
            sessionDao.load(address.name, address.deviceId)
        }
        return if (entity != null) SessionRecord(entity.serializedData) else SessionRecord()
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        return mutableListOf(1)
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        kotlinx.coroutines.runBlocking {
            sessionDao.save(SessionEntity(
                peerId = address.name,
                deviceId = address.deviceId,
                serializedData = record.serialize()
            ))
        }
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return kotlinx.coroutines.runBlocking {
            sessionDao.load(address.name, address.deviceId) != null
        }
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        kotlinx.coroutines.runBlocking {
            sessionDao.delete(address.name, address.deviceId)
        }
    }

    override fun deleteAllSessions(name: String) {
        kotlinx.coroutines.runBlocking {
            sessionDao.delete(name, 1)
        }
    }
}
