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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
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
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.escrow.EscrowRecoveryPolicy
import com.neop2p.data.escrow.EscrowScriptGate
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.escrow.toWireFields
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toDomain
import com.neop2p.data.network.TorState
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.p2p.routing.PaymentReceiptRejectPayload
import com.neop2p.data.tor.TorHttpPolicy
import com.neop2p.data.tor.TorManager
import com.neop2p.domain.model.*
import com.neop2p.domain.model.BitcoinAddressType
import com.neop2p.ui.components.ConnectionQualityChip
import com.neop2p.ui.theme.NeoMotion
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.util.TestTags
import com.neop2p.ui.util.PeerFingerprint
import com.neop2p.ui.util.ErrorCodes
import com.neop2p.ui.util.MoneyAction
import com.neop2p.ui.util.TorBlockDecision
import com.neop2p.ui.util.TorOverrideDialog
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
    private val torManager: TorManager,
    private val torHttpPolicy: TorHttpPolicy,
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

    // Fail-closed Tor gate for on-chain funding verification (see verifyFunding()).
    private val _pendingTorBlock = MutableStateFlow(false)
    val pendingTorBlock: StateFlow<Boolean> = _pendingTorBlock.asStateFlow()

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
        // Fail closed: Tor enabled but not connected asks for the explicit
        // direct override instead of failing the on-chain verification.
        if (TorBlockDecision.shouldOfferOverride(
                torManager.state.value !is TorState.Disabled,
                torManager.state.value
            )
        ) {
            _pendingTorBlock.value = true
            return
        }
        verifyFundingInternal(direct = false)
    }

    fun clearPendingTorBlock() {
        _pendingTorBlock.value = false
    }

    /** Runs the pending verification under the explicit action-scoped direct override. */
    fun confirmTorOverride() {
        if (!_pendingTorBlock.value) return
        _pendingTorBlock.value = false
        verifyFundingInternal(direct = true)
    }

    private fun verifyFundingInternal(direct: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return@launch
                val txid = _fundingTxId.value.trim()
                if (txid.isBlank()) {
                    _uiState.value = UiState.Error("Please provide a funding transaction id")
                    return@launch
                }
                // R11: the override covers every explorer request the verify
                // makes (provider rotation) and is cleared in runDirect's finally.
                val result = if (direct) {
                    torHttpPolicy.runDirect { escrowService.onEscrowFunded(current.escrowId, txid) }
                } else {
                    escrowService.onEscrowFunded(current.escrowId, txid)
                }
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
        // Fail closed with a clear reason: Tor enabled but not connected blocks
        // every explorer call, so the wallet cannot fetch UTXOs. The one-tap
        // path deliberately offers no direct override (irreversible broadcast),
        // so surface WHY instead of the generic "Could not fetch UTXOs".
        if (TorBlockDecision.shouldOfferOverride(
                torManager.state.value !is TorState.Disabled,
                torManager.state.value
            )
        ) {
            _fundingError.value = context.getString(R.string.escrow_funding_tor_not_ready)
            return
        }
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
                escrowService.markPaid(current.escrowId).fold(
                    onSuccess = { escrow ->
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
                    },
                    onFailure = { e ->
                        // 2026-09-26: the buyer funding hard gate fails closed when
                        // the deposit is unverified / the explorer is unreachable —
                        // surface it so the buyer can retry, never a silent no-op.
                        _uiState.value = UiState.Error(
                            "Failed to mark payment: ${e.message ?: "unknown error"}"
                        )
                    }
                )
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
                // 2026-09-26: the manual path and the automatic sweep escalation
                // share ONE payload builder (EscrowService.buildDisputePending) so
                // neither can drop the refund tx the arbitrator needs.
                val pending = escrowService.buildDisputePending(current.escrowId, myPeerId, reason)
                    ?: run {
                        _uiState.value = UiState.Error("Could not build the dispute payload — escrow missing")
                        return@launch
                    }
                // Phase 4: deliver the dispute to the counterparty AND the
                // arbitrator over LXMF (RNS path). Publish-then-commit: the
                // dispute must be delivered BEFORE the local row flips to
                // DISPUTED, or the arbitrator never sees it.
                val fields = pending.toWireFields(current)
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

    // ── CLTV recovery (C9, 2026-09-23) ──

    private val _recoverBusy = MutableStateFlow(false)
    val recoverBusy: StateFlow<Boolean> = _recoverBusy.asStateFlow()

    /**
     * C9 (Phase 1): the seller's unilateral CLTV recovery after maturity. The
     * service re-checks the policy, the attested destination, and the fee
     * ceiling before broadcasting.
     */
    fun recoverViaCltv() {
        if (_recoverBusy.value) return
        val current = (_uiState.value as? UiState.Success)?.data?.escrow ?: return
        _recoverBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            escrowService.recoverViaCltv(current.escrowId)
                .onSuccess { loadEscrow() }
                .onFailure { _requestRefundError.value = it.message }
            _recoverBusy.value = false
        }
    }
}
