package com.neop2p.ui.screens.escrow

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.neop2p.R
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toDomain
import com.neop2p.data.p2p.IdentityManager
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
    val showRefundDialog by viewModel.showRefundDialog.collectAsStateWithLifecycle()
    val refundDestination by viewModel.refundDestination.collectAsStateWithLifecycle()
    val refundFeeEstimate by viewModel.refundFeeEstimate.collectAsStateWithLifecycle()
    val refundBusy by viewModel.refundBusy.collectAsStateWithLifecycle()
    val refundError by viewModel.refundError.collectAsStateWithLifecycle()

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
                                isRole = data.role,
                                // Funding gate: seller provides the funding txid which is
                                // verified on-chain before the trade can proceed.
                                fundingTxId = data.fundingTxId,
                                onFundingTxIdChanged = { viewModel.setFundingTxId(it) },
                                onVerifyFundingTx = { viewModel.verifyFunding() },
                                onConfirmPayout = { viewModel.confirmPayout() },
                                onReleaseFunds = { viewModel.releaseFunds() },
                                onDispute = { viewModel.disputeEscrow() },
                                onCancelRefund = { viewModel.openRefundDialog() },
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        )

        if (showRefundDialog) {
            (state as? EscrowViewModel.UiState.Success)?.let { success ->
                RefundEscrowDialog(
                    escrow = success.data.escrow,
                    destinationAddress = refundDestination,
                    onDestinationAddressChange = { viewModel.onRefundDestinationChange(it) },
                    feeEstimate = refundFeeEstimate,
                    busy = refundBusy,
                    error = refundError,
                    onConfirm = { viewModel.cancelRefund() },
                    onDismiss = { viewModel.closeRefundDialog() }
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) = Box(
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
        modifier = Modifier.size(64.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = message,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(onClick = onRetry, modifier = Modifier.width(120.dp).height(40.dp)) {
        Text(stringResource(R.string.general_retry))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EscrowStatusChip(status: EscrowStatus, modifier: Modifier = Modifier) {
    val (container, content) = when (status) {
        EscrowStatus.FUNDING -> Color(0xFF854D0E) to Color(0xFFFCD34D)
        EscrowStatus.FUNDED -> Color(0xFF065F46) to Color(0xFF6EE7B7)
        EscrowStatus.SIGNED -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
        EscrowStatus.RELEASED -> Color(0xFF065F46) to Color(0xFF6EE7B7)
        EscrowStatus.DISPUTED -> Color(0xFF7F1D1D) to Color(0xFFFCA5A5)
        EscrowStatus.RESOLVING -> Color(0xFF581C87) to Color(0xFFC084FC)
        EscrowStatus.CANCELLED -> Color(0xFF78350F) to Color(0xFFFDE68A)
        EscrowStatus.REFUNDED -> Color(0xFF1F2937) to Color(0xFFD1D5DB)
    }
    Surface(shape = RoundedCornerShape(50), color = container, modifier = modifier) {
        Text(
            text = when (status) {
                EscrowStatus.FUNDING -> stringResource(R.string.escrow_status_pending)
                EscrowStatus.FUNDED -> stringResource(R.string.escrow_status_funded)
                EscrowStatus.SIGNED -> stringResource(R.string.escrow_status_signed)
                EscrowStatus.RELEASED -> stringResource(R.string.profile_completed)
                EscrowStatus.DISPUTED -> stringResource(R.string.escrow_status_disputed)
                EscrowStatus.RESOLVING -> stringResource(R.string.escrow_status_resolving)
                EscrowStatus.CANCELLED -> stringResource(R.string.escrow_status_cancelled)
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
    isRole: EscrowRole,
    fundingTxId: String,
    onFundingTxIdChanged: (String) -> Unit,
    onVerifyFundingTx: () -> Unit,
    onConfirmPayout: () -> Unit,
    onReleaseFunds: () -> Unit,
    onDispute: () -> Unit,
    onCancelRefund: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState())) {
        // Network warning banner
        if (BuildConfig.NETWORK == "mainnet") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
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
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
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
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = when (escrow.status) {
                        EscrowStatus.FUNDING -> stringResource(R.string.escrow_status_waiting_deposit)
                        EscrowStatus.FUNDED -> stringResource(R.string.escrow_status_deposit_confirmed)
                        EscrowStatus.SIGNED -> stringResource(R.string.escrow_status_ready_release)
                        EscrowStatus.RELEASED -> stringResource(R.string.profile_completed)
                        EscrowStatus.DISPUTED -> stringResource(R.string.escrow_status_in_dispute)
                        EscrowStatus.RESOLVING -> stringResource(R.string.escrow_status_reviewing)
                        EscrowStatus.CANCELLED -> stringResource(R.string.escrow_status_cancelled_desc)
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Trade details
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(stringResource(R.string.escrow_trade_details), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.escrow_amount))
                Text(stringResource(R.string.common_btc_amount, (escrow.tradeAmountSats / 100_000_000.0).toString()))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.escrow_fee))
                Text(stringResource(R.string.common_sats, escrow.feeAmountSats))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.escrow_network_fee))
                Text(stringResource(R.string.common_sats, escrow.networkFeeSats))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.escrow_total_required))
                Text(stringResource(R.string.common_sats, escrow.depositAmountSats))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── FUNDING step: the SELLER transfers BTC to the escrow address. ──
        if (escrow.status == EscrowStatus.FUNDING) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(stringResource(R.string.escrow_funding_address_label), style = MaterialTheme.typography.titleMedium)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = escrow.fundingAddress ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 3
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.escrow_deposit_required, escrow.depositAmountSats),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = fundingTxId,
                    onValueChange = onFundingTxIdChanged,
                    label = { Text(stringResource(R.string.escrow_funding_txid_label)) },
                    placeholder = { Text(stringResource(R.string.escrow_funding_txid_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                // The transfer is NOT trusted blindly: it is verified on-chain via
                // Mempool.space before the escrow may proceed past FUNDING.
                Button(
                    onClick = onVerifyFundingTx,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = fundingTxId.isNotBlank()
                ) {
                    Text(stringResource(R.string.escrow_verify_funding))
                }
                Spacer(Modifier.height(8.dp))
                // Fix 2: inform the user of the 15-minute auto-refund/auto-cancel.
                Text(
                    text = stringResource(R.string.escrow_timeout_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancelRefund,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.escrow_cancel_refund))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            return
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Action buttons (post-funding)
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            when (escrow.status) {
                EscrowStatus.FUNDED -> {
                    // Escrow funded & verified. Seller signs payout as SELLER with
                    // the seller's own key (role-appropriate, P0-1). Then release.
                    Text(
                        text = stringResource(R.string.escrow_funded_confirm_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onConfirmPayout, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(stringResource(R.string.escrow_release_funds))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCancelRefund,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.escrow_cancel_refund))
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
                }
                EscrowStatus.RESOLVING -> {
                    Text(
                        text = stringResource(R.string.escrow_arbitrator_reviewing),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                EscrowStatus.SIGNED, EscrowStatus.REFUNDED -> {
                    Text(
                        text = stringResource(R.string.escrow_transaction_complete),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                EscrowStatus.CANCELLED -> {
                    Text(
                        text = stringResource(R.string.escrow_status_cancelled_desc),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {}
            }
            // Dispute is always available until funds are released.
            if (escrow.status == EscrowStatus.FUNDED || escrow.status == EscrowStatus.SIGNED) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDispute,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.escrow_dispute))
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Fee transparency
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
            }
        }
    }
}

/**
 * Dialog for the "Cancel escrow & refund" flow.
 *
 * Asks where to withdraw the seller's deposit, shows the estimated network fee
 * and the refund amount after fee, then lets the user confirm.
 */
@Composable
private fun RefundEscrowDialog(
    escrow: Escrow,
    destinationAddress: String,
    onDestinationAddressChange: (String) -> Unit,
    feeEstimate: com.neop2p.data.escrow.EscrowService.RefundEstimateInfo?,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.escrow_cancel_refund_title)) },
        text = {
            Column(modifier = modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.escrow_refund_intro, escrow.depositAmountSats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // Fix 1: clear hint that the refund defaults to the seller's own wallet.
                Text(
                    text = stringResource(R.string.escrow_refund_seller_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = destinationAddress,
                    onValueChange = onDestinationAddressChange,
                    label = { Text(stringResource(R.string.escrow_refund_destination_label)) },
                    placeholder = { Text(stringResource(R.string.escrow_refund_destination_placeholder)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                feeEstimate?.let { est ->
                    Text(
                        text = stringResource(R.string.escrow_refund_fee_estimate, est.networkFeeSats),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.escrow_refund_amount_after_fee, est.refundAmountSats),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (feeEstimate == null && !busy) {
                    Text(
                        text = stringResource(R.string.escrow_refund_fee_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && destinationAddress.isNotBlank() && feeEstimate != null
            ) {
                Text(stringResource(R.string.escrow_refund_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.general_cancel))
            }
        },
        modifier = modifier
    )
}

enum class EscrowRole { BUYER, SELLER, ARBITRATOR, UNKNOWN }

@HiltViewModel
class EscrowViewModel @Inject constructor(
    private val escrowService: EscrowService,
    private val identityManager: IdentityManager,
    private val offerDao: OfferDao,
    savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "EscrowViewModel"
    }

    private val escrowId: String =
        savedStateHandle.get<String>("escrowId") ?: ""

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _fundingTxId = MutableStateFlow("")
    fun setFundingTxId(v: String) { _fundingTxId.value = v }

    // ── Cancel escrow & refund dialog state ──
    private val _showRefundDialog = MutableStateFlow(false)
    val showRefundDialog: StateFlow<Boolean> = _showRefundDialog.asStateFlow()

    private val _refundDestination = MutableStateFlow("")
    val refundDestination: StateFlow<String> = _refundDestination.asStateFlow()

    private val _refundFeeEstimate =
        MutableStateFlow<com.neop2p.data.escrow.EscrowService.RefundEstimateInfo?>(null)
    val refundFeeEstimate: StateFlow<com.neop2p.data.escrow.EscrowService.RefundEstimateInfo?> =
        _refundFeeEstimate.asStateFlow()

    private val _refundBusy = MutableStateFlow(false)
    val refundBusy: StateFlow<Boolean> = _refundBusy.asStateFlow()

    private val _refundError = MutableStateFlow<String?>(null)
    val refundError: StateFlow<String?> = _refundError.asStateFlow()

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: EscrowData) : UiState()
    }

    data class EscrowData(
        val escrow: Escrow,
        val role: EscrowRole,
        val fundingTxId: String,
        val buyerAddress: String
    )

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
                    _uiState.value = UiState.Success(
                        EscrowData(escrow, determineRole(escrow), _fundingTxId.value, buyerAddressFor(escrow))
                    )
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load escrow: ${e.message}")
            }
        }
    }

    /** Resolve the buyer's BTC receive address from the underlying offer. */
    private suspend fun buyerAddressFor(escrow: Escrow): String {
        return try {
            offerDao.getOffer(escrow.offerId).firstOrNull()?.toDomain()?.btcReceiveAddress
                ?.takeIf { it.isNotBlank() }
                ?: escrow.fundingAddress
                ?: ""
        } catch (e: Exception) {
            escrow.fundingAddress ?: ""
        }
    }

    /** Determine the current user's role using their own Bitcoin pubkey (P0-1). */
    private fun determineRole(escrow: Escrow): EscrowRole {
        val myPub = identityManager.getBitcoinPubKeyHex()
        return when {
            myPub.equals(escrow.buyerPubKeyHex, ignoreCase = true) -> EscrowRole.BUYER
            myPub.equals(escrow.sellerPubKeyHex, ignoreCase = true) -> EscrowRole.SELLER
            else -> EscrowRole.UNKNOWN
        }
    }

    fun refresh() {
        _uiState.value = UiState.Loading
        loadEscrow()
    }

    /**
     * Funding gate: the seller provides a txid; it is verified on-chain via
     * Mempool.space before the escrow can proceed past FUNDING. A blind "I've
     * paid" is not accepted — the transition is blocked if verification fails.
     */
    fun verifyFunding() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                val txid = _fundingTxId.value.trim()
                if (txid.isBlank()) {
                    _uiState.value = UiState.Error("Please provide a funding transaction id")
                    return@launch
                }
                val result = escrowService.onEscrowFunded(current.escrowId, txid)
                val updated = result.getOrNull()
                if (updated != null) {
                    _uiState.value = UiState.Success(
                        EscrowData(updated, determineRole(updated), _fundingTxId.value, buyerAddressFor(updated))
                    )
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Funding verification failed"
                    _uiState.value = UiState.Error("Funding not confirmed: $err")
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to verify funding: ${e.message}")
            }
        }
    }

    /**
     * After funding is confirmed, generate the payout and sign it as the role
     * the current user holds (buyer or seller) using their OWN key. This fixes
     * the old bug where the UI always signed with one key for both roles.
     */
    fun confirmPayout() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = (_uiState.value as? UiState.Success)?.data ?: return@launch
                val escrow = data.escrow
                val txid = escrow.fundingTxId ?: return@launch
                val privHex = identityManager.getBitcoinPrivateKeyHex()

                escrowService.generatePayoutTransaction(
                    escrowId = escrow.escrowId,
                    fundingTxId = txid,
                    buyerAddressStr = data.buyerAddress
                ).getOrThrow()

                // Sign with the role-appropriate key.
                val signed = when (data.role) {
                    EscrowRole.BUYER -> escrowService.signPayoutAsBuyer(escrow.escrowId, privHex)
                    EscrowRole.SELLER -> escrowService.signPayoutAsSeller(escrow.escrowId, privHex)
                    else -> Result.failure(Exception("Current user is not a signer on this escrow"))
                }
                if (signed.isFailure) {
                    _uiState.value = UiState.Error(signed.exceptionOrNull()?.message ?: "Signing failed")
                    return@launch
                }

                // Only the seller's role actually broadcasts (they hold the payout key
                // path); in a real 2-of-3 the counterparty supplies the second sig.
                if (data.role == EscrowRole.SELLER) {
                    val released = escrowService.releaseFunds(escrow.escrowId)
                    if (released.isFailure) {
                        _uiState.value = UiState.Error(released.exceptionOrNull()?.message ?: "Release failed")
                        return@launch
                    }
                }
                loadEscrow()
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to process payout: ${e.message}")
            }
        }
    }

    fun releaseFunds() = confirmPayout()

    fun disputeEscrow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                val updated = escrowService.disputeEscrow(current.escrowId).getOrNull()
                updated?.let { escrow ->
                    _uiState.value = UiState.Success(
                        EscrowData(escrow, determineRole(escrow), _fundingTxId.value, buyerAddressFor(escrow))
                    )
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to dispute: ${e.message}")
            }
        }
    }

    // ── Cancel escrow & refund ──

    fun openRefundDialog() {
        // Fix 1: pre-fill the refund destination with the current user's own
        // Bitcoin address (the seller/depositor). A cancelled escrow refunds to
        // the depositor by default; the user may still change it.
        _refundDestination.value = try {
            identityManager.getBitcoinAddress()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive seller refund address", e)
            ""
        }
        _refundError.value = null
        _refundFeeEstimate.value = null
        _showRefundDialog.value = true
        loadRefundEstimate()
    }

    fun closeRefundDialog() {
        if (_refundBusy.value) return
        _showRefundDialog.value = false
    }

    fun onRefundDestinationChange(v: String) {
        _refundDestination.value = v
    }

    /** Load the network fee + refund amount estimate for the dialog. */
    private fun loadRefundEstimate() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val escrow = (_uiState.value as? UiState.Success)?.data?.escrow
                    ?: return@launch
                val result = escrowService.getRefundEstimate(escrow.escrowId)
                if (result.isSuccess) {
                    _refundFeeEstimate.value = result.getOrThrow()
                } else {
                    _refundError.value = result.exceptionOrNull()?.message
                }
            } catch (e: Exception) {
                _refundError.value = e.message
            }
        }
    }

    /**
     * Cancel the escrow and refund the seller's deposit to the destination
     * address entered in the dialog. Uses the current user's own Bitcoin key.
     */
    fun cancelRefund() {
        val destination = _refundDestination.value.trim()
        if (destination.isBlank()) {
            _refundError.value = "Enter a destination address"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _refundBusy.value = true
            _refundError.value = null
            try {
                val escrow = (_uiState.value as? UiState.Success)?.data?.escrow
                    ?: return@launch
                val privHex = identityManager.getBitcoinPrivateKeyHex()
                val result = escrowService.cancelEscrowRefund(
                    escrowId = escrow.escrowId,
                    destinationAddressStr = destination,
                    privKeyHex = privHex
                )
                if (result.isSuccess) {
                    val updated = result.getOrThrow()
                    _showRefundDialog.value = false
                    _uiState.value = UiState.Success(
                        EscrowData(updated, determineRole(updated), _fundingTxId.value, buyerAddressFor(updated))
                    )
                } else {
                    _refundError.value = result.exceptionOrNull()?.message
                }
            } catch (e: Exception) {
                _refundError.value = "Refund failed: ${e.message}"
            } finally {
                _refundBusy.value = false
            }
        }
    }
}
