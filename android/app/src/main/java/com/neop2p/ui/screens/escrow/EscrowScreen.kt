package com.neop2p.ui.screens.escrow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neop2p.BuildConfig
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.escrow.EscrowService
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EscrowScreen(
    escrowId: String,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onEvidenceClick: (escrowId: String, submitterPeerId: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val viewModel: EscrowViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.escrow_details_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.general_back)
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
                    when (val s = state) {
                        is EscrowViewModel.UiState.Loading -> LoadingScreen()
                        is EscrowViewModel.UiState.Error -> ErrorScreen(
                            message = s.message,
                            onRetry = { viewModel.refresh() }
                        )
                        is EscrowViewModel.UiState.Success -> {
                            val data = s.data
                            EscrowContent(
                                escrow = data.escrow,
                                onConfirmPayment = { viewModel.confirmPayment() },
                                onReleaseFunds = { viewModel.releaseFunds() },
                                onDispute = { viewModel.disputeEscrow() },
                                onEvidenceClick = onEvidenceClick
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
        .background(color = MaterialTheme.colorScheme.background),
    contentAlignment = Alignment.Center
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
        .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_warning),
        contentDescription = stringResource(R.string.general_error),
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
        Text(stringResource(R.string.general_retry))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EscrowStatusChip(
    status: EscrowStatus,
    modifier: Modifier = Modifier
) {
    val (container, content) = when (status) {
        EscrowStatus.FUNDING -> Color(0xFF854D0E) to Color(0xFFFCD34D)      // pending amber
        EscrowStatus.FUNDED -> Color(0xFF065F46) to Color(0xFF6EE7B7)       // success green
        EscrowStatus.SIGNED -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)       // active blue
        EscrowStatus.RELEASED -> Color(0xFF065F46) to Color(0xFF6EE7B7)     // success green
        EscrowStatus.DISPUTED -> Color(0xFF7F1D1D) to Color(0xFFFCA5A5)     // dispute red
        EscrowStatus.RESOLVING -> Color(0xFF581C87) to Color(0xFFC084FC)    // settled purple
        EscrowStatus.REFUNDED -> Color(0xFF1F2937) to Color(0xFFD1D5DB)     // inactive grey
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        modifier = modifier
    ) {
        Text(
            text = when (status) {
                EscrowStatus.FUNDING -> stringResource(R.string.escrow_status_pending)
                EscrowStatus.FUNDED -> stringResource(R.string.escrow_status_funded)
                EscrowStatus.SIGNED -> stringResource(R.string.escrow_status_signed)
                EscrowStatus.RELEASED -> stringResource(R.string.profile_completed)
                EscrowStatus.DISPUTED -> stringResource(R.string.escrow_status_disputed)
                EscrowStatus.RESOLVING -> stringResource(R.string.escrow_status_resolving)
                EscrowStatus.REFUNDED -> stringResource(R.string.escrow_status_refunded)
            },
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EscrowContent(
    escrow: Escrow,
    onConfirmPayment: () -> Unit,
    onReleaseFunds: () -> Unit,
    onDispute: () -> Unit,
    onEvidenceClick: (escrowId: String, submitterPeerId: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        // Network warning banner
        if (BuildConfig.NETWORK == "mainnet") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.escrow_mainnet_warning),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Escrow header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Icon(
                painter = painterResource(
                    when (escrow.status) {
                        EscrowStatus.FUNDING -> R.drawable.ic_lock_open
                        EscrowStatus.FUNDED -> R.drawable.ic_lock
                        EscrowStatus.RELEASED -> R.drawable.ic_lock_open
                        EscrowStatus.DISPUTED -> R.drawable.ic_warning
                        else -> R.drawable.ic_help
                    }
                ),
                contentDescription = stringResource(R.string.escrow_cd_status),
                modifier = Modifier
                    .size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when (escrow.status) {
                        EscrowStatus.FUNDING -> stringResource(R.string.escrow_status_waiting_deposit)
                        EscrowStatus.FUNDED -> stringResource(R.string.escrow_status_deposit_confirmed)
                        EscrowStatus.SIGNED -> stringResource(R.string.escrow_status_ready_release)
                        EscrowStatus.RELEASED -> stringResource(R.string.profile_completed)
                        EscrowStatus.DISPUTED -> stringResource(R.string.escrow_status_in_dispute)
                        EscrowStatus.RESOLVING -> stringResource(R.string.escrow_status_reviewing)
                        EscrowStatus.REFUNDED -> stringResource(R.string.escrow_status_refunded)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(stringResource(R.string.escrow_id_format, escrow.escrowId.take(6)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            EscrowStatusChip(status = escrow.status)
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // Trade details
        Column(
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            Text(stringResource(R.string.escrow_trade_details), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.escrow_amount))
                Text(stringResource(R.string.common_btc_amount, (escrow.tradeAmountSats / 100_000_000.0).toString()))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.escrow_price_per_btc))
                val pricePerSat = if (escrow.tradeAmountSats > 0) escrow.depositAmountSats.toDouble() / escrow.tradeAmountSats else 0.0
                Text(stringResource(R.string.escrow_price_display, String.format("%,.0f", pricePerSat / 100_000_000)))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.escrow_fee))
                Text(stringResource(R.string.common_sats, escrow.feeAmountSats))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.escrow_total_required))
                Text(stringResource(R.string.common_sats, escrow.depositAmountSats))
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // Payment instructions
        Column(
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            Text(stringResource(R.string.escrow_payment_instructions), style = MaterialTheme.typography.titleMedium)
            // For v1: show placeholder - in real app, get from peer via chat
            Text(
                text = stringResource(R.string.escrow_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // Action buttons
        Column(
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            when (escrow.status) {
                EscrowStatus.FUNDING -> {
                    Button(
                        onClick = onConfirmPayment,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(stringResource(R.string.escrow_confirm_payment))
                    }
                }
                EscrowStatus.FUNDED -> {
                    Button(
                        onClick = onReleaseFunds,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(stringResource(R.string.escrow_release_funds))
                    }
                }
                EscrowStatus.RELEASED -> {
                    Text(
                        text = stringResource(R.string.escrow_released_to_counterparty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                EscrowStatus.DISPUTED -> {
                    Text(
                        text = stringResource(R.string.escrow_dispute_timelock),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onEvidenceClick(escrow.escrowId, escrow.buyerPeerId) },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_attach_file),
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.escrow_submit_evidence))
                    }
                }
                EscrowStatus.RESOLVING -> {
                    Text(
                        text = stringResource(R.string.escrow_arbitrator_reviewing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { onEvidenceClick(escrow.escrowId, escrow.buyerPeerId) },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_insert_drive_file),
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.escrow_view_evidence))
                    }
                }
                EscrowStatus.SIGNED, EscrowStatus.REFUNDED -> {
                    Text(
                        text = stringResource(R.string.escrow_transaction_complete),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Fee transparency
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.escrow_fee_transparency), style = MaterialTheme.typography.labelMedium)
                Text(
                    text = stringResource(R.string.escrow_fee_text),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = escrow.feeAddress,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Text(
                    text = stringResource(R.string.escrow_hardcoded_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ─── ViewModel ───────────────────────────────────────────────
@HiltViewModel
class EscrowViewModel @Inject constructor(
    private val escrowService: EscrowService,
    savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    private val escrowId: String =
        savedStateHandle.get<String>("escrowId") ?: ""

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: EscrowData) : UiState()
    }

    data class EscrowData(
        val escrow: Escrow
    )

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        loadEscrow()
    }

    private fun loadEscrow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val escrow = escrowService.getEscrow(escrowId)
                if (escrow == null) {
                    _uiState.value = UiState.Error("Escrow not found")
                } else {
                    _uiState.value = UiState.Success(EscrowData(escrow))
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load escrow: ${e.message}")
            }
        }
    }

    fun refresh() {
        _uiState.value = UiState.Loading
        loadEscrow()
    }

    fun confirmPayment() {
        // In real app: verify payment via chat confirmation
        // For v1: simulate release
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow
                    ?: return@launch

                val updated = escrowService.releaseFunds(current.escrowId).getOrNull()
                updated?.let { escrow ->
                    _uiState.value = UiState.Success(EscrowData(escrow))
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to confirm: ${e.message}")
            }
        }
    }

    fun releaseFunds() {
        confirmPayment() // Same action
    }

    fun disputeEscrow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow
                    ?: return@launch

                val updated = escrowService.disputeEscrow(current.escrowId).getOrNull()
                updated?.let { escrow ->
                    _uiState.value = UiState.Success(EscrowData(escrow))
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to dispute: ${e.message}")
            }
        }
    }
}
