package com.neop2p.data.p2p.store

import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.entity.*
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.InvalidKeyIdException

/**
 * SQLCipher-backed PreKeyStore for Signal Protocol.
 *
 * Replaces InMemoryPreKeyStore. PreKeys survive app restart.
 * All methods are called from Dispatchers.IO in SignalProtocol — runBlocking is safe here.
 */
class SqlCipherPreKeyStore(private val db: AppDatabase) : PreKeyStore {
    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val entity = runBlocking {
            db.signalPreKeyDao().load(preKeyId)
        } ?: throw InvalidKeyIdException("PreKey $preKeyId not found")
        return PreKeyRecord(entity.serialized_data)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        runBlocking {
            db.signalPreKeyDao().save(
                SignalPreKeyEntity(preKeyId, record.serialize())
            )
        }
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return runBlocking { db.signalPreKeyDao().contains(preKeyId) }
    }

    override fun removePreKey(preKeyId: Int) {
        runBlocking { db.signalPreKeyDao().remove(preKeyId) }
    }
}

/**
 * SQLCipher-backed SignedPreKeyStore for Signal Protocol.
 */
class SqlCipherSignedPreKeyStore(private val db: AppDatabase) : SignedPreKeyStore {
    override fun loadSignedPreKey(id: Int): SignedPreKeyRecord {
        val entity = runBlocking {
            db.signalSignedPreKeyDao().load(id)
        } ?: throw InvalidKeyIdException("SignedPreKey $id not found")
        return SignedPreKeyRecord(entity.serialized_data)
    }

    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) {
        runBlocking {
            db.signalSignedPreKeyDao().save(
                SignalSignedPreKeyEntity(id, record.serialize())
            )
        }
    }

    override fun containsSignedPreKey(id: Int): Boolean {
        return runBlocking { db.signalSignedPreKeyDao().contains(id) }
    }

    override fun removeSignedPreKey(id: Int) {
        runBlocking { db.signalSignedPreKeyDao().remove(id) }
    }
}

/**
 * SQLCipher-backed IdentityKeyStore for Signal Protocol.
 *
 * Stores the local identity key pair and trusted remote identities.
 * Trust model: trust-on-first-use (safety number verification out of scope for v1).
 */
class SqlCipherIdentityKeyStore(private val db: AppDatabase) : IdentityKeyStore {

    fun setIdentityKeyPair(pair: IdentityKeyPair) {
        runBlocking {
            db.signalIdentityDao().save(
                SignalIdentityEntity(
                    id = 1,
                    identity_key_pair = pair.serialize(),
                    local_registration_id = pair.publicKey.hashCode() and 0x7FFF // deterministic
                )
            )
        }
    }

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val entity = runBlocking { db.signalIdentityDao().load() }
            ?: throw IllegalStateException("IdentityKeyPair not set. Call setIdentityKeyPair() first.")
        return IdentityKeyPair(entity.identity_key_pair)
    }

    override fun getLocalRegistrationId(): Int {
        val entity = runBlocking { db.signalIdentityDao().load() }
            ?: return 1
        return entity.local_registration_id
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val hadExisting = runBlocking {
            db.signalTrustedIdentityDao().load(address.name) != null
        }
        runBlocking {
            db.signalTrustedIdentityDao().save(
                SignalTrustedIdentityEntity(
                    peer_id = address.name,
                    identity_key = identityKey.serialize(),
                    direction = "RECEIVING"
                )
            )
        }
        return hadExisting
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val entity = runBlocking { db.signalTrustedIdentityDao().load(address.name) }
            ?: return null
        return IdentityKey(entity.identity_key)
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        // Trust-on-first-use: if no identity stored for this peer, trust it.
        // If identity exists, verify it matches.
        val stored = runBlocking { db.signalTrustedIdentityDao().load(address.name) }
            ?: return true
        return stored.identity_key.contentEquals(identityKey.serialize())
    }
}

/**
 * SQLCipher-backed SessionStore for Signal Protocol.
 *
 * Key = peer_id#device_id. Survives app restart.
 */
class SqlCipherSessionStore(private val db: AppDatabase) : SessionStore {

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val entity = runBlocking {
            db.signalSessionDao().load(address.name, address.deviceId)
        }
        return if (entity != null) {
            SessionRecord(entity.serialized_data)
        } else {
            SessionRecord()
        }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        runBlocking {
            db.signalSessionDao().save(
                SignalSessionEntity(
                    peer_id = address.name,
                    device_id = address.deviceId,
                    serialized_data = record.serialize()
                )
            )
        }
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return runBlocking {
            db.signalSessionDao().contains(address.name, address.deviceId)
        }
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        runBlocking {
            db.signalSessionDao().remove(address.name, address.deviceId)
        }
    }

    override fun deleteAllSessions(address: String) {
        runBlocking {
            db.signalSessionDao().removeAll(address)
        }
    }
}
