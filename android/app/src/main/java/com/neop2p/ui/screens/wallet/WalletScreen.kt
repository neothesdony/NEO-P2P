package com.neop2p.ui.screens.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.neop2p.R
import com.neop2p.ui.util.ErrorCodes
import com.neop2p.ui.util.formatBtc
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.wallet.WalletService
import com.neop2p.domain.model.BitcoinAddressType
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.theme.buyColor
import com.neop2p.ui.theme.sellColor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onBack: () -> Unit,
    onTabChange: (com.neop2p.ui.components.AppTab) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: WalletViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val sendFeeEstimate by viewModel.sendFeeEstimate.collectAsStateWithLifecycle()
    val feeEstimateLoading by viewModel.feeEstimateLoading.collectAsStateWithLifecycle()
    val copiedEvent by viewModel.copiedEvent.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    LaunchedEffect(copiedEvent) {
        if (copiedEvent > 0) {
            snackbarHostState.showSnackbar(context.getString(R.string.wallet_copied))
        }
    }

    NeoP2PTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.wallet_title)) },
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
                    is WalletViewModel.UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is WalletViewModel.UiState.Error -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(s.message, textAlign = TextAlign.Center)
                        ErrorCodes.codeFor(s.message)?.let { code ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.error_code_line, code),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) {
                            Text(stringResource(R.string.general_retry))
                        }
                    }
                    is WalletViewModel.UiState.Success -> WalletContent(
                        state = s.data,
                        isRefreshing = s.refreshing,
                        isSending = isSending,
                        sendFeeEstimate = sendFeeEstimate,
                        feeEstimateLoading = feeEstimateLoading,
                        onEstimateFee = { amount, fromType -> viewModel.estimateSendFee(amount, fromType) },
                        onCopy = { addr ->
                            val clip = ClipData.newPlainText("NEO-P2P address", addr)
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(clip)
                            viewModel.showCopied()
                        },
                        onSend = { to, amount, fromType -> viewModel.send(to, amount, fromType) },
                        onRefresh = { viewModel.refresh() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletContent(
    state: WalletViewModel.WalletData,
    isRefreshing: Boolean,
    isSending: Boolean,
    sendFeeEstimate: Long?,
    feeEstimateLoading: Boolean,
    onEstimateFee: (Long, BitcoinAddressType?) -> Unit,
    onCopy: (String) -> Unit,
    onSend: (String, Long, BitcoinAddressType?) -> Unit,
    onRefresh: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    var pendingSend by remember { mutableStateOf<Triple<String, Long, BitcoinAddressType?>?>(null) }
    var toAddress by remember { mutableStateOf("") }
    var amountSats by remember { mutableStateOf("") }

    // QR scan → destination address. Accepts a bare address or a
    // bitcoin: URI (bitcoin:ADDR?amount=...), so any wallet's QR works.
    val scanPrompt = stringResource(R.string.wallet_scan_prompt)
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            val parsed = Uri.parse(contents.trim())
            toAddress = if (parsed.scheme.equals("bitcoin", ignoreCase = true))
                parsed.schemeSpecificPart.substringBefore('?')
            else
                contents.trim()
        }
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Balance card ──
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.wallet_balance),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.common_btc_amount, formatBtc(state.totalSats)),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (state.unconfirmedSats != 0L) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(
                                    R.string.wallet_unconfirmed,
                                    formatBtc(state.unconfirmedSats)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // ── Receive card (address + QR) ──
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.wallet_receive),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(12.dp))
                        // Legacy ↔ SegWit address toggle (both from the same key).
                        var selectedType by remember { mutableStateOf(BitcoinAddressType.SEGWIT) }
                        val displayAddress = state.addressFor(selectedType)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            BitcoinAddressType.entries.forEachIndexed { index, type ->
                                SegmentedButton(
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    shape = SegmentedButtonDefaults.itemShape(index, BitcoinAddressType.entries.size)
                                ) {
                                    Text(
                                        stringResource(
                                            if (type == BitcoinAddressType.LEGACY)
                                                R.string.wallet_address_type_legacy
                                            else
                                                R.string.wallet_address_type_segwit
                                        ),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        var qr by remember(displayAddress) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(displayAddress) {
                            qr = withContext(Dispatchers.Default) { generateQrCode(displayAddress) }
                        }
                        qr?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = stringResource(R.string.wallet_qr_cd),
                                modifier = Modifier.size(200.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            displayAddress,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onCopy(displayAddress) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.wallet_copy_address))
                        }
                    }
                }

                // ── Send card ──
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.wallet_send),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = toAddress,
                            onValueChange = { toAddress = it },
                            label = { Text(stringResource(R.string.wallet_to_address)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val options = ScanOptions().apply {
                                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                            setPrompt(scanPrompt)
                                            setBeepEnabled(false)
                                        }
                                        scanLauncher.launch(options)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.QrCodeScanner,
                                        contentDescription = stringResource(R.string.wallet_scan_qr)
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = amountSats,
                            onValueChange = { amountSats = it },
                            label = { Text(stringResource(R.string.wallet_amount_sats)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        // Send-from selector: which address type's UTXOs to spend.
                        // Auto spends across both (largest UTXOs first); Legacy /
                        // SegWit restrict the spend to that type's confirmed UTXOs.
                        var sendFrom by remember { mutableStateOf<BitcoinAddressType?>(null) }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val options = listOf<BitcoinAddressType?>(null, BitcoinAddressType.LEGACY, BitcoinAddressType.SEGWIT)
                            options.forEachIndexed { index, type ->
                                SegmentedButton(
                                    selected = sendFrom == type,
                                    onClick = { sendFrom = type },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size)
                                ) {
                                    Text(
                                        stringResource(
                                            when (type) {
                                                null -> R.string.wallet_send_from_auto
                                                BitcoinAddressType.LEGACY -> R.string.wallet_address_type_legacy
                                                BitcoinAddressType.SEGWIT -> R.string.wallet_address_type_segwit
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val amount = amountSats.toLongOrNull()
                                if (amount != null && amount > 0 && toAddress.isNotBlank()) {
                                    // Two-step: prepare, then confirm in a dialog
                                    // before any broadcast (real money).
                                    pendingSend = Triple(toAddress.trim(), amount, sendFrom)
                                    showConfirm = true
                                    // Fee preview: fetch the estimate for the
                                    // exact amount + send-from type so the user
                                    // sees fee and total before the irreversible
                                    // broadcast (was only visible post-hoc in tx history).
                                    onEstimateFee(amount, sendFrom)
                                }
                            },
                            enabled = !isSending && toAddress.isNotBlank() && (amountSats.toLongOrNull() ?: 0) > 0,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(stringResource(R.string.wallet_send_btc))
                        }
                    }
                }

                // ── History ──
                Text(
                    stringResource(R.string.wallet_history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                if (state.txs.isEmpty()) {
                    Text(
                        stringResource(R.string.wallet_no_txs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.txs.forEach { tx ->
                        TransactionCard(tx)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ── Send confirmation dialog ──
    pendingSend?.let { (to, amount, fromType) ->
        AlertDialog(
            onDismissRequest = {
                if (!isSending) {
                    showConfirm = false
                    pendingSend = null
                }
            },
            title = { Text(stringResource(R.string.wallet_confirm_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.wallet_confirm_message,
                            formatBtc(amount),
                            to
                        ) + if (fromType != null) "\n\n" + stringResource(
                            R.string.wallet_confirm_from,
                            stringResource(
                                if (fromType == BitcoinAddressType.LEGACY)
                                    R.string.wallet_address_type_legacy
                                else
                                    R.string.wallet_address_type_segwit
                            )
                        ) else ""
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        feeEstimateLoading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.wallet_fee_estimating),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        sendFeeEstimate != null -> {
                            val fee = sendFeeEstimate ?: 0L
                            Text(
                                stringResource(R.string.wallet_confirm_fee_line, formatBtc(fee)),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                stringResource(R.string.wallet_confirm_total_line, formatBtc(amount + fee)),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        pendingSend = null
                        onSend(to, amount, fromType)
                    },
                    enabled = !isSending
                ) {
                    Text(stringResource(R.string.wallet_confirm_send))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        pendingSend = null
                    },
                    enabled = !isSending
                ) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }
}

@Composable
private fun TransactionCard(tx: ChainMonitor.AddressTx) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    tx.txid.take(24) + "…",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    stringResource(
                        when (tx.direction) {
                            ChainMonitor.TxDirection.RECEIVE -> R.string.wallet_tx_received
                            ChainMonitor.TxDirection.SEND -> R.string.wallet_tx_sent
                            ChainMonitor.TxDirection.SELF -> R.string.wallet_tx_self
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (tx.direction) {
                        ChainMonitor.TxDirection.RECEIVE -> MaterialTheme.colorScheme.buyColor
                        ChainMonitor.TxDirection.SEND -> MaterialTheme.colorScheme.sellColor
                        ChainMonitor.TxDirection.SELF -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(
                        R.string.wallet_tx_time_fmt,
                        Date(tx.blockTimeSec * 1000)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    // For sends the amount is the NET effect on this wallet
                    // (negative when the tx moved funds out, incl. the fee).
                    // formatBtc keeps the value's own sign — SEND (negative)
                    // renders "-0.0010", RECEIVE renders "+0.0010" via prefix.
                    if (tx.direction == ChainMonitor.TxDirection.SEND)
                        stringResource(R.string.common_btc_amount, "-" + formatBtc(-tx.netSats))
                    else
                        stringResource(R.string.common_btc_amount, "+" + formatBtc(tx.netSats)),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (tx.direction == ChainMonitor.TxDirection.SEND && tx.confirmed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.wallet_fee_fmt,
                        formatBtc(tx.feeSats)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Render the address as a QR bitmap (bitcoin: URI so wallets can scan it). */
private fun generateQrCode(address: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = writer.encode("bitcoin:$address", BarcodeFormat.QR_CODE, 400, 400, hints)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.RGB_565)
        for (x in 0 until 400) {
            for (y in 0 until 400) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}

@HiltViewModel
class WalletViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletService: WalletService
) : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(
            val data: WalletData,
            val refreshing: Boolean = false
        ) : UiState()
    }

    data class WalletData(
        val address: String,
        val addresses: Map<BitcoinAddressType, String>,
        val totalSats: Long,
        val unconfirmedSats: Long,
        val txs: List<ChainMonitor.AddressTx>
    ) {
        fun addressFor(type: BitcoinAddressType): String = addresses[type].orEmpty()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _sendFeeEstimate = MutableStateFlow<Long?>(null)
    val sendFeeEstimate: StateFlow<Long?> = _sendFeeEstimate.asStateFlow()
    private val _feeEstimateLoading = MutableStateFlow(false)
    val feeEstimateLoading: StateFlow<Boolean> = _feeEstimateLoading.asStateFlow()

    /** Fetch a fresh fee estimate for the send-confirm preview. */
    fun estimateSendFee(amountSats: Long, fromType: BitcoinAddressType?) {
        if (_feeEstimateLoading.value) return
        _feeEstimateLoading.value = true
        _sendFeeEstimate.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _sendFeeEstimate.value = walletService.estimateSendFee(amountSats, fromType).getOrNull()
            } finally {
                _feeEstimateLoading.value = false
            }
        }
    }

    fun clearSendFeeEstimate() {
        _sendFeeEstimate.value = null
    }

    private val _copiedEvent = MutableStateFlow(0L)
    val copiedEvent: StateFlow<Long> = _copiedEvent.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val previous = _uiState.value
        if (previous is UiState.Success) {
            // Keep old content visible; the pull indicator carries the loading state.
            _uiState.value = UiState.Success(previous.data, refreshing = true)
        } else {
            _uiState.value = UiState.Loading
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = walletService.loadState().getOrElse {
                    if (previous is UiState.Success) {
                        _uiState.value = UiState.Success(previous.data, refreshing = false)
                    } else {
                        _uiState.value = UiState.Error(it.message ?: "Wallet load failed")
                    }
                    _error.value = it.message ?: "Wallet load failed"
                    return@launch
                }
                _uiState.value = UiState.Success(
                    WalletData(
                        address = state.address,
                        addresses = state.addresses,
                        totalSats = state.totalSats,
                        unconfirmedSats = state.unconfirmedSats,
                        txs = state.txs
                    ),
                    refreshing = false
                )
            } catch (e: Exception) {
                if (previous is UiState.Success) {
                    _uiState.value = UiState.Success(previous.data, refreshing = false)
                } else {
                    _uiState.value = UiState.Error(e.message ?: "Wallet load failed")
                }
                _error.value = e.message ?: "Wallet load failed"
            }
        }
    }

    fun send(toAddress: String, amountSats: Long, fromType: BitcoinAddressType? = null) {
        if (_isSending.value) return
        _isSending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                walletService.send(toAddress, amountSats, fromType)
                    .onSuccess { result ->
                        _error.value = context.getString(R.string.wallet_send_ok, result.txid.take(16))
                        refresh()
                    }
                    .onFailure {
                        _error.value = it.message ?: context.getString(R.string.wallet_send_failed)
                    }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun showCopied() {
        _copiedEvent.value = System.currentTimeMillis()
    }

    fun consumeError() {
        _error.value = null
    }
}
