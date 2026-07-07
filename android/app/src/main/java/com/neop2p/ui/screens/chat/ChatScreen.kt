package com.neop2p.ui.screens.chat

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.*
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.ClickableText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.p2p.*
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
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
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            },
            content = { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (state) {
                        is ChatViewModel.Loading -> LoadingScreen()
                        is ChatViewModel.Error -> ErrorScreen(
                            message = (it as ChatViewModel.Error).message,
                            onRetry = { viewModel.loadMessages() }
                        )
                        is ChatViewModel.Success -> {
                            val data = (it as ChatViewModel.Success).data
                            ChatContent(
                                messages = data.messages,
                                offerId = offerId,
                                peerId = peerId,
                                onMessageSent = { viewModel.sendMessage(it) },
                                onFileReceived = { viewModel.handleReceivedFile(it) },
                                onEscrowCreated = onEscrowCreated
                            )
                        }
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingScreen(
    modifier: Modifier = Modifier
) = Box(
    modifier = modifier
        .fillMaxSize()
        .background(
            color = if (isSystemInDarkTheme()) Color(0xFF0D1117) else Color.White
        )
        .align(Alignment.Center)
) {
    CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) = Column(
    modifier = modifier
        .fillMaxSize()
        .padding(24.dp)
        .align(Alignment.Center)
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_warning),
        contentDescription = "Error",
        modifier = Modifier
            .size(64.dp)
            .wrapContentSize(align = Alignment.Center)
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = message,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onRetry,
        modifier = Modifier
            .width(120.dp)
            .height(40.dp)
    ) {
        Text("Retry")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    messages: List<ChatMessage>,
    offerId: String,
    peerId: String,
    onMessageSent: (String) -> Unit,
    onFileReceived: (ReceivedFile) -> Unit,
    onEscrowCreated: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column {
        // Messages list
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            items(items = messages) { message ->
                ChatMessageItem(
                    message = message,
                    isMine = message.senderPeerId == viewModel.myPeerId.value
                )
            }
        }

        // Message input box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .align(Alignment.CenterVertically)
            ) {
                // Attach button (file)
                IconButton(
                    onClick = { /* TODO: File picker */ }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_attach_file),
                        contentDescription = "Attach file"
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Text input
                TextField(
                    value = viewModel.messageText,
                    onValueChange = { viewModel.updateMessageText(it) },
                    label = { Text("Message") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    colors = TextFieldDefaults.textFieldColors(
                        backgroundColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    imeAction = ImeAction.Send,
                    onImeActionListener = {
                        val text = viewModel.messageText.trim()
                        if (text.isNotBlank()) {
                            viewModel.sendMessage(text)
                            viewModel.updateMessageText("")
                        }
                        true
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Send button
                IconButton(
                    onClick = {
                        val text = viewModel.messageText.trim()
                        if (text.isNotBlank()) {
                            viewModel.sendMessage(text)
                            viewModel.updateMessageText("")
                        }
                    },
                    enabled = viewModel.messageText.trim().isNotBlank()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_send),
                        contentDescription = "Send"
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Create escrow button
                Button(
                    onClick = {
                        // TODO: Create escrow for this offer
                    },
                    modifier = Modifier
                        .height(32.dp)
                ) {
                    Text("Create Escrow")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    isMine: Boolean,
    modifier: Modifier = Modifier
) {
    val alignment = if (isMine) Arrangement.End else Arrangement.Start
    val backgroundColor = if (isMine)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Avatar / nickname
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            if (!isMine) {
                Text(
                    text = message.senderNickname.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                        .align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Message bubble
        Column(
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isMine)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(
                        color = backgroundColor,
                        shape = MaterialTheme.shapes.medium
                    )
                    .clickable { /* TODO: Handle clicks */ }
                    )

            // File attachment indicator
            message.fileAttachment?.let {
                Row(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Arrangement.End
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_insert_drive_file),
                        contentDescription = "File attachment",
                        modifier = Modifier
                            .size(20.dp)
                            .tint(MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Text(
                        text = "Payment proof",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Timestamp / status
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            Text(
                text = message.timeAgo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
                        if (!isMine && !message.isRead) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .padding(top = 2.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        }
        }
    }
}

// ─── ViewModel ───────────────────────────────────────────────
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

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var chatJob: Job? = null
    private val messageHandler = Handler(Looper.getMainLooper())

    init {
        initializeChat()
    }

    private fun initializeChat() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Initialize Signal Protocol if not already
                val initResult = signalProtocol.initialize()
                if (initResult.isFailure) {
                    throw initResult.exceptionOrNull() ?: Exception("Signal init failed")
                }

                // Get our identity
                val identity = identityManager.getOrCreateIdentity()
                myPeerId.value = identity.peerId

                // For v1: mock some messages
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

    fun sendMessage(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val identity = identityManager.getOrCreateIdentity()
                val peerId = "dummy_peer" // In real app, get from context

                // Encrypt the message
                val encryptResult = signalProtocol.encrypt(peerId, text.encodeToByteArray())
                if (encryptResult.isFailure) {
                    throw encryptResult.exceptionOrNull() ?: Exception("Encryption failed")
                }

                // Send via libp2p stream (simplified)
                // In real app: open stream and send encrypted bytes

                // Add to local list optimistically
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
                // TODO: Show error
            }
        }
    }

    fun handleReceivedFile(file: ReceivedFile) {
        // Decrypt and save file
        // For v1: just acknowledge
    }
}

// ─── Chat Data Classes ─────────────────────────────────────
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
