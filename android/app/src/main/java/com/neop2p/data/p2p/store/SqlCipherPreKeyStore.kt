package com.neop2p.data.p2p.store

import com.neop2p.data.local.dao.PreKeyDao
import com.neop2p.data.local.entity.PreKeyEntity
import org.whispersystems.libsignal.InvalidKeyIdException
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.PreKeyStore

/**
 * Room/SQLCipher-backed PreKeyStore for Signal Protocol.
 */
class SqlCipherPreKeyStore(
    private val preKeyDao: PreKeyDao
) : PreKeyStore {

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val entity = kotlinx.coroutines.runBlocking { preKeyDao.load(preKeyId) }
            ?: throw InvalidKeyIdException("PreKey not found: $preKeyId")
        return PreKeyRecord(entity.serializedData)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        kotlinx.coroutines.runBlocking {
            preKeyDao.save(PreKeyEntity(preKeyId = preKeyId, serializedData = record.serialize()))
        }
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return kotlinx.coroutines.runBlocking { preKeyDao.load(preKeyId) != null }
    }

    override fun removePreKey(preKeyId: Int) {
        kotlinx.coroutines.runBlocking { preKeyDao.remove(preKeyId) }
    }
}
