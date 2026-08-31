package com.neop2p.ui.screens.chat

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neop2p.R
import com.neop2p.data.local.dao.ChatMessageDao
import com.neop2p.data.p2p.*
import com.neop2p.data.p2p.protocol.AppMessage
import com.neop2p.data.p2p.protocol.EnvelopeCodec
import com.neop2p.data.p2p.queue.OfflineQueue
import com.neop2p.data.p2p.routing.ChatRouter
import com.neop2p.data.p2p.routing.PaymentReceiptPayload
import com.neop2p.data.p2p.routing.PaymentReceiptRejectPayload
import com.neop2p.data.p2p.routing.parsePaymentReceiptPayload
import com.neop2p.data.p2p.routing.parsePaymentReceiptRejectPayload
import com.neop2p.domain.model.*
import com.neop2p.service.AppForegroundTracker
import com.neop2p.service.NotificationDispatcher
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.components.ConnectionQualityChip
import com.neop2p.ui.util.PeerFingerprint
import com.neop2p.ui.util.formatBtc
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.neop2p.data.local.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    offerId: String,
    peerId: String,
    onBack: () -> Unit,
    onOpenEscrow: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: ChatViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Mark this conversation as open so the notification dispatcher suppresses
    // chat pings for it, and clear any pending notifications for this offer.
    DisposableEffect(offerId) {
        viewModel.appForegroundTracker.setOpenConversation(offerId)
        viewModel.cancelChatNotifications(offerId)
        onDispose {
            viewModel.appForegroundTracker.setOpenConversation("")
        }
    }

    // Wire the real E2EE pipeline once the nav args are available.
    LaunchedEffect(peerId, offerId) {
        viewModel.setConversation(peerId, offerId)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val sendError by viewModel.sendError.collectAsStateWithLifecycle()
    LaunchedEffect(sendError) {
        sendError?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSendError()
        }
    }

    NeoP2PTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.chat_with_peer))
                            // TOFU trust anchor: 8-word fingerprint of the
                            // peer's identity. Compare out-of-band (phone/WA)
                            // to detect a relay-level MITM on first contact.
                            val wordList = remember { PeerFingerprint.loadWordList(context) }
                            if (wordList.isNotEmpty()) {
                                Text(
                                    text = PeerFingerprint.display(peerId, wordList),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clickable {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as? android.content.ClipboardManager
                                            clipboard?.setPrimaryClip(
                                                android.content.ClipData.newPlainText(
                                                    "NEO-P2P fingerprint",
                                                    PeerFingerprint.display(peerId, wordList)
                                                )
                                            )
                                            android.widget.Toast.makeText(
                                                context,
                                                context.getString(R.string.chat_fingerprint_copied),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                )
                            }
                            // Connection quality of the peer (F05b): relayed
                            // peers depend on the WS relay.
                            ConnectionQualityChip(
                                quality = viewModel.qualityOf(peerId),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    },
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
                    is ChatViewModel.UiState.Error -> ChatErrorScreen(
                        message = s.message,
                        onRetry = { viewModel.loadMessages() }
                    )
                    is ChatViewModel.UiState.Success -> ChatContent(
                        messages = s.data.messages,
                        sessionState = s.data.sessionState,
                        isSeller = s.data.isSeller,
                        escrowFunded = s.data.escrowFunded,
                        escrow = s.data.escrow,
                        paymentDetails = s.data.paymentDetails,
                        paymentShared = s.data.paymentShared,
                        offerId = offerId,
                        peerId = peerId,
                        viewModel = viewModel,
                        onOpenEscrow = { escrowId -> onOpenEscrow(escrowId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatErrorScreen(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = stringResource(R.string.general_error),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.general_retry))
        }
    }
}

@Composable
private fun ChatContent(
    messages: List<ChatMessage>,
    sessionState: ChatViewModel.SessionState,
    isSeller: Boolean,
    escrowFunded: Boolean,
    escrow: com.neop2p.data.local.entity.EscrowEntity?,
    paymentDetails: Map<String, com.neop2p.domain.model.PaymentDetails>,
    paymentShared: Boolean,
    offerId: String,
    peerId: String,
    viewModel: ChatViewModel,
    onOpenEscrow: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // Honest connection banner instead of silently proceeding.
        if (sessionState != ChatViewModel.SessionState.SESSION_READY) {
            val label = when (sessionState) {
                ChatViewModel.SessionState.CONNECTING -> stringResource(R.string.chat_connecting)
                ChatViewModel.SessionState.OFFLINE -> stringResource(R.string.chat_offline_queue)
                ChatViewModel.SessionState.ERROR -> stringResource(R.string.chat_session_error)
                ChatViewModel.SessionState.SESSION_READY -> ""
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // U3: live escrow status banner — the buyer's device has no escrow row
        // of its own until the seller creates it and the kind:33337 sync event
        // lands; once it does, show the status and let the user open the screen.
        escrow?.let { e ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.chat_escrow_status_banner, e.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onOpenEscrow(e.escrow_id) }) {
                        Text(stringResource(R.string.chat_escrow_open))
                    }
                }
            }
        }

        // Sellers share their bank details ONLY after the escrow is funded
        // (so the buyer's BTC is secured before any IDR transfer).
        if (isSeller && paymentDetails.isNotEmpty() && escrowFunded && !paymentShared) {
            Button(
                onClick = { viewModel.sharePaymentDetails() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Filled.AccountBalance, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_share_payment))
            }
        } else if (isSeller && paymentDetails.isNotEmpty() && !escrowFunded && !paymentShared) {
            // Inform the seller they must fund the escrow before sharing.
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_wait_funded),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 24.dp)
                    )
                }
            } else {
                items(messages, key = { it.messageId }) { message ->
                    ChatMessageItem(
                        message = message,
                        isMine = message.senderPeerId == viewModel.myPeerId.value,
                        onOpenEscrow = onOpenEscrow
                    )
                }
            }
        }

        val text by viewModel.messageText.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                val name = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
                } ?: "attached_file"
                viewModel.sendFileAttachment(it, name, context)
            }
        }
        // Chat is locked until the escrow is funded. Once funded, the seller
        // shares bank details and both parties can chat.
        if (!escrowFunded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Text(
                        text = stringResource(R.string.chat_locked_until_funded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        try {
                            launcher.launch(arrayOf("*/*"))
                        } catch (_: Exception) {
                            // File picker not available on this device.
                        }
                    }
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.chat_cd_attach))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { viewModel.updateMessageText(it) },
                    label = { Text(stringResource(R.string.chat_message_input)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        val trimmed = text.trim()
                        if (trimmed.isNotBlank()) {
                            viewModel.sendMessage(trimmed)
                        }
                    },
                    enabled = text.trim().isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_cd_send))
                }
            }
        }

    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    isMine: Boolean,
    onOpenEscrow: (String) -> Unit = {}
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
                if (message.paymentReceipt != null) {
                    PaymentReceiptCard(message.paymentReceipt)
                } else if (message.paymentReject != null) {
                    PaymentRejectCard(message.paymentReject)
                } else if (message.paymentDetails) {
                    PaymentDetailsCard(message.text)
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.timeAgo(context),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Own-bubble delivery status (Briar MessageStatus pattern):
                    // delivered live = "✓ Terkirim"; queued (peer offline) =
                    // "Menunggu rekan online" once it's been ~10s.
                    if (isMine) {
                        Spacer(Modifier.width(6.dp))
                        val queuedLong = message.deliveredAt == null &&
                            System.currentTimeMillis() - message.timestamp > 10_000
                        Text(
                            text = stringResource(
                                if (message.deliveredAt != null) R.string.chat_sent
                                else if (queuedLong) R.string.chat_queued
                                else R.string.chat_sending
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (message.deliveredAt != null)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Render a structured {"type":"payment_details",...} card for the buyer. */
@Composable
private fun PaymentDetailsCard(payload: String) {
    val methods = remember(payload) { parsePaymentDetailsPayload(payload) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.chat_payment_details_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        if (methods.isEmpty()) {
            Text(stringResource(R.string.chat_payment_details_empty), style = MaterialTheme.typography.bodySmall)
        } else {
            methods.forEach { (method, num, holder) ->
                Text(
                    text = method.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(R.string.chat_payment_account_label, num), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.chat_payment_name_label, holder), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * Render a structured {"type":"payment_receipt",...} card: method, reference,
 * amount and sent-at timestamp, with an expandable screenshot when the peer
 * attached one (base64 → Bitmap via android.util.Base64 + BitmapFactory).
 */
@Composable
private fun PaymentReceiptCard(payload: PaymentReceiptPayload) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.chat_receipt_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.chat_receipt_method_label, payload.method.uppercase()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(stringResource(R.string.chat_receipt_reference_label, payload.reference), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.chat_receipt_amount_label, formatBtc(payload.amountSats)), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.chat_receipt_sent_label, formatReceiptTime(context, payload.sentAt)),
            style = MaterialTheme.typography.bodySmall
        )
        val image = payload.imageBase64
        if (image != null && image.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chat_receipt_view_image),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = !expanded }
            )
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                // Decode off the main thread (Dispatchers.IO) and memory-bounded
                // (~1024 px longest side via inSampleSize) — see decodeBase64Image.
                var bitmap by remember(image) { mutableStateOf<Bitmap?>(null) }
                var decoding by remember(image) { mutableStateOf(true) }
                LaunchedEffect(image) {
                    decoding = true
                    bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        decodeBase64Image(image)
                    }
                    decoding = false
                }
                val decoded = bitmap
                if (decoded != null) {
                    Image(
                        bitmap = decoded.asImageBitmap(),
                        contentDescription = stringResource(R.string.chat_receipt_title),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .clip(MaterialTheme.shapes.small)
                    )
                } else if (decoding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.chat_receipt_title), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Render a structured {"type":"payment_receipt_reject",...} card (seller →
 * buyer): the rejected reference, the machine reason code with Bahasa copy,
 * and an optional free-text note. The buyer can resubmit (opens the receipt
 * composer) or dispute — the escrow status was NOT changed by the rejection.
 */
@Composable
private fun PaymentRejectCard(
    payload: PaymentReceiptRejectPayload
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.chat_reject_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.chat_reject_reference, payload.reference),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                when (payload.reason) {
                    "JUMLAH_SALAH" -> R.string.chat_reject_reason_amount
                    "NAMA_BEDA" -> R.string.chat_reject_reason_name
                    "BELUM_MASUK" -> R.string.chat_reject_reason_not_received
                    else -> R.string.chat_reject_reason_other
                }
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        if (payload.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = payload.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.chat_reject_funds_locked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Longest-side cap (px) for receipt screenshots decoded in chat: bounds the decoded
 * bitmap to roughly 1024*1024*4 bytes (~4 MB) instead of the full-resolution capture. */
private const val MAX_RECEIPT_IMAGE_DIMENSION_PX = 1024

/**
 * Decode a base64 image payload to a Bitmap, or null when it isn't valid image data.
 * Memory-bounded: reads bounds first (inJustDecodeBounds — no pixels allocated), then
 * decodes with an inSampleSize that keeps the longest side <= 1024 px and never upscales.
 * MUST be called off the main thread (callers run it on Dispatchers.IO).
 */
private fun decodeBase64Image(base64: String): Bitmap? = try {
    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sampleSize = 1
    val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
    while (longestSide / sampleSize > MAX_RECEIPT_IMAGE_DIMENSION_PX) {
        sampleSize *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
} catch (_: Exception) {
    null
}

/** Compact local-time label for a receipt timestamp (e.g. "Aug 26, 14:05"). */
private fun formatReceiptTime(context: Context, epochMillis: Long): String {
    val date = android.text.format.DateFormat.getDateFormat(context)
    val time = android.text.format.DateFormat.getTimeFormat(context)
    return "${date.format(java.util.Date(epochMillis))} ${time.format(java.util.Date(epochMillis))}"
}

private fun parsePaymentDetailsPayload(payload: String): List<Triple<String, String, String>> {
    return try {
        val obj = Json.parseToJsonElement(payload).jsonObject
        val methods = obj["methods"]?.jsonObject ?: return emptyList()
        methods.mapNotNull { (method, v) ->
            val m = v.jsonObject
            val num = m["accountNumber"]?.jsonPrimitive?.content ?: ""
            val holder = m["accountHolder"]?.jsonPrimitive?.content ?: ""
            if (num.isBlank() && holder.isBlank()) null else Triple(method, num, holder)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val signalProtocol: SignalProtocol,
    private val chatRouter: ChatRouter,
    private val rnsTransport: com.neop2p.data.p2p.RnsTransport,
    private val chatMessageDao: ChatMessageDao,
    private val offerDao: com.neop2p.data.local.dao.OfferDao,
    private val escrowDao: com.neop2p.data.local.dao.EscrowDao,
    private val notificationDispatcher: NotificationDispatcher,
    val appForegroundTracker: AppForegroundTracker,
    private val peerRegistry: com.neop2p.data.p2p.store.PeerRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val myPeerId = MutableStateFlow("")

    /** Connection quality of the peer (F05b chip). */
    fun qualityOf(peerId: String): com.neop2p.data.p2p.store.PeerRegistry.ConnectionQuality =
        peerRegistry.qualityOf(peerId)

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: ChatData) : UiState()
    }

    data class ChatData(
        val messages: List<ChatMessage>,
        val sessionState: SessionState = SessionState.CONNECTING,
        // The current user is the SELLER (creator of this SELL offer) and can
        // share their payment details with the buyer via E2EE chat.
        val isSeller: Boolean = false,
        // True only once the on-chain escrow is FUNDED, so the seller only
        // shares their bank details after the buyer's BTC is secured.
        val escrowFunded: Boolean = false,
        // U3: the escrow row for this offer (null until the seller creates it
        // and the kind:33337 sync event lands on this device). Lets the BUYER
        // see live escrow status and open the escrow screen.
        val escrow: com.neop2p.data.local.entity.EscrowEntity? = null,
        // This offer's stored payment details (bank number + holder name).
        val paymentDetails: Map<String, com.neop2p.domain.model.PaymentDetails> = emptyMap(),
        // True once the seller has shared their payment details this session.
        val paymentShared: Boolean = false
    )

    /** Honest connection state instead of silently showing nothing. */
    enum class SessionState { CONNECTING, SESSION_READY, OFFLINE, ERROR }

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    private var currentPeerId: String = ""
    private var offerId: String = ""

    /**
     * Called by the screen once the peer/offer nav args are known, wiring the
     * real E2EE pipeline. Idempotent.
     */
    fun setConversation(peerId: String, offerId: String) {
        if (peerId.isBlank()) {
            _uiState.value = UiState.Error(context.getString(R.string.chat_no_peer_selected))
            return
        }
        if (this.currentPeerId == peerId && this.offerId == offerId) return
        this.currentPeerId = peerId
        this.offerId = offerId
        initializeChat()
    }

    private fun initializeChat() {
        if (currentPeerId.isBlank()) {
            _uiState.value = UiState.Error(context.getString(R.string.chat_no_peer_selected))
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

                // Determine seller role + stored payment details from this offer.
                val offer = try {
                    offerDao.getOfferSync(offerId)?.toDomain()
                } catch (e: Exception) {
                    android.util.Log.w("ChatScreen", "Offer load failed: ${e.message}")
                    null
                }
                val isSeller = offer?.creatorPeerId == identity.peerId && offer.type == OfferType.SELL
                val paymentDetails = offer?.paymentDetails.orEmpty()

                // Only let the seller share bank details after the escrow is
                // FUNDED (buyer's BTC is secured on-chain). Locked ONLY while
                // the escrow is still FUNDING — once funded it stays unlocked
                // through the whole lifecycle (PAYMENT_PENDING, RECEIPT_SENT,
                // CONFIRMING, RELEASED).
                val escrowFunded = try {
                    val esc = escrowDao.getEscrowByOfferId(offerId)
                    esc != null && esc.status != "FUNDING"
                } catch (e: Exception) {
                    android.util.Log.w("ChatScreen", "Escrow status load failed: ${e.message}")
                    false
                }

                // 2) Load persisted history (decrypts ciphertext from Room).
                val history = try {
                    chatRouter.loadHistory(offerId)
                } catch (e: Exception) {
                    android.util.Log.w("ChatScreen", "History load failed: ${e.message}")
                    emptyList()
                }
                // Mark history read once rendered.
                try { chatMessageDao.markAsRead(offerId) } catch (_: Exception) {}

                // 3) Send a pre-key request so the peer replies with their X25519
                //    pubkey; the orchestrator stores it, then we mark ready.
                val hasSession = signalProtocol.hasStoredSession(currentPeerId)
                val ready = establishSession()

                _uiState.value = UiState.Success(
                    ChatData(
                        messages = history,
                        sessionState = when {
                            ready || hasSession -> SessionState.SESSION_READY
                            else -> SessionState.OFFLINE
                        },
                        isSeller = isSeller,
                        escrowFunded = escrowFunded,
                        paymentDetails = paymentDetails
                    )
                )

                // Auto-share fallback: if the escrow is ALREADY FUNDED when the
                // seller opens the chat (orchestrator transition may have fired
                // before this build, or the auto-share was skipped), share the
                // bank details now. Router dedupes per offer — idempotent.
                if (isSeller && escrowFunded && paymentDetails.isNotEmpty()) {
                    chatRouter.autoSharePaymentDetails(currentPeerId, offerId, paymentDetails)
                }

                observeInbound()
                observeEscrowFunding()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(context.getString(R.string.chat_init_failed))
            }
        }
    }

    /**
     * Reactively watch the on-chain escrow for this offer. When it transitions
     * to FUNDED, unlock the chat (and the seller's "Share payment details"
     * button) live — the user may fund the escrow on the escrow screen and
     * return here, or the peer may fund it while this screen is open.
     */
    private fun observeEscrowFunding() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            escrowDao.observeEscrowByOfferId(offerId)
                .collect { esc ->
                    val funded = esc != null && esc.status != "FUNDING"
                    _uiState.update { state ->
                        val data = (state as? UiState.Success)?.data ?: return@update state
                        if (data.escrowFunded == funded && data.escrow?.escrow_id == esc?.escrow_id) state
                        else UiState.Success(
                            data.copy(escrowFunded = funded, escrow = esc)
                        )
                    }
                }
        }
    }

    /**
     * Request the peer's pre-key bundle and report whether a usable E2EE session
     * already exists (restored from SQLCipher) or the handshake is pending.
     */
    private suspend fun establishSession(): Boolean {
        return try {
            // Send the handshake request over the RNS transport.
            rnsTransport.send(
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
                    val plain = decrypted.plaintext.toString(Charsets.UTF_8)
                    // Structured payment-details envelopes are NOT chat: the
                    // bank card renders from the offer row (escrow detail),
                    // and the sweep re-shares them every 60s — appending a
                    // card here would duplicate one per sweep.
                    if (plain.trimStart().startsWith("{\"type\":\"payment_details\"")) return@collect
                    val receipt = parsePaymentReceiptPayload(plain)
                    val reject = parsePaymentReceiptRejectPayload(plain)
                    appendMessage(
                        ChatMessage(
                            messageId = "recv_${decrypted.timestamp}_${decrypted.plaintext.size}",
                            offerId = offerId,
                            senderPeerId = currentPeerId,
                            senderNickname = "",
                            // Structured receipts/rejects render as a card, not raw JSON.
                            text = if (receipt != null || reject != null) "" else plain,
                            timestamp = decrypted.timestamp,
                            isRead = true,
                            paymentDetails = plain.trimStart().startsWith("{\"type\":\"payment_details\""),
                            paymentReceipt = receipt,
                            paymentReject = reject
                        )
                    )
                }
        }
        // When the peer's bundle arrives and the session becomes usable, flip
        // the honest banner from OFFLINE to READY.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            signalProtocol.sessionEstablished
                .filter { it == currentPeerId }
                .collect {
                    _uiState.update { state ->
                        val data = (state as? UiState.Success)?.data ?: return@update state
                        UiState.Success(data.copy(sessionState = SessionState.SESSION_READY))
                    }
                }
        }
        // Inbound LXMF file transfers become chat bubbles.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            rnsTransport.receivedFiles
                .filter { it.fromPeerId == currentPeerId }
                .collect { file ->
                    appendMessage(
                        ChatMessage(
                            messageId = "rns_file_recv_${System.currentTimeMillis()}",
                            offerId = offerId,
                            senderPeerId = currentPeerId,
                            senderNickname = "",
                            text = "[File: ${file.fileName}, ${file.data.size} bytes]",
                            timestamp = System.currentTimeMillis(),
                            isRead = true,
                            fileAttachment = true
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

    fun consumeSendError() {
        _sendError.value = null
    }

    /** Send a real E2EE-encrypted message over the transport via ChatRouter. */
    fun sendMessage(text: String) {
        val peer = currentPeerId
        if (peer.isBlank()) return
        // Chat is locked until the on-chain escrow is funded.
        val escrowFunded = (uiState.value as? UiState.Success)?.data?.escrowFunded == true
        if (!escrowFunded) {
            _sendError.value = context.getString(R.string.chat_locked_until_funded)
            return
        }
        val targetOffer = offerId
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = chatRouter.sendText(peer, targetOffer, text.toByteArray(Charsets.UTF_8))
            result.onSuccess { delivered ->
                appendMessage(
                    ChatMessage(
                        messageId = "sent_${System.currentTimeMillis()}",
                        offerId = targetOffer,
                        senderPeerId = myPeerId.value,
                        senderNickname = "",
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isRead = false,
                        // true = delivered live; false = queued for when the
                        // peer comes online (bubble shows "Menunggu rekan online").
                        deliveredAt = if (delivered) System.currentTimeMillis() else null
                    )
                )
                _messageText.value = ""
            }.onFailure {
                android.util.Log.w("ChatScreen", "Send failed (peer offline?): ${it.message}")
                _sendError.value = context.getString(R.string.chat_send_failed)
            }
        }
    }

    /**
     * Share the seller's payment details (bank number + holder name) with the
     * buyer over the E2EE chat channel. The payload is a small JSON envelope
     * that the buyer's client renders as a structured card.
     */
    fun sharePaymentDetails() {
        val peer = currentPeerId
        if (peer.isBlank()) return
        // Only share bank details AFTER the on-chain escrow is funded.
        val escrowFunded = (uiState.value as? UiState.Success)?.data?.escrowFunded == true
        if (!escrowFunded) {
            _sendError.value = context.getString(R.string.chat_wait_funded)
            return
        }
        val targetOffer = offerId
        val details = (uiState.value as? UiState.Success)?.data?.paymentDetails.orEmpty()
        if (details.isEmpty()) return

        val payload = buildString {
            append("{\"type\":\"payment_details\",\"methods\":")
            val entries = details.entries.toList()
            append("{")
            entries.forEachIndexed { index, entry ->
                if (index > 0) append(",")
                val method = entry.key
                val d = entry.value
                append("\"${method}\":{")
                append("\"accountNumber\":\"${d.accountNumber}\"")
                append(",\"accountHolder\":\"${d.accountHolder}\"")
                append("}")
            }
            append("}}")
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = chatRouter.sendText(peer, targetOffer, payload.toByteArray(Charsets.UTF_8))
            result.onSuccess {
                appendMessage(
                    ChatMessage(
                        messageId = "sent_pay_${System.currentTimeMillis()}",
                        offerId = targetOffer,
                        senderPeerId = myPeerId.value,
                        senderNickname = "",
                        text = payload,
                        timestamp = System.currentTimeMillis(),
                        isRead = false,
                        paymentDetails = true
                    )
                )
                _uiState.update { state ->
                    val data = (state as? UiState.Success)?.data ?: return@update state
                    UiState.Success(data.copy(paymentShared = true))
                }
            }.onFailure {
                android.util.Log.w("ChatScreen", "Share payment details failed: ${it.message}")
                _sendError.value = context.getString(R.string.chat_send_failed)
            }
        }
    }

    fun sendFileAttachment(uri: android.net.Uri, fileName: String, context: android.content.Context) {
        val peer = currentPeerId
        val targetOffer = offerId
        if (peer.isBlank()) return
        // Chat (and file sharing) is locked until the escrow is funded.
        val escrowFunded = (uiState.value as? UiState.Success)?.data?.escrowFunded == true
        if (!escrowFunded) {
            _sendError.value = context.getString(R.string.chat_locked_until_funded)
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.isEmpty()) {
                        _sendError.value = context.getString(R.string.chat_attach_failed, fileName)
                        return@launch
                    }
                    chatRouter.sendFile(peer, targetOffer, fileName, bytes)
                        .onSuccess { msg ->
                            appendMessage(msg)
                        }
                        .onFailure { e ->
                            android.util.Log.w("ChatScreen", "File send failed: ${e.message}")
                            _sendError.value = context.getString(R.string.chat_attach_failed, fileName)
                        }
                } ?: run {
                    _sendError.value = context.getString(R.string.chat_attach_failed, fileName)
                }
            } catch (e: Exception) {
                _sendError.value = context.getString(R.string.chat_attach_failed, fileName)
            }
        }
    }

    /**
     * Dismiss any pending notifications for this conversation. Called when the
     * chat screen is displayed so the user isn't pinged about a conversation
     * they're already looking at.
     */
    fun cancelChatNotifications(offerId: String) {
        notificationDispatcher.cancelChat(offerId)
    }
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
    val fileAttachment: Boolean = false,
    val paymentDetails: Boolean = false,
    // Non-null when the peer received the message (live delivery). Null for
    // queued messages (peer offline) — bubble shows "Menunggu rekan online".
    val deliveredAt: Long? = null,
    // Structured E2EE payment receipt (text card + optional screenshot image).
    // In-memory only; ciphertext-only persistence unchanged.
    val paymentReceipt: PaymentReceiptPayload? = null,
    // Structured E2EE payment-receipt rejection (seller → buyer): which
    // reference was rejected + machine reason. Rendered as a card, in-memory
    // only (ciphertext-only persistence unchanged).
    val paymentReject: PaymentReceiptRejectPayload? = null
) {
    fun timeAgo(context: android.content.Context): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> context.getString(R.string.chat_just_now)
            diff < 3_600_000 -> context.getString(R.string.chat_minutes_ago, (diff / 60_000).toInt())
            diff < 86_400_000 -> context.getString(R.string.chat_hours_ago, (diff / 3_600_000).toInt())
            else -> context.getString(R.string.chat_days_ago, (diff / 86_400_000).toInt())
        }
    }
}
