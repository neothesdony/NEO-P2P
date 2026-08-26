package com.neop2p.ui.screens.escrow

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import com.neop2p.domain.model.BitcoinAddressType
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
    onEvidenceClick: (escrowId: String) -> Unit = {},
    onOpenReceipt: (escrowId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: EscrowViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val showRefundDialog by viewModel.showRefundDialog.collectAsStateWithLifecycle()
    val refundDestination by viewModel.refundDestination.collectAsStateWithLifecycle()
    val refundFeeEstimate by viewModel.refundFeeEstimate.collectAsStateWithLifecycle()
    val refundBusy by viewModel.refundBusy.collectAsStateWithLifecycle()
    val refundError by viewModel.refundError.collectAsStateWithLifecycle()
    val fundingBusy by viewModel.fundingBusy.collectAsStateWithLifecycle()
    val fundingError by viewModel.fundingError.collectAsStateWithLifecycle()
    val fundingMessage by viewModel.fundingMessage.collectAsStateWithLifecycle()
    val showRating by viewModel.showRating.collectAsStateWithLifecycle()
    val ratingBusy by viewModel.ratingBusy.collectAsStateWithLifecycle()
    val ratingError by viewModel.ratingError.collectAsStateWithLifecycle()
    var showFundingConfirm by remember { mutableStateOf(false) }
    var showMarkPaidConfirm by remember { mutableStateOf(false) }

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
                        is EscrowViewModel.UiState.Pending -> EscrowPendingScreen(
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
                                onFundFromWallet = { showFundingConfirm = true },
                                onSwitchFundingType = { viewModel.switchFundingType(it) },
                                fundingBusy = fundingBusy,
                                fundingError = fundingError,
                                fundingMessage = fundingMessage,
                                onConsumeFundingMessage = { viewModel.consumeFundingMessage() },
                                onConsumeFundingError = { viewModel.consumeFundingError() },
                                onMarkPaid = { showMarkPaidConfirm = true },
                                onDispute = { viewModel.disputeEscrow() },
                                onOpenEvidence = { onEvidenceClick(escrowId) },
                                onOpenReceipt = { onOpenReceipt(escrowId) },
                                onConfirmReceipt = { viewModel.confirmReceipt() },
                                onCancelRefund = { viewModel.openRefundDialog() },
                                paymentDetails = data.paymentDetails,
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

        if (showFundingConfirm) {
            (state as? EscrowViewModel.UiState.Success)?.let { success ->
                val esc = success.data.escrow
                AlertDialog(
                    onDismissRequest = { showFundingConfirm = false },
                    title = { Text(stringResource(R.string.escrow_funding_confirm_title)) },
                    text = { Text(stringResource(R.string.escrow_funding_confirm_body, esc.depositAmountSats)) },
                    confirmButton = {
                        Button(
                            onClick = {
                                showFundingConfirm = false
                                viewModel.fundFromWallet()
                            },
                            enabled = !fundingBusy
                        ) {
                            Text(stringResource(R.string.escrow_funding_confirm_yes))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showFundingConfirm = false }) {
                            Text(stringResource(R.string.general_cancel))
                        }
                    }
                )
            }
        }

        if (showMarkPaidConfirm) {
            AlertDialog(
                onDismissRequest = { showMarkPaidConfirm = false },
                title = { Text(stringResource(R.string.escrow_mark_paid_confirm_title)) },
                text = { Text(stringResource(R.string.escrow_mark_paid_confirm_body)) },
                confirmButton = {
                    Button(
                        onClick = {
                            showMarkPaidConfirm = false
                            viewModel.markPaid()
                        }
                    ) {
                        Text(stringResource(R.string.escrow_mark_paid_confirm_yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMarkPaidConfirm = false }) {
                        Text(stringResource(R.string.general_cancel))
                    }
                }
            )
        }

        if (showRating) {
            (state as? EscrowViewModel.UiState.Success)?.let { success ->
                RateCounterpartyDialog(
                    peerLabel = success.data.counterpartyLabel,
                    busy = ratingBusy,
                    error = ratingError,
                    onPositive = { viewModel.rateCounterparty(wasPositive = true) },
                    onNegative = { viewModel.rateCounterparty(wasPositive = false) },
                    onDismiss = { viewModel.dismissRating() }
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

/** U3: the escrow row hasn't arrived from the counterparty yet (kind:33337
 *  sync pending). Show a waiting state with a manual retry; the screen's
 *  ViewModel also polls automatically for ~2.5 min. */
@Composable
private fun EscrowPendingScreen(
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
        imageVector = Icons.Filled.Info,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp)
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.escrow_pending_waiting),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyLarge
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.escrow_pending_hint),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onRetry) {
        Text(stringResource(R.string.general_retry))
    }
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
private fun StepTracker(
    currentStep: Int,          // 0-based index into steps
    steps: List<EscrowStep>,
    labels: Map<EscrowStep, String>
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        steps.forEachIndexed { index, step ->
            val done = index < currentStep
            val active = index == currentStep
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (done) MaterialTheme.colorScheme.primary
                            else if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Text(
                        "${index + 1}",
                        Modifier.align(Alignment.Center),
                        color = if (done || active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    labels[step] ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EscrowStatusChip(
    status: EscrowStatus,
    fundingTxId: String = "",
    modifier: Modifier = Modifier
) {
    val (container, content) = when (status) {
        EscrowStatus.FUNDING -> Color(0xFF854D0E) to Color(0xFFFCD34D)
        EscrowStatus.FUNDED -> Color(0xFF065F46) to Color(0xFF6EE7B7)
        EscrowStatus.PAYMENT_PENDING -> Color(0xFF78350F) to Color(0xFFFDE68A)
        EscrowStatus.RECEIPT_SENT -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
        EscrowStatus.SIGNED -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
        EscrowStatus.CONFIRMING -> Color(0xFF1E3A8A) to Color(0xFF93C5FD)
        EscrowStatus.RELEASED -> Color(0xFF065F46) to Color(0xFF6EE7B7)
        EscrowStatus.DISPUTED -> Color(0xFF7F1D1D) to Color(0xFFFCA5A5)
        EscrowStatus.RESOLVING -> Color(0xFF581C87) to Color(0xFFC084FC)
        EscrowStatus.CANCELLED -> Color(0xFF78350F) to Color(0xFFFDE68A)
        EscrowStatus.REFUNDED -> Color(0xFF1F2937) to Color(0xFFD1D5DB)
    }
    Surface(shape = RoundedCornerShape(50), color = container, modifier = modifier) {
        Text(
            text = when (status) {
                EscrowStatus.FUNDING -> stringResource(
                    if (fundingTxId.isNotBlank()) R.string.escrow_status_in_progress
                    else R.string.escrow_status_pending
                )
                EscrowStatus.FUNDED -> stringResource(R.string.escrow_status_funded)
                EscrowStatus.PAYMENT_PENDING -> stringResource(R.string.escrow_status_payment_pending)
                EscrowStatus.RECEIPT_SENT -> stringResource(R.string.escrow_status_receipt_sent)
                EscrowStatus.SIGNED -> stringResource(R.string.escrow_status_signed)
                EscrowStatus.CONFIRMING -> stringResource(R.string.escrow_paid_status)
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
    onFundFromWallet: () -> Unit,
    onSwitchFundingType: (BitcoinAddressType) -> Unit,
    fundingBusy: Boolean,
    fundingError: String?,
    fundingMessage: String?,
    onConsumeFundingMessage: () -> Unit,
    onConsumeFundingError: () -> Unit,
    onMarkPaid: () -> Unit,
    onDispute: () -> Unit,
    onOpenEvidence: () -> Unit,
    onOpenReceipt: () -> Unit,
    onConfirmReceipt: () -> Unit,
    onCancelRefund: () -> Unit,
    paymentDetails: Map<String, com.neop2p.domain.model.PaymentDetails>,
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
                        EscrowStatus.PAYMENT_PENDING -> R.drawable.ic_help
                        EscrowStatus.RECEIPT_SENT -> R.drawable.ic_check_circle
                        EscrowStatus.CONFIRMING -> R.drawable.ic_check_circle
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
                        EscrowStatus.FUNDING -> stringResource(
                            // The deposit may already be broadcast (wallet
                            // funding) — show "waiting for confirmation" then.
                            if (fundingTxId.isNotBlank()) R.string.escrow_status_waiting_confirmation
                            else R.string.escrow_status_waiting_deposit
                        )
                        EscrowStatus.FUNDED -> stringResource(R.string.escrow_status_deposit_confirmed)
                        EscrowStatus.PAYMENT_PENDING -> stringResource(R.string.escrow_status_payment_pending)
                        EscrowStatus.RECEIPT_SENT -> stringResource(R.string.escrow_status_receipt_sent)
                        EscrowStatus.SIGNED -> stringResource(R.string.escrow_status_ready_release)
                        EscrowStatus.CONFIRMING -> stringResource(R.string.escrow_paid_status)
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
            EscrowStatusChip(status = escrow.status, fundingTxId = fundingTxId)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Guided step tracker (role-adaptive): 1 Fund → 2 Pay → 3 Confirm → 4 Release.
        val roleSteps = stepsForRole(isRole.name)
        StepTracker(
            currentStep = currentStepFor(escrow.status, roleSteps),
            steps = roleSteps,
            labels = mapOf(
                EscrowStep.FUND to stringResource(R.string.escrow_step_fund),
                EscrowStep.PAY to stringResource(R.string.escrow_step_pay),
                EscrowStep.CONFIRM to stringResource(R.string.escrow_step_confirm),
                EscrowStep.RELEASE to stringResource(R.string.escrow_step_release)
            )
        )

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

        // ── Bank transfer details (auto-shared over E2EE chat on FUNDED) ──
        // Both roles see the card once the details exist: the seller reads
        // their own stored details; the buyer reads the envelope the seller's
        // device auto-sent when the escrow became FUNDED (persisted into the
        // local offer row by ChatRouter, so the card shows even if the chat
        // was never opened).
        if (paymentDetails.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    stringResource(R.string.escrow_bank_details_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                paymentDetails.forEach { (method, details) ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = method.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                stringResource(R.string.escrow_bank_account_number, details.accountNumber),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                stringResource(R.string.escrow_bank_account_holder, details.accountHolder),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── FUNDING step: the SELLER transfers BTC to the escrow address. ──
        // Role-gated: only the SELLER funds the escrow. The buyer must NOT see
        // the escrow address / deposit buttons (their payment is FIAT to the
        // seller's bank account, which happens later at PAYMENT_PENDING) —
        // showing "Send BTC to this escrow address" to the buyer was a real bug.
        if (escrow.status == EscrowStatus.FUNDING && isRole == EscrowRole.SELLER) {
            val ctx = LocalContext.current
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(stringResource(R.string.escrow_funding_address_label), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                // Legacy (P2SH 2…) ↔ SegWit (P2WSH bc1/tb1) funding address
                // toggle — the same 2-of-3 redeem script, only the carrier
                // changes. Both types work with every modern wallet; SegWit
                // escrows pay ~half the spend fee (witness discount). The
                // address is FINAL once a deposit lands, so the toggle is
                // only offered while the escrow is still unfunded.
                Text(stringResource(R.string.escrow_funding_type_label), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BitcoinAddressType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = escrow.fundingScriptType == type,
                            onClick = {
                                // The address is FINAL once a deposit is
                                // broadcast — a pending tx pays the old address.
                                if (fundingTxId.isBlank() && escrow.fundingScriptType != type) {
                                    onSwitchFundingType(type)
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, BitcoinAddressType.entries.size)
                        ) {
                            Text(
                                stringResource(
                                    if (type == BitcoinAddressType.LEGACY)
                                        R.string.escrow_funding_type_legacy
                                    else
                                        R.string.escrow_funding_type_segwit
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = escrow.fundingAddress ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 3
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.escrow_deposit_required, escrow.depositAmountSats),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!escrow.fundingAddress.isNullOrBlank()) {
                            IconButton(
                                onClick = {
                                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as? android.content.ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            "NEO-P2P Escrow Address", escrow.fundingAddress
                                        )
                                    )
                                    android.widget.Toast.makeText(
                                        ctx, R.string.escrow_address_copied, android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_copy),
                                    contentDescription = stringResource(R.string.escrow_cd_copy_address)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // One-tap: send the exact deposit from the seller's own wallet.
                Button(
                    onClick = onFundFromWallet,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !fundingBusy && escrow.fundingAddress != null && fundingTxId.isBlank()
                ) {
                    if (fundingBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.escrow_funding_sending_short))
                    } else {
                        Icon(Icons.Filled.AccountBalance, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.escrow_fund_from_wallet))
                    }
                }
                if (fundingMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = fundingMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onConsumeFundingMessage) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (fundingError != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = fundingError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onConsumeFundingError) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = fundingTxId,
                    onValueChange = onFundingTxIdChanged,
                    label = { Text(stringResource(R.string.escrow_funding_txid_label)) },
                    placeholder = { Text(stringResource(R.string.escrow_funding_txid_placeholder)) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
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
        }

        // ── FUNDING step, BUYER view: the seller deposits BTC; the buyer
        // pays FIAT (bank transfer) later. No escrow address, no deposit
        // buttons — just a clear status so the buyer knows the trade is
        // progressing and not stuck.
        if (escrow.status == EscrowStatus.FUNDING && isRole == EscrowRole.BUYER) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.escrow_funding_wait_buyer_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.escrow_funding_wait_buyer_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.escrow_timeout_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Action buttons (post-funding)
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            when (escrow.status) {
                EscrowStatus.FUNDED -> {
                    // U2: NO release from FUNDED — the service-level gate
                    // (releaseFunds requires RECEIPT_SENT/CONFIRMING) is the
                    // ONLY release path, via confirmReceipt. Role text only.
                    Text(
                        text = if (isRole == EscrowRole.BUYER)
                            stringResource(R.string.escrow_funded_wait_seller)
                        else
                            stringResource(R.string.escrow_funded_wait_buyer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (isRole == EscrowRole.BUYER) {
                        Button(onClick = onMarkPaid, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(stringResource(R.string.escrow_mark_paid))
                        }
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
                EscrowStatus.SIGNED -> {
                    // Payout signed; the buyer marks the fiat payment as sent
                    // (starts the payment window). NO release button — the
                    // seller's confirmReceipt is the only release path.
                    Text(
                        text = if (isRole == EscrowRole.BUYER)
                            stringResource(R.string.escrow_funded_wait_seller)
                        else
                            stringResource(R.string.escrow_funded_wait_buyer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (isRole == EscrowRole.BUYER) {
                        Button(onClick = onMarkPaid, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(stringResource(R.string.escrow_mark_paid))
                        }
                    }
                }
                EscrowStatus.PAYMENT_PENDING, EscrowStatus.RECEIPT_SENT -> {
                    // Guided flow: the buyer has marked the fiat payment as sent.
                    // Buyer sends the structured receipt; the SELLER is the only
                    // one who can release (confirmReceipt → payout broadcast).
                    Text(
                        text = if (escrow.status == EscrowStatus.RECEIPT_SENT)
                            stringResource(R.string.escrow_receipt_waiting)
                        else stringResource(R.string.escrow_paid_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    PaymentWindowCountdown(escrow = escrow)
                    Spacer(Modifier.height(12.dp))
                    if (isRole == EscrowRole.BUYER) {
                        // Buyer side: open the receipt composer (marks paid +
                        // sends the E2EE receipt card + screenshot).
                        Button(onClick = onOpenReceipt, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(stringResource(R.string.escrow_open_receipt))
                        }
                    } else if (escrow.status == EscrowStatus.RECEIPT_SENT) {
                        // Seller side: the receipt EXISTS — show reference + confirm gate.
                        // (On PAYMENT_PENDING there is no receipt yet, so no confirm
                        // button — confirmReceipt would fail.)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.escrow_receipt_card_title),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                escrow.receiptReference?.let { ref ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        stringResource(R.string.escrow_receipt_reference_value, ref),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.escrow_paid_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onConfirmReceipt,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(stringResource(R.string.escrow_confirm_idr_received))
                        }
                    }
                }
                EscrowStatus.CONFIRMING -> {
                    // Buyer marked the fiat payment as sent. The seller must
                    // release (or dispute) before the payment window expires.
                    Text(
                        text = stringResource(R.string.escrow_paid_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    PaymentWindowCountdown(escrow = escrow)
                    Spacer(Modifier.height(12.dp))
                    if (isRole == EscrowRole.SELLER) {
                        Button(onClick = onConfirmReceipt, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(stringResource(R.string.escrow_confirm_idr_received))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.escrow_paid_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        onClick = onOpenEvidence,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(painterResource(id = R.drawable.ic_attach_file), contentDescription = null)
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
                        onClick = onOpenEvidence,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(painterResource(id = R.drawable.ic_insert_drive_file), contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.escrow_view_evidence))
                    }
                }
                EscrowStatus.REFUNDED -> {
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
            if (escrow.status == EscrowStatus.FUNDED || escrow.status == EscrowStatus.SIGNED ||
                escrow.status == EscrowStatus.PAYMENT_PENDING || escrow.status == EscrowStatus.RECEIPT_SENT ||
                escrow.status == EscrowStatus.CONFIRMING
            ) {
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
                    fontFamily = FontFamily.Monospace,
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

/**
 * Live countdown for the payment window (PAYMENT_PENDING/RECEIPT_SENT/
 * CONFIRMING statuses). Ticks every second and shows the time the seller has
 * left to release or dispute before the escrow auto-transitions to DISPUTED.
 */
@Composable
private fun PaymentWindowCountdown(escrow: Escrow, modifier: Modifier = Modifier) {
    val deadline = (escrow.paidAt ?: escrow.createdAt) + EscrowService.PAYMENT_WINDOW_MS
    var remainingMs by remember { mutableLongStateOf((deadline - System.currentTimeMillis()).coerceAtLeast(0L)) }
    LaunchedEffect(deadline) {
        while (remainingMs > 0) {
            delay(1_000)
            remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }
    val remaining = remainingMs
    val text = if (remaining <= 0) {
        stringResource(R.string.escrow_payment_window_expired)
    } else {
        val totalSec = remaining / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        stringResource(
            R.string.escrow_payment_window,
            "%02d:%02d:%02d".format(h, m, s)
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (remaining <= 0) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

/**
 * Post-trade rating dialog. Shown once when an escrow reaches RELEASED or
 * REFUNDED; publishes a signed kind:33335 attestation via the reputation
 * system (the plumbing existed but had no UI entry point).
 */
@Composable
private fun RateCounterpartyDialog(
    peerLabel: String,
    busy: Boolean,
    error: String?,
    onPositive: () -> Unit,
    onNegative: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.escrow_rate_title)) },
        text = {
            Column(modifier = modifier) {
                Text(stringResource(R.string.escrow_rate_body, peerLabel))
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onPositive, enabled = !busy) {
                    Text(stringResource(R.string.escrow_rate_positive))
                }
                TextButton(onClick = onNegative, enabled = !busy) {
                    Text(stringResource(R.string.escrow_rate_negative))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.escrow_rate_later))
            }
        },
        modifier = modifier
    )
}

/**
 * The four guided-flow steps shown in the role-adaptive step tracker.
 */
enum class EscrowStep { FUND, PAY, CONFIRM, RELEASE }

/** Role-adaptive step list: what each side sees in the tracker. */
fun stepsForRole(role: String): List<EscrowStep> = when (role) {
    "SELLER" -> listOf(EscrowStep.FUND, EscrowStep.CONFIRM, EscrowStep.RELEASE)
    else -> listOf(EscrowStep.PAY, EscrowStep.RELEASE) // BUYER / unknown
}

/** 0-based index of the current step within the role's step list.
 * Steps the role never sees count as done; terminal/edge statuses land on the
 * last step. */
fun currentStepFor(status: EscrowStatus, steps: List<EscrowStep>): Int {
    if (steps.isEmpty()) return 0
    val active = when (status) {
        EscrowStatus.FUNDING, EscrowStatus.FUNDED -> EscrowStep.FUND
        EscrowStatus.PAYMENT_PENDING, EscrowStatus.RECEIPT_SENT -> EscrowStep.PAY
        EscrowStatus.CONFIRMING -> EscrowStep.CONFIRM
        EscrowStatus.RELEASED -> EscrowStep.RELEASE
        else -> return steps.lastIndex
    }
    val idx = steps.indexOf(active)
    if (idx >= 0) return idx
    // Not in this role's list → treat as done; point at the next visible step.
    val next = steps.indexOfFirst { it.ordinal > active.ordinal }
    return if (next < 0) steps.lastIndex else next
}

@HiltViewModel
class EscrowViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val escrowService: EscrowService,
    private val walletService: com.neop2p.data.wallet.WalletService,
    private val identityManager: IdentityManager,
    private val reputationSystem: com.neop2p.data.reputation.ReputationSystem,
    private val nostrClient: com.neop2p.data.p2p.NostrClient,
    private val offerDao: OfferDao,
    savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "EscrowViewModel"
    }

    /** Escrow ids for which the rating dialog was already offered this process. */
    private val _ratingOffered = mutableSetOf<String>()

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

    // ── Auto-fund from wallet state ──
    private val _fundingBusy = MutableStateFlow(false)
    val fundingBusy: StateFlow<Boolean> = _fundingBusy.asStateFlow()

    private val _fundingError = MutableStateFlow<String?>(null)
    val fundingError: StateFlow<String?> = _fundingError.asStateFlow()

    private val _fundingMessage = MutableStateFlow<String?>(null)
    val fundingMessage: StateFlow<String?> = _fundingMessage.asStateFlow()

    // ── Post-trade rating state ──
    private val _showRating = MutableStateFlow(false)
    val showRating: StateFlow<Boolean> = _showRating.asStateFlow()

    private val _ratingBusy = MutableStateFlow(false)
    val ratingBusy: StateFlow<Boolean> = _ratingBusy.asStateFlow()

    private val _ratingError = MutableStateFlow<String?>(null)
    val ratingError: StateFlow<String?> = _ratingError.asStateFlow()

    fun consumeFundingMessage() { _fundingMessage.value = null }
    fun consumeFundingError() { _fundingError.value = null }

    /**
     * Switch the escrow's funding address between Legacy (P2SH) and SegWit
     * (P2WSH) while it is still unfunded. The 2-of-3 redeem script is the
     * same; only the carrier + network-fee estimate change. The service
     * refuses once a deposit exists (status > FUNDING).
     */
    fun switchFundingType(type: BitcoinAddressType) {
        if (_fundingBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _fundingBusy.value = true
            _fundingError.value = null
            try {
                val result = escrowService.switchFundingType(escrowId, type)
                result.onSuccess { updated ->
                    _uiState.value = UiState.Success(
                        EscrowData(
                            escrow = updated,
                            role = determineRole(updated),
                            fundingTxId = _fundingTxId.value,
                            buyerAddress = buyerAddressFor(updated),
                            paymentDetails = paymentDetailsFor(updated)
                        )
                    )
                }.onFailure {
                    _fundingError.value = context.getString(
                        R.string.escrow_funding_error,
                        it.message ?: ""
                    )
                }
            } catch (e: Exception) {
                _fundingError.value = context.getString(R.string.escrow_funding_error, e.message ?: "")
            } finally {
                _fundingBusy.value = false
            }
        }
    }

    sealed class UiState {
        object Loading : UiState()
        /** The escrow row does not exist yet (buyer side pre-sync) — waiting. */
        object Pending : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: EscrowData) : UiState()
    }

    data class EscrowData(
        val escrow: Escrow,
        val role: EscrowRole,
        val fundingTxId: String,
        val buyerAddress: String,
        val counterpartyLabel: String = "",
        // Bank details (number + holder) for this trade. SELLER: own stored
        // details; BUYER: populated when the auto-shared E2EE chat envelope
        // lands (persisted into the offer row by ChatRouter).
        val paymentDetails: Map<String, com.neop2p.domain.model.PaymentDetails> = emptyMap()
    )

    init {
        loadEscrow()
        // Live refresh: remote kind:33337 events (and local transitions) for
        // THIS escrow reload the screen immediately — the buyer's open screen
        // must flip to In Progress / Funded without a manual re-open.
        viewModelScope.launch(Dispatchers.IO) {
            escrowService.transitions
                .filter { it.escrowId == escrowId }
                .collect { loadEscrow() }
        }
        // Live refresh for the bank card: when the seller's auto-shared
        // payment-details envelope lands (E2EE chat → ChatRouter persists it
        // into the offer row), the buyer's open escrow screen must show the
        // card without a manual re-open.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val esc = escrowService.getEscrow(escrowId) ?: return@launch
                offerDao.getOffer(esc.offerId)
                    .distinctUntilChanged()
                    .collect { loadEscrow() }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadEscrow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val escrow = escrowService.getEscrow(escrowId)
                if (escrow == null) {
                    // U3: the buyer's device may not have the escrow row yet —
                    // it is created by the seller and arrives via the kind:33337
                    // sync event. Show a pending state and retry briefly; the
                    // row should land within seconds of the seller acting.
                    _uiState.value = UiState.Pending
                    for (attempt in 1..30) {
                        delay(5_000)
                        val now = escrowService.getEscrow(escrowId)
                        if (now != null) {
                            loadEscrow()
                            return@launch
                        }
                    }
                    _uiState.value = UiState.Error("Escrow not found")
                } else {
                    val role = determineRole(escrow)
                    // Persisted funding txid (broadcast before confirmation) —
                    // seed the field so the UI disables double-send and shows
                    // "waiting for confirmation" even after an app restart.
                    if (_fundingTxId.value.isBlank()) {
                        escrow.fundingTxId?.let { _fundingTxId.value = it }
                    }
                    // Recovery: a deposit broadcast by a pre-fix build (or any
                    // broadcast whose txid was never persisted) is re-discovered
                    // on-chain so the UI shows "In progress" and blocks a
                    // double-send instead of demanding a second deposit.
                    if (_fundingTxId.value.isBlank() && escrow.status == EscrowStatus.FUNDING) {
                        val recovered = escrowService.recoverFundingTxId(escrowId)
                        if (recovered != null) _fundingTxId.value = recovered
                    }
                    _uiState.value = UiState.Success(
                        EscrowData(
                            escrow = escrow,
                            role = role,
                            fundingTxId = _fundingTxId.value,
                            buyerAddress = buyerAddressFor(escrow),
                            counterpartyLabel = counterpartyLabelFor(escrow, role),
                            paymentDetails = paymentDetailsFor(escrow)
                        )
                    )
                    maybeShowRating(escrow, role)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load escrow: ${e.message}")
            }
        }
    }

    /** Short label for the counterparty (peer id tail) used by the rating dialog. */
    private fun counterpartyLabelFor(escrow: Escrow, role: EscrowRole): String {
        val peerId = when (role) {
            EscrowRole.BUYER -> escrow.sellerPeerId
            EscrowRole.SELLER -> escrow.buyerPeerId
            else -> return ""
        }
        return peerId.take(8)
    }

    /**
     * Offer the post-trade rating dialog once when the escrow reaches a
     * terminal state (RELEASED / REFUNDED). The dialog is dismissible ("Later")
     * and never re-shown for the same escrow in this process.
     */
    private fun maybeShowRating(escrow: Escrow, role: EscrowRole) {
        if (role == EscrowRole.UNKNOWN) return
        if (escrow.status != EscrowStatus.RELEASED && escrow.status != EscrowStatus.REFUNDED) return
        if (_ratingOffered.contains(escrow.escrowId)) return
        _ratingOffered.add(escrow.escrowId)
        _ratingError.value = null
        _showRating.value = true
    }

    /** Resolve the buyer's BTC receive address from the escrow (U1: populated
     *  at accept time / via kind:33337 sync), falling back to the offer, then
     *  to the escrow's own funding address (single-key demo compat). */
    private suspend fun buyerAddressFor(escrow: Escrow): String {
        return try {
            escrow.buyerBtcAddress?.takeIf { it.isNotBlank() }
                ?: offerDao.getOffer(escrow.offerId).firstOrNull()?.toDomain()?.btcReceiveAddress
                    ?.takeIf { it.isNotBlank() }
                ?: escrow.fundingAddress
                ?: ""
        } catch (e: Exception) {
            escrow.fundingAddress ?: ""
        }
    }

    /** Bank details (number + holder) for this trade. The SELLER reads their
     *  own stored offer details; the BUYER reads the details the seller
     *  auto-shared over E2EE chat (persisted into the local offer row by
     *  ChatRouter) so the escrow detail screen shows the bank card without
     *  opening the chat. */
    private suspend fun paymentDetailsFor(escrow: Escrow): Map<String, com.neop2p.domain.model.PaymentDetails> {
        return try {
            offerDao.getOffer(escrow.offerId).first()?.toDomain()?.paymentDetails.orEmpty()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Determine the current user's role via PEER ID (W4: in the single-key
     * model both role pubkeys are the same key, so pubkey comparison cannot
     * distinguish buyer from seller — compare peer IDs instead). */
    private fun determineRole(escrow: Escrow): EscrowRole {
        val myPeerId = identityManager.myPeerId()
        return when {
            myPeerId == escrow.buyerPeerId -> EscrowRole.BUYER
            myPeerId == escrow.sellerPeerId -> EscrowRole.SELLER
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
                        EscrowData(
                            escrow = updated,
                            role = determineRole(updated),
                            fundingTxId = _fundingTxId.value,
                            buyerAddress = buyerAddressFor(updated),
                            paymentDetails = paymentDetailsFor(updated)
                        )
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
     * Auto-fund the escrow from the seller's own wallet (P2PKH, BIP-44).
     *
     * Sends the exact [Escrow.depositAmountSats] (crypto + 0.3% fee + network
     * fee) to the 2-of-3 P2SH address via WalletService, then immediately
     * verifies the deposit on-chain and moves the escrow to FUNDED.
     *
     * The seller approves a confirmation dialog in the UI first; the broadcast
     * is irreversible.
     */
    fun fundFromWallet() {
        if (_fundingBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _fundingBusy.value = true
            _fundingError.value = null
            _fundingMessage.value = null
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow
                    ?: return@launch
                val addr = current.fundingAddress ?: return@launch
                val amount = current.depositAmountSats
                _fundingMessage.value = context.getString(R.string.escrow_funding_sending, amount)

                // 1) Broadcast the transfer from the seller's wallet.
                val send = walletService.send(addr, amount).getOrElse {
                    _fundingError.value = context.getString(
                        R.string.escrow_funding_send_failed,
                        it.message ?: ""
                    )
                    return@launch
                }
                // 2) Auto-fill the txid and verify on-chain.
                _fundingTxId.value = send.txid
                val result = escrowService.onEscrowFunded(current.escrowId, send.txid)
                val updated = result.getOrNull()
                if (updated != null) {
                    _uiState.value = UiState.Success(
                        EscrowData(
                            escrow = updated,
                            role = determineRole(updated),
                            fundingTxId = _fundingTxId.value,
                            buyerAddress = buyerAddressFor(updated),
                            paymentDetails = paymentDetailsFor(updated)
                        )
                    )
                    _fundingMessage.value = context.getString(
                        R.string.escrow_funding_confirmed,
                        send.txid.take(16)
                    )
                } else {
                    val err = result.exceptionOrNull()?.message ?: "Funding verification failed"
                    _fundingError.value = context.getString(R.string.escrow_funding_verify_failed, err)
                }
            } catch (e: Exception) {
                _fundingError.value = context.getString(R.string.escrow_funding_error, e.message ?: "")
            } finally {
                _fundingBusy.value = false
            }
        }
    }

    /** Buyer marks the fiat payment as sent (unlocks the receipt composer). */
    fun markPaid() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                val updated = escrowService.markPaid(current.escrowId).getOrNull()
                updated?.let { escrow ->
                    _uiState.value = UiState.Success(
                        EscrowData(
                            escrow = escrow,
                            role = determineRole(escrow),
                            fundingTxId = _fundingTxId.value,
                            buyerAddress = buyerAddressFor(escrow),
                            counterpartyLabel = counterpartyLabelFor(escrow, determineRole(escrow)),
                            paymentDetails = paymentDetailsFor(escrow)
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to mark payment: ${e.message}")
            }
        }
    }

    /** Seller confirms "IDR received" — the ONLY release gate (confirmReceipt
     * broadcasts the 2-of-3 payout via the existing release machinery). */
    fun confirmReceipt() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                val updated = escrowService.confirmReceipt(current.escrowId).getOrNull()
                updated?.let { escrow ->
                    _uiState.value = UiState.Success(
                        EscrowData(
                            escrow = escrow,
                            role = determineRole(escrow),
                            fundingTxId = _fundingTxId.value,
                            buyerAddress = buyerAddressFor(escrow),
                            counterpartyLabel = counterpartyLabelFor(escrow, determineRole(escrow)),
                            paymentDetails = paymentDetailsFor(escrow)
                        )
                    )
                } ?: run {
                    // Broadcast failure (e.g. chain unreachable): surface it.
                    val err = runCatching {
                        escrowService.getEscrow(current.escrowId)?.let { _uiState.value = UiState.Success(
                            EscrowData(
                                escrow = it,
                                role = determineRole(it),
                                fundingTxId = _fundingTxId.value,
                                buyerAddress = buyerAddressFor(it),
                                counterpartyLabel = counterpartyLabelFor(it, determineRole(it)),
                                paymentDetails = paymentDetailsFor(it)
                            )
                        ) }
                    }.exceptionOrNull()
                    if (err != null) _uiState.value = UiState.Error("Release failed: ${err.message}")
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to confirm receipt: ${e.message}")
            }
        }
    }

    /**
     * Publish a signed kind:33335 attestation rating the counterparty after a
     * completed trade. This is the missing half of the reputation loop: the
     * receive/verify/display plumbing existed, but nothing ever called it.
     */
    fun rateCounterparty(wasPositive: Boolean) {
        if (_ratingBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _ratingBusy.value = true
            _ratingError.value = null
            try {
                val data = (_uiState.value as? UiState.Success)?.data ?: return@launch
                val escrow = data.escrow
                val myPeerId = identityManager.getOrCreateIdentity().peerId
                val targetPeerId = when (data.role) {
                    EscrowRole.BUYER -> escrow.sellerPeerId
                    EscrowRole.SELLER -> escrow.buyerPeerId
                    else -> return@launch
                }
                val attestation = reputationSystem.createAttestation(
                    myPeerId = myPeerId,
                    targetPeerId = targetPeerId,
                    wasPositive = wasPositive,
                    volumeSats = escrow.tradeAmountSats
                )
                nostrClient.publishAttestation(
                    fromPeer = attestation.fromPeer,
                    targetPeer = attestation.targetPeer,
                    outcome = attestation.outcome.name,
                    volumeSats = attestation.volumeSats,
                    timestamp = attestation.timestamp,
                    signatureHex = attestation.signature.joinToString("") { "%02x".format(it) }
                )
                _showRating.value = false
            } catch (e: Exception) {
                _ratingError.value = context.getString(R.string.escrow_rate_failed, e.message ?: "")
            } finally {
                _ratingBusy.value = false
            }
        }
    }

    fun dismissRating() {
        if (_ratingBusy.value) return
        _showRating.value = false
    }

    fun disputeEscrow() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                val updated = escrowService.disputeEscrow(current.escrowId).getOrNull()
                updated?.let { escrow ->
                    // Publish the dispute to the relay (kind:33386) so the
                    // counterparty AND the arbitrator learn about it. Carries
                    // the redeem script + unsigned payout tx so a remote
                    // arbitrator can sign the resolution without holding the
                    // escrow row.
                    val myPeerId = runCatching { identityManager.getOrCreateIdentity().peerId }
                        .getOrNull() ?: ""
                    val unsignedHex = escrow.psbtUnsigned?.toString(Charsets.UTF_8)
                    nostrClient.publishDispute(
                        escrowId = escrow.escrowId,
                        openedBy = myPeerId,
                        reason = context.getString(R.string.escrow_dispute),
                        redeemScriptHex = escrow.redeemScriptHex,
                        unsignedTxHex = unsignedHex,
                        depositSats = escrow.depositAmountSats,
                        fundingScriptType = escrow.fundingScriptType.name
                    )
                    _uiState.value = UiState.Success(
                        EscrowData(
                            escrow = escrow,
                            role = determineRole(escrow),
                            fundingTxId = _fundingTxId.value,
                            buyerAddress = buyerAddressFor(escrow),
                            counterpartyLabel = counterpartyLabelFor(escrow, determineRole(escrow))
                        )
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
            identityManager.getBitcoinAddress(BitcoinAddressType.LEGACY)
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
