package com.neop2p.data.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real WebRTC manager using Stream WebRTC SDK (wraps Google WebRTC).
 *
 * Handles peer-to-peer data channels for file transfer and payment proofs.
 */
@Singleton
class WebRTCManager @Inject constructor() {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val DATA_CHANNEL_LABEL = "neop2p-file-transfer"
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

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(androidAppContext)
                .setFieldTrials("")
                .createInitializationOptions())

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()

            Log.d(TAG, "WebRTC initialized")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC", e)
            Result.failure(e)
        }
    }

    suspend fun createPeerConnection(peerId: String, isOfferer: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                    PeerConnection.IceServer.builder("turn:relay1.custom-minipc.com:3478")
                        .setUsername("neop2p")
                        .setPassword("changeme_debug")
                        .createIceServer()
                )

                val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
                rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL
                rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

                peerConnection = peerConnectionFactory?.createPeerConnection(
                    rtcConfig,
                    object : PeerConnection.Observer {
                        override fun onIceCandidate(candidate: IceCandidate?) {
                            Log.d(TAG, "ICE candidate: ${candidate?.sdp}")
                        }
                        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                            Log.d(TAG, "Signaling state: $state")
                        }
                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                            val connected = state == PeerConnection.IceConnectionState.CONNECTED
                            _state.update { it.copy(isConnected = connected, connectionState = state?.name ?: "unknown") }
                            Log.d(TAG, "ICE connection: $state")
                        }
                        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                        override fun onAddStream(stream: MediaStream?) {}
                        override fun onRemoveStream(stream: MediaStream?) {}
                        override fun onDataChannel(channel: DataChannel?) {
                            Log.d(TAG, "Data channel received: ${channel?.label()}")
                            if (channel?.label() == DATA_CHANNEL_LABEL) {
                                dataChannel = channel
                                setupDataChannel(channel)
                            }
                        }
                        override fun onRenegotiationNeeded() {}
                        override fun onAddTrack(track: RtpReceiver?, streams: Array<out MediaStream>?) {}
                    }
                )

                if (isOfferer) {
                    val init = DataChannel.Init()
                    init.ordered = true
                    dataChannel = peerConnection?.createDataChannel(DATA_CHANNEL_LABEL, init)
                    dataChannel?.let { setupDataChannel(it) }

                    val constraints = MediaConstraints()
                    peerConnection?.createOffer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() { Log.d(TAG, "Local description set") }
                                override fun onSetFailure(msg: String?) { Log.e(TAG, "Set local desc failed: $msg") }
                                override fun onCreateSuccess(sdp: SessionDescription?) {}
                                override fun onCreateFailure(msg: String?) {}
                            }, sdp)
                        }
                        override fun onCreateFailure(msg: String?) { Log.e(TAG, "Create offer failed: $msg") }
                        override fun onSetSuccess() {}
                        override fun onSetFailure(msg: String?) {}
                    }, constraints)
                }

                _state.update { it.copy(peerId = peerId, connectionState = "connecting") }
                Log.d(TAG, "Peer connection created for $peerId (offerer=$isOfferer)")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create peer connection", e)
                Result.failure(e)
            }
    }

    private fun setupDataChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() { Log.d(TAG, "Data channel state: ${channel.state()}") }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                Log.d(TAG, "Data channel received ${data.size} bytes")
                val header = data.take(4).toByteArray()
                val fileNameLen = header[0].toInt() and 0xFF
                val fileName = data.drop(4).take(fileNameLen).toByteArray().toString(Charsets.UTF_8)
                val fileData = data.drop(4 + fileNameLen).toByteArray()
                CoroutineScope(Dispatchers.IO).launch {
                    _receivedFiles.emit(ReceivedFile(_state.value.peerId, fileName, fileData, "application/octet-stream"))
                }
            }
        })
    }

    suspend fun sendFile(peerId: String, fileName: String, data: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val channel = dataChannel ?: return@withContext Result.failure(Exception("Data channel not established"))
                val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
                val header = byteArrayOf(fileNameBytes.size.toByte())
                val payload = header + fileNameBytes + data
                val buffer = DataChannel.Buffer(java.nio.ByteBuffer.wrap(payload), false)
                if (channel.send(buffer)) {
                    Log.d(TAG, "Sent file: $fileName (${data.size} bytes)")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Data channel send failed"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send file", e)
                Result.failure(e)
            }
        }

    suspend fun close() {
        withContext(Dispatchers.IO) {
            dataChannel?.close()
            dataChannel = null
            peerConnection?.close()
            peerConnection = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            _state.update { WebRTCState() }
            Log.d(TAG, "WebRTC closed")
        }
    }
}

// Application context holder for WebRTC initialization
private lateinit var androidAppContext: android.content.Context
fun initWebRTCContext(context: android.content.Context) { androidAppContext = context }
