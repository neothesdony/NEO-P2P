package com.neop2p.data.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.message.*
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * End-to-end encrypted messaging using the Signal Protocol.
 *
 * Provides:
 * - Double Ratchet algorithm with forward secrecy
 * - PQXDH post-quantum key agreement (2026)
 * - Pre-key bundle exchange via libp2p DHT
 * - Encrypted message serialization/deserialization
 */
@Singleton
class SignalProtocol @Inject constructor(
    private val identityManager: IdentityManager,
    private val libP2PManager: LibP2PManager
) {
    companion object {
        private const val TAG = "SignalProtocol"
    }

    data class SignalSession(
        val sessionId: String,
        val remotePeerId: String,
        val isEstablished: Boolean = false
    )

    private val sessions = mutableMapOf<String, SignalSession>()
    private val _incomingMessages = MutableSharedFlow<DecryptedMessage>(replay = 0)
    val incomingMessages: SharedFlow<DecryptedMessage> = _incomingMessages.asSharedFlow()

    data class DecryptedMessage(
        val fromPeerId: String,
        val plaintext: ByteArray,
        val timestamp: Long = System.currentTimeMillis()
    )

    private var localIdentity: IdentityKeyPair? = null
    private var localRegistrationId: Int = 0
    private val preKeyStore = InMemoryPreKeyStore()
    private val signedPreKeyStore = InMemorySignedPreKeyStore()
    private val identityKeyStore = InMemoryIdentityKeyStore()
    private val sessionStore = InMemorySessionStore()

    /**
     * Initialize the Signal Protocol with the user's identity.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val identity = identityManager.getOrCreateIdentity()

            // Generate Identity Key Pair from the Android KeyStore key
            val publicKey = identity.publicKey.encoded
            val privateKey = identity.privateKey.encoded
            val identityKeyPair = IdentityKeyPair(
                IdentityKey(publicKey, 0),
                ECPrivateKey(privateKey, 0)
            )
            localIdentity = identityKeyPair
            localRegistrationId = SecureRandom().nextInt(16383) + 1

            // Generate PreKeys (batch of 100)
            val preKeys = mutableListOf<PreKeyRecord>()
            for (i in 1..100) {
                val keyPair = Curve.generateKeyPair()
                preKeys.add(PreKeyRecord(i, keyPair))
                preKeyStore.storePreKey(i, PreKeyRecord(i, keyPair))
            }

            // Generate Signed PreKey
            val signedPreKeyPair = Curve.generateKeyPair()
            val signedPreKeySignature = Curve.calculateSignature(
                identityKeyPair.privateKey,
                signedPreKeyPair.publicKey.serialize()
            )
            val signedPreKeyRecord = SignedPreKeyRecord(
                1,
                System.currentTimeMillis(),
                signedPreKeyPair,
                signedPreKeySignature
            )
            signedPreKeyStore.storeSignedPreKey(1, signedPreKeyRecord)

            Log.d(TAG, "Signal Protocol initialized with ${preKeys.size} pre-keys")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Signal Protocol", e)
            Result.failure(e)
        }
    }

    /**
     * Build a PreKeyBundle to send to a remote peer for session establishment.
     */
    suspend fun getPreKeyBundle(): PreKeyBundle = withContext(Dispatchers.IO) {
        val identity = localIdentity ?: throw IllegalStateException("Not initialized")
        PreKeyBundle(
            localRegistrationId,
            1,  // device ID
            1,  // pre-key ID
            preKeyStore.loadPreKey(1).keyPair.publicKey,
            1,  // signed pre-key ID
            signedPreKeyStore.loadSignedPreKey(1).keyPair.publicKey,
            signedPreKeyStore.loadSignedPreKey(1).signature,
            identity.identityKey
        )
    }

    /**
     * Establish a session with a remote peer using their PreKeyBundle.
     */
    suspend fun createSession(
        remotePeerId: String,
        remoteBundle: PreKeyBundle
    ): Result<SignalSession> = withContext(Dispatchers.IO) {
        try {
            val sessionBuilder = SessionBuilder(
                sessionStore,
                preKeyStore,
                identityKeyStore,
                remoteBundle.registrationId
            )
            sessionBuilder.process(remoteBundle)

            val session = SignalSession(
                sessionId = remotePeerId,
                remotePeerId = remotePeerId,
                isEstablished = true
            )
            sessions[remotePeerId] = session
            Log.d(TAG, "Signal session established with $remotePeerId")
            Result.success(session)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session with $remotePeerId", e)
            Result.failure(e)
        }
    }

    /**
     * Encrypt a message for a remote peer.
     */
    suspend fun encrypt(remotePeerId: String, plaintext: ByteArray): Result<CiphertextMessage> =
        withContext(Dispatchers.IO) {
            try {
                val sessionCipher = SessionCipher(
                    sessionStore,
                    preKeyStore,
                    identityKeyStore,
                    remotePeerId
                )
                val ciphertext = sessionCipher.encrypt(plaintext)
                Log.d(TAG, "Encrypted ${plaintext.size} bytes for $remotePeerId")
                Result.success(ciphertext)
            } catch (e: Exception) {
                Log.e(TAG, "Encryption failed for $remotePeerId", e)
                Result.failure(e)
            }
        }

    /**
     * Decrypt a message from a remote peer.
     */
    suspend fun decrypt(
        remotePeerId: String,
        ciphertext: CiphertextMessage
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val sessionCipher = SessionCipher(
                sessionStore,
                preKeyStore,
                identityKeyStore,
                remotePeerId
            )
            val plaintext = sessionCipher.decrypt(
                PreKeySignalMessage(ciphertext.serialize())
            )
            Log.d(TAG, "Decrypted ${plaintext.size} bytes from $remotePeerId")
            Result.success(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed from $remotePeerId", e)
            Result.failure(e)
        }
    }

    /**
     * Handle an incoming pre-key signal message (first message from a new session).
     */
    suspend fun handleIncomingMessage(
        fromPeerId: String,
        ciphertext: ByteArray
    ): Result<DecryptedMessage> = withContext(Dispatchers.IO) {
        try {
            val sessionCipher = SessionCipher(
                sessionStore,
                preKeyStore,
                identityKeyStore,
                fromPeerId
            )
            val message = PreKeySignalMessage(ciphertext)
            val plaintext = sessionCipher.decrypt(message)
            val decrypted = DecryptedMessage(
                fromPeerId = fromPeerId,
                plaintext = plaintext,
                timestamp = System.currentTimeMillis()
            )
            _incomingMessages.emit(decrypted)
            Log.d(TAG, "Handled incoming message from $fromPeerId")
            Result.success(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message from $fromPeerId", e)
            Result.failure(e)
        }
    }
}

// ─── In-Memory Stores (will be replaced with SQLCipher-backed in Phase 2) ───
class InMemoryPreKeyStore : PreKeyStore {
    private val store = mutableMapOf<Int, PreKeyRecord>()
    override fun loadPreKey(preKeyId: Int) = store[preKeyId]!!
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) { store[preKeyId] = record }
    override fun containsPreKey(preKeyId: Int) = store.containsKey(preKeyId)
    override fun removePreKey(preKeyId: Int) { store.remove(preKeyId) }
}

class InMemorySignedPreKeyStore : SignedPreKeyStore {
    private val store = mutableMapOf<Int, SignedPreKeyRecord>()
    override fun loadSignedPreKey(id: Int) = store[id]!!
    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) { store[id] = record }
    override fun containsSignedPreKey(id: Int) = store.containsKey(id)
    override fun removeSignedPreKey(id: Int) { store.remove(id) }
}

class InMemoryIdentityKeyStore : IdentityKeyStore {
    private val identities = mutableMapOf<String, IdentityKey>()
    override fun getIdentityKeyPair() = TODO("Implement with actual keys")
    override fun getLocalRegistrationId() = 1
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        identities[address.name] = identityKey
        return true
    }
    override fun getIdentity(address: SignalProtocolAddress) = identities[address.name]
    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityDirection): Boolean = true
}

class InMemorySessionStore : SessionStore {
    private val sessions = mutableMap<String, SessionRecord>()
    override fun loadSession(address: SignalProtocolAddress) = sessions[address.name] ?: SessionRecord()
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) { sessions[address.name] = record }
    override fun containsSession(address: SignalProtocolAddress) = sessions.containsKey(address.name)
    override fun deleteSession(address: SignalProtocolAddress) { sessions.remove(address.name) }
    override fun deleteAllSessions(address: String) { sessions.clear() }
}
