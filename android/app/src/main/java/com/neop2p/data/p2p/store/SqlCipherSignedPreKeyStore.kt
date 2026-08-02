package com.neop2p.data.p2p.store

import com.neop2p.data.local.dao.SignedPreKeyDao
import com.neop2p.data.local.entity.SignedPreKeyEntity
import org.whispersystems.libsignal.InvalidKeyIdException
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.libsignal.state.SignedPreKeyStore

/**
 * Room/SQLCipher-backed SignedPreKeyStore for Signal Protocol.
 */
class SqlCipherSignedPreKeyStore(
    private val signedPreKeyDao: SignedPreKeyDao
) : SignedPreKeyStore {

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val entity = kotlinx.coroutines.runBlocking { signedPreKeyDao.load(signedPreKeyId) }
            ?: throw InvalidKeyIdException("SignedPreKey not found: $signedPreKeyId")
        return SignedPreKeyRecord(entity.serializedData)
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> {
        return kotlinx.coroutines.runBlocking {
            signedPreKeyDao.loadAll().map { SignedPreKeyRecord(it.serializedData) }.toMutableList()
        }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        kotlinx.coroutines.runBlocking {
            signedPreKeyDao.save(SignedPreKeyEntity(signedPreKeyId = signedPreKeyId, serializedData = record.serialize()))
        }
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return kotlinx.coroutines.runBlocking { signedPreKeyDao.load(signedPreKeyId) != null }
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        kotlinx.coroutines.runBlocking { signedPreKeyDao.remove(signedPreKeyId) }
    }
}
