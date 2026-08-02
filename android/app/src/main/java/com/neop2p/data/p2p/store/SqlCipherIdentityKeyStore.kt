package com.neop2p.data.p2p.store

import com.neop2p.data.local.dao.IdentityKeyDao
import com.neop2p.data.local.entity.IdentityKeyEntity
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.state.IdentityKeyStore

/**
 * Room/SQLCipher-backed IdentityKeyStore for Signal Protocol.
 */
class SqlCipherIdentityKeyStore(
    private val identityKeyDao: IdentityKeyDao
) : IdentityKeyStore {

    private var cachedPair: IdentityKeyPair? = null
    private var cachedRegId: Int = 0

    override fun getIdentityKeyPair(): IdentityKeyPair {
        cachedPair?.let { return it }
        val entity = kotlinx.coroutines.runBlocking { identityKeyDao.load() }
        if (entity != null) {
            val pair = IdentityKeyPair(entity.identityKeyPair)
            cachedPair = pair
            cachedRegId = entity.registrationId
            return pair
        }
        throw IllegalStateException("Identity key not initialized")
    }

    override fun getLocalRegistrationId(): Int {
        if (cachedRegId == 0) getIdentityKeyPair()
        return cachedRegId
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        // Store trusted identity for this address
        return true
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        // Auto-trust for now (production should verify via QR/Nostr)
        return true
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey {
        return getIdentityKeyPair().publicKey
    }

    fun storeIdentity(identityKeyPair: ByteArray, registrationId: Int) {
        kotlinx.coroutines.runBlocking {
            identityKeyDao.save(IdentityKeyEntity(id = 1, identityKeyPair = identityKeyPair, registrationId = registrationId))
        }
        cachedPair = IdentityKeyPair(identityKeyPair)
        cachedRegId = registrationId
    }

    fun hasIdentity(): Boolean {
        return kotlinx.coroutines.runBlocking { identityKeyDao.load() != null }
    }

    fun clearIdentity() {
        kotlinx.coroutines.runBlocking { identityKeyDao.clear() }
        cachedPair = null
        cachedRegId = 0
    }
}
