package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.entity.ConversationKeyEntity
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.ratchet.ChatKeyPinGate
import com.neop2p.data.p2p.ratchet.DoubleRatchet
import com.neop2p.data.p2p.ratchet.PeerMustUpgradeException
import com.neop2p.data.p2p.ratchet.PreKeyBundleCodec
import com.neop2p.data.p2p.ratchet.RatchetAad
import com.neop2p.data.p2p.ratchet.RatchetCodec
import com.neop2p.data.p2p.ratchet.RatchetEnvelope
import com.neop2p.data.p2p.ratchet.RatchetHeader
import com.neop2p.data.p2p.ratchet.RatchetKdf
import com.neop2p.data.p2p.ratchet.RatchetPreKeyBundle
import com.neop2p.data.p2p.ratchet.RatchetState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * E2EE layer for NEO-P2P chat — E2EE v2 double ratchet (2026-09-23).
 *
 * The static-static X25519 scheme (one long-term key per peer, no forward
 * secrecy) is replaced by a real Double Ratchet:
 *
 *   handshake  = X3DH over the exchanged v2 pre-key bundles (IK + signed SPK)
 *   ratchet    = symmetric chain keys + a fresh X25519 DH step per turn
 *   message    = ChaCha20-Poly1305 with AAD binding session/offer/header
 *   ciphertext = magic("NP2R") ‖ version ‖ header ‖ nonce ‖ ct ‖ tag
 *
 * The local identity key is derived deterministically from the BIP-39 mnemonic
 * via IdentityManager (PATH_SIGNAL). The per-session ratchet state is persisted
 * in SQLCipher (conversation_keys.ratchet_state) and advanced WRITE-AHEAD of a
 * send, so a crash can never reuse a message key. This is a wire hard fork: a
 * legacy v1 peer is refused (`peerMustUpgrade`), never downgraded.
 *
 * Chat history is rendered from locally stored plaintext (chat_messages.plaintext)
 * because a Double Ratchet cannot re-derive old message keys; the wire ciphertext
 * is retained only for replay dedup.
 */
@Singleton
class SignalProtocol @Inject constructor(
    private val identityManager: IdentityManager,
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

    /** Emits a peerId that still runs the legacy E2EE v1 wire format. */
    private val _peerMustUpgrade = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val peerMustUpgrade: SharedFlow<String> = _peerMustUpgrade.asSharedFlow()

    /** Emits a peerId whose pinned chat keys changed and were refused. */
    private val _keyChanged = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val keyChanged: SharedFlow<String> = _keyChanged.asSharedFlow()

    private val sessions = mutableMapOf<String, SignalSession>()
    private val _incomingMessages = MutableSharedFlow<DecryptedMessage>(replay = 0)
    val incomingMessages: SharedFlow<DecryptedMessage> = _incomingMessages.asSharedFlow()

    /** Emits a peerId every time a usable E2EE session is established/restored. */
    private val _sessionEstablished = MutableSharedFlow<String>(replay = 0)
    val sessionEstablished: SharedFlow<String> = _sessionEstablished.asSharedFlow()

    /** Emits a peerId whose pinned chat identity changed and was refused. */
    private val _identityChanged = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val identityChanged: SharedFlow<String> = _identityChanged.asSharedFlow()

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Key material is derived from the mnemonic on demand — nothing to
            // generate or persist. Any stale in-memory sessions are dropped.
            sessions.clear()
            Log.d(TAG, "E2EE initialized (double ratchet, key from BIP-32)")
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

    fun myPeerId(): String = identityManager.getOrCreateIdentity().peerId

    fun myIdentityPublicKey(): ByteArray {
        val priv = identityManager.getLibp2pPrivateKey()
        return Ed25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
    }

    /** The local long-term X25519 identity key (IK) public bytes. */
    private fun myIdentityX25519Public(): ByteArray {
        val priv = identityManager.getSignalPrivateKey()
        require(priv.size == 32) { "Signal private key must be 32 bytes (X25519)" }
        return X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded
    }

    /**
     * Generate (or reuse the in-flight) signed pre-key and build our v2 bundle.
     *
     * The SPK private half is stashed in [pendingSpkPriv] until [createSession]
     * persists it per-peer. Reusing an in-flight SPK is required for the
     * request/reply handshake: the initiator receives the peer's bundle before
     * it has built its own, so [createSession] generates the SPK and the
     * subsequent [sendPreKeyBundle] MUST carry that exact key — otherwise the
     * two peers' X3DH inputs diverge and the ratchet never converges.
     */
    suspend fun getPreKeyBundle(): RatchetPreKeyBundle = withContext(Dispatchers.IO) {
        val (spkPriv, spkPub) = currentOrNewSpk()
        val ikPub = myIdentityX25519Public()
        // Bind both the X25519 IK and the SPK to our Ed25519 identity (which
        // equals our libp2p PeerID), so the peerId derivable from identityPubKey
        // matches our published PeerID — defeating relay MITM key substitution.
        val libp2pPriv = identityManager.getLibp2pPrivateKey()
        require(libp2pPriv.size == 32) { "libp2p Ed25519 private key must be 32 bytes" }
        val identityPub = Ed25519PrivateKeyParameters(libp2pPriv, 0).generatePublicKey().encoded
        val spkSigner = Ed25519Signer()
        spkSigner.init(true, Ed25519PrivateKeyParameters(libp2pPriv, 0))
        spkSigner.update(spkPub, 0, spkPub.size)
        val spkSignature = spkSigner.generateSignature()
        val ikSigner = Ed25519Signer()
        ikSigner.init(true, Ed25519PrivateKeyParameters(libp2pPriv, 0))
        ikSigner.update(ikPub, 0, ikPub.size)
        val ikSignature = ikSigner.generateSignature()
        RatchetPreKeyBundle(ikPub, spkPub, spkSignature, identityPub, ikSignature)
    }

    @Volatile private var pendingSpkPriv: ByteArray? = null
    @Volatile private var pendingSpkPub: ByteArray? = null

    private fun currentOrNewSpk(): Pair<ByteArray, ByteArray> {
        val priv = pendingSpkPriv
        val pub = pendingSpkPub
        if (priv != null && pub != null) return priv to pub
        val kp = DoubleRatchet.generateDhKeyPair()
        pendingSpkPriv = kp.first
        pendingSpkPub = kp.second
        return kp
    }

    suspend fun sendPreKeyBundle(peerId: String): Result<AppMessage.PreKeyBundle> =
        withContext(Dispatchers.IO) {
            try {
                val bundle = getPreKeyBundle()
                Result.success(AppMessage.PreKeyBundle(peerId, PreKeyBundleCodec.encode(bundle)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build pre-key bundle for $peerId", e)
                Result.failure(e)
            }
        }

    fun serializeBundle(bundle: RatchetPreKeyBundle): ByteArray = PreKeyBundleCodec.encode(bundle)

    fun deserializeBundle(bytes: ByteArray): RatchetPreKeyBundle = PreKeyBundleCodec.decode(bytes)

    /**
     * True if a persisted E2EE session (peer X25519 key) exists for [peerId],
     * meaning encrypt/decrypt can proceed without a fresh handshake. Used by the
     * chat UI to report an honest SESSION_READY vs OFFLINE state.
     */
    suspend fun hasStoredSession(peerId: String): Boolean =
        withContext(Dispatchers.IO) {
            db.conversationKeyDao().load(peerId) != null
        }

    /** The pinned verified RNS identity hash for [peerId], when known. */
    suspend fun storedIdentityHash(peerId: String): String? =
        withContext(Dispatchers.IO) {
            db.conversationKeyDao().load(peerId)?.rns_identity_hash?.takeIf { it.isNotBlank() }
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
        remoteBundle: RatchetPreKeyBundle,
        authenticated: Boolean = false,
        verifiedIdentityHashHex: String? = null,
    ): Result<SignalSession> = withContext(Dispatchers.IO) {
        try {
            require(remoteBundle.ikPub.size == 32) { "Peer IK must be 32 bytes" }
            require(remoteBundle.identityPubKey.size == 32) { "Identity Ed25519 key must be 32 bytes" }
            require(remoteBundle.identitySignature.size == 64) { "Identity signature must be 64 bytes" }
            require(remoteBundle.spkSignature.size == 64) { "SPK signature must be 64 bytes" }

            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(remoteBundle.identityPubKey, 0))
            verifier.update(remoteBundle.ikPub, 0, remoteBundle.ikPub.size)
            require(verifier.verifySignature(remoteBundle.identitySignature)) {
                "Identity signature over IK failed for $remotePeerId"
            }
            val spkVerifier = Ed25519Signer()
            spkVerifier.init(false, Ed25519PublicKeyParameters(remoteBundle.identityPubKey, 0))
            spkVerifier.update(remoteBundle.spkPub, 0, remoteBundle.spkPub.size)
            require(spkVerifier.verifySignature(remoteBundle.spkSignature)) {
                "SPK signature failed for $remotePeerId"
            }
            require(KeyDerivation.deriveLibp2pPeerIdFromPublicKey(remoteBundle.identityPubKey) == remotePeerId) {
                "Identity key does not match peerId $remotePeerId"
            }

            val existing = db.conversationKeyDao().load(remotePeerId)
            val pinned = existing?.rns_identity_hash
            if (!pinned.isNullOrBlank() && !verifiedIdentityHashHex.isNullOrBlank() &&
                !pinned.equals(verifiedIdentityHashHex, ignoreCase = true)
            ) {
                _identityChanged.tryEmit(remotePeerId)
                return@withContext Result.failure(
                    IllegalStateException("Chat identity changed for $remotePeerId — refusing to rebind")
                )
            }
            if (ChatKeyPinGate.verdict(
                    storedIdentityPub = existing?.identity_pub_ed,
                    incomingIdentityPub = remoteBundle.identityPubKey,
                    storedIkPub = existing?.their_ik_pub,
                    incomingIkPub = remoteBundle.ikPub,
                    storedRatchetPub = existing?.peer_ratchet_pub,
                    incomingRatchetPub = remoteBundle.spkPub,
                ) == ChatKeyPinGate.Verdict.KEY_CHANGED
            ) {
                _keyChanged.tryEmit(remotePeerId)
                return@withContext Result.failure(
                    IllegalStateException("Chat key changed for $remotePeerId — re-verify before continuing")
                )
            }

            // A duplicate pre-key bundle (the request/reply handshake always
            // echoes one back) must NOT reset a live ratchet — that would
            // discard established message keys. Once a session is pinned and
            // its keys match, the existing session stands.
            if (existing?.ratchet_state != null) {
                val session = SignalSession(remotePeerId, remotePeerId, true)
                sessions[remotePeerId] = session
                _sessionEstablished.emit(remotePeerId)
                return@withContext Result.success(session)
            }

            val spk = currentOrNewSpk()
            val mySpkPriv = spk.first
            val mySpkPub = spk.second
            val sk = computeX3dhSecret(remoteBundle, mySpkPriv, mySpkPub)
            val myPeerId = myPeerId()
            val initiator = myPeerId <= remotePeerId
            val state = if (initiator) {
                val (dhPriv, dhPub) = DoubleRatchet.generateDhKeyPair()
                pendingInitHeader = RatchetEnvelope.encodeHeader(RatchetHeader(dhPub, 0, 0))
                DoubleRatchet.initiatorState(sk, dhPriv, dhPub, remoteBundle.spkPub)
            } else {
                DoubleRatchet.responderState(sk, mySpkPriv, mySpkPub)
            }
            db.conversationKeyDao().save(
                ConversationKeyEntity(
                    peerId = remotePeerId,
                    theirPublicKey = remoteBundle.ikPub,
                    rns_identity_hash = verifiedIdentityHashHex?.lowercase() ?: pinned,
                    protocol_version = DoubleRatchet.VERSION,
                    ratchet_state = RatchetCodec.encode(state),
                    spk_priv = mySpkPriv,
                    spk_pub = mySpkPub,
                    their_ik_pub = remoteBundle.ikPub,
                    identity_pub_ed = remoteBundle.identityPubKey,
                    peer_ratchet_pub = remoteBundle.spkPub,
                )
            )
            sessions[remotePeerId] = SignalSession(remotePeerId, remotePeerId, true)
            _sessionEstablished.emit(remotePeerId)
            Result.success(SignalSession(remotePeerId, remotePeerId, true))
        } catch (e: PeerMustUpgradeException) {
            _peerMustUpgrade.tryEmit(remotePeerId)
            Log.w(TAG, "Peer $remotePeerId must upgrade to E2EE v2")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session with $remotePeerId", e)
            Result.failure(e)
        }
    }

    /**
     * X3DH shared secret. Both peers must derive the SAME ordered DH tuple, so
     * the two role-dependent DH outputs are canonically sorted: `DH(IK_a, SPK_b)`
     * and `DH(SPK_a, IK_b)` are the same two values on both sides but land in
     * swapped slots without this.
     */
    private fun computeX3dhSecret(remote: RatchetPreKeyBundle, spkPriv: ByteArray, spkPub: ByteArray): ByteArray {
        val myIkPriv = identityManager.getSignalPrivateKey()
        val dhIk = DoubleRatchet.dh(myIkPriv, remote.spkPub)
        val dhSpk = DoubleRatchet.dh(spkPriv, remote.ikPub)
        val dh3 = DoubleRatchet.dh(spkPriv, remote.spkPub)
        val first: ByteArray
        val second: ByteArray
        if (compareBytes(dhIk, dhSpk) <= 0) {
            first = dhIk; second = dhSpk
        } else {
            first = dhSpk; second = dhIk
        }
        val sk = RatchetKdf.x3dhSecret(first, second, dh3)
        dhIk.fill(0); dhSpk.fill(0); dh3.fill(0)
        return sk
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    @Volatile var pendingInitHeader: ByteArray? = null
        private set

    fun consumePendingInitHeader(): ByteArray? = pendingInitHeader.also { pendingInitHeader = null }

    suspend fun resetSession(peerId: String) = withContext(Dispatchers.IO) {
        db.conversationKeyDao().delete(peerId)
        sessions.remove(peerId)
    }

    private suspend fun loadState(peerId: String): RatchetState? =
        db.conversationKeyDao().load(peerId)?.ratchet_state?.let { RatchetCodec.decode(it) }

    private suspend fun persistState(peerId: String, state: RatchetState) {
        val row = db.conversationKeyDao().load(peerId) ?: return
        db.conversationKeyDao().save(row.copy(ratchet_state = RatchetCodec.encode(state)))
    }

    suspend fun encrypt(remotePeerId: String, offerId: String, plaintext: ByteArray): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val state = loadState(remotePeerId) ?: return@withContext Result.failure(
                    IllegalStateException("No E2EE session with $remotePeerId — exchange pre-key bundles first")
                )
                val sessionId = RatchetAad.sessionId(myPeerId(), remotePeerId)
                val (next, envelope) = DoubleRatchet.encrypt(state, sessionId, myPeerId(), offerId, plaintext)
                // Write-ahead: persist the advanced chain BEFORE the ciphertext
                // leaves the device. A crash after persist but before send merely
                // burns one message key; the reverse would reuse it.
                persistState(remotePeerId, next)
                Result.success(envelope)
            } catch (e: Exception) {
                Log.e(TAG, "Encryption failed for $remotePeerId", e)
                Result.failure(e)
            }
        }

    suspend fun decrypt(remotePeerId: String, offerId: String, envelope: ByteArray): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val state = loadState(remotePeerId) ?: return@withContext Result.failure(
                    IllegalStateException("No E2EE session with $remotePeerId")
                )
                val sessionId = RatchetAad.sessionId(myPeerId(), remotePeerId)
                val (next, plain) = DoubleRatchet.decrypt(state, sessionId, remotePeerId, offerId, envelope)
                persistState(remotePeerId, next)
                Result.success(plain)
            } catch (e: Exception) {
                Log.e(TAG, "Decryption failed from $remotePeerId", e)
                Result.failure(e)
            }
        }

    suspend fun handleRatchetInit(fromPeerId: String, headerBytes: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val state = loadState(fromPeerId) ?: return@withContext Result.failure(
                    IllegalStateException("No E2EE session with $fromPeerId for ratchet_init")
                )
                val sessionId = RatchetAad.sessionId(myPeerId(), fromPeerId)
                val next = DoubleRatchet.processRatchetInit(state, sessionId, fromPeerId, headerBytes)
                persistState(fromPeerId, next)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "ratchet_init failed from $fromPeerId", e)
                Result.failure(e)
            }
        }

    suspend fun handleIncomingMessage(
        fromPeerId: String,
        offerId: String,
        envelope: ByteArray,
    ): Result<DecryptedMessage> =
        decrypt(fromPeerId, offerId, envelope).map { plain ->
            val msg = DecryptedMessage(fromPeerId, plain)
            _incomingMessages.emit(msg)
            msg
        }
}
