package com.neop2p.data.p2p

import android.util.Log
import com.neop2p.BuildConfig
import com.neop2p.NeoP2PConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real WebRTC manager using Stream WebRTC SDK (wraps Google WebRTC).
 *
 * Handles peer-to-peer data channels for file transfer and payment proofs.
 * Signaling (SDP offer/answer + ICE candidates) is exchanged over the libp2p
 * transport via [WebRTCSignalCodec] framing.
 */
@Singleton
class WebRTCManager @Inject constructor(
    private val p2pTransport: HybridP2PTransport
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val DATA_CHANNEL_LABEL = "neop2p-file-transfer"
        private const val SIGNAL_TOPIC = "webrtc-signal"
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
    private var signalJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    /**
     * Create a peer connection and start listening for signaling messages
     * from [peerId] over the libp2p transport.
     */
    suspend fun createPeerConnection(peerId: String, isOfferer: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val iceServers = NeoP2PConfig.TURN_SERVERS.mapNotNull { server ->
                    val builder = PeerConnection.IceServer.builder(server.uri)
                    if (server.username != null && server.credential != null) {
                        builder.setUsername(server.username).setPassword(server.credential)
                    }
                    builder.createIceServer()
                }

                val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
                rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL
                rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

                peerConnection = peerConnectionFactory?.createPeerConnection(
                    rtcConfig,
                    object : PeerConnection.Observer {
                        override fun onIceCandidate(candidate: IceCandidate?) {
                            candidate ?: return
                            Log.d(TAG, "ICE candidate: ${candidate.sdp}")
                            // Send candidate to the remote peer over libp2p
                            val payload = WebRTCSignalCodec.encodeIceCandidate(
                                candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex
                            )
                            scope.launch { p2pTransport.send(peerId, payload, SIGNAL_TOPIC) }
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
                            sdp ?: return
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    Log.d(TAG, "Local description set")
                                    // Send offer to the remote peer over libp2p
                                    val payload = WebRTCSignalCodec.encodeOffer(sdp.description)
                                    scope.launch { p2pTransport.send(peerId, payload, SIGNAL_TOPIC) }
                                }
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

                // Listen for signaling messages from the remote peer
                signalJob?.cancel()
                signalJob = scope.launch {
                    p2pTransport.incomingMessages
                        .filter { it.type == SIGNAL_TOPIC && it.fromPeerId == peerId }
                        .collect { env ->
                            handleSignal(env.data)
                        }
                }

                _state.update { it.copy(peerId = peerId, connectionState = "connecting") }
                Log.d(TAG, "Peer connection created for $peerId (offerer=$isOfferer)")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create peer connection", e)
                Result.failure(e)
            }
        }

    /**
     * Handle an inbound signaling message: remote SDP offer/answer or ICE candidate.
     */
    private fun handleSignal(data: ByteArray) {
        WebRTCSignalCodec.decodeOffer(data)?.let { sdp ->
            Log.d(TAG, "Received remote offer")
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set")
                    val constraints = MediaConstraints()
                    peerConnection?.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            sdp ?: return
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onSetSuccess() {
                                    Log.d(TAG, "Answer set")
                                    val payload = WebRTCSignalCodec.encodeAnswer(sdp.description)
                                    scope.launch { p2pTransport.send(_state.value.peerId, payload, SIGNAL_TOPIC) }
                                }
                                override fun onSetFailure(msg: String?) { Log.e(TAG, "Set answer failed: $msg") }
                                override fun onCreateSuccess(sdp: SessionDescription?) {}
                                override fun onCreateFailure(msg: String?) {}
                            }, sdp)
                        }
                        override fun onCreateFailure(msg: String?) { Log.e(TAG, "Create answer failed: $msg") }
                        override fun onSetSuccess() {}
                        override fun onSetFailure(msg: String?) {}
                    }, constraints)
                }
                override fun onSetFailure(msg: String?) { Log.e(TAG, "Set remote desc failed: $msg") }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(msg: String?) {}
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            return
        }

        WebRTCSignalCodec.decodeAnswer(data)?.let { sdp ->
            Log.d(TAG, "Received remote answer")
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() { Log.d(TAG, "Remote answer set") }
                override fun onSetFailure(msg: String?) { Log.e(TAG, "Set remote answer failed: $msg") }
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(msg: String?) {}
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            return
        }

        WebRTCSignalCodec.decodeIceCandidate(data)?.let { candidate ->
            Log.d(TAG, "Received ICE candidate: $candidate")
            peerConnection?.addIceCandidate(IceCandidate("", 0, candidate))
            return
        }
        Log.w(TAG, "Unknown signaling message (${data.size} bytes)")
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
            signalJob?.cancel()
            signalJob = null
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
