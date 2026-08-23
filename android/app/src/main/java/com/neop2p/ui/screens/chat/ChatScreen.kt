package com.neop2p.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neop2p.R
import com.neop2p.data.p2p.*
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.protocol.EnvelopeCodec
import com.neop2p.data.p2p.queue.OfflineQueue
import com.neop2p.data.p2p.routing.ChatRouter
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    offerId: String,
    peerId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ChatViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Wire the real E2EE pipeline once the nav args are available.
    LaunchedEffect(peerId, offerId) {
        viewModel.setConversation(peerId, offerId)
    }

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.chat_with_peer)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.general_back)
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (val s = state) {
                    is ChatViewModel.UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is ChatViewModel.UiState.Error -> Text(
                        text = s.message,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    is ChatViewModel.UiState.Success -> ChatContent(
                        messages = s.data.messages,
                        sessionState = s.data.sessionState,
                        offerId = offerId,
                        peerId = peerId,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatContent(
    messages: List<ChatMessage>,
    sessionState: ChatViewModel.SessionState,
    offerId: String,
    peerId: String,
    viewModel: ChatViewModel
) {
    Column(Modifier.fillMaxSize()) {
        // Honest connection banner instead of silently proceeding.
        if (sessionState != ChatViewModel.SessionState.SESSION_READY) {
            val label = when (sessionState) {
                ChatViewModel.SessionState.CONNECTING -> "Connecting E2EE…"
                ChatViewModel.SessionState.OFFLINE -> "Peer offline — messages will queue"
                ChatViewModel.SessionState.ERROR -> "Session error"
                ChatViewModel.SessionState.SESSION_READY -> ""
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.messageId }) { message ->
                ChatMessageItem(
                    message = message,
                    isMine = message.senderPeerId == viewModel.myPeerId.value
                )
            }
        }

        var text by remember { mutableStateOf(viewModel.messageText.value) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { }) {
                Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.chat_cd_attach))
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    viewModel.updateMessageText(it)
                },
                label = { Text(stringResource(R.string.chat_message_input)) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(
                onClick = {
                    val trimmed = text.trim()
                    if (trimmed.isNotBlank()) {
                        viewModel.sendMessage(trimmed)
                        text = ""
                        viewModel.updateMessageText("")
                    }
                },
                enabled = text.trim().isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_cd_send))
            }
        }

    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    isMine: Boolean
) {
    val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            color = if (isMine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = message.timeAgo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val p2pTransport: HybridP2PTransport,
    private val signalProtocol: SignalProtocol,
    private val chatRouter: ChatRouter,
    private val webRTCManager: WebRTCManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val myPeerId = MutableStateFlow("")

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: ChatData) : UiState()
    }

    data class ChatData(
        val messages: List<ChatMessage>,
        val sessionState: SessionState = SessionState.CONNECTING
    )

    /** Honest connection state instead of silently showing nothing. */
    enum class SessionState { CONNECTING, SESSION_READY, OFFLINE, ERROR }

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private var currentPeerId: String = ""
    private var offerId: String = ""

    /**
     * Called by the screen once the peer/offer nav args are known, wiring the
     * real E2EE pipeline. Idempotent.
     */
    fun setConversation(peerId: String, offerId: String) {
        if (peerId.isBlank()) {
            _uiState.value = UiState.Error("No peer selected for chat")
            return
        }
        if (this.currentPeerId == peerId && this.offerId == offerId) return
        this.currentPeerId = peerId
        this.offerId = offerId
        initializeChat()
    }

    private fun initializeChat() {
        if (currentPeerId.isBlank()) {
            _uiState.value = UiState.Error("No peer selected for chat")
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 1) E2EE crypto init (non-fatal so the UI still renders).
                try {
                    signalProtocol.initialize()
                } catch (sigEx: Throwable) {
                    android.util.Log.w("ChatScreen", "E2EE init unavailable: ${sigEx.message}")
                }

                val identity = identityManager.getOrCreateIdentity()
                myPeerId.value = identity.peerId

                // 2) Send a pre-key request so the peer replies with their X25519
                //    pubkey; the orchestrator stores it, then we mark ready.
                val ready = establishSession()

                _uiState.value = UiState.Success(
                    ChatData(
                        messages = emptyList(),
                        sessionState = if (ready) SessionState.SESSION_READY else SessionState.OFFLINE
                    )
                )

                observeInbound()
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to initialize chat: ${e.message}")
            }
        }
    }

    /**
     * Request the peer's pre-key bundle and report whether a usable E2EE session
     * already exists (restored from SQLCipher) or the handshake is pending.
     */
    private suspend fun establishSession(): Boolean {
        return try {
            // Send the handshake request over the transport.
            p2pTransport.send(
                currentPeerId,
                EnvelopeCodec.encode(AppMessage.PreKeyRequest(currentPeerId)).data,
                "pre_key_request"
            )
            // A persisted session key means encrypt/decrypt already work.
            signalProtocol.hasStoredSession(currentPeerId)
        } catch (e: Exception) {
            android.util.Log.w("ChatScreen", "Pre-key request failed: ${e.message}")
            false
        }
    }

    private fun observeInbound() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            signalProtocol.incomingMessages
                .filter { it.fromPeerId == currentPeerId }
                .collect { decrypted ->
                    appendMessage(
                        ChatMessage(
                            messageId = "recv_${decrypted.timestamp}_${decrypted.plaintext.size}",
                            offerId = offerId,
                            senderPeerId = currentPeerId,
                            senderNickname = "",
                            text = decrypted.plaintext.toString(Charsets.UTF_8),
                            timestamp = decrypted.timestamp,
                            isRead = true
                        )
                    )
                }
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        _uiState.update { state ->
            val current = (state as? UiState.Success)?.data ?: ChatData(emptyList())
            if (current.messages.any { it.messageId == msg.messageId }) state
            else UiState.Success(current.copy(messages = current.messages + msg))
        }
    }

    fun loadMessages() {
        initializeChat()
    }

    fun updateMessageText(text: String) {
        _messageText.value = text
    }

    /** Send a real E2EE-encrypted message over the transport via ChatRouter. */
    fun sendMessage(text: String) {
        val peer = currentPeerId
        if (peer.isBlank()) return
        val targetOffer = offerId
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = chatRouter.sendText(peer, targetOffer, text.toByteArray(Charsets.UTF_8))
            result.onSuccess {
                appendMessage(
                    ChatMessage(
                        messageId = "sent_${System.currentTimeMillis()}",
                        offerId = targetOffer,
                        senderPeerId = myPeerId.value,
                        senderNickname = "",
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isRead = false
                    )
                )
            }.onFailure {
                android.util.Log.w("ChatScreen", "Send failed (peer offline?): ${it.message}")
            }
            _messageText.value = ""
        }
    }

    fun handleReceivedFile(file: ReceivedFile) {}
}

/**
 * In-memory chat message. Only ciphertext is persisted to Room; plaintext is
 * held in memory for the live session and never written to disk.
 */
data class ChatMessage(
    val messageId: String,
    val offerId: String,
    val senderPeerId: String,
    val senderNickname: String,
    val text: String,
    val timestamp: Long,
    val isRead: Boolean = false,
    val fileAttachment: Boolean = false
) {
    val timeAgo: String by lazy {
        val diff = System.currentTimeMillis() - timestamp
        when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${(diff / 60_000).toInt()} menit yang lalu"
            diff < 86_400_000 -> "${(diff / 3_600_000).toInt()} jam yang lalu"
            else -> "${(diff / 86_400_000).toInt()} hari yang lalu"
        }
    }
}

data class ReceivedFile(
    val fromPeerId: String,
    val fileName: String,
    val data: ByteArray,
    val mimeType: String
)
