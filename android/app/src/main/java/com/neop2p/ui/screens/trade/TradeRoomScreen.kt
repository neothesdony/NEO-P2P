package com.neop2p.ui.screens.trade

import androidx.compose.foundation.layout.*
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
import com.neop2p.ui.screens.escrow.EscrowStatusChip
import com.neop2p.ui.screens.escrow.EscrowStep
import com.neop2p.ui.screens.escrow.NextActionBar
import com.neop2p.ui.screens.escrow.PayInstructionCard
import com.neop2p.ui.screens.escrow.StepTracker
import com.neop2p.ui.screens.escrow.currentStepFor
import com.neop2p.ui.screens.escrow.stepsForRole
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
                            0 -> EscrowTabContent(data, onOpenEscrow, onOpenReceipt)
                            else -> ChatTabContent(data, offerId, onOpenChat)
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
    onOpenEscrow: (String) -> Unit,
    onOpenReceipt: (String) -> Unit
) {
    val esc = data.escrow
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (esc == null) {
            Text(stringResource(R.string.trade_room_no_escrow), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.trade_room_no_escrow_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
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
        }
    }
}

@HiltViewModel
class TradeRoomViewModel @Inject constructor(
    private val offerDao: OfferDao,
    private val escrowDao: EscrowDao,
    private val identityManager: com.neop2p.data.p2p.IdentityManager
) : ViewModel() {
    sealed class State {
        object Loading : State()
        data class Error(val message: String) : State()
        data class Ready(val data: TradeRoomData) : State()
    }
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

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
                val domain = offer.toDomain()
                _state.value = State.Ready(
                    resolveTradeRoom(domain, escrowDao.getEscrowByOfferId(offerId)?.toDomain(), myId)
                )
                // Live observers run OUTSIDE the try/catch: a Room flow that
                // throws (e.g. closed DB) must not flip the hub to Error
                // permanently — the initial load already succeeded.
                runCatching {
                    escrowDao.observeEscrowByOfferId(offerId).collect { entity ->
                        val current = (_state.value as? State.Ready)?.data ?: return@collect
                        _state.value = State.Ready(resolveTradeRoom(current.offer, entity?.toDomain(), myId))
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
                        val fresh = entity.toDomain()
                        _state.value = State.Ready(
                            resolveTradeRoom(fresh, current.escrow, myId)
                        )
                    }
                }
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Load failed")
            }
        }
    }
}
