package com.neop2p.ui.screens.escrow

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.neop2p.ui.theme.escrowStatusColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neop2p.BuildConfig
import com.neop2p.R
import com.neop2p.data.escrow.EscrowScriptGate
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toDomain
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.p2p.routing.PaymentReceiptRejectPayload
import com.neop2p.domain.model.*
import com.neop2p.domain.model.BitcoinAddressType
import com.neop2p.ui.components.ConnectionQualityChip
import com.neop2p.ui.theme.NeoMotion
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.util.PeerFingerprint
import com.neop2p.ui.util.ErrorCodes
import com.neop2p.ui.util.MoneyAction
import com.neop2p.ui.util.formatBtc
import com.neop2p.ui.util.formatIdr
import com.neop2p.ui.util.generateQrCode
import com.neop2p.ui.util.moneyAction
import com.neop2p.ui.util.uniquePaymentCode
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
    // Live funding-txid input state: the field must bind to this flow, NOT
    // the uiState snapshot — uiState is only re-emitted on load/action, so
    // binding to it made every keystroke snap the field back to "".
    val fundingTxId by viewModel.fundingTxId.collectAsStateWithLifecycle()
    val requestRefundError by viewModel.requestRefundError.collectAsStateWithLifecycle()
    val fundingBusy by viewModel.fundingBusy.collectAsStateWithLifecycle()
    val fundingError by viewModel.fundingError.collectAsStateWithLifecycle()
    val fundingMessage by viewModel.fundingMessage.collectAsStateWithLifecycle()
    val fundingMinerFeeEstimate by viewModel.fundingMinerFeeEstimate.collectAsStateWithLifecycle()
    val showRating by viewModel.showRating.collectAsStateWithLifecycle()
    val ratingBusy by viewModel.ratingBusy.collectAsStateWithLifecycle()
    val ratingError by viewModel.ratingError.collectAsStateWithLifecycle()
    val showRejectDialog by viewModel.showRejectDialog.collectAsStateWithLifecycle()
    val rejectReason by viewModel.rejectReason.collectAsStateWithLifecycle()
    val rejectNote by viewModel.rejectNote.collectAsStateWithLifecycle()
    val rejectBusy by viewModel.rejectBusy.collectAsStateWithLifecycle()
    val rejectError by viewModel.rejectError.collectAsStateWithLifecycle()
    val markPaidBusy by viewModel.markPaidBusy.collectAsStateWithLifecycle()
    val confirmReceiptBusy by viewModel.confirmReceiptBusy.collectAsStateWithLifecycle()
    val disputeBusy by viewModel.disputeBusy.collectAsStateWithLifecycle()
    val signPayoutBusy by viewModel.signPayoutBusy.collectAsStateWithLifecycle()
    val signPayoutDone by viewModel.signPayoutDone.collectAsStateWithLifecycle()
    var showFundingConfirm by remember { mutableStateOf(false) }
    var showMarkPaidConfirm by remember { mutableStateOf(false) }
    var showDisputeConfirm by remember { mutableStateOf(false) }
    // Relay-dependence gate (F05b): when the counterparty is only reachable
    // via the WS relay, money actions first ask for explicit confirmation.
    // The pending action fires after the user confirms.
    var showRelayConfirm by remember { mutableStateOf(false) }
    var pendingRelayAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // F-1 (2026-09-13): "Request refund" cannot succeed for an in-flight
    // deposit — surface that (and any cancel failure) without a dialog.
    LaunchedEffect(requestRefundError) {
        requestRefundError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeRequestRefundError()
        }
    }

    NeoP2PTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            val counterpartyPeerId = when (data.role) {
                                EscrowRole.BUYER -> data.escrow.sellerPeerId
                                EscrowRole.SELLER -> data.escrow.buyerPeerId
                                else -> ""
                            }
                            val counterpartyQuality = viewModel.qualityOf(counterpartyPeerId)
                            // Money actions over anything but a live DIRECT
                            // libp2p link need explicit confirmation (the relay
                            // can die mid-trade). RELAYED = via the WS relay;
                            // OFFLINE = a stale DIRECT downgraded when the
                            // libp2p connection dropped — both must be gated.
                            fun gateRelayed(action: () -> Unit) {
                                if (counterpartyQuality !=
                                    com.neop2p.data.p2p.store.PeerRegistry.ConnectionQuality.DIRECT
                                ) {
                                    pendingRelayAction = action
                                    showRelayConfirm = true
                                } else {
                                    action()
                                }
                            }
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f)) {
                                    EscrowContent(
                                        escrow = data.escrow,
                                        isRole = data.role,
                                        counterpartyQuality = counterpartyQuality,
                                        // Funding gate: seller provides the funding txid which is
                                        // verified on-chain before the trade can proceed.
                                        fundingTxId = fundingTxId,
                                        onFundingTxIdChanged = { viewModel.setFundingTxId(it) },
                                        onVerifyFundingTx = { viewModel.verifyFunding() },
                                        onFundFromWallet = { gateRelayed { showFundingConfirm = true } },
                                        onSwitchFundingType = { viewModel.switchFundingType(it) },
                                        fundingBusy = fundingBusy,
                                        fundingError = fundingError,
                                        fundingMessage = fundingMessage,
                                        fundingMinerFeeEstimate = fundingMinerFeeEstimate,
                                        onConsumeFundingMessage = { viewModel.consumeFundingMessage() },
                                        onConsumeFundingError = { viewModel.consumeFundingError() },
                                        onMarkPaid = { gateRelayed { showMarkPaidConfirm = true } },
                                        onDispute = { showDisputeConfirm = true },
                                        onOpenEvidence = { onEvidenceClick(escrowId) },
                                        onOpenReceipt = { onOpenReceipt(escrowId) },
                                        onConfirmReceipt = { gateRelayed { viewModel.confirmReceipt() } },
                                        onRejectReceipt = { viewModel.openRejectDialog() },
                                        onRequestRefund = { viewModel.requestRefund() },
                                        onCopied = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                                        markPaidBusy = markPaidBusy,
                                        confirmReceiptBusy = confirmReceiptBusy,
                                        disputeBusy = disputeBusy,
                                        onSignPayout = { viewModel.signPayoutIfBuyer() },
                                        signPayoutBusy = signPayoutBusy,
                                        signPayoutDone = signPayoutDone,
                                        paymentDetails = data.paymentDetails,
                                        fiatAmount = data.fiatAmount,
                                        scriptVerdict = data.scriptVerdict,
                                        modifier = Modifier.verticalScroll(rememberScrollState())
                                    )
                                }
                                // Sticky "Langkah Anda selanjutnya" — one primary
                                // action per role+state, countdown when a window
                                // is running (RoboSats/Binance pattern).
                                NextActionBar(
                                    escrow = data.escrow,
                                    isRole = data.role,
                                    fiatAmount = data.fiatAmount,
                                    fundingTxId = fundingTxId
                                )
                            }
                        }
                    }
                }
            }
        )

        if (showRejectDialog) {
            (state as? EscrowViewModel.UiState.Success)?.let { success ->
                val esc = success.data.escrow
                RejectReceiptDialog(
                    reference = esc.receiptReference.orEmpty(),
                    reason = rejectReason,
                    note = rejectNote,
                    busy = rejectBusy,
                    error = rejectError,
                    onReasonChange = { viewModel.onRejectReasonChange(it) },
                    onNoteChange = { viewModel.onRejectNoteChange(it) },
                    onConfirm = { viewModel.rejectReceipt() },
                    onDismiss = { viewModel.closeRejectDialog() }
                )
            }
        }

        if (showFundingConfirm) {
            (state as? EscrowViewModel.UiState.Success)?.let { success ->
                val esc = success.data.escrow
                AlertDialog(
                    onDismissRequest = { showFundingConfirm = false },
                    title = { Text(stringResource(R.string.escrow_funding_confirm_title)) },
                    text = { Text(stringResource(R.string.escrow_funding_confirm_body, formatBtc(esc.depositAmountSats))) },
                    confirmButton = {
                        Button(
                            onClick = {
                                haptics.moneyAction(MoneyAction.FUND)
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
                            haptics.moneyAction(MoneyAction.MARK_PAID)
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

        // Opening a dispute is irreversible until the arbitrator rules — the
        // funds stay frozen and the resolution is binding. Confirm first.
        if (showDisputeConfirm) {
            AlertDialog(
                onDismissRequest = { showDisputeConfirm = false },
                title = { Text(stringResource(R.string.escrow_dispute_confirm_title)) },
                text = { Text(stringResource(R.string.escrow_dispute_confirm_body)) },
                confirmButton = {
                    Button(
                        onClick = {
                            haptics.moneyAction(MoneyAction.DISPUTE)
                            showDisputeConfirm = false
                            viewModel.disputeEscrow()
                        },
                        enabled = !disputeBusy
                    ) {
                        Text(stringResource(R.string.escrow_dispute_confirm_yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDisputeConfirm = false }) {
                        Text(stringResource(R.string.general_cancel))
                    }
                }
            )
        }

        // Relay-dependence gate: the counterparty is only reachable via the
        // WS relay, which can die mid-trade. The user explicitly accepts the
        // risk before the money action fires.
        if (showRelayConfirm) {
            AlertDialog(
                onDismissRequest = {
                    showRelayConfirm = false
                    pendingRelayAction = null
                },
                title = { Text(stringResource(R.string.escrow_relay_confirm_title)) },
                text = { Text(stringResource(R.string.escrow_relay_confirm_body)) },
                confirmButton = {
                    Button(
                        onClick = {
                            showRelayConfirm = false
                            pendingRelayAction?.invoke()
                            pendingRelayAction = null
                        }
                    ) {
                        Text(stringResource(R.string.escrow_relay_confirm_yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showRelayConfirm = false
                        pendingRelayAction = null
                    }) {
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

/** U3: the escrow row hasn't arrived from the counterparty yet (LXMF escrow_status
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
    ErrorCodes.codeFor(message)?.let { code ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.error_code_line, code),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(24.dp))
    Button(onClick = onRetry, modifier = Modifier.width(120.dp).height(40.dp)) {
        Text(stringResource(R.string.general_retry))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StepTracker(
    currentStep: Int,          // 0-based index into steps
    steps: List<EscrowStep>,
    labels: Map<EscrowStep, String>
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        steps.forEachIndexed { index, step ->
            val visual = stepVisual(index, currentStep)
            val targetDot = when (visual) {
                StepVisual.DONE -> MaterialTheme.colorScheme.primary
                StepVisual.ACTIVE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                StepVisual.PENDING -> MaterialTheme.colorScheme.surfaceVariant
            }
            val targetLabel = if (visual == StepVisual.ACTIVE) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
            val dotColor by animateColorAsState(
                targetDot, animationSpec = NeoMotion.emphasizedColor, label = "stepDot$index"
            )
            val labelColor by animateColorAsState(
                targetLabel, animationSpec = NeoMotion.standardColor, label = "stepLabel$index"
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                ) {
                    Text(
                        "${index + 1}",
                        Modifier.align(Alignment.Center),
                        color = if (visual == StepVisual.DONE || visual == StepVisual.ACTIVE) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    labels[step] ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EscrowStatusChip(
    status: EscrowStatus,
    fundingTxId: String = "",
    modifier: Modifier = Modifier
) {
    val (targetContainer, targetContent) = MaterialTheme.colorScheme.escrowStatusColors(status)
    val container by animateColorAsState(
        targetContainer, animationSpec = NeoMotion.emphasizedColor, label = "chipContainer"
    )
    val contentColor by animateColorAsState(
        targetContent, animationSpec = NeoMotion.emphasizedColor, label = "chipContent"
    )
    Surface(shape = CircleShape, color = container, modifier = modifier) {
        Text(
            text = when (status) {
                EscrowStatus.FUNDING -> stringResource(
                    if (fundingTxId.isNotBlank()) R.string.escrow_status_in_progress
                    else R.string.escrow_status_pending
                )
                EscrowStatus.FUNDED -> stringResource(R.string.escrow_status_funded)
                EscrowStatus.PAYMENT_PENDING -> stringResource(R.string.escrow_chip_payment_pending)
                EscrowStatus.RECEIPT_SENT -> stringResource(R.string.escrow_chip_receipt_sent)
                EscrowStatus.SIGNED -> stringResource(R.string.escrow_status_signed)
                EscrowStatus.CONFIRMING -> stringResource(R.string.escrow_paid_status)
                EscrowStatus.RELEASED -> stringResource(R.string.profile_completed)
                EscrowStatus.DISPUTED -> stringResource(R.string.escrow_status_disputed)
                EscrowStatus.RESOLVING -> stringResource(R.string.escrow_status_resolving)
                EscrowStatus.CANCELLED -> stringResource(R.string.escrow_status_cancelled)
                EscrowStatus.REFUNDED -> stringResource(R.string.escrow_status_refunded)
            },
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EscrowContent(
    escrow: Escrow,
    isRole: EscrowRole,
    counterpartyQuality: com.neop2p.data.p2p.store.PeerRegistry.ConnectionQuality =
        com.neop2p.data.p2p.store.PeerRegistry.ConnectionQuality.OFFLINE,
    fundingTxId: String,
    onFundingTxIdChanged: (String) -> Unit,
    onVerifyFundingTx: () -> Unit,
    onFundFromWallet: () -> Unit,
    onSwitchFundingType: (BitcoinAddressType) -> Unit,
    fundingBusy: Boolean,
    fundingError: String?,
    fundingMessage: String?,
    fundingMinerFeeEstimate: Long?,
    onConsumeFundingMessage: () -> Unit,
    onConsumeFundingError: () -> Unit,
    onMarkPaid: () -> Unit,
    onDispute: () -> Unit,
    onOpenEvidence: () -> Unit,
    onOpenReceipt: () -> Unit,
    onConfirmReceipt: () -> Unit,
    onRejectReceipt: () -> Unit = {},
    onRequestRefund: () -> Unit,
    onCopied: (String) -> Unit = {},
    markPaidBusy: Boolean = false,
    confirmReceiptBusy: Boolean = false,
    disputeBusy: Boolean = false,
    onSignPayout: () -> Unit = {},
    signPayoutBusy: Boolean = false,
    signPayoutDone: Boolean = false,
    paymentDetails: Map<String, com.neop2p.domain.model.PaymentDetails>,
    fiatAmount: Long = 0L,
    scriptVerdict: EscrowScriptGate.Verdict? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
        // Network warning banner
        if (BuildConfig.NETWORK == "mainnet") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.escrow_mainnet_warning),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        // F3 warning: shows before any payment action. A NON-ok verdict warns
        // immediately; a NULL verdict (no redeem script on the row) is only
        // suspicious once the escrow is at/past funding — a fresh FUNDING row
        // can legitimately still be mid-sync, and terminal rows carry no
        // pending payment. Active funded states only.
        val scriptAtRisk = escrow.status == EscrowStatus.FUNDED ||
            escrow.status == EscrowStatus.SIGNED ||
            escrow.status == EscrowStatus.PAYMENT_PENDING ||
            escrow.status == EscrowStatus.RECEIPT_SENT ||
            escrow.status == EscrowStatus.CONFIRMING ||
            escrow.status == EscrowStatus.DISPUTED ||
            escrow.status == EscrowStatus.RESOLVING
        if (scriptVerdict?.ok == false || (scriptVerdict == null && scriptAtRisk)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.escrow_script_unverified_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.escrow_script_unverified_body), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Escrow header
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
            // weight(1f): the status text must wrap inside the REMAINING row
            // width, never push the status chip to zero width (the chip used
            // to collapse into a vertical one-character-per-line capsule).
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
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
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2
                )
                Text(stringResource(R.string.escrow_id_format, escrow.escrowId.take(6)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.clickable {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("NEO-P2P escrowId", escrow.escrowId)
                        )
                        onCopied(context.getString(R.string.escrow_id_copied))
                    }
                )
                // TOFU trust anchor: 8-word fingerprint of the COUNTERPARTY's
                // identity. Compare out-of-band (phone/WA) before releasing —
                // the only protection against a relay-level MITM.
                val fpPeerId = when (isRole) {
                    EscrowRole.BUYER -> escrow.sellerPeerId
                    EscrowRole.SELLER -> escrow.buyerPeerId
                    else -> ""
                }
                if (fpPeerId.isNotBlank()) {
                    val fpWordList = remember { PeerFingerprint.loadWordList(context) }
                    if (fpWordList.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.escrow_fingerprint_label) + " " +
                                PeerFingerprint.display(fpPeerId, fpWordList),
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
                                            PeerFingerprint.display(fpPeerId, fpWordList)
                                        )
                                    )
                                    onCopied(context.getString(R.string.chat_fingerprint_copied))
                                }
                        )
                    }
                    // Connection quality of the counterparty (F05b): relayed
                    // peers depend on the WS relay — the user must know before
                    // money actions that the link can die.
                    ConnectionQualityChip(
                        quality = counterpartyQuality,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            EscrowStatusChip(status = escrow.status, fundingTxId = fundingTxId)
        }

        // P0 dispute frozen banner — funds locked, evidence is the weapon.
        if (escrow.status == EscrowStatus.DISPUTED || escrow.status == EscrowStatus.RESOLVING) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.escrow_dispute_frozen_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.escrow_dispute_frozen_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.escrow_dispute_frozen_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
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

        // Trade details — the buyer only cares about what they receive and
        // what they pay in fiat; the fee rows (0.5% seller-only + network fee +
        // total deposit) are the SELLER's funding math and stay seller-side.
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.escrow_trade_details), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.escrow_amount))
                Text(stringResource(R.string.common_btc_amount, formatBtc(escrow.tradeAmountSats)))
            }
            if (isRole == EscrowRole.SELLER) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.escrow_fee))
                    Text(stringResource(R.string.common_btc_amount, formatBtc(escrow.feeAmountSats)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.escrow_network_fee))
                    Text(stringResource(R.string.common_sats, escrow.networkFeeSats))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.escrow_total_required))
                    Text(stringResource(R.string.common_btc_amount, formatBtc(escrow.depositAmountSats)))
                }
            }
        }

        // ── Bank transfer details (auto-shared over E2EE chat on FUNDED) ──
        // Both roles see the card once the details exist: the seller reads
        // their own stored details; the buyer reads the envelope the seller's
        // device auto-sent when the escrow became FUNDED (persisted into the
        // local offer row by ChatRouter, so the card shows even if the chat
        // was never opened).
        if (paymentDetails.isNotEmpty()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    stringResource(R.string.escrow_bank_details_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                paymentDetails.forEach { (method, details) ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    ) {
                        Column(Modifier.padding(8.dp)) {
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

        // ── Pay instruction card (buyer) — exact IDR + unique code ──
        // The seller checks the amount TAIL, not a notes field (Indodax/Flip
        // kode-unik convention): "Transfer tepat Rp 1.250.432 — 432 kode unikmu".
        // The code is derived deterministically from the escrowId so BOTH
        // devices agree without any extra message (escrowId syncs via
        // LXMF escrow_status; the fiat amount syncs via the offer).
        if (isRole == EscrowRole.BUYER &&
            (escrow.status == EscrowStatus.FUNDED ||
                escrow.status == EscrowStatus.PAYMENT_PENDING ||
                escrow.status == EscrowStatus.RECEIPT_SENT)
        ) {
            if (paymentDetails.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.escrow_pay_no_details),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                PayInstructionCard(
                    fiatAmount = fiatAmount,
                    escrowId = escrow.escrowId,
                    methods = paymentDetails.keys,
                    paymentDetails = paymentDetails,
                    onCopied = onCopied,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        // Seller side: the expected amount WITH the unique-code suffix, so the
        // seller verifies the transfer TAIL, not just "about the right amount".
        if (isRole == EscrowRole.SELLER && fiatAmount > 0L &&
            (escrow.status == EscrowStatus.PAYMENT_PENDING ||
                escrow.status == EscrowStatus.RECEIPT_SENT ||
                escrow.status == EscrowStatus.CONFIRMING)
        ) {
            Text(
                text = stringResource(
                    R.string.escrow_expected_payment,
                    formatIdr(fiatAmount + uniquePaymentCode(escrow.escrowId, fiatAmount))
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
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
                                text = stringResource(R.string.escrow_deposit_required, formatBtc(escrow.depositAmountSats)),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Overpayment notice (2026-09-04): the seller
                            // deposited MORE than the required amount. The
                            // excess is returned to the seller by the payout
                            // and refund paths — never kept as fee.
                            escrow.fundedAmountSats?.takeIf { it > escrow.depositAmountSats }?.let { funded ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        R.string.escrow_overpayment_notice,
                                        formatBtc(funded - escrow.depositAmountSats)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            // Underpayment notice (2026-09-04): the seller
                            // deposited LESS than required. The partial deposit
                            // is recorded — cancel & refund it, then create a
                            // new escrow (a top-up would create a second output
                            // the payout/refund cannot spend).
                            escrow.fundedAmountSats?.takeIf { it > 0L && it < escrow.depositAmountSats }?.let { funded ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        R.string.escrow_underpayment_notice,
                                        formatBtc(funded),
                                        formatBtc(escrow.depositAmountSats - funded)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            // Transparency: the seller's wallet ALSO pays a
                            // miner fee to broadcast this funding tx (on top of
                            // the deposit, which already includes the payout's
                            // network fee). Show the estimate so the seller
                            // sees the full on-chain cost before sending.
                            if (fundingMinerFeeEstimate != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        R.string.escrow_funding_miner_fee_estimate,
                                        fundingMinerFeeEstimate!!
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                                    onCopied(ctx.getString(R.string.escrow_address_copied))
                                },
                                modifier = Modifier.size(48.dp)
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
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.general_close),
                                    modifier = Modifier.size(18.dp)
                                )
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
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = fundingError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                ErrorCodes.codeFor(fundingError)?.let { code ->
                                    Text(
                                        text = stringResource(R.string.error_code_line, code),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            IconButton(onClick = onConsumeFundingError) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.general_close),
                                    modifier = Modifier.size(18.dp)
                                )
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
                // Mempool pending progress: a broadcast-but-unconfirmed deposit
                // is "in progress" — show the tx is visible on the network with
                // an explorer deep link (Peach TransactionInMempool pattern).
                if (fundingTxId.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.escrow_tx_in_mempool, escrow.requiredConfirmations),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            val explorerUrl =
                                "https://mempool.space/${if (BuildConfig.NETWORK == "mainnet") "" else "testnet4/"}tx/$fundingTxId"
                            TextButton(
                                onClick = {
                                    runCatching {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(explorerUrl)
                                        )
                                        ctx.startActivity(intent)
                                    }
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text(stringResource(R.string.escrow_open_explorer))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // The transfer is NOT trusted blindly: it is verified on-chain via
                // Mempool.space before the escrow may proceed past FUNDING.
                FilledTonalButton(
                    onClick = onVerifyFundingTx,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = fundingTxId.isNotBlank()
                ) {
                    Text(stringResource(R.string.escrow_verify_funding))
                }
                Spacer(Modifier.height(8.dp))
                // Fix 2: inform the user of the 30-minute auto-cancel window.
                FundingWindowCountdown(escrow = escrow, isSweepAuthority = true)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.escrow_timeout_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // Cancel is only safe while NOTHING has been broadcast: once a
                // funding txid is entered (deposit in flight / "In progress"),
                // cancelling could orphan the deposit — the only safe paths are
                // Verify (→ FUNDED) or clearing the txid field first.
                // EXCEPTION (2026-09-04): a PARTIAL deposit (underpaid) is
                // recorded on the escrow — cancelling refunds the partial BTC
                // back to the seller instead of stranding it in the multisig.
                val partialDeposit = escrow.fundedAmountSats
                    ?.takeIf { it > 0L && it < escrow.depositAmountSats }
                OutlinedButton(
                    onClick = onRequestRefund,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    enabled = (fundingTxId.isBlank() && !fundingBusy) || partialDeposit != null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.escrow_cancel_unfunded))
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
                            text = stringResource(
                                // Title mirrors the body (fundingWaitBodyKey): once
                                // the txid synced the deposit is broadcast, only
                                // confirmation is pending — "Waiting for seller
                                // deposit" would contradict it.
                                if (fundingTxId.isNotBlank()) R.string.escrow_status_waiting_confirmation
                                else R.string.escrow_funding_wait_buyer_title
                            ),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(fundingWaitBodyKey(fundingTxId.isNotBlank())),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                FundingWindowCountdown(escrow = escrow, isSweepAuthority = false)
                // No dispute button here (2026-09-05): FUNDING is not
                // disputable — the deposit is either not yet broadcast (the
                // 30-min funding window auto-cancels) or in flight (the
                // arbitrator's resolution would spend an unconfirmed output).
                // The buyer's exit from a stuck FUNDING escrow is the
                // auto-cancel, not a dispute.
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Action buttons (post-funding)
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
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
                    if (isRole == EscrowRole.SELLER) {
                        Spacer(Modifier.height(8.dp))
                        RefundWindowCountdown(escrow = escrow)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (isRole == EscrowRole.BUYER) {
                        Button(
                            onClick = onMarkPaid,
                            enabled = !markPaidBusy,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            if (markPaidBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_mark_paid_sending))
                            } else {
                                Text(stringResource(R.string.escrow_mark_paid))
                            }
                        }
                    } else {
                        // Cancel & Refund is the SELLER's escape hatch (they
                        // deposited the BTC). The buyer must NOT see it — the
                        // buyer's fiat payment is not recoverable from the
                        // escrow and the 2-of-3 spend is seller-gated anyway.
                        OutlinedButton(
                            onClick = onRequestRefund,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.escrow_request_refund))
                        }
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
                        Button(
                            onClick = onMarkPaid,
                            enabled = !markPaidBusy,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            if (markPaidBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_mark_paid_sending))
                            } else {
                                Text(stringResource(R.string.escrow_mark_paid))
                            }
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
                        // Escape hatch: the buyer may dispute instead of
                        // waiting on the seller (a stuck seller must never
                        // leave the buyer with no exit).
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onDispute,
                            enabled = !disputeBusy,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            if (disputeBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_disputing))
                            } else {
                                Text(stringResource(R.string.escrow_dispute))
                            }
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
                            onClick = {
                                haptics.moneyAction(MoneyAction.CONFIRM_RELEASE)
                                onConfirmReceipt()
                            },
                            enabled = !confirmReceiptBusy,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            if (confirmReceiptBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_releasing))
                            } else {
                                Text(stringResource(R.string.escrow_confirm_idr_received))
                            }
                        }
                        // Reject path: the seller can decline the receipt with a
                        // structured reason over E2EE chat. NO status change —
                        // confirmReceipt remains the only release gate; this is
                        // evidence in the thread so the buyer can fix/resubmit
                        // or dispute instead of guessing why nothing happened.
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRejectReceipt,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.escrow_reject_receipt))
                        }
                        // Escape hatch: the seller may dispute instead of
                        // releasing (a stuck/broken payout must never leave the
                        // seller with no exit).
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onDispute,
                            enabled = !disputeBusy,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            if (disputeBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_disputing))
                            } else {
                                Text(stringResource(R.string.escrow_dispute))
                            }
                        }
                    } else {
                        // PAYMENT_PENDING: no receipt yet — only the dispute
                        // escape hatch (no release, no cancel).
                        TextButton(
                            onClick = onDispute,
                            enabled = !disputeBusy,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            if (disputeBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_disputing))
                            } else {
                                Text(stringResource(R.string.escrow_dispute))
                            }
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
                        Button(
                            onClick = {
                                haptics.moneyAction(MoneyAction.CONFIRM_RELEASE)
                                onConfirmReceipt()
                            },
                            enabled = !confirmReceiptBusy,
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            if (confirmReceiptBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_releasing))
                            } else {
                                Text(stringResource(R.string.escrow_confirm_idr_received))
                            }
                        }
                        // Escape hatch: a failed broadcast must not trap the
                        // seller — dispute escalates to arbitration instead.
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onDispute,
                            enabled = !disputeBusy,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            if (disputeBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_disputing))
                            } else {
                                Text(stringResource(R.string.escrow_dispute))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onRequestRefund,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.escrow_request_refund))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.escrow_paid_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // C1d: buyer-side "Sign payout" fallback. Auto-sign is
                        // attempted on CONFIRMING, but if the buyer's app was
                        // closed at that moment, the seller's release waits for
                        // the buyer signature forever. This button signs + sends
                        // it on demand so the release can complete.
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onSignPayout,
                            enabled = !signPayoutBusy,
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            if (signPayoutBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_signing_payout))
                            } else {
                                Text(stringResource(R.string.escrow_sign_payout))
                            }
                        }
                        if (signPayoutDone) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.escrow_sign_payout_sent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        // Escape hatch: the buyer may dispute instead of
                        // waiting on the seller (a stuck seller must never
                        // leave the buyer with no exit).
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onDispute,
                            enabled = !disputeBusy,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            if (disputeBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.escrow_disputing))
                            } else {
                                Text(stringResource(R.string.escrow_dispute))
                            }
                        }
                    }
                }
                EscrowStatus.RELEASED -> {
                    ReleaseCheckmark()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.escrow_released_to_counterparty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TradeCompletionCard(
                        escrow = escrow,
                        fiatAmount = fiatAmount,
                        statusRes = R.string.profile_completed
                    )
                }
                EscrowStatus.DISPUTED -> {
                    Text(
                        text = stringResource(R.string.escrow_dispute_timelock),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onOpenEvidence,
                        modifier = Modifier.fillMaxWidth().height(40.dp)
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
                        modifier = Modifier.fillMaxWidth().height(40.dp)
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
                    Spacer(Modifier.height(12.dp))
                    TradeCompletionCard(
                        escrow = escrow,
                        fiatAmount = fiatAmount,
                        statusRes = R.string.escrow_status_refunded
                    )
                }
                EscrowStatus.CANCELLED -> {
                    Text(
                        text = stringResource(R.string.escrow_status_cancelled_desc),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TradeCompletionCard(
                        escrow = escrow,
                        fiatAmount = fiatAmount,
                        statusRes = R.string.escrow_status_cancelled
                    )
                }
                else -> {}
            }
            // Dispute is always available until funds are released. The
            // per-status branches above already render their own dispute
            // button for PAYMENT_PENDING / RECEIPT_SENT / CONFIRMING — this
            // catch-all covers only the statuses that don't (FUNDED, SIGNED),
            // so the escape hatch never appears twice.
            if (escrow.status == EscrowStatus.FUNDED || escrow.status == EscrowStatus.SIGNED) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDispute,
                    enabled = !disputeBusy,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    if (disputeBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.escrow_disputing))
                    } else {
                        Text(stringResource(R.string.escrow_dispute))
                    }
                }
            }
        }

        // Fee transparency (compact) — seller-only: the 0.5% fee and the fee
        // wallet address are the seller's cost; the buyer pays no fee and
        // doesn't need this card.
        if (isRole == EscrowRole.SELLER) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = stringResource(R.string.escrow_fee_transparency) + " — " +
                            stringResource(R.string.escrow_fee_text),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = escrow.feeAddress,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Buyer-facing pay instruction card: exact IDR amount with the unique-code
 * suffix (copyable), the seller's rails, QRIS note, and the two safety lines
 * every Indonesian P2P flow teaches (own-account transfer, no crypto words
 * in the transfer note). Reference: Indodax/Flip kode-unik, Binance ID
 * safety copy, BI QRIS payer sequence.
 */
@Composable
internal fun PayInstructionCard(
    fiatAmount: Long,
    escrowId: String,
    methods: Set<String>,
    paymentDetails: Map<String, com.neop2p.domain.model.PaymentDetails> = emptyMap(),
    onCopied: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val code = uniquePaymentCode(escrowId, fiatAmount)
    val totalAmount = fiatAmount + code
    val formattedTotal = formatIdr(totalAmount)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.escrow_pay_instruction_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            // The exact amount to transfer — one copyable string with the
            // code digits visually emphasized (the seller reads the tail).
            Text(
                text = formattedTotal,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.escrow_pay_amount_exact, formattedTotal, code.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("NEO-P2P amount", formattedTotal)
                        )
                        onCopied(context.getString(R.string.escrow_pay_amount_copied))
                    }
                ) {
                    Text(stringResource(R.string.escrow_pay_copy_amount))
                }
            }
            // Live amount helper: buyer types what they actually sent, we compare
            // to expected total (fiat+code) and show ✓/✗ with delta — prevents
            // the seller releasing on a short payment (missing kode unik).
            var enteredAmount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            OutlinedTextField(
                value = enteredAmount,
                onValueChange = { enteredAmount = it.filter { c -> c.isDigit() }.take(12) },
                label = { Text(stringResource(R.string.escrow_pay_entered_label)) },
                placeholder = { Text(formattedTotal) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            val enteredLong = enteredAmount.toLongOrNull()
            if (enteredAmount.isNotBlank() && enteredLong != null) {
                val isMatch = enteredLong == totalAmount
                Text(
                    text = if (isMatch) stringResource(R.string.escrow_pay_match_ok, formattedTotal)
                    else stringResource(R.string.escrow_pay_match_fail, formatIdr(enteredLong), formattedTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                if (!isMatch) {
                    Text(
                        text = stringResource(R.string.error_code_line, com.neop2p.ui.util.ErrorCodes.ERR_AMOUNT_MISMATCH),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.escrow_pay_amount_mismatch),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // Rail-mismatch guard: the transfer must use the methods the
            // seller registered. Paying via a different bank/e-wallet makes
            // the proof ambiguous (the seller checks their OWN account).
            Text(
                text = stringResource(R.string.escrow_pay_rail_mismatch),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // BI-FAST/RTGS nuance: BI-FAST caps apply per bank; large amounts
            // may need RTGS. Neutral copy — the cap differs per bank and is
            // deliberately NOT hardcoded (user must confirm their own limit).
            if (methods.any { it != "qris" }) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.escrow_pay_bifast_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (methods.any { it == "qris" }) {
                Spacer(Modifier.height(4.dp))
                // The seller's static QRIS string — render as scannable QR +
                // copyable text so the buyer can scan in their e-wallet. Uses
                // the same generateQrCode() as wallet/invite (zxing).
                val qrisString = paymentDetails["qris"]?.qrisString.orEmpty()
                if (qrisString.isNotBlank()) {
                    val qrisBitmap = androidx.compose.runtime.remember(qrisString) {
                        com.neop2p.ui.util.generateQrCode(qrisString, 320)
                    }
                    qrisBitmap?.let { bmp ->
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.escrow_pay_qris_cd),
                            modifier = Modifier
                                .size(220.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = stringResource(R.string.escrow_pay_qris_string, qrisString),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("NEO-P2P QRIS", qrisString)
                                )
                                onCopied(context.getString(R.string.escrow_pay_qris_copied))
                            }
                        ) {
                            Text(stringResource(R.string.escrow_pay_copy_qris))
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.escrow_pay_qris_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.escrow_pay_own_account),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.escrow_pay_no_crypto_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Trade-completion summary card (Bisq bisq-mobile#420 pattern): shown on
 * terminal states (RELEASED / REFUNDED / CANCELLED) with the amounts, fee,
 * kode unik, dates and txids, plus a "Save / Share proof" button that pushes
 * a plain-text summary through the system share sheet — the seller keeps a
 * record without any server. Txids may be absent (never funded), so rows are
 * conditional.
 */
/**
 * Signature moment for the escrow's terminal win: a checkmark badge that
 * springs in with the NeoMotion emphasized token. Functional first — the
 * TradeCompletionCard below carries the data; this just marks the moment.
 */
@Composable
private fun ReleaseCheckmark(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = NeoMotion.emphasized,
        label = "releaseCheck"
    )
    LaunchedEffect(Unit) { visible = true }
    Box(
        modifier = modifier
            .size(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = stringResource(R.string.escrow_released_cd),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Composable
private fun TradeCompletionCard(
    escrow: Escrow,
    fiatAmount: Long,
    statusRes: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val code = if (fiatAmount > 0L) uniquePaymentCode(escrow.escrowId, fiatAmount) else null
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.escrow_completion_title, stringResource(statusRes)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.escrow_completion_amount, formatBtc(escrow.tradeAmountSats)),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            if (fiatAmount > 0L) {
                Text(
                    text = stringResource(R.string.escrow_completion_fiat, formatIdr(fiatAmount)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (escrow.feeAmountSats > 0L) {
                Text(
                    text = stringResource(R.string.escrow_completion_fee, formatBtc(escrow.feeAmountSats)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            code?.let {
                Text(
                    text = stringResource(R.string.escrow_completion_code, it.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            escrow.fundingTxId?.let {
                Text(
                    text = stringResource(R.string.escrow_completion_funding_tx, it.take(16)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            escrow.payoutTxId?.let {
                Text(
                    text = stringResource(R.string.escrow_completion_payout_tx, it.take(16)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.escrow_completion_date, formatDate(escrow.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val sb = StringBuilder()
                    sb.append("NEO-P2P ").append(context.getString(statusRes)).append('\n')
                    sb.append(context.getString(R.string.escrow_completion_amount, formatBtc(escrow.tradeAmountSats))).append('\n')
                    if (fiatAmount > 0L) sb.append(context.getString(R.string.escrow_completion_fiat, formatIdr(fiatAmount))).append('\n')
                    code?.let { sb.append(context.getString(R.string.escrow_completion_code, it.toString())).append('\n') }
                    escrow.fundingTxId?.let { sb.append(context.getString(R.string.escrow_completion_funding_tx, it)).append('\n') }
                    escrow.payoutTxId?.let { sb.append(context.getString(R.string.escrow_completion_payout_tx, it)).append('\n') }
                    sb.append(context.getString(R.string.escrow_completion_id, escrow.escrowId))
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(send, context.getString(R.string.escrow_completion_share))
                    )
                },
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) {
                Text(stringResource(R.string.escrow_completion_share))
            }
        }
    }
}

/**
 * Sticky "next action" bar pinned at the bottom of the escrow detail —
 * RoboSats/Binance single-action-per-state pattern: exactly ONE primary
 * message per role+state, with a live countdown when a window is running.
 * Disabled/informational states show the reason instead of a dead button.
 */
@Composable
internal fun NextActionBar(
    escrow: Escrow,
    isRole: EscrowRole,
    fiatAmount: Long,
    fundingTxId: String = "",
    modifier: Modifier = Modifier
) {
    val status = escrow.status
    val isSeller = isRole == EscrowRole.SELLER

    // Single primary message per role+state; countdown only while a window
    // is actually running (FUNDING seller, FUNDED/PENDING/RECEIPT buyer).
    val showCountdown = (status == EscrowStatus.FUNDING && isSeller) ||
        (status == EscrowStatus.FUNDED && !isSeller) ||
        (status == EscrowStatus.PAYMENT_PENDING && !isSeller) ||
        (status == EscrowStatus.RECEIPT_SENT && !isSeller)

    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (NeoMotion.fadeIn + NeoMotion.slideUp).togetherWith(NeoMotion.fadeOut)
        },
        label = "nextAction"
    ) { s ->
        // The lambda param `s` (not `status`) is what changes per transition.
        val text: String? = when {
            s == EscrowStatus.FUNDING && isSeller ->
                stringResource(fundingNextActionKey(fundingTxId.isNotBlank(), isSeller = true))
            s == EscrowStatus.FUNDING ->
                stringResource(fundingNextActionKey(fundingTxId.isNotBlank(), isSeller = false))
            s == EscrowStatus.FUNDED && !isSeller && fiatAmount > 0L ->
                stringResource(R.string.next_action_pay_buyer, formatIdr(fiatAmount))
            s == EscrowStatus.FUNDED ->
                stringResource(R.string.next_action_funded_seller)
            s == EscrowStatus.PAYMENT_PENDING && !isSeller ->
                stringResource(R.string.next_action_payment_pending_buyer)
            s == EscrowStatus.PAYMENT_PENDING ->
                stringResource(R.string.next_action_payment_pending_seller)
            s == EscrowStatus.RECEIPT_SENT && isSeller ->
                stringResource(R.string.next_action_receipt_seller)
            s == EscrowStatus.RECEIPT_SENT ->
                stringResource(R.string.next_action_receipt_buyer)
            s == EscrowStatus.CONFIRMING && isSeller ->
                stringResource(R.string.next_action_confirming_seller)
            s == EscrowStatus.CONFIRMING ->
                stringResource(R.string.next_action_confirming_buyer)
            s == EscrowStatus.DISPUTED || s == EscrowStatus.RESOLVING ->
                stringResource(R.string.next_action_disputed)
            s == EscrowStatus.SIGNED ->
                stringResource(R.string.next_action_signed)
            else -> null
        }
        if (text == null) return@AnimatedContent

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_info_outline),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (showCountdown) {
                    Spacer(Modifier.height(2.dp))
                    when {
                        status == EscrowStatus.FUNDING && isSeller -> FundingWindowCountdown(escrow, isSweepAuthority = true)
                        else -> PaymentWindowCountdown(escrow)
                    }
                }
            }
        }
    }
}

/**
 * Dialog for the seller's "Tolak Bukti" flow.
 *
 * Picks a machine reason code (JUMLAH_SALAH / NAMA_BEDA / BELUM_MASUK /
 * LAINNYA) plus an optional free-text note, then sends the structured
 * rejection over E2EE chat. The escrow status is NOT changed — the release
 * gate stays confirmReceipt-only; the rejection is evidence in the thread.
 */
@Composable
private fun RejectReceiptDialog(
    reference: String,
    reason: String,
    note: String,
    busy: Boolean,
    error: String?,
    onReasonChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.escrow_reject_receipt_title)) },
        text = {
            Column(modifier = modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.escrow_reject_intro, reference),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.escrow_reject_no_status_change),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.escrow_reject_reason_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(4.dp))
                val reasons = listOf(
                    "JUMLAH_SALAH",
                    "NAMA_BEDA",
                    "BELUM_MASUK",
                    "LAINNYA"
                )
                reasons.forEach { r ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onReasonChange(r) }
                    ) {
                        RadioButton(
                            selected = reason == r,
                            onClick = { onReasonChange(r) },
                            enabled = !busy
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                when (r) {
                                    "JUMLAH_SALAH" -> R.string.escrow_reject_reason_amount
                                    "NAMA_BEDA" -> R.string.escrow_reject_reason_name
                                    "BELUM_MASUK" -> R.string.escrow_reject_reason_not_received
                                    else -> R.string.escrow_reject_reason_other
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (reason == "LAINNYA" || note.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = onNoteChange,
                        label = { Text(stringResource(R.string.escrow_reject_note_label)) },
                        placeholder = { Text(stringResource(R.string.escrow_reject_note_placeholder)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
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
                enabled = !busy && reason.isNotBlank()
            ) {
                Text(stringResource(R.string.escrow_reject_confirm))
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
    val primaryDeadline = (escrow.paidAt ?: escrow.createdAt) + EscrowService.PAYMENT_WINDOW_MS
    val graceDeadline = primaryDeadline + EscrowService.PAYMENT_GRACE_MS
    val now = System.currentTimeMillis()
    val inGrace = now > primaryDeadline && now < graceDeadline
    val deadline = graceDeadline
    var remainingMs by remember { mutableLongStateOf((deadline - now).coerceAtLeast(0L)) }
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
        val base = stringResource(R.string.escrow_payment_window, "%02d:%02d:%02d".format(h, m, s))
        if (inGrace) base + " — " + stringResource(R.string.escrow_grace_suffix) else base
    }
    val targetColor = if (remaining <= 0) MaterialTheme.colorScheme.error
    else if (inGrace) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val animatedColor by animateColorAsState(
        targetColor, animationSpec = NeoMotion.standardColor, label = "paymentCountdownColor"
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = animatedColor,
        modifier = modifier
    )
}

/**
 * Body copy for the buyer's FUNDING waiting card. A static "the seller is
 * depositing" is wrong once the mirrored row carries a funding txid (the
 * same-status escrow_status refresh delivers it) — the deposit is broadcast,
 * only confirmation is pending.
 */
fun fundingWaitBodyKey(hasFundingTxId: Boolean): Int =
    if (hasFundingTxId) R.string.escrow_funding_broadcast_buyer_body
    else R.string.escrow_funding_wait_buyer_body

/**
 * Next-action copy for the FUNDING step. Once the funding txid exists the
 * story flips from "send BTC" (seller) / "waiting for deposit" (buyer) to
 * "broadcast — waiting for confirmation": the fund button is disabled, the
 * txid field is filled, Verify is the real next action. Shared by both
 * roles — both are waiting on the same chain confirmations.
 */
fun fundingNextActionKey(hasFundingTxId: Boolean, isSeller: Boolean): Int = when {
    hasFundingTxId -> R.string.next_action_funding_broadcast
    isSeller -> R.string.next_action_funding_seller
    else -> R.string.next_action_funding_buyer
}

/**
 * Which expired-message a device shows when the funding window reaches zero.
 * Only the SELLER's device runs the 60s sweep (EscrowService.expireStaleEscrows,
 * seller-gated) and its outcome may be CANCELLED or FUNDED-promotion — never
 * claim a terminal state here. The buyer's device has no authority: the
 * cancellation is an LXMF escrow_status event, so the buyer's copy waits for
 * the seller's confirmation instead of asserting a state on the local clock
 * (device clocks skew — the emulator test device is ~70 min behind).
 */
fun fundingWindowExpiredKey(isSweepAuthority: Boolean): Int =
    if (isSweepAuthority) R.string.escrow_funding_window_checking
    else R.string.escrow_funding_window_syncing

/**
 * Live countdown for the seller's funding window (FUNDING status). Ticks every
 * second and shows the time left before an unfunded escrow auto-cancels
 * (30 min from creation, warning at 15 min).
 */
@Composable
private fun FundingWindowCountdown(
    escrow: Escrow,
    isSweepAuthority: Boolean,
    modifier: Modifier = Modifier
) {
    val deadline = escrow.createdAt + EscrowService.ESCROW_FUNDING_TIMEOUT_MS
    var remainingMs by remember { mutableLongStateOf((deadline - System.currentTimeMillis()).coerceAtLeast(0L)) }
    LaunchedEffect(deadline) {
        while (remainingMs > 0) {
            delay(1_000)
            remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }
    val remaining = remainingMs
    val text = if (remaining <= 0) {
        stringResource(fundingWindowExpiredKey(isSweepAuthority))
    } else {
        val totalSec = remaining / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        stringResource(
            R.string.escrow_funding_window,
            "%02d:%02d:%02d".format(h, m, s)
        )
    }
    val targetColor = if (remaining <= 0) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    val animatedColor by animateColorAsState(
        targetColor, animationSpec = NeoMotion.standardColor, label = "fundingCountdownColor"
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = animatedColor,
        modifier = modifier
    )
}

/**
 * Live countdown for the funded-but-stalled auto-refund window (FUNDED status).
 * Ticks every second and shows the time left before the escrow auto-refunds to
 * the seller (2 h from funding confirmation + 2 h grace).
 */
@Composable
private fun RefundWindowCountdown(escrow: Escrow, modifier: Modifier = Modifier) {
    val deadline = (escrow.fundedAt ?: escrow.createdAt) +
        EscrowService.ESCROW_FUNDED_STALL_TIMEOUT_MS + EscrowService.FUNDED_STALL_GRACE_MS
    var remainingMs by remember { mutableLongStateOf((deadline - System.currentTimeMillis()).coerceAtLeast(0L)) }
    LaunchedEffect(deadline) {
        while (remainingMs > 0) {
            delay(1_000)
            remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }
    val remaining = remainingMs
    val text = if (remaining <= 0) {
        stringResource(R.string.escrow_refund_window_expired)
    } else {
        val totalSec = remaining / 1000
        val d = totalSec / 86400
        val h = (totalSec % 86400) / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val hms = "%02d:%02d:%02d".format(h, m, s)
        stringResource(
            R.string.escrow_refund_window,
            if (d > 0) "${d}d $hms" else hms
        )
    }
    val targetColor = if (remaining <= 0) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    val animatedColor by animateColorAsState(
        targetColor, animationSpec = NeoMotion.standardColor, label = "refundCountdownColor"
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = animatedColor,
        modifier = modifier
    )
}

/**
 * Post-trade rating dialog. Shown once when an escrow reaches RELEASED or
 * REFUNDED; publishes a signed local attestation attestation via the reputation
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
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    TextButton(onClick = onPositive, enabled = !busy) {
                        Text(stringResource(R.string.escrow_rate_positive))
                    }
                    TextButton(onClick = onNegative, enabled = !busy) {
                        Text(stringResource(R.string.escrow_rate_negative))
                    }
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

/** Visual state of one step-tracker dot. */
internal enum class StepVisual { DONE, ACTIVE, PENDING }

internal fun stepVisual(index: Int, currentStep: Int): StepVisual = when {
    index < currentStep -> StepVisual.DONE
    index == currentStep -> StepVisual.ACTIVE
    else -> StepVisual.PENDING
}

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

private fun formatDate(epochMillis: Long): String =
    java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))

@HiltViewModel
class EscrowViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val escrowService: EscrowService,
    private val walletService: com.neop2p.data.wallet.WalletService,
    private val identityManager: IdentityManager,
    private val reputationSystem: com.neop2p.data.reputation.ReputationSystem,
    private val offerDao: OfferDao,
    private val chainMonitor: com.neop2p.data.escrow.ChainMonitor,
    private val peerDao: com.neop2p.data.local.dao.PeerDao,
    private val chatRouter: com.neop2p.data.p2p.routing.ChatRouter,
    private val peerRegistry: com.neop2p.data.p2p.store.PeerRegistry,
    private val pendingDisputeStore: com.neop2p.data.local.PendingDisputeStore,
    private val rnsTransport: com.neop2p.data.p2p.RnsTransport,
    savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "EscrowViewModel"

        // Approximate vsize of the seller's wallet→escrow funding tx
        // (1 P2PKH input 148 + 1 output 34 + ~10 fixed overhead). Used to
        // estimate the EXTRA miner fee the seller's wallet pays on top of
        // the escrow deposit — that fee is NOT part of the escrow itself.
        private const val FUNDING_TX_APPROX_VSIZE = 192L
    }

    private val escrowId: String =
        savedStateHandle.get<String>("escrowId") ?: ""

    // Post-trade rating: mark an escrow as offered/rated PERSISTENTLY so the
    // dialog never comes back after a restart or re-entering the screen
    // (the old in-memory set reset every process start). Keyed by escrowId.
    private val ratedPrefs =
        context.getSharedPreferences("escrow_rated", android.content.Context.MODE_PRIVATE)
    private fun isRatedOffer(escrowId: String): Boolean =
        ratedPrefs.getBoolean("rated_$escrowId", false)
    private fun markRated(escrowId: String) {
        ratedPrefs.edit().putBoolean("rated_$escrowId", true).apply()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _fundingTxId = MutableStateFlow("")
    val fundingTxId: StateFlow<String> = _fundingTxId.asStateFlow()
    fun setFundingTxId(v: String) { _fundingTxId.value = v }

    // ── Reject receipt dialog state (seller-only) ──
    private val _showRejectDialog = MutableStateFlow(false)
    val showRejectDialog: StateFlow<Boolean> = _showRejectDialog.asStateFlow()
    private val _rejectReason = MutableStateFlow("")
    val rejectReason: StateFlow<String> = _rejectReason.asStateFlow()
    private val _rejectNote = MutableStateFlow("")
    val rejectNote: StateFlow<String> = _rejectNote.asStateFlow()
    private val _rejectBusy = MutableStateFlow(false)
    val rejectBusy: StateFlow<Boolean> = _rejectBusy.asStateFlow()
    private val _rejectError = MutableStateFlow<String?>(null)
    val rejectError: StateFlow<String?> = _rejectError.asStateFlow()

    fun openRejectDialog() {
        val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return
        // Role + status gate: only the SELLER on RECEIPT_SENT/CONFIRMING with
        // an existing receipt can reject (mirror of the confirmReceipt gate).
        if (determineRole(current) != EscrowRole.SELLER) return
        if (current.status != EscrowStatus.RECEIPT_SENT && current.status != EscrowStatus.CONFIRMING) return
        if (current.receiptReference.isNullOrBlank()) return
        _rejectError.value = null
        _rejectReason.value = ""
        _rejectNote.value = ""
        _showRejectDialog.value = true
    }

    fun closeRejectDialog() {
        if (_rejectBusy.value) return
        _showRejectDialog.value = false
    }

    fun onRejectReasonChange(reason: String) { _rejectReason.value = reason }
    fun onRejectNoteChange(note: String) { _rejectNote.value = note }

    /**
     * Send a structured payment-receipt rejection to the buyer over E2EE chat.
     * Deliberately does NOT change the escrow status: confirmReceipt remains
     * the ONLY release gate. The rejection is evidence in the trade thread
     * and an instruction to the buyer (fix + resubmit, or dispute).
     */
    fun rejectReceipt() {
        val reason = _rejectReason.value
        if (reason.isBlank()) {
            _rejectError.value = context.getString(R.string.escrow_reject_reason_required)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _rejectBusy.value = true
            _rejectError.value = null
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                if (determineRole(current) != EscrowRole.SELLER) return@launch
                if (current.status != EscrowStatus.RECEIPT_SENT && current.status != EscrowStatus.CONFIRMING) return@launch
                val reference = current.receiptReference ?: return@launch
                chatRouter.sendRejectMessage(
                    offerId = current.offerId,
                    peerId = current.buyerPeerId,
                    payload = PaymentReceiptRejectPayload(
                        reference = reference,
                        reason = reason,
                        note = _rejectNote.value
                    )
                ).onSuccess {
                    _showRejectDialog.value = false
                    _rejectError.value = null
                }.onFailure { err ->
                    _rejectError.value = context.getString(R.string.escrow_reject_failed, err.message ?: "")
                }
            } catch (e: Exception) {
                _rejectError.value = context.getString(R.string.escrow_reject_failed, e.message ?: "")
            } finally {
                _rejectBusy.value = false
            }
        }
    }

    private val _requestRefundError = MutableStateFlow<String?>(null)
    val requestRefundError: StateFlow<String?> = _requestRefundError.asStateFlow()

    // ── Auto-fund from wallet state ──
    private val _fundingBusy = MutableStateFlow(false)
    val fundingBusy: StateFlow<Boolean> = _fundingBusy.asStateFlow()

    private val _fundingError = MutableStateFlow<String?>(null)
    val fundingError: StateFlow<String?> = _fundingError.asStateFlow()

    private val _fundingMessage = MutableStateFlow<String?>(null)
    val fundingMessage: StateFlow<String?> = _fundingMessage.asStateFlow()

    // Extra miner fee the SELLER's wallet pays to broadcast the funding tx
    // (wallet → escrow). NOT part of the escrow deposit — this is what makes
    // "the seller pays 2 network fees" visible up front.
    private val _fundingMinerFeeEstimate = MutableStateFlow<Long?>(null)
    val fundingMinerFeeEstimate: StateFlow<Long?> = _fundingMinerFeeEstimate.asStateFlow()

    // ── Post-trade rating state ──
    private val _showRating = MutableStateFlow(false)
    val showRating: StateFlow<Boolean> = _showRating.asStateFlow()

    private val _ratingBusy = MutableStateFlow(false)
    val ratingBusy: StateFlow<Boolean> = _ratingBusy.asStateFlow()

    private val _ratingError = MutableStateFlow<String?>(null)
    val ratingError: StateFlow<String?> = _ratingError.asStateFlow()

    // ── Main-action busy state (mark paid / release / dispute) ──
    // These three actions were silent: no busy flag existed, so the buttons
    // gave zero feedback while the IO block ran (markPaid, confirmReceipt
    // broadcasts the payout, disputeEscrow builds a refund tx + publishes
    // LXMF dispute message with relay-ack gating — the longest ops in the app).
    private val _markPaidBusy = MutableStateFlow(false)
    val markPaidBusy: StateFlow<Boolean> = _markPaidBusy.asStateFlow()

    private val _confirmReceiptBusy = MutableStateFlow(false)
    val confirmReceiptBusy: StateFlow<Boolean> = _confirmReceiptBusy.asStateFlow()

    private val _signPayoutBusy = MutableStateFlow(false)
    val signPayoutBusy: StateFlow<Boolean> = _signPayoutBusy.asStateFlow()

    // One-shot "Signature sent to seller" confirmation for the C1d fallback.
    private val _signPayoutDone = MutableStateFlow(false)
    val signPayoutDone: StateFlow<Boolean> = _signPayoutDone.asStateFlow()
    fun consumeSignPayoutDone() { _signPayoutDone.value = false }

    private val _disputeBusy = MutableStateFlow(false)
    val disputeBusy: StateFlow<Boolean> = _disputeBusy.asStateFlow()

    fun consumeFundingMessage() { _fundingMessage.value = null }
    fun consumeFundingError() { _fundingError.value = null }
    fun consumeRequestRefundError() { _requestRefundError.value = null }

    /** Re-estimate the seller's wallet→escrow broadcast fee (fastest rate). */
    fun refreshFundingMinerFeeEstimate() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val feeRate = chainMonitor.estimateFees().fastest
                _fundingMinerFeeEstimate.value = feeRate * FUNDING_TX_APPROX_VSIZE
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Funding fee estimate failed: ${e.message}")
                _fundingMinerFeeEstimate.value = null
            }
        }
    }

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
                            paymentDetails = paymentDetailsFor(updated),
                            fiatAmount = fiatAmountFor(updated),
                            scriptVerdict = escrowService.scriptVerdictFor(escrowId)
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
        val paymentDetails: Map<String, com.neop2p.domain.model.PaymentDetails> = emptyMap(),
        // Fiat amount (IDR) the buyer must pay — from the linked offer row.
        // Rendered with the unique-code suffix on the pay instruction card
        // (the seller checks the amount TAIL, per the Indodax/Flip convention).
        val fiatAmount: Long = 0L,
        // F3: attestation verdict for this escrow's redeem script. Null for
        // legacy rows with no script. Rendered as a warning banner when not ok.
        val scriptVerdict: EscrowScriptGate.Verdict? = null
    )

    init {
        loadEscrow()
        // Live refresh: remote LXMF escrow_status events (and local transitions) for
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
                    // it is created by the seller and arrives via the LXMF escrow_status
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
                    // Seller-facing: show the extra wallet→escrow broadcast fee
                    // up front (the "second network fee"). Refreshed on every
                    // load so the estimate tracks current fee rates.
                    if (role == EscrowRole.SELLER && escrow.status == EscrowStatus.FUNDING) {
                        refreshFundingMinerFeeEstimate()
                    }
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
                            paymentDetails = paymentDetailsFor(escrow),
                            fiatAmount = fiatAmountFor(escrow),
                            scriptVerdict = escrowService.scriptVerdictFor(escrowId)
                        )
                    )
                    maybeShowRating(escrow, role)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load escrow: ${e.message}")
            }
        }
    }

    /** Short label for the counterparty (nickname when known, else peer id
     *  tail) used by the rating dialog. */
    private suspend fun counterpartyLabelFor(escrow: Escrow, role: EscrowRole): String {
        val peerId = when (role) {
            EscrowRole.BUYER -> escrow.sellerPeerId
            EscrowRole.SELLER -> escrow.buyerPeerId
            else -> return ""
        }
        val nickname = runCatching { peerDao.getPeerSync(peerId)?.nickname.orEmpty() }
            .getOrDefault("")
        return if (nickname.isNotBlank()) nickname else peerId.take(8)
    }

    /**
     * Offer the post-trade rating dialog once when the escrow reaches a
     * terminal state (RELEASED / REFUNDED). The dialog is dismissible ("Later")
     * and never re-shown for the same escrow in this process.
     */
    private fun maybeShowRating(escrow: Escrow, role: EscrowRole) {
        if (role == EscrowRole.UNKNOWN) return
        if (escrow.status != EscrowStatus.RELEASED && escrow.status != EscrowStatus.REFUNDED) return
        // Persistent gate: already offered or rated this escrow → never again,
        // even across process restarts.
        if (isRatedOffer(escrow.escrowId)) return
        markRated(escrow.escrowId)
        _ratingError.value = null
        _showRating.value = true
    }

    /** Resolve the buyer's BTC receive address from the escrow (U1: populated
     *  at accept time / via LXMF escrow_status sync), falling back to the offer, then
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

    /** Fiat amount (IDR) the buyer must transfer for this escrow, from the
     *  linked offer. 0 when the offer row is missing (should not happen once
     *  the escrow exists — the offer always precedes it). */
    private suspend fun fiatAmountFor(escrow: Escrow): Long {
        return try {
            offerDao.getOffer(escrow.offerId).first()?.fiat_amount ?: 0L
        } catch (e: Exception) {
            0L
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

    /** Connection quality of a counterparty peer (F05b chip). */
    fun qualityOf(peerId: String): com.neop2p.data.p2p.store.PeerRegistry.ConnectionQuality =
        peerRegistry.qualityOf(peerId)

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
                            paymentDetails = paymentDetailsFor(updated),
                            fiatAmount = fiatAmountFor(updated),
                            scriptVerdict = escrowService.scriptVerdictFor(escrowId)
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
     * Sends the exact [Escrow.depositAmountSats] (crypto + 0.5% fee + network
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
                _fundingMessage.value = context.getString(R.string.escrow_funding_sending, formatBtc(amount))

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
                            paymentDetails = paymentDetailsFor(updated),
                            fiatAmount = fiatAmountFor(updated),
                            scriptVerdict = escrowService.scriptVerdictFor(escrowId)
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
        if (_markPaidBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _markPaidBusy.value = true
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
                            paymentDetails = paymentDetailsFor(escrow),
                            fiatAmount = fiatAmountFor(escrow),
                            scriptVerdict = escrowService.scriptVerdictFor(escrowId)
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to mark payment: ${e.message}")
            } finally {
                _markPaidBusy.value = false
            }
        }
    }

    /** Seller confirms "IDR received" — the ONLY release gate (confirmReceipt
     * broadcasts the 2-of-3 payout via the existing release machinery). */
    fun confirmReceipt() {
        if (_confirmReceiptBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _confirmReceiptBusy.value = true
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                escrowService.confirmReceipt(current.escrowId)
                    .onSuccess { escrow ->
                        _uiState.value = UiState.Success(
                            EscrowData(
                                escrow = escrow,
                                role = determineRole(escrow),
                                fundingTxId = _fundingTxId.value,
                                buyerAddress = buyerAddressFor(escrow),
                                counterpartyLabel = counterpartyLabelFor(escrow, determineRole(escrow)),
                                paymentDetails = paymentDetailsFor(escrow),
                                fiatAmount = fiatAmountFor(escrow),
                                scriptVerdict = escrowService.scriptVerdictFor(escrowId)
                            )
                        )
                    }
                    .onFailure { err ->
                        // Surface the broadcast failure instead of silently
                        // reloading: a dead release button must show why.
                        _uiState.value = UiState.Error("Release failed: ${err.message}")
                    }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to confirm receipt: ${e.message}")
            } finally {
                _confirmReceiptBusy.value = false
            }
        }
    }

    /**
     * C1d (2026-09-11): buyer-side manual "Sign payout" fallback. When the
     * seller confirms (CONFIRMING), the buyer's payout signature is auto-sent
     * (signPayoutAsBuyerIfLocal) — but if the buyer's app was closed during
     * CONFIRMING, that auto-sign missed and the release would wait forever.
     * This action lets the buyer sign + deliver the signature on demand so the
     * seller's release can combine it (2-of-3). No-op for a non-buyer or a
     * non-CONFIRMING escrow (the underlying helper guards).
     */
    fun signPayoutIfBuyer() {
        if (_signPayoutBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _signPayoutBusy.value = true
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                escrowService.signPayoutAsBuyerIfLocal(current.escrowId)
                _signPayoutBusy.value = false
                _signPayoutDone.value = true
            } catch (e: Exception) {
                _signPayoutBusy.value = false
                _uiState.value = UiState.Error("Failed to sign payout: ${e.message}")
            }
        }
    }

    /**
     * Publish a signed local attestation attestation rating the counterparty after a
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
                // Best-effort LXMF delivery to the counterparty. The
                // attestation is ALREADY persisted locally (source of
                // truth); a failed send re-queues via RESENDABLE_TYPES and
                // resends on the peer's next announce. Never send to self
                // (single-key demo: buyer and seller are the same peerId).
                if (targetPeerId != myPeerId) {
                    rnsTransport.sendAttestation(
                        targetPeerId,
                        reputationSystem.toWireJson(attestation)
                    ).onFailure { e ->
                        Log.w(TAG, "Attestation send to $targetPeerId failed (queued for resend): ${e.message}")
                    }
                }
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

    fun disputeEscrow(reason: String = "unspecified") {
        if (_disputeBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _disputeBusy.value = true
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                // Publish-then-commit (P0 2026-08-30): the dispute must reach the
                // relay (LXMF dispute message, ack-gated) BEFORE the local row flips to
                // DISPUTED. The old order stranded DISPUTED locally when the
                // relay was unreachable (arbitrator never saw it). Build the
                // payload from `current` (pre-dispute) so a publish failure leaves
                // the escrow in its prior state and the user can retry.
                val myPeerId = runCatching { identityManager.getOrCreateIdentity().peerId }
                    .getOrNull() ?: ""
                var unsignedHex = current.psbtUnsigned?.toString(Charsets.UTF_8)
                // If no payout exists yet (dispute opened before confirmReceipt/SIGNED),
                // auto-build it now so the arbitrator gets both Release + Refund options.
                // Uses fundingTxId + buyer address (fallback to fundingAddress for demo).
                if (unsignedHex.isNullOrBlank() && !current.fundingTxId.isNullOrBlank()) {
                    val buyerAddr = current.buyerBtcAddress?.takeIf { it.isNotBlank() } ?: current.fundingAddress
                    if (!buyerAddr.isNullOrBlank()) {
                        val gen = try {
                            escrowService.generatePayoutTransaction(
                                escrowId = current.escrowId,
                                fundingTxId = current.fundingTxId!!,
                                fundingOutputIndex = current.fundingVout.toInt(),
                                buyerAddressStr = buyerAddr
                            )
                        } catch (_: Exception) { Result.failure(Exception("gen failed")) }
                        if (gen.isSuccess) {
                            unsignedHex = gen.getOrNull()
                            android.util.Log.d("EscrowViewModel", "Auto-generated payout for dispute ${current.escrowId} psbtLen=${unsignedHex?.length ?: 0}")
                        } else {
                            android.util.Log.w("EscrowViewModel", "Auto-gen payout failed for ${current.escrowId}: ${gen.exceptionOrNull()?.message}")
                        }
                    }
                }
                val refundHex = escrowService.buildDisputeRefundTxHex(current.escrowId)
                val pending = com.neop2p.data.local.PendingDisputeStore.PendingDispute(
                    escrowId = current.escrowId,
                    openedBy = myPeerId,
                    reason = reason,
                    redeemScriptHex = current.redeemScriptHex,
                    psbtHex = unsignedHex,
                    refundTxHex = refundHex,
                    depositSats = current.fundedAmountSats ?: current.depositAmountSats,
                    fundingScriptType = current.fundingScriptType.name,
                    sellerRefundAddress = current.sellerRefundAddress,
                    // F2 (2026-09-12): carry the role keys + role-signed
                    // destination attestations so the arbitrator can verify
                    // where the payout/refund MUST go. Public values only.
                    offerId = current.offerId,
                    buyerBtcAddress = current.buyerBtcAddress,
                    buyerPubKeyHex = current.buyerPubKeyHex,
                    sellerPubKeyHex = current.sellerPubKeyHex,
                    tradeSats = current.tradeAmountSats,
                    sellerRefundAttestation = current.sellerRefundAttestation,
                    buyerAddressAttestation = current.buyerAddressAttestation
                )
                // Phase 4: deliver the dispute to the counterparty AND the
                // arbitrator over LXMF (RNS path). Publish-then-commit: the
                // dispute must be delivered BEFORE the local row flips to
                // DISPUTED, or the arbitrator never sees it.
                val fields = buildMap {
                    pending.redeemScriptHex?.let { put("redeem_script_hex", it) }
                    pending.psbtHex?.let { put("psbt_hex", it) }
                    pending.refundTxHex?.let { put("refund_tx_hex", it) }
                    pending.depositSats?.let { put("deposit_sats", it.toString()) }
                    pending.fundingScriptType?.let { put("funding_script_type", it) }
                    pending.sellerRefundAddress?.let { put("seller_refund_address", it) }
                    // F2 (2026-09-12): role keys + role-signed attestations so
                    // the arbitrator can verify the payout/refund destination
                    // against the escrow's authorized keys.
                    pending.offerId?.let { put("offer_id", it) }
                    pending.buyerBtcAddress?.takeIf { it.isNotBlank() }?.let { put("buyer_btc_address", it) }
                    pending.buyerPubKeyHex?.let { put("buyer_pubkey_hex", it) }
                    pending.sellerPubKeyHex?.let { put("seller_pubkey_hex", it) }
                    put("trade_sats", current.tradeAmountSats.toString())
                    pending.sellerRefundAttestation?.let { put("seller_refund_attestation", it) }
                    pending.buyerAddressAttestation?.let { put("buyer_address_attestation", it) }
                    // v23 (2026-09-02): carry the parties so the arbitrator —
                    // who has NO local escrow row — can deliver the resolution
                    // to the buyer AND seller. Pre-v23 the arbitrator resolved
                    // to nobody and funds stayed locked in the multisig.
                    put("buyer_peer_id", current.buyerPeerId)
                    put("seller_peer_id", current.sellerPeerId)
                }
                val counterparty = if (current.buyerPeerId == myPeerId) current.sellerPeerId else current.buyerPeerId
                var counterpartyDelivered = true
                if (counterparty.isNotBlank()) {
                    counterpartyDelivered = rnsTransport.sendDispute(
                        toPeerId = counterparty,
                        escrowId = pending.escrowId,
                        openedBy = pending.openedBy,
                        reason = pending.reason,
                        fields = fields
                    ).isSuccess
                }
                val arbPeerId = com.neop2p.NeoP2PConfig.ARBITRATOR_PEER_ID
                if (arbPeerId.isNotBlank() && arbPeerId != counterparty) {
                    val arbOk = rnsTransport.sendDispute(
                        toPeerId = arbPeerId,
                        escrowId = pending.escrowId,
                        openedBy = pending.openedBy,
                        reason = pending.reason,
                        fields = fields
                    ).isSuccess
                    if (!arbOk) {
                        Log.w(TAG, "Arbitrator not reached for dispute ${pending.escrowId} — best-effort, will retry on announce")
                    }
                }
                // Slice 4: the counterparty is the gate; the arbitrator is
                // best-effort (an offline arbitrator must not block the
                // dispute from opening — the sweep retry reaches it later).
                val delivered = com.neop2p.data.escrow.EscrowService.disputeDeliveryVerdict(
                    counterpartyDelivered = counterpartyDelivered
                )
                // v23 (2026-09-02): persist the undelivered targets so the 60s
                // sweep retries ONLY what failed. The counterparty is the
                // open-gate, but the arbitrator must still be reached — a
                // dispute the arbitrator never sees is unresolvable (funds
                // locked). When the counterparty acked but the arbitrator did
                // not, the dispute still opens AND a per-target row keeps the
                // arbitrator delivery alive.
                val undelivered = buildList {
                    if (!counterpartyDelivered && counterparty.isNotBlank()) add(counterparty)
                    if (arbPeerId.isNotBlank() && arbPeerId != counterparty) add(arbPeerId)
                }
                if (!delivered) {
                    pendingDisputeStore.save(pending.copy(targets = undelivered))
                    _uiState.value = UiState.Error(
                        "Dispute delivery failed — saved for retry (LXMF did not deliver): " +
                            " — will auto-retry every 60s"
                    )
                    return@launch
                } else {
                    if (undelivered.isNotEmpty()) {
                        pendingDisputeStore.save(pending.copy(targets = undelivered))
                    } else {
                        pendingDisputeStore.remove(current.escrowId)
                    }
                }
                // Delivered — now mark locally DISPUTED + sync 33337.
                val updated = escrowService.disputeEscrow(current.escrowId).getOrNull()
                // `updated` null means disputeEscrow's status guard rejected
                // (already DISPUTED/terminal) — treat as success and reload.
                val escrow = updated ?: escrowService.getEscrow(current.escrowId) ?: current
                _uiState.value = UiState.Success(
                    EscrowData(
                        escrow = escrow,
                        role = determineRole(escrow),
                        fundingTxId = _fundingTxId.value,
                        buyerAddress = buyerAddressFor(escrow),
                        counterpartyLabel = counterpartyLabelFor(escrow, determineRole(escrow)),
                        paymentDetails = paymentDetailsFor(escrow),
                        fiatAmount = fiatAmountFor(escrow),
                        scriptVerdict = escrowService.scriptVerdictFor(escrowId)
                    )
                )
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to dispute: ${e.message}")
            } finally {
                _disputeBusy.value = false
            }
        }
    }

    // ── Request refund (F-1, 2026-09-13) ──

    /**
     * F-1 (2026-09-13): a refund needs the arbitrator's signature, so this either cancels a
     * never-funded escrow locally or opens a dispute for the arbitrator to co-sign.
     */
    fun requestRefund() {
        if (_disputeBusy.value) return
        val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return
        _requestRefundError.value = null
        when (EscrowService.refundRequestKind(
            status = current.status.name,
            requiresOnChainRefund = EscrowService.cancelRequiresOnChainRefund(
                current.status, current.fundingTxId, current.fundedAmountSats
            ),
            canDispute = EscrowService.canDisputeFromStatus(current.status.name)
        )) {
            EscrowService.RefundRequestKind.LOCAL_CANCEL -> viewModelScope.launch(Dispatchers.IO) {
                escrowService.cancelUnfundedEscrow(current.escrowId)
                    .onSuccess { loadEscrow() }
                    .onFailure { _requestRefundError.value = it.message }
            }
            EscrowService.RefundRequestKind.OPEN_DISPUTE -> disputeEscrow(reason = "seller_refund_request")
            EscrowService.RefundRequestKind.WAIT_FOR_CONFIRMATION ->
                _requestRefundError.value = context.getString(R.string.escrow_refund_needs_confirmation)
            EscrowService.RefundRequestKind.REJECT -> Unit
        }
    }
}
