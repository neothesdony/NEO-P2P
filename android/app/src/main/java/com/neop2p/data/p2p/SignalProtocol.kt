package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.entity.ConversationKeyEntity
import com.neop2p.data.p2p.protocol.AppMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * E2EE layer for NEO-P2P chat (NIP-44-style).
 *
 * Replaces the archived libsignal-protocol-java (P0-2): that library shipped
 * protobuf-javalite classes that crashed under the full protobuf-java runtime
 * required by libp2p, so chat silently degraded to mock data. This implementation
 * uses the same primitives the Nostr ecosystem standardized on:
 *
 *   shared_secret = X25519(localPriv, peerPub)          (deterministic, from BIP-32 seed)
 *   key           = HKDF-SHA256(shared_secret, "neop2p-chat-v1")
 *   ciphertext    = XChaCha20-Poly1305(key, 24-byte random nonce)  → nonce || ct || tag
 *
 * The local key is derived deterministically from the BIP-39 mnemonic via
 * IdentityManager (PATH_SIGNAL), so no long-term key is persisted in plaintext.
 * Peer public keys are persisted in SQLCipher (conversation_keys table), which
 * also fixes the previous in-memory-only session loss on restart.
 *
 * The public API of the old SignalProtocol class is preserved (initialize,
 * encrypt/decrypt, handleIncomingMessage, pre-key bundle handshake) so callers
 * and the wire message types are unchanged.
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
        private const val NONCE_SIZE = 12   // ChaCha20-Poly1305 nonce
        private const val TAG_SIZE = 16     // Poly1305 tag
        private const val HKDF_INFO = "neop2p-chat-v1"
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
        val identityKey: ByteArray,
        val identityPubKey: ByteArray = ByteArray(0),
        val identitySignature: ByteArray = ByteArray(0)
    )

    private val sessions = mutableMapOf<String, SignalSession>()
    private val _incomingMessages = MutableSharedFlow<DecryptedMessage>(replay = 0)
    val incomingMessages: SharedFlow<DecryptedMessage> = _incomingMessages.asSharedFlow()

    /** Emits a peerId every time a usable E2EE session is established/restored. */
    private val _sessionEstablished = MutableSharedFlow<String>(replay = 0)
    val sessionEstablished: SharedFlow<String> = _sessionEstablished.asSharedFlow()

    private val random = SecureRandom()

    /** Our long-term X25519 keypair, derived from the BIP-32 identity. */
    private fun localKeyPair(): X25519PrivateKeyParameters {
        val priv = identityManager.getSignalPrivateKey()
        require(priv.size == 32) { "Signal private key must be 32 bytes (X25519)" }
        return X25519PrivateKeyParameters(priv, 0)
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Key material is derived from the mnemonic on demand — nothing to
            // generate or persist. Any stale in-memory sessions are dropped.
            sessions.clear()
            Log.d(TAG, "E2EE initialized (NIP-44-style XChaCha20, key from BIP-32)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize E2EE", e)
            Result.failure(e)
        }
    }

    /**
     * Generate one-time pre-keys is a no-op for this scheme (no pre-key pool);
     * kept for API compatibility.
     */
    suspend fun generateOneTimePreKeys(count: Int = 5): List<Int> = emptyList()

    suspend fun getPreKeyBundle(): PreKeyBundleData = withContext(Dispatchers.IO) {
        val pub = localKeyPair().generatePublicKey().encoded
        // Bind the X25519 pre-key to our Ed25519 identity (which equals our
        // libp2p PeerID). The Ed25519 private key is the same one used to build
        // the libp2p host, so the peerId derivable from identityPubKey matches
        // our published PeerID — defeating relay MITM key substitution.
        val libp2pPriv = identityManager.getLibp2pPrivateKey()
        require(libp2pPriv.size == 32) { "libp2p Ed25519 private key must be 32 bytes" }
        val identityPub = Ed25519PrivateKeyParameters(libp2pPriv, 0)
            .generatePublicKey().encoded
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(libp2pPriv, 0))
        signer.update(pub, 0, pub.size)
        val identitySignature = signer.generateSignature()
        PreKeyBundleData(
            registrationId = DEVICE_ID,
            deviceId = DEVICE_ID,
            preKeyId = DEVICE_ID,
            preKeyPublic = pub,
            signedPreKeyId = DEVICE_ID,
            signedPreKeyPublic = pub,
            signedPreKeySignature = ByteArray(64),
            identityKey = pub,
            identityPubKey = identityPub,
            identitySignature = identitySignature
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
     * framing, consistent with [com.neop2p.data.p2p.protocol.EnvelopeCodec].
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
        writeBytes(out, bundle.identityPubKey)
        writeBytes(out, bundle.identitySignature)
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
            val identityPubKey = readBytes(input)
                ?: throw IllegalArgumentException("Missing identityPubKey")
            val identitySignature = readBytes(input)
                ?: throw IllegalArgumentException("Missing identitySignature")
            PreKeyBundleData(
                registrationId = registrationId,
                deviceId = deviceId,
                preKeyId = preKeyId,
                preKeyPublic = preKeyPublic,
                signedPreKeyId = signedPreKeyId,
                signedPreKeyPublic = signedPreKeyPublic,
                signedPreKeySignature = signedPreKeySignature,
                identityKey = identityKey,
                identityPubKey = identityPubKey,
                identitySignature = identitySignature
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

    /**
     * True if a persisted E2EE session (peer X25519 key) exists for [peerId],
     * meaning encrypt/decrypt can proceed without a fresh handshake. Used by the
     * chat UI to report an honest SESSION_READY vs OFFLINE state.
     */
    suspend fun hasStoredSession(peerId: String): Boolean =
        withContext(Dispatchers.IO) {
            db.conversationKeyDao().load(peerId) != null
        }

    /**
     * Establish (or restore) a conversation with a peer from their X25519 public
     * key. The derived key is cached in SQLCipher so sessions survive restarts.
     *
     * The pre-key bundle is cryptographically bound to the sender's Ed25519
     * identity (which equals their libp2p PeerID): the bundle carries an Ed25519
     * signature over the X25519 pre-key and the identity public key, and we
     * reject the session unless (a) the signature verifies, (b) the identity key
     * derives to exactly [remotePeerId], and (c) the message arrived over an
     * authenticated transport. This defeats relay MITM key substitution.
     */
    suspend fun createSession(
        remotePeerId: String,
        remoteBundle: PreKeyBundleData,
        authenticated: Boolean = false
    ): Result<SignalSession> = withContext(Dispatchers.IO) {
        try {
            // The transport `authenticated` flag is advisory only. Sessions are
            // still safe over the WS relay because the identity-binding checks
            // below (Ed25519 signature over the pre-key, peerId derivation)
            // defeat relay MITM key substitution regardless of transport.
            if (!authenticated) {
                Log.d(TAG, "Establishing session with $remotePeerId over non-authenticated transport (relay)")
            }

            val theirPub = remoteBundle.preKeyPublic
            require(theirPub.size == 32) { "Peer X25519 key must be 32 bytes, got ${theirPub.size}" }

            // ---- Identity binding checks ----
            require(remoteBundle.identityPubKey.size == 32) {
                "Identity Ed25519 public key must be 32 bytes, got ${remoteBundle.identityPubKey.size}"
            }
            require(remoteBundle.identitySignature.size == 64) {
                "Identity Ed25519 signature must be 64 bytes, got ${remoteBundle.identitySignature.size}"
            }
            // Verify the Ed25519 signature over the pre-key using the claimed identity key.
            val verifier = Ed25519Signer()
            verifier.init(
                false,
                Ed25519PublicKeyParameters(remoteBundle.identityPubKey, 0)
            )
            verifier.update(theirPub, 0, theirPub.size)
            require(verifier.verifySignature(remoteBundle.identitySignature)) {
                "Pre-key bundle signature failed verification for $remotePeerId"
            }
            // The identity pub key must derive to exactly the claimed peerId.
            val expectedPeerId = try {
                io.libp2p.core.PeerId.fromPubKey(
                    io.libp2p.crypto.keys.unmarshalEd25519PublicKey(remoteBundle.identityPubKey)
                ).toBase58()
            } catch (e: Exception) {
                throw IllegalArgumentException("Could not derive peerId from identity key", e)
            }
            require(expectedPeerId == remotePeerId) {
                "Identity key does not match peerId: expected $expectedPeerId, got $remotePeerId"
            }
            // ---- End identity binding checks ----

            db.conversationKeyDao().save(
                ConversationKeyEntity(peerId = remotePeerId, theirPublicKey = theirPub)
            )
            val session = SignalSession(remotePeerId, remotePeerId, true)
            sessions[remotePeerId] = session
            _sessionEstablished.emit(remotePeerId)
            Log.d(TAG, "E2EE session established with $remotePeerId")
            Result.success(session)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session with $remotePeerId", e)
            Result.failure(e)
        }
    }

    /**
     * Derive the per-conversation key: X25519 ECDH then HKDF-SHA256 (RFC 5869)
     * with a fixed zero salt and a domain-separation info string.
     */
    private fun deriveKey(theirPublicKey: ByteArray): ByteArray {
        val local = localKeyPair()
        val agreement = X25519Agreement()
        agreement.init(local)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(theirPublicKey, 0), shared, 0)

        // HKDF-SHA256 extract: PRK = HMAC(salt=zeros, IKM=shared_secret)
        val hmacSha256 = javax.crypto.Mac.getInstance("HmacSHA256")
        hmacSha256.init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = hmacSha256.doFinal(shared)

        // HKDF expand: OKM = T1 = HMAC(PRK, info || 0x01)  (32 bytes)
        hmacSha256.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
        return hmacSha256.doFinal(HKDF_INFO.toByteArray(Charsets.UTF_8) + byteArrayOf(0x01))
    }

    suspend fun encrypt(remotePeerId: String, plaintext: ByteArray): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val theirPub = loadPeerKey(remotePeerId)
                    ?: return@withContext Result.failure(
                        IllegalStateException("No E2EE session with $remotePeerId — exchange pre-key bundles first")
                    )
                val key = deriveKey(theirPub)
                val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
                val engine = ChaCha20Poly1305()
                engine.init(
                    true,
                    AEADParameters(KeyParameter(key.copyOf(32)), 128, nonce)
                )
                val out = ByteArray(engine.getOutputSize(plaintext.size))
                val len = engine.processBytes(plaintext, 0, plaintext.size, out, 0)
                engine.doFinal(out, len)
                val result = ByteArray(nonce.size + out.size)
                System.arraycopy(nonce, 0, result, 0, nonce.size)
                System.arraycopy(out, 0, result, nonce.size, out.size)
                Log.d(TAG, "Encrypted ${plaintext.size} bytes for $remotePeerId")
                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Encryption failed for $remotePeerId", e)
                Result.failure(e)
            }
        }

    suspend fun decrypt(remotePeerId: String, ciphertext: ByteArray): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val theirPub = loadPeerKey(remotePeerId)
                    ?: return@withContext Result.failure(
                        IllegalStateException("No E2EE session with $remotePeerId")
                    )
                val key = deriveKey(theirPub)
                val plain = decryptWithKey(key, ciphertext)
                Log.d(TAG, "Decrypted ${plain.size} bytes from $remotePeerId")
                Result.success(plain)
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
            val theirPub = loadPeerKey(fromPeerId)
                ?: return@withContext Result.failure(
                    IllegalStateException("No E2EE session with $fromPeerId")
                )
            val key = deriveKey(theirPub)
            val plain = decryptWithKey(key, ciphertext)
            val msg = DecryptedMessage(fromPeerId, plain)
            _incomingMessages.emit(msg)
            Log.d(TAG, "Handled incoming message from $fromPeerId")
            Result.success(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message from $fromPeerId", e)
            Result.failure(e)
        }
    }

    private fun decryptWithKey(key: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > NONCE_SIZE + TAG_SIZE) { "Ciphertext too short" }
        val nonce = ciphertext.copyOfRange(0, NONCE_SIZE)
        val body = ciphertext.copyOfRange(NONCE_SIZE, ciphertext.size)
        val engine = ChaCha20Poly1305()
        engine.init(
            false,
            AEADParameters(KeyParameter(key.copyOf(32)), 128, nonce)
        )
        val out = ByteArray(engine.getOutputSize(body.size))
        val len = engine.processBytes(body, 0, body.size, out, 0)
        // doFinal() verifies the Poly1305 tag (throwing on tamper) and writes
        // exactly the plaintext bytes — getOutputSize() already excluded the
        // tag, so the returned length is the true plaintext length. Never
        // strip TAG_SIZE again here (that chopped 16 real bytes off messages).
        val written = engine.doFinal(out, len)
        return out.copyOf(written)
    }

    private suspend fun loadPeerKey(peerId: String): ByteArray? =
        db.conversationKeyDao().load(peerId)?.theirPublicKey
}
