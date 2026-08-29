package com.neop2p.ui.screens.trade

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeRoomScreen(
    offerId: String,
    onBack: () -> Unit,
    onOpenEscrow: (String) -> Unit = {},
    onOpenChat: (String, String) -> Unit = { _, _ -> },
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
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.trade_room_tab_escrow)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.trade_room_tab_chat)) })
                }
                when (val s = state) {
                    is TradeRoomViewModel.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                    is TradeRoomViewModel.State.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text(s.message)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { viewModel.load(offerId) }) { Text(stringResource(R.string.general_retry)) }
                        }
                    }
                    is TradeRoomViewModel.State.Ready -> {
                        if (tab == 0) {
                            TradeEscrowTab(escrowId = s.escrowId, offerId = offerId, onOpenEscrow = onOpenEscrow)
                        } else {
                            TradeChatTab(offerId = offerId, peerId = s.peerId, onOpenChat = onOpenChat)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TradeEscrowTab(escrowId: String?, offerId: String, onOpenEscrow: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        if (escrowId == null) {
            Text(stringResource(R.string.trade_room_no_escrow), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.trade_room_no_escrow_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Button(onClick = { onOpenEscrow(escrowId) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.trade_room_open_escrow))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.trade_room_escrow_id, escrowId.take(8)), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TradeChatTab(offerId: String, peerId: String, onOpenChat: (String, String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        if (peerId.isBlank()) {
            Text(stringResource(R.string.trade_room_no_peer), style = MaterialTheme.typography.bodyMedium)
        } else {
            Button(onClick = { onOpenChat(offerId, peerId) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.chat_with_peer))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.trade_room_peer_id, peerId.take(12)), style = MaterialTheme.typography.labelSmall)
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
        data class Ready(val escrowId: String?, val peerId: String) : State()
    }
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    fun load(offerId: String) {
        _state.value = State.Loading
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val offer = offerDao.getOfferSync(offerId)
                if (offer == null) {
                    _state.value = State.Error("Offer not found")
                    return@launch
                }
                val escrow = escrowDao.getEscrowByOfferId(offerId)
                val myId = runCatching { identityManager.getOrCreateIdentity().peerId }.getOrDefault("")
                val peerId = when {
                    offer.creator_peer_id == myId -> offer.matched_peer_id ?: ""
                    else -> offer.creator_peer_id
                }
                _state.value = State.Ready(escrow?.escrow_id, peerId)
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Load failed")
            }
        }
    }
}
