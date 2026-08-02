package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.p2p.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.whispersystems.libsignal.*
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.protocol.CiphertextMessage
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SignalMessage
import org.whispersystems.libsignal.state.*
import org.whispersystems.libsignal.util.KeyHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Signal Protocol implementation using libsignal-protocol-java.
 * Uses the 4 SQLCipher-backed stores for persistence.
 */
@Singleton
class SignalProtocol @Inject constructor(
    private val identityManager: IdentityManager,
    private val p2pTransport: P2PTransport,
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "SignalProtocol"
        private const val DEVICE_ID = 1
        private const val MAX_ONE_TIME_PRE_KEYS = 100
        private const val SIGNED_PRE_KEY_ID = 1
    }

    data class SignalSession(
        val sessionId: String,
        val remotePeerId: String,
        val isEstablished: Boolean = false
    )

    data class DecryptedMessage(
        val fromPeerId: String,
        val plaintext: ByteArray,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class PreKeyBundleData(
        val registrationId: Int,
        val deviceId: Int,
        val preKeyId: Int,
        val preKeyPublic: ByteArray,
        val signedPreKeyId: Int,
        val signedPreKeyPublic: ByteArray,
        val signedPreKeySignature: ByteArray,
        val identityKey: ByteArray
    )

    private val sessions = mutableMapOf<String, SignalSession>()
    private val _incomingMessages = MutableSharedFlow<DecryptedMessage>(replay = 0)
    val incomingMessages: SharedFlow<DecryptedMessage> = _incomingMessages.asSharedFlow()

    // Signal stores (SQLCipher-backed, implement libsignal interfaces)
    private lateinit var preKeyStore: SqlCipherPreKeyStore
    private lateinit var sessionStore: SqlCipherSessionStore
    private lateinit var signedPreKeyStore: SqlCipherSignedPreKeyStore
    private lateinit var identityKeyStore: SqlCipherIdentityKeyStore

    private var localRegistrationId: Int = 0

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            preKeyStore = SqlCipherPreKeyStore(db.preKeyDao())
            sessionStore = SqlCipherSessionStore(db.sessionDao())
            signedPreKeyStore = SqlCipherSignedPreKeyStore(db.signedPreKeyDao())
            identityKeyStore = SqlCipherIdentityKeyStore(db.identityKeyDao())

            if (!identityKeyStore.hasIdentity()) {
                val identity = KeyHelper.generateIdentityKeyPair()
                localRegistrationId = KeyHelper.generateRegistrationId(false)
                identityKeyStore.storeIdentity(identity.serialize(), localRegistrationId)
                Log.d(TAG, "Generated new Signal identity (regId=$localRegistrationId)")
            } else {
                localRegistrationId = identityKeyStore.getLocalRegistrationId()
                Log.d(TAG, "Loaded existing Signal identity (regId=$localRegistrationId)")
            }

            ensurePreKeys()
            Log.d(TAG, "Signal Protocol initialized")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Signal Protocol", e)
            Result.failure(e)
        }
    }

    private fun ensurePreKeys() {
        if (!signedPreKeyStore.containsSignedPreKey(SIGNED_PRE_KEY_ID)) {
            val signedPreKey = KeyHelper.generateSignedPreKey(identityKeyStore.getIdentityKeyPair(), SIGNED_PRE_KEY_ID)
            signedPreKeyStore.storeSignedPreKey(SIGNED_PRE_KEY_ID, signedPreKey)
            Log.d(TAG, "Generated signed pre-key (id=$SIGNED_PRE_KEY_ID)")
        }
    }

    suspend fun getPreKeyBundle(): PreKeyBundleData = withContext(Dispatchers.IO) {
        val preKeyId = 1
        val preKey = try { preKeyStore.loadPreKey(preKeyId) } catch (_: Exception) { null }
        val signedPreKey = try { signedPreKeyStore.loadSignedPreKey(SIGNED_PRE_KEY_ID) } catch (_: Exception) { null }

        PreKeyBundleData(
            registrationId = localRegistrationId,
            deviceId = DEVICE_ID,
            preKeyId = preKeyId,
            preKeyPublic = preKey?.keyPair?.publicKey?.serialize() ?: ByteArray(32),
            signedPreKeyId = SIGNED_PRE_KEY_ID,
            signedPreKeyPublic = signedPreKey?.keyPair?.publicKey?.serialize() ?: ByteArray(32),
            signedPreKeySignature = signedPreKey?.signature ?: ByteArray(64),
            identityKey = identityKeyStore.getIdentityKeyPair().publicKey.serialize()
        )
    }

    suspend fun createSession(
        remotePeerId: String,
        remoteBundle: PreKeyBundleData
    ): Result<SignalSession> = withContext(Dispatchers.IO) {
        try {
            val theirIdentityKey = IdentityKey(remoteBundle.identityKey, 0)
            val theirPreKeyPub = Curve.decodePoint(remoteBundle.preKeyPublic, 0)
            val theirSignedPreKeyPub = Curve.decodePoint(remoteBundle.signedPreKeyPublic, 0)

            val builder = SessionBuilder(
                sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore,
                SignalProtocolAddress(remotePeerId, DEVICE_ID)
            )

            val bundle = org.whispersystems.libsignal.state.PreKeyBundle(
                remoteBundle.registrationId, remoteBundle.deviceId,
                remoteBundle.preKeyId, theirPreKeyPub,
                remoteBundle.signedPreKeyId, theirSignedPreKeyPub,
                remoteBundle.signedPreKeySignature, theirIdentityKey
            )

            builder.process(bundle)

            val session = SignalSession(remotePeerId, remotePeerId, true)
            sessions[remotePeerId] = session
            Log.d(TAG, "Signal session established with $remotePeerId")
            Result.success(session)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session with $remotePeerId", e)
            Result.failure(e)
        }
    }

    suspend fun encrypt(remotePeerId: String, plaintext: ByteArray): Result<CiphertextMessage> =
        withContext(Dispatchers.IO) {
            try {
                val cipher = SessionCipher(
                    sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore,
                    SignalProtocolAddress(remotePeerId, DEVICE_ID)
                )
                val ct = cipher.encrypt(plaintext)
                Log.d(TAG, "Encrypted ${plaintext.size} bytes for $remotePeerId (type=${ct.type})")
                Result.success(ct)
            } catch (e: Exception) {
                Log.e(TAG, "Encryption failed for $remotePeerId", e)
                Result.failure(e)
            }
        }

    suspend fun decrypt(remotePeerId: String, ciphertext: CiphertextMessage): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val cipher = SessionCipher(
                    sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore,
                    SignalProtocolAddress(remotePeerId, DEVICE_ID)
                )
                val serialized = ciphertext.serialize()
                val plaintext = try {
                    cipher.decrypt(PreKeySignalMessage(serialized))
                } catch (_: Exception) {
                    cipher.decrypt(SignalMessage(serialized))
                }
                Log.d(TAG, "Decrypted ${plaintext.size} bytes from $remotePeerId")
                Result.success(plaintext)
            } catch (e: Exception) {
                Log.e(TAG, "Decryption failed from $remotePeerId", e)
                Result.failure(e)
            }
        }

    suspend fun handleIncomingMessage(
        fromPeerId: String,
        ciphertext: ByteArray
    ): Result<DecryptedMessage> = withContext(Dispatchers.IO) {
        try {
            val cipher = SessionCipher(
                sessionStore, preKeyStore, signedPreKeyStore, identityKeyStore,
                SignalProtocolAddress(fromPeerId, DEVICE_ID)
            )
            val plaintext = try {
                cipher.decrypt(PreKeySignalMessage(ciphertext))
            } catch (_: Exception) {
                cipher.decrypt(SignalMessage(ciphertext))
            }

            val msg = DecryptedMessage(fromPeerId, plaintext)
            _incomingMessages.emit(msg)
            Log.d(TAG, "Handled incoming message from $fromPeerId")
            Result.success(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message from $fromPeerId", e)
            Result.failure(e)
        }
    }
}
