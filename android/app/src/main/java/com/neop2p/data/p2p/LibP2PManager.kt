package com.neop2p.data.p2p

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub libp2p manager.
 *
 * The java-libp2p dependency version in this project does not match the API surface
 * used by the original code (KeyKt, Host.builder, TcpTransport, ProtocolBinding, etc.).
 * This stub keeps the DI graph and UI compiling. The real P2P networking layer needs
 * either a compatible java-libp2p version or a different P2P library.
 */
@Singleton
class LibP2PManager @Inject constructor(
    private val identityManager: IdentityManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "LibP2PManager"
    }

    data class ConnectionState(
        val isRunning: Boolean = false,
        val peerId: String = "",
        val connectedPeers: Int = 0,
        val listenAddresses: List<String> = emptyList()
    )

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var scope: CoroutineScope? = null

    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val identity = identityManager.getOrCreateIdentity()
            _connectionState.value = ConnectionState(
                isRunning = true,
                peerId = identity.peerId,
                connectedPeers = 0,
                listenAddresses = emptyList()
            )
            Log.d(TAG, "libp2p stub started: ${identity.peerId}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start libp2p stub", e)
            Result.failure(e)
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            scope?.cancel()
            _connectionState.value = ConnectionState()
            Log.d(TAG, "libp2p stub stopped")
        }
    }

    suspend fun openChatStream(peerId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        Result.failure(Exception("libp2p networking is stubbed"))
    }

    suspend fun openKeyExchangeStream(peerId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        Result.failure(Exception("libp2p networking is stubbed"))
    }

    suspend fun subscribeToTopic(topic: String, onMessage: (ByteArray) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            Result.failure(Exception("GossipSub is stubbed"))
        }

    suspend fun publishToTopic(topic: String, data: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            Result.failure(Exception("GossipSub is stubbed"))
        }

    suspend fun findPeer(peerId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        Result.success(emptyList())
    }
}
