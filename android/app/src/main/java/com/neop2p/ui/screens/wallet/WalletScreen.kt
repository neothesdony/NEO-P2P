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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
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
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.escrow.ChainMonitor
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.network.TorState
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.tor.TorHttpPolicy
import com.neop2p.data.tor.TorManager
import com.neop2p.domain.model.EscrowStatus
import com.neop2p.ui.util.ErrorCodes
import com.neop2p.ui.util.MoneyAction
import com.neop2p.ui.util.TestTags
import com.neop2p.ui.util.TorBlockDecision
import com.neop2p.ui.util.TorOverrideDialog
import com.neop2p.ui.util.formatBtc
import com.neop2p.ui.util.moneyAction
import com.neop2p.ui.util.parseBtcToSats
import com.neop2p.data.wallet.WalletService
import com.neop2p.data.wallet.WalletFeePolicy
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

@Composable
private fun WalletInputError.text(): String = when (this) {
    WalletInputError.WRONG_NETWORK -> stringResource(
        R.string.wallet_error_wrong_network,
        if (NeoP2PConfig.network == "mainnet") "testnet" else "mainnet"
    )
    else -> stringResource(messageRes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: WalletViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingTorBlock by viewModel.pendingTorBlock.collectAsStateWithLifecycle()
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

    // P0.7: reserve a fresh receive index for this screen's lifetime, release
    // it when the flow is torn down. No screen derives its own receive address.
    DisposableEffect(Unit) {
        viewModel.beginReceive()
        onDispose { viewModel.endReceive() }
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
                        onEstimateFee = { amount, fromType, tier, custom ->
                            viewModel.estimateSendFee(amount, fromType, tier, custom)
                        },
                        onCopy = { addr ->
                            val clip = ClipData.newPlainText("NEO-P2P address", addr)
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(clip)
                            viewModel.showCopied()
                        },
                        onSend = { to, amount, fromType, maxFee, tier, custom ->
                            viewModel.send(to, amount, fromType, maxFee, tier, custom)
                        },
                        onRefresh = { viewModel.refresh() }
                    )
                }
            }
        }
        if (pendingTorBlock) {
            TorOverrideDialog(
                onConfirm = { viewModel.confirmTorOverride() },
                onDismiss = { viewModel.clearPendingTorBlock() }
            )
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
    onEstimateFee: (Long, BitcoinAddressType?, WalletFeePolicy.FeeTier, Long?) -> Unit,
    onCopy: (String) -> Unit,
    onSend: (String, Long, BitcoinAddressType?, Long?, WalletFeePolicy.FeeTier, Long?) -> Unit,
    onRefresh: () -> Unit
) {
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var pendingSend by rememberSaveable { mutableStateOf<Triple<String, Long, BitcoinAddressType?>?>(null) }
    var toAddress by rememberSaveable { mutableStateOf("") }
    var amountBtc by rememberSaveable { mutableStateOf("") }
    // P2.2: confirmation-speed tier for the send-confirm dialog.
    var feeTier by rememberSaveable { mutableStateOf(WalletFeePolicy.FeeTier.FAST) }
    var customRateText by rememberSaveable { mutableStateOf("") }
    val customRate = customRateText.trim().toLongOrNull()
    val haptics = LocalHapticFeedback.current

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
                        if (state.lockedInEscrowSats != 0L) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(
                                    R.string.wallet_locked_in_escrow,
                                    formatBtc(state.lockedInEscrowSats)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                stringResource(R.string.wallet_locked_in_escrow_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
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
                        var selectedType by rememberSaveable { mutableStateOf(BitcoinAddressType.SEGWIT) }
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
                        // Inline validation: wrong-network / dust / fee>balance are shown
                        // BEFORE the irreversible dialog, not after a failed broadcast.
                        // Audit P3-7: only confirmed UTXOs are spendable, so
                        // validate against the confirmed balance. The balance
                        // card still shows the total (including unconfirmed).
                        val spendableSats = state.confirmedSats
                        val amountSatsForValidation = parseBtcToSats(amountBtc)
                        val addressError: WalletInputError? = btcAddressInputError(
                            com.neop2p.data.wallet.btcAddressError(
                                address = toAddress,
                                params = if (NeoP2PConfig.network == "mainnet")
                                    org.bitcoinj.params.MainNetParams.get()
                                else
                                    org.bitcoinj.params.TestNet3Params.get(),
                                otherParams = if (NeoP2PConfig.network == "mainnet")
                                    org.bitcoinj.params.TestNet3Params.get()
                                else
                                    org.bitcoinj.params.MainNetParams.get()
                            )
                        )
                        val amountError: WalletInputError? =
                            sendAmountError(amountSatsForValidation, spendableSats)
                        OutlinedTextField(
                            value = toAddress,
                            onValueChange = { toAddress = it },
                            label = { Text(stringResource(R.string.wallet_to_address)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = addressError != null,
                            supportingText = addressError?.let { error ->
                                { Text(error.text(), color = MaterialTheme.colorScheme.error) }
                            },
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
                        addressError?.code?.let { c ->
                            Text(stringResource(R.string.error_code_line, c), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = amountBtc,
                            onValueChange = { amountBtc = it },
                            label = { Text(stringResource(R.string.wallet_amount_sats)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = amountError != null,
                            supportingText = amountError?.let { error ->
                                { Text(error.text(), color = MaterialTheme.colorScheme.error) }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                            )
                        )
                        amountError?.code?.let { c ->
                            Text(stringResource(R.string.error_code_line, c), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        // Send-from selector: which address type's UTXOs to spend.
                        // Auto spends across both (largest UTXOs first); Legacy /
                        // SegWit restrict the spend to that type's confirmed UTXOs.
                        var sendFrom by rememberSaveable { mutableStateOf<BitcoinAddressType?>(null) }
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
                        val sendEnabled = !isSending && toAddress.isNotBlank() && addressError == null && amountError == null && (amountSatsForValidation ?: 0L) > 0
                        Button(
                            onClick = {
                                val amount = parseBtcToSats(amountBtc)
                                if (amount != null && amount > 0 && toAddress.isNotBlank() && addressError == null && amountError == null) {
                                    // Two-step: prepare, then confirm in a dialog
                                    // before any broadcast (real money).
                                    pendingSend = Triple(toAddress.trim(), amount, sendFrom)
                                    showConfirm = true
                                    // Fee preview: fetch the estimate for the
                                    // exact amount + send-from type so the user
                                    // sees fee and total before the irreversible
                                    // broadcast (was only visible post-hoc in tx history).
                                    onEstimateFee(amount, sendFrom, feeTier, customRate)
                                }
                            },
                            enabled = sendEnabled,
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag(TestTags.WALLET_SEND)
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
                    Spacer(Modifier.height(8.dp))
                    // P2.2: confirmation-speed tier. Changing it re-estimates
                    // the fee for the exact amount; the confirmed tier's fee
                    // becomes the ceiling checked at broadcast.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WalletFeePolicy.FeeTier.entries.forEach { tier ->
                            FilterChip(
                                selected = feeTier == tier,
                                onClick = {
                                    feeTier = tier
                                    onEstimateFee(amount, fromType, tier, customRate)
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            when (tier) {
                                                WalletFeePolicy.FeeTier.FAST -> R.string.wallet_fee_tier_fast
                                                WalletFeePolicy.FeeTier.MEDIUM -> R.string.wallet_fee_tier_medium
                                                WalletFeePolicy.FeeTier.SLOW -> R.string.wallet_fee_tier_slow
                                                WalletFeePolicy.FeeTier.CUSTOM -> R.string.wallet_fee_tier_custom
                                            }
                                        ),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                    if (feeTier == WalletFeePolicy.FeeTier.CUSTOM) {
                        OutlinedTextField(
                            value = customRateText,
                            onValueChange = { text ->
                                customRateText = text.filter { it.isDigit() }.take(4)
                                onEstimateFee(amount, fromType, feeTier, customRateText.toLongOrNull())
                            },
                            label = { Text("sat/vB") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptics.moneyAction(MoneyAction.SEND_BTC)
                        showConfirm = false
                        pendingSend = null
                        onSend(to, amount, fromType, sendFeeEstimate, feeTier, customRate)
                    },
                    enabled = !isSending
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.wallet_sending))
                    } else {
                        Text(stringResource(R.string.wallet_confirm_send))
                    }
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
    private val walletService: WalletService,
    private val escrowDao: EscrowDao,
    private val identityManager: IdentityManager,
    private val torManager: TorManager,
    private val torHttpPolicy: TorHttpPolicy
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
        val addresses: Map<BitcoinAddressType, String>,
        val totalSats: Long,
        val confirmedSats: Long,
        val unconfirmedSats: Long,
        val lockedInEscrowSats: Long = 0L,
        val txs: List<ChainMonitor.AddressTx>
    ) {
        fun addressFor(type: BitcoinAddressType): String = addresses[type].orEmpty()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Fail-closed Tor gate: when Tor is enabled but not connected the load is
    // held behind an explicit direct-override dialog instead of failing silently.
    private val _pendingTorBlock = MutableStateFlow(false)
    val pendingTorBlock: StateFlow<Boolean> = _pendingTorBlock.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _sendFeeEstimate = MutableStateFlow<Long?>(null)
    val sendFeeEstimate: StateFlow<Long?> = _sendFeeEstimate.asStateFlow()
    private val _feeEstimateLoading = MutableStateFlow(false)
    val feeEstimateLoading: StateFlow<Boolean> = _feeEstimateLoading.asStateFlow()

    /** Fetch a fresh fee estimate for the send-confirm preview. */
    fun estimateSendFee(
        amountSats: Long,
        fromType: BitcoinAddressType?,
        tier: WalletFeePolicy.FeeTier = WalletFeePolicy.FeeTier.FAST,
        customRate: Long? = null
    ) {
        if (_feeEstimateLoading.value) return
        _feeEstimateLoading.value = true
        _sendFeeEstimate.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _sendFeeEstimate.value =
                    walletService.estimateSendFee(amountSats, fromType, tier, customRate).getOrNull()
            } finally {
                _feeEstimateLoading.value = false
            }
        }
    }

    private val _copiedEvent = MutableStateFlow(0L)
    val copiedEvent: StateFlow<Long> = _copiedEvent.asStateFlow()

    /** P0.7: the receive index currently reserved by the on-screen flow. */
    private var reservedReceive: WalletService.ReceiveAddresses? = null

    init {
        refresh()
    }

    /** Reserve a fresh receive index for the receive flow (idempotent). */
    fun beginReceive() {
        if (reservedReceive != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val receive = runCatching { walletService.reserveReceiveAddress() }.getOrNull()
                ?: return@launch
            reservedReceive = receive
            val current = _uiState.value
            if (current is UiState.Success) {
                _uiState.value = current.copy(data = current.data.copy(addresses = receive.asMap()))
            }
        }
    }

    /** Release the reservation when the receive flow leaves the screen. */
    fun endReceive() {
        val index = reservedReceive?.index ?: return
        reservedReceive = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { walletService.releaseReceiveIndex(index) }
        }
    }

    fun refresh() {
        // Fail closed: Tor enabled but not connected offers the explicit direct
        // override instead of silently failing every explorer request.
        if (TorBlockDecision.shouldOfferOverride(
                torManager.state.value !is TorState.Disabled,
                torManager.state.value
            )
        ) {
            _pendingTorBlock.value = true
            return
        }
        refreshInternal(direct = false)
    }

    fun clearPendingTorBlock() {
        _pendingTorBlock.value = false
        // A dismissed prompt on a cold open would otherwise strand the screen
        // on the un-retryable Loading state; fall back to the Error view so
        // the user can retry once Tor connects or after choosing direct.
        if (_uiState.value !is UiState.Success) {
            _uiState.value = UiState.Error(context.getString(R.string.tor_override_title))
        }
    }

    /** Runs the pending load under the explicit action-scoped direct override. */
    fun confirmTorOverride() {
        if (!_pendingTorBlock.value) return
        _pendingTorBlock.value = false
        refreshInternal(direct = true)
    }

    private fun refreshInternal(direct: Boolean) {
        val previous = _uiState.value
        if (previous is UiState.Success) {
            // Keep old content visible; the pull indicator carries the loading state.
            _uiState.value = UiState.Success(previous.data, refreshing = true)
        } else {
            _uiState.value = UiState.Loading
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Cold open: render the last persisted snapshot at once instead of
            // blocking on the full HD scan (tens of seconds on a high-RTT
            // link); the live scan below replaces it when it completes.
            if (previous !is UiState.Success) {
                walletService.cachedState()?.let { cached ->
                    _uiState.value = UiState.Success(
                        buildData(reservedReceive?.asMap() ?: cached.addresses, cached),
                        refreshing = true
                    )
                }
            }
            try {
                // R11: one override covers the whole scan (many requests);
                // runDirect clears it in a finally so the next action blocks again.
                val loaded = if (direct) {
                    torHttpPolicy.runDirect { walletService.loadState() }
                } else {
                    walletService.loadState()
                }
                val state = loaded.getOrElse {
                    val fallback = (_uiState.value as? UiState.Success)?.data
                    if (fallback != null) {
                        _uiState.value = UiState.Success(fallback, refreshing = false)
                    } else {
                        _uiState.value = UiState.Error(it.message ?: "Wallet load failed")
                    }
                    _error.value = it.message ?: "Wallet load failed"
                    return@launch
                }
                _uiState.value = UiState.Success(
                    buildData(reservedReceive?.asMap() ?: state.addresses, state),
                    refreshing = false
                )
            } catch (e: Exception) {
                val fallback = (_uiState.value as? UiState.Success)?.data
                if (fallback != null) {
                    _uiState.value = UiState.Success(fallback, refreshing = false)
                } else {
                    _uiState.value = UiState.Error(e.message ?: "Wallet load failed")
                }
                _error.value = e.message ?: "Wallet load failed"
            }
        }
    }

    private suspend fun buildData(
        addresses: Map<BitcoinAddressType, String>,
        state: WalletService.WalletState
    ): WalletData = WalletData(
        addresses = addresses,
        totalSats = state.totalSats,
        confirmedSats = state.confirmedSats,
        unconfirmedSats = state.unconfirmedSats,
        lockedInEscrowSats = lockedInEscrowSats(),
        txs = state.txs
    )

    fun send(
        toAddress: String,
        amountSats: Long,
        fromType: BitcoinAddressType? = null,
        maxFeeSats: Long? = null,
        tier: WalletFeePolicy.FeeTier = WalletFeePolicy.FeeTier.FAST,
        customRate: Long? = null
    ) {
        if (_isSending.value) return
        _isSending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                walletService.send(toAddress, amountSats, fromType, maxFeeSats, tier, customRate)
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

    /**
     * Sum of deposits the CURRENT identity (as SELLER) has locked in live
     * escrows. The deposit physically left the wallet into the 2-of-3
     * multisig, so this is NOT subtracted from the balance — it is shown as
     * a separate "locked" line so the user understands where the funds went.
     * Terminal states (RELEASED/REFUNDED/CANCELLED) no longer lock anything.
     */
    private suspend fun lockedInEscrowSats(): Long {
        val myPeerId = runCatching { identityManager.myPeerId() }.getOrNull() ?: return 0L
        val active = setOf(
            EscrowStatus.FUNDING, EscrowStatus.FUNDED, EscrowStatus.SIGNED,
            EscrowStatus.PAYMENT_PENDING, EscrowStatus.RECEIPT_SENT,
            EscrowStatus.CONFIRMING, EscrowStatus.DISPUTED, EscrowStatus.RESOLVING
        )
        return try {
            escrowDao.getAllEscrowsSync()
                .filter { it.seller_peer_id.equals(myPeerId, ignoreCase = true) }
                .filter { runCatching { EscrowStatus.valueOf(it.status) }.getOrNull() in active }
                // funded_amount_sats is the ACTUAL on-chain funding value
                // (Room v24): equals the deposit for exact deposits, HIGHER
                // when the seller overpaid. The excess is locked in the
                // multisig too and returns to the seller via payout/refund,
                // so it belongs in the locked figure. Pre-v24 rows fall back
                // to the deposit.
                .sumOf { it.funded_amount_sats ?: it.deposit_amount_sats }
        } catch (e: Exception) {
            0L
        }
    }

    fun consumeError() {
        _error.value = null
    }
}
