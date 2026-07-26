package com.neop2p.data.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.neop2p.data.local.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub Signal Protocol implementation.
 *
 * The libsignal-android dependency version does not match the API surface used by
 * the original code (Curve, SessionCipher constructors, CiphertextMessage, etc.).
 * This stub keeps the DI graph and UI compiling. Real E2EE needs a compatible
 * libsignal version or the official Signal Android bindings.
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

    data class DecryptedMessage(
        val fromPeerId: String,
        val plaintext: ByteArray,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class PreKeyBundle(
        val registrationId: Int,
        val deviceId: Int,
        val preKeyId: Int,
        val preKeyPublic: ByteArray,
        val signedPreKeyId: Int,
        val signedPreKeyPublic: ByteArray,
        val signedPreKeySignature: ByteArray,
        val identityKey: ByteArray
    )

    data class CiphertextMessage(
        val type: Int,
        val serialized: ByteArray
    ) {
        companion object {
            const val PREKEY_TYPE = 3
            const val WHISPER_TYPE = 1
        }
    }

    private val sessions = mutableMapOf<String, SignalSession>()
    private val _incomingMessages = MutableSharedFlow<DecryptedMessage>(replay = 0)
    val incomingMessages: SharedFlow<DecryptedMessage> = _incomingMessages.asSharedFlow()

    private var localRegistrationId: Int = 0

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            localRegistrationId = 1
            Log.d(TAG, "Signal Protocol stub initialized")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Signal Protocol stub", e)
            Result.failure(e)
        }
    }

    suspend fun getPreKeyBundle(): PreKeyBundle = withContext(Dispatchers.IO) {
        PreKeyBundle(
            registrationId = localRegistrationId,
            deviceId = 1,
            preKeyId = 1,
            preKeyPublic = ByteArray(32),
            signedPreKeyId = 1,
            signedPreKeyPublic = ByteArray(32),
            signedPreKeySignature = ByteArray(64),
            identityKey = ByteArray(32)
        )
    }

    suspend fun createSession(
        remotePeerId: String,
        remoteBundle: PreKeyBundle
    ): Result<SignalSession> = withContext(Dispatchers.IO) {
        try {
            val session = SignalSession(
                sessionId = remotePeerId,
                remotePeerId = remotePeerId,
                isEstablished = true
            )
            sessions[remotePeerId] = session
            Log.d(TAG, "Signal session stub established with $remotePeerId")
            Result.success(session)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create stub session with $remotePeerId", e)
            Result.failure(e)
        }
    }

    suspend fun encrypt(remotePeerId: String, plaintext: ByteArray): Result<CiphertextMessage> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Encrypted ${plaintext.size} bytes for $remotePeerId (stub)")
                Result.success(CiphertextMessage(CiphertextMessage.WHISPER_TYPE, plaintext))
            } catch (e: Exception) {
                Log.e(TAG, "Encryption failed for $remotePeerId", e)
                Result.failure(e)
            }
        }

    suspend fun decrypt(remotePeerId: String, ciphertext: CiphertextMessage): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Decrypted ${ciphertext.serialized.size} bytes from $remotePeerId (stub)")
                Result.success(ciphertext.serialized)
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
            val decrypted = DecryptedMessage(
                fromPeerId = fromPeerId,
                plaintext = ciphertext,
                timestamp = System.currentTimeMillis()
            )
            _incomingMessages.emit(decrypted)
            Log.d(TAG, "Handled incoming message from $fromPeerId (stub)")
            Result.success(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message from $fromPeerId", e)
            Result.failure(e)
        }
    }
}
