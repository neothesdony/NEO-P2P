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
     * Sign attestation data with the peer's Nostr (BIP-340 Schnorr / secp256k1)
     * key derived from the BIP-32 identity. Verification uses the peer's
     * x-only secp256k1 public key, which is exactly what is persisted in
     * `PeerEntity.nostr_pubkey` (64 hex chars = 32 bytes).
     *
     * NOTE: this was previously signed with the libp2p Ed25519 key but verified
     * against the Nostr secp256k1 pubkey bytes — a type mismatch that made every
     * remote attestation fail silently. Now sign+verify use the same key type.
     */
    private fun signAttestation(data: ByteArray): ByteArray {
        return try {
            val keyPair = identityManager.getNostrKeyPair()
            val privKey = hexToBytes(keyPair.privateKeyHex)
            val auxRand = java.security.SecureRandom().generateSeed(32)
            val signature = com.neop2p.data.p2p.Schnorr.sign(privKey, data, auxRand)
            Log.d(TAG, "Attestation signed via BIP-340 Schnorr (${signature.size}-byte signature)")
            signature
        } catch (e: Exception) {
            Log.e(TAG, "Attestation signing failed", e)
            ByteArray(0)
        }
    }

    /**
     * Verify a BIP-340 Schnorr signature against a peer's Nostr public key.
     * Peer public keys are stored in the local Peer DAO as `nostr_pubkey`
     * (x-only 32-byte hex), which is exactly what [Schnorr.verify] expects.
     */
    private fun verifyAttestation(
        data: ByteArray,
        signature: ByteArray,
        peerId: String
    ): Boolean {
        return try {
            val peerPubKey = loadPeerPublicKey(peerId) ?: return false
            val valid = com.neop2p.data.p2p.Schnorr.verify(peerPubKey, data, signature)
            if (!valid) Log.w(TAG, "Attestation signature verification failed for $peerId")
            valid
        } catch (e: Exception) {
            Log.e(TAG, "Attestation signature verification error for $peerId", e)
            false
        }
    }

    /**
     * Load a peer's Nostr x-only secp256k1 public key from local storage.
     * Returns null if the key is not known yet (hex length != 64).
     */
    private fun loadPeerPublicKey(peerId: String): ByteArray? {
        return try {
            val peer = kotlinx.coroutines.runBlocking { db.peerDao().getPeerSync(peerId) } ?: return null
            val hexKey = peer.nostr_pubkey
            if (hexKey.length == 64) {
                hexToBytes(hexKey)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load peer public key for $peerId", e)
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
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