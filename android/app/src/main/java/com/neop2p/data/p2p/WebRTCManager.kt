package com.neop2p.data.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebRTC manager for direct P2P file transfers (payment proofs).
 *
 * Uses AOSP WebRTC (built into Android) for:
 * - Encrypted data channels for file transfer
 * - Low-latency direct connections when NAT allows
 * - STUN/TURN fallback for NAT traversal
 *
 * This is used ONLY for file transfers (payment proof screenshots).
 * Chat uses libp2p + Signal Protocol instead.
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

    private val _state = MutableStateFlow(WebRTCState())
    val state: StateFlow<WebRTCState> = _state.asStateFlow()

    private val _receivedFiles = MutableSharedFlow<ReceivedFile>(replay = 0)
    val receivedFiles: SharedFlow<ReceivedFile> = _receivedFiles.asSharedFlow()

    data class ReceivedFile(
        val fromPeerId: String,
        val fileName: String,
        val data: ByteArray,
        val mimeType: String
    )

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    /**
     * Initialize WebRTC with ICE servers (STUN/TURN).
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            iceServers = NeoP2PConfig.TURN_SERVERS.mapNotNull { config ->
                if (config.username != null && config.credential != null) {
                    PeerConnection.IceServer.builder(config.uri)
                        .setUsername(config.username)
                        .setPassword(config.credential)
                        .createIceServer()
                } else {
                    PeerConnection.IceServer.builder(config.uri).createIceServer()
                }
            }

            Log.d(TAG, "WebRTC initialized with ${iceServers.size} ICE servers")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC", e)
            Result.failure(e)
        }
    }

    /**
     * Create a peer connection for file transfer.
     */
    suspend fun createPeerConnection(
        peerId: String,
        isOfferer: Boolean
    ): Result<PeerConnection> = withContext(Dispatchers.IO) {
        try {
            val eglBase = EglBase.create()

            val config = PeerConnection.RTCConfiguration(iceServers)
            config.iceTransportsType = PeerConnection.IceTransportsType.RELAY  // Prefer relay for mobile
            config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE

            val factory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .createPeerConnectionFactory()

            val observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "ICE candidate: ${candidate.serverUrl}")
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onSignalingChange(state: SignalingState) {}
                override fun onIceConnectionChange(state: IceConnectionState) {
                    _state.update {
                        it.copy(connectionState = state.name)
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: IceGatheringState) {}

                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}

                override fun onDataChannel(channel: DataChannel) {
                    Log.d(TAG, "Data channel received")
                    setupDataChannel(channel, peerId)
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed")
                }

                @Override
                override fun onAddTrack(receiver: RtpReceiver, tracks: Array<out MediaStream>) {}
            }

            val connection = factory.createConnection(config, observer)
            peerConnection = connection

            if (isOfferer) {
                // Create data channel
                val init = DataChannel.Init().apply {
                    ordered = true
                    negotiated = false
                }
                dataChannel = connection.createDataChannel("neop2p-files", init)
                setupDataChannel(dataChannel!!, peerId)
            }

            _state.update { it.copy(isConnected = true, peerId = peerId) }
            Log.d(TAG, "Peer connection created for $peerId")
            Result.success(connection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create peer connection", e)
            Result.failure(e)
        }
    }

    /**
     * Setup data channel for incoming data.
     */
    private fun setupDataChannel(channel: DataChannel, peerId: String) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Log.d(TAG, "Data channel state: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)

                // Simple protocol: first 4 bytes = file name length, then name, then content
                scope.launch {
                    if (data.size > 4) {
                        val nameLen = ((data[0].toInt() and 0xFF) shl 24) or
                                ((data[1].toInt() and 0xFF) shl 16) or
                                ((data[2].toInt() and 0xFF) shl 8) or
                                (data[3].toInt() and 0xFF)
                        var offset = 4
                        if (data.size >= offset + nameLen + 1) {
                            val fileName = data.copyOfRange(offset, offset + nameLen).decodeToString()
                            offset += nameLen
                            val fileData = data.copyOfRange(offset, data.size)
                            val file = ReceivedFile(
                                fromPeerId = peerId,
                                fileName = fileName,
                                data = fileData,
                                mimeType = fileName.substringAfterLast('.', "application/octet-stream")
                            )
                            _receivedFiles.emit(file)
                            Log.d(TAG, "Received file: $fileName (${fileData.size} bytes)")
                        }
                    }
                }
            }
        })
    }

    /**
     * Send a file via WebRTC data channel.
     */
    suspend fun sendFile(peerId: String, fileName: String, data: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val channel = dataChannel ?: return@withContext Result.failure(
                    Exception("Data channel not available")
                )

                // Protocol: [4 bytes name length][name bytes][file bytes]
                val nameBytes = fileName.encodeToByteArray()
                val header = ByteArray(4).apply {
                    this[0] = ((nameBytes.size shr 24) and 0xFF).toByte()
                    this[1] = ((nameBytes.size shr 16) and 0xFF).toByte()
                    this[2] = ((nameBytes.size shr 8) and 0xFF).toByte()
                    this[3] = (nameBytes.size and 0xFF).toByte()
                }
                val message = header + nameBytes + data

                // Send in chunks if > 16KB (WebRTC message limit)
                val chunkSize = 16_000
                message.toList().chunked(chunkSize).forEach { chunk ->
                    val buf = ByteBuffer.allocateDirect(chunk.size)
                    buf.put(chunk.toByteArray())
                    buf.rewind()
                    channel.send(DataChannel.Buffer(buf, false))
                }

                Log.d(TAG, "Sent file: $fileName (${data.size} bytes) to $peerId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send file", e)
                Result.failure(e)
            }
        }

    /**
     * Close the connection.
     */
    suspend fun close() {
        withContext(Dispatchers.IO) {
            dataChannel?.close()
            peerConnection?.close()
            dataChannel = null
            peerConnection = null
            _state.update { WebRTCState() }
            Log.d(TAG, "WebRTC connection closed")
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
