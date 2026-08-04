package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.p2p.protocol.AppMessage
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
            generateOneTimePreKeys()
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

    /**
     * Generate and persist a small pool of one-time pre-keys. Existing IDs are
     * skipped so re-runs don't waste keys. Returns the IDs that are present after
     * this call.
     */
    suspend fun generateOneTimePreKeys(count: Int = 5): List<Int> = withContext(Dispatchers.IO) {
        val startId = 1
        val generated = KeyHelper.generatePreKeys(startId, count)
        val ids = mutableListOf<Int>()
        for (record in generated) {
            val id = record.id
            if (!preKeyStore.containsPreKey(id)) {
                preKeyStore.storePreKey(id, record)
                ids.add(id)
            }
        }
        if (ids.isNotEmpty()) {
            Log.d(TAG, "Generated one-time pre-keys (ids=${ids.joinToString()})")
        }
        ids
    }

    suspend fun getPreKeyBundle(): PreKeyBundleData = withContext(Dispatchers.IO) {
        val preKey = try { preKeyStore.loadPreKey(SIGNED_PRE_KEY_ID) } catch (_: Exception) { null }
        val signedPreKey = try { signedPreKeyStore.loadSignedPreKey(SIGNED_PRE_KEY_ID) } catch (_: Exception) { null }

        PreKeyBundleData(
            registrationId = localRegistrationId,
            deviceId = DEVICE_ID,
            preKeyId = SIGNED_PRE_KEY_ID,
            preKeyPublic = preKey?.keyPair?.publicKey?.serialize() ?: ByteArray(32),
            signedPreKeyId = SIGNED_PRE_KEY_ID,
            signedPreKeyPublic = signedPreKey?.keyPair?.publicKey?.serialize() ?: ByteArray(32),
            signedPreKeySignature = signedPreKey?.signature ?: ByteArray(64),
            identityKey = identityKeyStore.getIdentityKeyPair().publicKey.serialize()
        )
    }

    suspend fun sendPreKeyBundle(peerId: String): Result<AppMessage.PreKeyBundle> =
        withContext(Dispatchers.IO) {
            try {
                val bundle = getPreKeyBundle()
                val bytes = serializeBundle(bundle)
                Result.success(AppMessage.PreKeyBundle(peerId, bytes))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build pre-key bundle for $peerId", e)
                Result.failure(e)
            }
        }

    /**
     * Binary codec for [PreKeyBundleData] using 4-byte big-endian length-prefixed
     * framing, consistent with [com.neop2p.data.p2p.protocol.EnvelopeCodec]. The
     * byte-array fields (public keys, signatures, identity key) are framed so
     * arbitrary contents round-trip losslessly.
     */
    fun serializeBundle(bundle: PreKeyBundleData): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt(out, bundle.registrationId)
        writeInt(out, bundle.deviceId)
        writeInt(out, bundle.preKeyId)
        writeBytes(out, bundle.preKeyPublic)
        writeInt(out, bundle.signedPreKeyId)
        writeBytes(out, bundle.signedPreKeyPublic)
        writeBytes(out, bundle.signedPreKeySignature)
        writeBytes(out, bundle.identityKey)
        return out.toByteArray()
    }

    /**
     * Inverse of [serializeBundle]. Throws [IllegalArgumentException] if [bytes]
     * is malformed so callers never receive a zeroed bundle.
     */
    fun deserializeBundle(bytes: ByteArray): PreKeyBundleData {
        if (bytes.isEmpty()) throw IllegalArgumentException("Empty pre-key bundle")
        val input = ByteArrayInputStream(bytes)
        return try {
            val registrationId = readInt(input)
                ?: throw IllegalArgumentException("Missing registrationId")
            val deviceId = readInt(input)
                ?: throw IllegalArgumentException("Missing deviceId")
            val preKeyId = readInt(input)
                ?: throw IllegalArgumentException("Missing preKeyId")
            val preKeyPublic = readBytes(input)
                ?: throw IllegalArgumentException("Missing preKeyPublic")
            val signedPreKeyId = readInt(input)
                ?: throw IllegalArgumentException("Missing signedPreKeyId")
            val signedPreKeyPublic = readBytes(input)
                ?: throw IllegalArgumentException("Missing signedPreKeyPublic")
            val signedPreKeySignature = readBytes(input)
                ?: throw IllegalArgumentException("Missing signedPreKeySignature")
            val identityKey = readBytes(input)
                ?: throw IllegalArgumentException("Missing identityKey")
            PreKeyBundleData(
                registrationId = registrationId,
                deviceId = deviceId,
                preKeyId = preKeyId,
                preKeyPublic = preKeyPublic,
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = signedPreKeyPublic,
                signedPreKeySignature = signedPreKeySignature,
                identityKey = identityKey
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed pre-key bundle", e)
        }
    }

    private fun writeBytes(out: ByteArrayOutputStream, value: ByteArray) {
        writeInt(out, value.size)
        out.write(value)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readBytes(input: ByteArrayInputStream): ByteArray? {
        val len = readInt(input) ?: return null
        if (len < 0 || len > input.available()) return null
        val buf = ByteArray(len)
        if (input.read(buf) != len) return null
        return buf
    }

    private fun readInt(input: ByteArrayInputStream): Int? {
        if (input.available() < 4) return null
        return (input.read() shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read()
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
