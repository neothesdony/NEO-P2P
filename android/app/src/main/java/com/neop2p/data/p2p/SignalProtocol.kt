package com.neop2p.data.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.*
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.p2p.store.*
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * End-to-end encrypted messaging using the Signal Protocol.
 *
 * Provides:
 * - Double Ratchet algorithm with forward secrecy
 * - X3DH key agreement (Curve25519)
 * - Pre-key bundle exchange via libp2p DHT
 * - Encrypted message serialization/deserialization
 */
@Singleton
class SignalProtocol @Inject constructor(
    private val identityManager: IdentityManager,
    private val libP2PManager: LibP2PManager,
    private val db: AppDatabase
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

    // Signal Protocol stores — SQLCipher-backed, survive app restart
    private val preKeyStore = SqlCipherPreKeyStore(db)
    private val signedPreKeyStore = SqlCipherSignedPreKeyStore(db)
    private val identityKeyStore = SqlCipherIdentityKeyStore(db)
    private val sessionStore = SqlCipherSessionStore(db)

    private var localRegistrationId: Int = 0

    /**
     * Initialize the Signal Protocol with a fresh Curve25519 identity key pair.
     *
     * NOTE: Signal Protocol uses X3DH (Curve25519), NOT the Android KeyStore Ed25519 key.
     * We generate a separate Curve25519 key pair here for Signal.
     * In production, this key pair should be persisted (encrypted with the Keystore key).
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Generate a fresh Curve25519 identity key pair for Signal Protocol
            val identityKeyPair = Curve.generateKeyPair()
            localRegistrationId = SecureRandom().nextInt(16383) + 1

            // Store identity key pair in the store
            identityKeyStore.setIdentityKeyPair(IdentityKeyPair(identityKeyPair))

            // Generate PreKeys (batch of 100)
            for (i in 1..100) {
                val keyPair = Curve.generateKeyPair()
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

            Log.d(TAG, "Signal Protocol initialized with 100 pre-keys")
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
        val identity = identityKeyStore.getIdentityKeyPair()
        val identityKey = IdentityKey(identity.publicKey.serialize())
        PreKeyBundle(
            localRegistrationId,
            1,  // device ID
            1,  // pre-key ID
            preKeyStore.loadPreKey(1).keyPair.publicKey,
            1,  // signed pre-key ID
            signedPreKeyStore.loadSignedPreKey(1).keyPair.publicKey,
            signedPreKeyStore.loadSignedPreKey(1).signature,
            identityKey
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
            val remoteAddress = SignalProtocolAddress(remotePeerId, 1)
            val sessionBuilder = SessionBuilder(
                sessionStore,
                preKeyStore,
                signedPreKeyStore,
                identityKeyStore,
                remoteAddress
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
                val remoteAddress = SignalProtocolAddress(remotePeerId, 1)
                val sessionCipher = SessionCipher(
                    sessionStore,
                    preKeyStore,
                    identityKeyStore,
                    remoteAddress
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
            val remoteAddress = SignalProtocolAddress(remotePeerId, 1)
            val sessionCipher = SessionCipher(
                sessionStore,
                preKeyStore,
                identityKeyStore,
                remoteAddress
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
     * Handle an incoming message from a remote peer.
     * Supports both PreKeySignalMessage (first message in session) and
     * SignalMessage (subsequent messages in established session).
     */
    suspend fun handleIncomingMessage(
        fromPeerId: String,
        ciphertext: ByteArray
    ): Result<DecryptedMessage> = withContext(Dispatchers.IO) {
        try {
            val remoteAddress = SignalProtocolAddress(fromPeerId, 1)
            val sessionCipher = SessionCipher(
                sessionStore,
                preKeyStore,
                identityKeyStore,
                remoteAddress
            )

            // Try PreKeySignalMessage first (first message in a session)
            val plaintext = try {
                val preKeyMessage = PreKeySignalMessage(ciphertext)
                sessionCipher.decrypt(preKeyMessage)
            } catch (_: Exception) {
                // Not a PreKeySignalMessage — try as regular SignalMessage
                // (subsequent messages in an established session)
                val signalMessage = SignalMessage(ciphertext)
                sessionCipher.decrypt(signalMessage)
            }

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

    /**
     * Decrypt a message when the ciphertext type is known.
     */
    suspend fun decrypt(
        remotePeerId: String,
        ciphertext: CiphertextMessage
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val remoteAddress = SignalProtocolAddress(remotePeerId, 1)
            val sessionCipher = SessionCipher(
                sessionStore,
                preKeyStore,
                identityKeyStore,
                remoteAddress
            )

            val plaintext = when (ciphertext.type()) {
                CiphertextMessage.PREKEY_TYPE -> {
                    sessionCipher.decrypt(PreKeySignalMessage(ciphertext.serialize()))
                }
                CiphertextMessage.WHISPER_TYPE -> {
                    sessionCipher.decrypt(SignalMessage(ciphertext.serialize()))
                }
                else -> throw IllegalArgumentException("Unsupported ciphertext type: ${ciphertext.type()}")
            }
            Log.d(TAG, "Decrypted ${plaintext.size} bytes from $remotePeerId")
            Result.success(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for $remotePeerId", e)
            Result.failure(e)
        }
    }
}
