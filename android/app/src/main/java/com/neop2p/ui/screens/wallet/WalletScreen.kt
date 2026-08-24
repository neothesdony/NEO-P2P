package com.neop2p.ui.screens.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
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
import com.neop2p.R
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.wallet.WalletService
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: WalletViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
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
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) {
                            Text(stringResource(R.string.general_retry))
                        }
                    }
                    is WalletViewModel.UiState.Success -> WalletContent(
                        state = s.data,
                        onCopy = {
                            val clip = ClipData.newPlainText("NEO-P2P address", s.data.address)
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(clip)
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.wallet_copied))
                            }
                        },
                        onSend = { to, amount -> viewModel.send(to, amount) },
                        onRefresh = { viewModel.refresh() }
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletContent(
    state: WalletViewModel.WalletData,
    onCopy: () -> Unit,
    onSend: (String, Long) -> Unit,
    onRefresh: () -> Unit
) {
    var showSendDialog by remember { mutableStateOf(false) }
    var toAddress by remember { mutableStateOf("") }
    var amountSats by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Balance card ──
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.wallet_balance),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "%.8f BTC".format(state.totalSats / 100_000_000.0),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (state.unconfirmedSats != 0L) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.wallet_unconfirmed, "%.8f".format(state.unconfirmedSats / 100_000_000.0)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // ── Receive card (address + QR) ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.wallet_receive), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                val qr = remember(state.address) { generateQrCode(state.address) }
                qr?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.wallet_qr_cd),
                        modifier = Modifier.size(200.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    state.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.wallet_copy_address))
                }
            }
        }

        // ── Send card ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.wallet_send), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = toAddress,
                    onValueChange = { toAddress = it },
                    label = { Text(stringResource(R.string.wallet_to_address)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountSats,
                    onValueChange = { amountSats = it },
                    label = { Text(stringResource(R.string.wallet_amount_sats)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val amount = amountSats.toLongOrNull()
                        if (amount != null && amount > 0 && toAddress.isNotBlank()) {
                            sending = true
                            onSend(toAddress.trim(), amount)
                            toAddress = ""
                            amountSats = ""
                            sending = false
                        }
                    },
                    enabled = !sending && toAddress.isNotBlank() && (amountSats.toLongOrNull() ?: 0) > 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(stringResource(R.string.wallet_send_btc))
                }
            }
        }

        // ── History ──
        Text(stringResource(R.string.wallet_history), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        if (state.txs.isEmpty()) {
            Text(
                stringResource(R.string.wallet_no_txs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.txs.forEach { tx ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            tx.txid.take(24) + "…",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                if (tx.confirmed) stringResource(R.string.wallet_tx_confirmed)
                                else stringResource(R.string.wallet_tx_pending),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tx.confirmed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                "%.8f BTC".format(tx.totalOutSats / 100_000_000.0),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
    private val walletService: WalletService,
    private val identityManager: IdentityManager
) : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: WalletData) : UiState()
    }

    data class WalletData(
        val address: String,
        val totalSats: Long,
        val unconfirmedSats: Long,
        val txs: List<com.neop2p.data.escrow.ChainMonitor.AddressTx>
    )

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = walletService.loadState().getOrElse {
                    _uiState.value = UiState.Error(it.message ?: "Wallet load failed")
                    return@launch
                }
                _uiState.value = UiState.Success(
                    WalletData(
                        address = state.address,
                        totalSats = state.totalSats,
                        unconfirmedSats = state.unconfirmedSats,
                        txs = state.txs
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Wallet load failed")
            }
        }
    }

    fun send(toAddress: String, amountSats: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            walletService.send(toAddress, amountSats)
                .onSuccess { result ->
                    _error.value = context.getString(R.string.wallet_send_ok, result.txid.take(16))
                    refresh()
                }
                .onFailure {
                    _error.value = it.message ?: context.getString(R.string.wallet_send_failed)
                }
        }
    }

    fun consumeError() {
        _error.value = null
    }
}
