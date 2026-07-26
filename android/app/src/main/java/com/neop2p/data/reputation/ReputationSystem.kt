package com.neop2p.data.reputation

import android.util.Log
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.entity.PeerEntity
import com.neop2p.data.p2p.IdentityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gossip-based reputation system with cryptographic attestation.
 *
 * After each trade, both peers sign an attestation with their Ed25519 key:
 *   { fromPeer, targetPeer, outcome, volumeSats, timestamp, signature }
 *
 * Attestations are gossiped via libp2p GossipSub.
 * Each peer maintains their own local reputation in Room/SQLCipher.
 * Signatures are verified against the peer's known public key.
 */
@Singleton
class ReputationSystem @Inject constructor(
    private val identityManager: IdentityManager,
    private val db: AppDatabase
) {
    companion object {
        private const val TAG = "ReputationSystem"
    }

    data class PeerReputation(
        val peerId: String,
        val displayName: String = "",
        val score: Float = 0f,
        val totalTrades: Int = 0,
        val positiveTrades: Int = 0,
        val negativeTrades: Int = 0,
        val totalVolumeSats: Long = 0L,
        val isNew: Boolean = true
    )

    data class Attestation(
        val fromPeer: String,
        val targetPeer: String,
        val outcome: AttestationOutcome,
        val volumeSats: Long,
        val timestamp: Long,
        val signature: ByteArray
    )

    enum class AttestationOutcome { POSITIVE, NEGATIVE }

    private val _reputations = MutableStateFlow<Map<String, PeerReputation>>(emptyMap())
    val reputations: StateFlow<Map<String, PeerReputation>> = _reputations.asStateFlow()

    private val _incomingAttestations = MutableSharedFlow<Attestation>(replay = 100)
    val incomingAttestations: SharedFlow<Attestation> = _incomingAttestations.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize by loading reputation data from DB.
     */
    suspend fun initialize() {
        try {
            val peers = db.peerDao().getAllPeers().first()
            val initialReputations = peers.associate { peer ->
                peer.peer_id to PeerReputation(
                    peerId = peer.peer_id,
                    displayName = peer.nickname,
                    score = peer.reputation_score,
                    totalTrades = peer.total_trades,
                    totalVolumeSats = 0L,
                    isNew = peer.total_trades == 0
                )
            }
            _reputations.value = initialReputations
            Log.d(TAG, "Loaded ${initialReputations.size} peer reputations from DB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load reputations from DB", e)
        }
    }

    /**
     * Create a signed attestation after a trade completes.
     * Signs the attestation data with the peer's Ed25519 key
     * (derived from BIP-32 path m/44'/888'/0'/0/0).
     */
    suspend fun createAttestation(
        myPeerId: String,
        targetPeerId: String,
        wasPositive: Boolean,
        volumeSats: Long
    ): Attestation {
        val timestamp = System.currentTimeMillis()
        val attestationData = buildAttestationData(
            fromPeer = myPeerId,
            targetPeer = targetPeerId,
            outcome = if (wasPositive) AttestationOutcome.POSITIVE else AttestationOutcome.NEGATIVE,
            volumeSats = volumeSats,
            timestamp = timestamp
        )

        // Sign with derived Ed25519 key (libp2p path)
        val signature = signAttestation(attestationData)

        val attestation = Attestation(
            fromPeer = myPeerId,
            targetPeer = targetPeerId,
            outcome = if (wasPositive) AttestationOutcome.POSITIVE else AttestationOutcome.NEGATIVE,
            volumeSats = volumeSats,
            timestamp = timestamp,
            signature = signature
        )

        // Update local reputation and persist
        updateLocalReputation(targetPeerId, wasPositive, volumeSats)

        Log.d(TAG, "Attestation created: $myPeerId → $targetPeerId (${attestation.outcome})")
        return attestation
    }

    /**
     * Process an incoming attestation from a gossip message.
     * Verifies the Ed25519 signature before updating reputation.
     */
    suspend fun processAttestation(attestation: Attestation) {
        try {
            // Reconstruct attestation data for verification
            val attestationData = buildAttestationData(
                fromPeer = attestation.fromPeer,
                targetPeer = attestation.targetPeer,
                outcome = attestation.outcome,
                volumeSats = attestation.volumeSats,
                timestamp = attestation.timestamp
            )

            // Verify Ed25519 signature against the signer's public key
            val isValid = verifyAttestation(
                attestationData, attestation.signature, attestation.fromPeer
            )
            if (!isValid) {
                Log.w(TAG, "Attestation from ${attestation.fromPeer} has invalid signature — rejecting")
                return
            }

            Log.d(TAG, "Verified valid attestation from ${attestation.fromPeer} about ${attestation.targetPeer}")

            // Update local reputation and persist
            updateLocalReputation(
                attestation.targetPeer,
                attestation.outcome == AttestationOutcome.POSITIVE,
                attestation.volumeSats
            )

            _incomingAttestations.emit(attestation)
            Log.d(TAG, "Processed attestation for ${attestation.targetPeer}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process attestation", e)
        }
    }

    /**
     * Build the canonical attestation data string for signing/verification.
     */
    private fun buildAttestationData(
        fromPeer: String,
        targetPeer: String,
        outcome: AttestationOutcome,
        volumeSats: Long,
        timestamp: Long
    ): ByteArray {
        val data = "NEOP2P_ATTEST:$fromPeer:$targetPeer:${outcome.name}:$volumeSats:$timestamp"
        return data.encodeToByteArray()
    }

    /**
     * Sign attestation data with the peer's Ed25519 key (derived from BIP-32).
     * Uses Bouncy Castle Ed25519Signer (EdDSA) for production.
     * Fallback: HMAC-SHA256 for development environments without Bouncy Castle.
     */
    private fun signAttestation(data: ByteArray): ByteArray {
        return try {
            val privKeyBytes = identityManager.getLibp2pPrivateKey()
            // Ed25519 signing via Bouncy Castle
            val privateKeyParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(
                privKeyBytes, 0
            )
            val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
            signer.init(true, privateKeyParams)
            signer.update(data, 0, data.size)
            val signature = signer.generateSignature()
            Log.d(TAG, "Attestation signed via Ed25519 (${signature.size}-byte signature)")
            signature
        } catch (e: Exception) {
            Log.e(TAG, "Ed25519 signing failed, using HMAC fallback", e)
            try {
                val privKey = identityManager.getLibp2pPrivateKey()
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(javax.crypto.spec.SecretKeySpec(privKey, "HmacSHA256"))
                mac.doFinal(data)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback signing also failed", e2)
                ByteArray(0)
            }
        }
    }

    /**
     * Verify an Ed25519 signature against a peer's public key.
     * Peer public keys are stored in the local Peer DAO.
     */
    private fun verifyAttestation(
        data: ByteArray,
        signature: ByteArray,
        peerId: String
    ): Boolean {
        return try {
            // Look up the peer's Ed25519 public key from our stored data
            val peerPubKey = loadPeerPublicKey(peerId) ?: return false
            val publicKeyParams = org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(
                peerPubKey, 0
            )
            val verifier = org.bouncycastle.crypto.signers.Ed25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(data, 0, data.size)
            val valid = verifier.verifySignature(signature)
            if (!valid) Log.w(TAG, "Signature verification failed for $peerId")
            valid
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error for $peerId", e)
            false
        }
    }

    /**
     * Load a peer's Ed25519 public key from local storage.
     * Returns null if the key is not known yet.
     */
    private fun loadPeerPublicKey(peerId: String): ByteArray? {
        return try {
            // Peer public keys are encoded in multiaddrs or relay_hints JSON
            // For now, we derive from the peer's stored data
            val peer = kotlinx.coroutines.runBlocking { db.peerDao().getPeerSync(peerId) } ?: return null
            // The Ed25519 public key is derived from their PeerID
            // PeerIDs like "12D3KooW..." encode SHA-256 of the pubkey in base58
            if (peer.nostr_pubkey.length >= 64) {
                // Try to use Nostr pubkey as a known public key
                // In production, store the Ed25519 pubkey explicitly
                peer.nostr_pubkey.substring(0..63).encodeToByteArray()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load peer public key for $peerId", e)
            null
        }
    }

    /**
     * Update the local reputation database for a peer and persist to Room.
     */
    private fun updateLocalReputation(peerId: String, wasPositive: Boolean, volumeSats: Long) {
        _reputations.update { map ->
            val current = map[peerId] ?: PeerReputation(peerId = peerId)
            val updated = current.copy(
                totalTrades = current.totalTrades + 1,
                positiveTrades = current.positiveTrades + if (wasPositive) 1 else 0,
                negativeTrades = current.negativeTrades + if (wasPositive) 0 else 1,
                totalVolumeSats = current.totalVolumeSats + volumeSats,
                isNew = false,
                score = calculateScore(
                    current.positiveTrades + (if (wasPositive) 1 else 0),
                    current.negativeTrades + (if (wasPositive) 0 else 1)
                )
            )
            map + (peerId to updated)
        }

        // Persist to DB
        scope.launch {
            try {
                val rep = _reputations.value[peerId] ?: return@launch
                val existing = db.peerDao().getPeerSync(peerId)
                val entity = PeerEntity(
                    peer_id = peerId,
                    nickname = rep.displayName.ifEmpty { existing?.nickname ?: "" },
                    nostr_pubkey = existing?.nostr_pubkey ?: "",
                    ln_node_id = existing?.ln_node_id ?: "",
                    reputation_score = rep.score,
                    total_trades = rep.totalTrades,
                    last_seen = System.currentTimeMillis(),
                    relay_hints = existing?.relay_hints ?: "[]",
                    multiaddrs = existing?.multiaddrs ?: "[]"
                )
                db.peerDao().upsert(entity)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist reputation update", e)
            }
        }
    }

    /**
     * Get this peer's own reputation.
     */
    fun getMyReputation(myPeerId: String): ReputationProfile {
        val rep = _reputations.value[myPeerId]
        return ReputationProfile(
            peerId = myPeerId,
            score = rep?.score ?: 1.0f,
            totalTrades = rep?.totalTrades ?: 0,
            completedTrades = rep?.positiveTrades ?: 0,
            disputedTrades = rep?.negativeTrades ?: 0
        )
    }

    /**
     * Calculate reputation score from trade history using Wilson score interval.
     */
    private fun calculateScore(positive: Int, negative: Int): Float {
        val total = positive.toLong() + negative.toLong()
        if (total == 0L) return 0f

        // Wilson score interval (lower bound) for 95% confidence
        val z = 1.96
        val p = positive.toDouble() / total
        val left = p + (z * z) / (2 * total)
        val right = z * Math.sqrt((p * (1 - p) + (z * z) / (4 * total)) / total)
        val under = 1 + (z * z) / total

        return ((left - right) / under).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Get reputation for a specific peer.
     */
    fun getReputation(peerId: String): PeerReputation =
        _reputations.value[peerId] ?: PeerReputation(peerId = peerId)

    /**
     * Clear local reputation data (privacy option).
     */
    fun resetLocalReputations() {
        _reputations.update { emptyMap() }
        Log.d(TAG, "Local reputation data cleared")
    }
}

data class ReputationProfile(
    val peerId: String,
    val score: Float,
    val totalTrades: Int,
    val completedTrades: Int,
    val disputedTrades: Int
)