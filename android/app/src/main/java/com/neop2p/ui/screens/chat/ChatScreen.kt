package com.neop2p.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neop2p.R
import com.neop2p.data.p2p.*
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
    onEscrowCreated: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ChatViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Chat with Peer") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Back"
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
                        offerId = offerId,
                        peerId = peerId,
                        viewModel = viewModel,
                        onEscrowCreated = onEscrowCreated
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatContent(
    messages: List<ChatMessage>,
    offerId: String,
    peerId: String,
    viewModel: ChatViewModel,
    onEscrowCreated: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
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
                Icon(Icons.Default.AttachFile, contentDescription = "Attach file")
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    viewModel.updateMessageText(it)
                },
                label = { Text("Message") },
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
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }

        Button(
            onClick = { onEscrowCreated(offerId) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text("Create Escrow")
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
    private val libP2PManager: LibP2PManager,
    private val signalProtocol: SignalProtocol,
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
        val messages: List<ChatMessage>
    )

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    init {
        initializeChat()
    }

    private fun initializeChat() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val initResult = signalProtocol.initialize()
                if (initResult.isFailure) {
                    throw initResult.exceptionOrNull() ?: Exception("Signal init failed")
                }
                val identity = identityManager.getOrCreateIdentity()
                myPeerId.value = identity.peerId

                val mockMessages = listOf(
                    ChatMessage(
                        messageId = "msg_1",
                        offerId = "dummy_offer",
                        senderPeerId = "peer_123",
                        senderNickname = "Trader_Ani",
                        text = "Hai, ini BTC asli. Escrow sudah saya buat.",
                        timestamp = System.currentTimeMillis() - 60_000,
                        isRead = true
                    ),
                    ChatMessage(
                        messageId = "msg_2",
                        offerId = "dummy_offer",
                        senderPeerId = identity.peerId,
                        senderNickname = "",
                        text = "Baik, saya transfer lewat BCA sekarang.",
                        timestamp = System.currentTimeMillis() - 30_000,
                        isRead = false
                    )
                )
                _uiState.value = UiState.Success(ChatData(mockMessages))
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to initialize chat: ${e.message}")
            }
        }
    }

    fun loadMessages() {
        _uiState.value = UiState.Loading
        initializeChat()
    }

    fun updateMessageText(text: String) {
        _messageText.value = text
    }

    fun sendMessage(text: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val identity = identityManager.getOrCreateIdentity()
                val newMessage = ChatMessage(
                    messageId = "msg_${System.currentTimeMillis()}",
                    offerId = "dummy_offer",
                    senderPeerId = identity.peerId,
                    senderNickname = "",
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    isRead = false
                )
                _uiState.update { state ->
                    val current = (state as? UiState.Success)?.data ?: ChatData(emptyList())
                    UiState.Success(ChatData(current.messages + listOf(newMessage)))
                }
                _messageText.value = ""
            } catch (e: Exception) {
                // TODO: surface error
            }
        }
    }

    fun handleReceivedFile(file: ReceivedFile) {}
}

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
