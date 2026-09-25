package com.neop2p.ui.screens.trade

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neop2p.R
import com.neop2p.data.local.dao.EscrowDao
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toDomain
import com.neop2p.domain.model.Escrow
import com.neop2p.domain.model.EscrowRole
import com.neop2p.domain.model.EscrowStatus
import com.neop2p.domain.model.OfferStatus
import com.neop2p.ui.screens.escrow.EscrowStatusChip
import com.neop2p.ui.screens.escrow.EscrowStep
import com.neop2p.ui.screens.escrow.NextActionBar
import com.neop2p.ui.screens.escrow.PayInstructionCard
import com.neop2p.ui.screens.escrow.StepTracker
import com.neop2p.ui.screens.escrow.currentStepFor
import com.neop2p.ui.screens.escrow.stepsForRole
import com.neop2p.ui.components.ReputationBadge
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Trade hub: the single destination a user lands on after accepting an offer
 * (and the natural home for a live trade). Shows the escrow status header
 * (chip + role-adaptive step tracker + next-action bar with countdown), the
 * buyer's pay instruction card inline, a "chat locked until funded" tooltip,
 * and one-tap shortcuts to the full Escrow and Chat screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeRoomScreen(
    offerId: String,
    onBack: () -> Unit,
    onOpenEscrow: (String) -> Unit = {},
    onOpenChat: (String, String) -> Unit = { _, _ -> },
    onOpenReceipt: (String) -> Unit = {},
    viewModel: TradeRoomViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) } // 0 = Escrow, 1 = Chat
    LaunchedEffect(offerId) { viewModel.load(offerId) }

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.trade_room_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(id = R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.general_back))
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                when (val s = state) {
                    is TradeRoomViewModel.State.Loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    is TradeRoomViewModel.State.Error -> Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.message)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { viewModel.load(offerId) }) {
                                Text(stringResource(R.string.general_retry))
                            }
                        }
                    }
                    is TradeRoomViewModel.State.Ready -> {
                        val data = s.data
                        val esc = data.escrow

                        // The buyer hub stacks a tall pay-instruction card above
                        // the Escrow/Chat tabs. Without a scroll container the
                        // tabs and their action buttons are clipped off-screen
                        // on small devices / tall cards. Scroll the whole body.
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Status header: chip + step tracker + next action.
                            if (esc != null) {
                                EscrowStatusChip(
                                    status = esc.status,
                                    fundingTxId = esc.fundingTxId.orEmpty(),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                val roleSteps = stepsForRole(data.role.name)
                                StepTracker(
                                    currentStep = currentStepFor(esc.status, roleSteps),
                                    steps = roleSteps,
                                    labels = mapOf(
                                        EscrowStep.FUND to stringResource(R.string.escrow_step_fund),
                                        EscrowStep.PAY to stringResource(R.string.escrow_step_pay),
                                        EscrowStep.CONFIRM to stringResource(R.string.escrow_step_confirm),
                                        EscrowStep.RELEASE to stringResource(R.string.escrow_step_release)
                                    )
                                )
                                NextActionBar(
                                    escrow = esc,
                                    isRole = data.role,
                                    fiatAmount = data.fiatAmount,
                                    fundingTxId = esc.fundingTxId.orEmpty()
                                )
                            }

                            // Buyer: pay instruction card inline (exact IDR + unique code).
                            if (esc != null && data.role == EscrowRole.BUYER &&
                                esc.status in setOf(
                                    EscrowStatus.FUNDED, EscrowStatus.SIGNED,
                                    EscrowStatus.PAYMENT_PENDING, EscrowStatus.RECEIPT_SENT,
                                    EscrowStatus.CONFIRMING
                                )
                            ) {
                                PayInstructionCard(
                                    fiatAmount = data.fiatAmount,
                                    escrowId = esc.escrowId,
                                    methods = data.paymentDetails.keys,
                                    paymentDetails = data.paymentDetails,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }

                            // Chat-locked tooltip: explains why the chat is unavailable.
                            if (esc == null || esc.status == EscrowStatus.FUNDING) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Lock, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(R.string.chat_locked_until_funded),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }

                            TabRow(selectedTabIndex = tab) {
                                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.trade_room_tab_escrow)) })
                                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.trade_room_tab_chat)) })
                            }
                            when (tab) {
                                0 -> EscrowTabContent(
                                    data = data,
                                    creating = creating,
                                    onCreateEscrow = { viewModel.createEscrow(data.offer) },
                                    onOpenEscrow = onOpenEscrow,
                                    onOpenReceipt = onOpenReceipt
                                )
                                else -> ChatTabContent(data, offerId, onOpenChat)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EscrowTabContent(
    data: TradeRoomData,
    creating: Boolean,
    onCreateEscrow: () -> Unit,
    onOpenEscrow: (String) -> Unit,
    onOpenReceipt: (String) -> Unit
) {
    val esc = data.escrow
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (esc == null) {
            if (data.isCreator && data.offer.status == OfferStatus.MATCHED) {
                // Seller: the buyer accepted but no escrow exists yet — show the
                // match and let the seller create the escrow from here instead of
                // bouncing to the offer-detail screen.
                Text(
                    stringResource(R.string.trade_room_match_accepted),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.home_fiat_amount, data.fiatAmount),
                    style = MaterialTheme.typography.headlineSmall
                )
                if (data.peerId.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.trade_room_peer_id, data.peerId.take(12)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ReputationBadge(reputation = data.counterpartyReputation)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onCreateEscrow,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(stringResource(R.string.trade_room_create_escrow))
                }
            } else {
                Text(stringResource(R.string.trade_room_no_escrow), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.trade_room_no_escrow_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Button(onClick = { onOpenEscrow(esc.escrowId) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.trade_room_open_escrow))
            }
            if (data.role == EscrowRole.BUYER &&
                esc.status in setOf(EscrowStatus.PAYMENT_PENDING, EscrowStatus.RECEIPT_SENT)
            ) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onOpenReceipt(esc.escrowId) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.escrow_open_receipt))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.trade_room_escrow_id, esc.escrowId.take(8)),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ChatTabContent(
    data: TradeRoomData,
    offerId: String,
    onOpenChat: (String, String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (data.peerId.isBlank()) {
            Text(stringResource(R.string.trade_room_no_peer), style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = { onOpenChat(offerId, data.peerId) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.chat_with_peer))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.trade_room_peer_id, data.peerId.take(12)),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))
            ReputationBadge(reputation = data.counterpartyReputation)
        }
    }
}

@HiltViewModel
class TradeRoomViewModel @Inject constructor(
    private val offerDao: OfferDao,
    private val escrowDao: EscrowDao,
    private val escrowService: com.neop2p.data.escrow.EscrowService,
    private val identityManager: com.neop2p.data.p2p.IdentityManager,
    private val reputationSystem: com.neop2p.data.reputation.ReputationSystem
) : ViewModel() {
    sealed class State {
        object Loading : State()
        data class Error(val message: String) : State()
        data class Ready(val data: TradeRoomData) : State()
    }
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    /**
     * Seller CTA: create the escrow for a MATCHED SELL offer. Delegates to the
     * shared EscrowService path (same as the offer-detail CTA), then notifies the
     * buyer. On failure the hub flips to Error (with a retry) instead of silently
     * doing nothing.
     */
    fun createEscrow(offer: com.neop2p.domain.model.TradeOffer) {
        if (_creating.value) return
        _creating.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = escrowService.createSellerEscrow(offer)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                _creating.value = false
                if (result.isFailure) {
                    _state.value = State.Error(result.exceptionOrNull()?.message ?: "Create escrow failed")
                }
            }
        }
    }

    fun load(offerId: String) {
        _state.value = State.Loading
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val offer = offerDao.getOfferSync(offerId)
                if (offer == null) {
                    _state.value = State.Error("Offer not found")
                    return@launch
                }
                val myId = runCatching { identityManager.getOrCreateIdentity().peerId }.getOrDefault("")
                fun ready(offer: com.neop2p.domain.model.TradeOffer, escrow: com.neop2p.domain.model.Escrow?) {
                    val data = resolveTradeRoom(offer, escrow, myId)
                    val rep = if (data.peerId.isNotBlank()) {
                        runCatching { reputationSystem.getReputation(data.peerId) }.getOrNull()
                    } else null
                    _state.value = State.Ready(data.copy(counterpartyReputation = rep))
                }
                val domain = offer.toDomain()
                ready(domain, escrowDao.getEscrowByOfferId(offerId)?.toDomain())
                // Live observers run OUTSIDE the try/catch: a Room flow that
                // throws (e.g. closed DB) must not flip the hub to Error
                // permanently — the initial load already succeeded.
                runCatching {
                    escrowDao.observeEscrowByOfferId(offerId).collect { entity ->
                        val current = (_state.value as? State.Ready)?.data ?: return@collect
                        ready(current.offer, entity?.toDomain())
                    }
                }
                // Live: the OFFER row too — the seller's bank details arrive via
                // E2EE chat AFTER the hub loaded (auto-share at FUNDED), and
                // ChatRouter persists them into the offer row. Without this
                // observer the buyer's pay card would stay empty of rails until
                // they leave and re-enter the hub.
                runCatching {
                    offerDao.getOffer(offerId).collect { entity ->
                        val current = (_state.value as? State.Ready)?.data ?: return@collect
                        if (entity == null) return@collect
                        ready(entity.toDomain(), current.escrow)
                    }
                }
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Load failed")
            }
        }
    }
}
