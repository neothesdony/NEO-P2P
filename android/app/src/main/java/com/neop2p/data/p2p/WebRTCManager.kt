package com.neop2p.data.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub WebRTC manager.
 *
 * The Stream WebRTC SDK version in this project does not match the API surface
 * used by the original code. This stub keeps the DI graph and UI compiling.
 */
@Singleton
class WebRTCManager @Inject constructor() {
    companion object {
        private const val TAG = "WebRTCManager"
    }

    data class WebRTCState(
        val isConnected: Boolean = false,
        val peerId: String = "",
        val connectionState: String = "disconnected"
    )

    data class ReceivedFile(
        val fromPeerId: String,
        val fileName: String,
        val data: ByteArray,
        val mimeType: String
    )

    private val _state = MutableStateFlow(WebRTCState())
    val state: StateFlow<WebRTCState> = _state.asStateFlow()

    private val _receivedFiles = MutableSharedFlow<ReceivedFile>(replay = 0)
    val receivedFiles: SharedFlow<ReceivedFile> = _receivedFiles.asSharedFlow()

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "WebRTC stub initialized")
        Result.success(Unit)
    }

    suspend fun createPeerConnection(peerId: String, isOfferer: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            _state.update { it.copy(isConnected = false, peerId = peerId, connectionState = "stub") }
            Log.d(TAG, "Peer connection stub for $peerId")
            Result.success(Unit)
        }

    suspend fun sendFile(peerId: String, fileName: String, data: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "File send stub: $fileName (${data.size} bytes) to $peerId")
            Result.failure(Exception("WebRTC file transfer is stubbed"))
        }

    suspend fun close() {
        withContext(Dispatchers.IO) {
            _state.update { WebRTCState() }
            Log.d(TAG, "WebRTC stub closed")
        }
    }
}
