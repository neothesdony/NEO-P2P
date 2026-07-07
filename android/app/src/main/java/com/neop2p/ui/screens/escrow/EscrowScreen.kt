package com.neop2p.ui.screens.escrow

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
    modifier: Modifier = Modifier
) {
    val viewModel: EscrowViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Escrow Details") },
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
                                onDispute = { viewModel.disputeEscrow() }
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
        ),
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
private fun EscrowContent(
    escrow: Escrow,
    onConfirmPayment: () -> Unit,
    onReleaseFunds: () -> Unit,
    onDispute: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
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
                contentDescription = "Escrow status",
                modifier = Modifier
                    .size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when (escrow.status) {
                        EscrowStatus.FUNDING -> "Waiting for deposit"
                        EscrowStatus.FUNDED -> "Deposit confirmed"
                        EscrowStatus.SIGNED -> "Ready to release"
                        EscrowStatus.RELEASED -> "Completed"
                        EscrowStatus.DISPUTED -> "In dispute"
                        EscrowStatus.RESOLVING -> "Arbitrator reviewing"
                        EscrowStatus.REFUNDED -> "Refunded"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Escrow #${escrow.escrowId.take(6)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // Trade details
        Column(
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            Text("Trade Details", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Amount")
                Text("${escrow.tradeAmountSats / 100_000_000.00000000} BTC")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Price per BTC")
                val pricePerSat = if (escrow.tradeAmountSats > 0) escrow.depositAmountSats.toDouble() / escrow.tradeAmountSats else 0.0
                Text("Rp ${String.format("%,.0f", pricePerSat / 100_000_000)},00")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("NEO-P2P Fee (1%)")
                Text("${escrow.feeAmountSats} sats")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Total Required")
                Text("${escrow.depositAmountSats} sats")
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // Payment instructions
        Column(
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            Text("Payment Instructions", style = MaterialTheme.typography.titleMedium)
            // For v1: show placeholder - in real app, get from peer via chat
            Text(
                text = "Bank Transfer:\n" +
                     "Bank: BCA\n" +
                     "A/N: *** Sari\n" +
                     "No: 1234567890\n" +
                     "Amount: Rp 1,500,000\n" +
                     "Note: \"btc123\"",
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
                        Text("I've Made Payment")
                    }
                }
                EscrowStatus.FUNDED -> {
                    Button(
                        onClick = onReleaseFunds,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Release Funds")
                    }
                }
                EscrowStatus.RELEASED -> {
                    Text(
                        text = "Funds released to counterparty",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                EscrowStatus.DISPUTED -> {
                    Text(
                        text = "Dispute in progress - 7-day timelock active",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                EscrowStatus.RESOLVING -> {
                    Text(
                        text = "Arbitrator reviewing evidence",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                EscrowStatus.SIGNED, EscrowStatus.REFUNDED -> {
                    Text(
                        text = "Transaction complete",
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
                Text("Fee Transparency", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "1% of every trade goes to:",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = escrow.feeAddress,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Text(
                    text = "(This address is hardcoded in the open-source app)",
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
    private val escrowService: EscrowService
) : ViewModel() {

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
                // For v1: load mock data
                val mockEscrow = Escrow(
                    escrowId = "escrow_123456",
                    offerId = "offer_123",
                    type = EscrowType.LIGHTNING,
                    depositAmountSats = 1_010_000, // 1.01 BTC (1% fee)
                    tradeAmountSats = 1_000_000, // 1.00 BTC
                    feeAmountSats = 10_000, // 0.01 BTC fee
                    feeAddress = NeoP2PConfig.FEE_WALLET_ADDRESS,
                    buyerPeerId = "buyer_peer_123",
                    sellerPeerId = "seller_peer_456",
                    status = EscrowStatus.FUNDING
                )

                _uiState.value = UiState.Success(EscrowData(mockEscrow))
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
