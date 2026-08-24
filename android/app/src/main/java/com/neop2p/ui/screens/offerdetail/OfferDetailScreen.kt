package com.neop2p.ui.screens.offerdetail

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.R
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.local.*
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.dao.PeerDao
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.p2p.NostrClient
import com.neop2p.data.reputation.ReputationSystem
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.theme.buyColor
import com.neop2p.ui.theme.sellColor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferDetailScreen(
    offerId: String,
    onBack: () -> Unit,
    onChatClick: (String, String) -> Unit,
    onEscrowCreated: (String) -> Unit,
    onEdit: () -> Unit = {}
) {
    val viewModel: OfferDetailViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAcceptDialog by remember { mutableStateOf(false) }

    // Load the offer once on first composition (prevents infinite loading spinner).
    LaunchedEffect(offerId) {
        viewModel.loadOffer(offerId)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.offer_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.general_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                is OfferDetailViewModel.UiState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                is OfferDetailViewModel.UiState.Error -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(s.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadOffer(offerId) }) {
                            Text(stringResource(R.string.general_retry))
                        }
                    }
                }
                is OfferDetailViewModel.UiState.Success -> OfferDetailContent(
                    offer = s.data.offer,
                    peer = s.data.peer,
                    reputation = s.data.reputation,
                    isOwnOffer = s.data.isOwnOffer,
                    onAccept = { showAcceptDialog = true },
                    onChatClick = {
                        // Chat target depends on who's viewing:
                        //  - the creator (isOwnOffer=true) talks to the acceptor (matchedPeerId)
                        //  - the acceptor (isOwnOffer=false) talks to the creator (creatorPeerId)
                        val target = if (s.data.isOwnOffer) {
                            s.data.offer.matchedPeerId?.takeIf { it.isNotBlank() }
                                ?: s.data.offer.creatorPeerId
                        } else {
                            s.data.offer.creatorPeerId
                        }
                        onChatClick(offerId, target)
                    },
                    // A seller whose SELL offer was accepted (MATCHED but not yet
                    // escrowed) can create the escrow so they can deposit BTC.
                    onCreateEscrow = { offer ->
                        viewModel.createSellerEscrow(offer) { escrowId ->
                            if (escrowId != null) onEscrowCreated(escrowId)
                        }
                    },
                    onDelete = { viewModel.deleteOffer(s.data.offer) },
                    onEdit = onEdit
                )
            }
        }
    }

    // Confirm the offer acceptance, which locks it (status=MATCHED) and opens chat.
    if (showAcceptDialog) {
        val offer = (state as? OfferDetailViewModel.UiState.Success)?.data?.offer
        AlertDialog(
            onDismissRequest = { showAcceptDialog = false },
            title = { Text(stringResource(R.string.offer_accept_confirm_title)) },
            text = { Text(stringResource(R.string.offer_accept_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showAcceptDialog = false
                        offer?.let {
                            viewModel.acceptOffer(
                                offer = it,
                                // If the accepting user is the SELLER (accepting a BUY
                                // offer), create the escrow first so they can deposit BTC.
                                onAccepted = { escrowId ->
                                    if (escrowId != null) {
                                        onEscrowCreated(escrowId)
                                    } else {
                                        onChatClick(it.offerId, it.creatorPeerId)
                                    }
                                }
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.offer_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAcceptDialog = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }
}

@Composable
private fun OfferDetailContent(
    offer: TradeOffer,
    peer: Peer?,
    reputation: ReputationScore?,
    isOwnOffer: Boolean,
    onAccept: () -> Unit,
    onChatClick: () -> Unit,
    onCreateEscrow: (TradeOffer) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val isBuy = offer.type == OfferType.BUY
    val isLocked = offer.status != OfferStatus.OPEN

    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (isBuy) stringResource(R.string.offer_buying) else stringResource(R.string.offer_selling),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isBuy) MaterialTheme.colorScheme.buyColor else MaterialTheme.colorScheme.sellColor
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = offer.asset.ticker,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    DetailRow(stringResource(R.string.offer_amount), stringResource(R.string.offer_detail_btc_amount, (offer.cryptoAmountSats / 100_000_000.0).toString()))
                    DetailRow(stringResource(R.string.offer_price), stringResource(R.string.offer_detail_price_btc, String.format("%,.0f", offer.pricePerUnit)))
                    DetailRow(stringResource(R.string.offer_total_fiat), stringResource(R.string.offer_fiat_format, String.format("%,.0f", offer.fiatAmount.toDouble())))
                    DetailRow(stringResource(R.string.offer_fee_1), stringResource(R.string.common_sats, offer.feeSats))
                    DetailRow(stringResource(R.string.offer_total_deposit_label), stringResource(R.string.common_sats, offer.totalDepositSats))
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.offer_payment_methods), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            offer.fiatMethods.forEach { method ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.AccountBalance, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(method.uppercase(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.offer_trader), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = peer?.nickname?.ifBlank { stringResource(R.string.general_anonymous) } ?: stringResource(R.string.general_anonymous),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        reputation?.let { rep ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${(rep.score * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        rep.score >= 0.9f -> MaterialTheme.colorScheme.primary
                                        rep.score >= 0.7f -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                )
                                Text(
                                    stringResource(R.string.trades_suffix_format, rep.totalTrades),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                // Text label alongside the color so the score isn't
                                // communicated by color alone (WCAG 1.4.1).
                                Text(
                                    text = stringResource(
                                        when {
                                            rep.score >= 0.9f -> R.string.reputation_excellent
                                            rep.score >= 0.7f -> R.string.reputation_fair
                                            else -> R.string.reputation_poor
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            when {
                isOwnOffer -> {
                    // You cannot trade with your own offer — edit it or delete it.
                    // But once someone accepts it (status != OPEN) the trade is
                    // live: surface the chat entry so you can talk to the buyer.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onEdit,
                            Modifier.weight(1f).height(56.dp)
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.offer_edit))
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.offer_delete_own))
                        }
                    }
                    if (isLocked) {
                        // Seller's own SELL offer that a buyer accepted but no
                        // escrow exists yet → surface the funding gate so the
                        // seller can create & deposit into the multisig.
                        val isSellerPendingEscrow = offer.type == OfferType.SELL &&
                            offer.status == OfferStatus.MATCHED
                        if (isSellerPendingEscrow) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { onCreateEscrow(offer) },
                                Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.offer_create_escrow))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onChatClick,
                            Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_with_peer))
                        }
                    }
                }
                isLocked -> {
                    // Already accepted by someone — the trade is ongoing, so
                    // surface the chat entry instead of a dead-end lock icon.
                    Button(
                        onClick = {},
                        Modifier.fillMaxWidth().height(56.dp),
                        enabled = false
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.offer_locked))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onChatClick,
                        Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_with_peer))
                    }
                }
                else -> {
                    // Open offer from another peer — accept it to lock and trade.
                    Button(
                        onClick = onAccept,
                        Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text(stringResource(R.string.offer_accept))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

data class ReputationScore(
    val score: Float,
    val totalTrades: Int
)

data class DetailData(
    val offer: TradeOffer,
    val peer: Peer?,
    val reputation: ReputationScore?,
    val isOwnOffer: Boolean
)

@HiltViewModel
class OfferDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reputationSystem: ReputationSystem,
    private val offerDao: OfferDao,
    private val peerDao: PeerDao,
    private val identityManager: IdentityManager,
    private val nostrClient: NostrClient,
    private val escrowService: EscrowService,
    private val deletedOfferStore: DeletedOfferStore
) : androidx.lifecycle.ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val data: DetailData) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun loadOffer(offerId: String) {
        if (offerId.isBlank()) {
            _uiState.value = UiState.Error(context.getString(R.string.offer_no_selected))
            return
        }
        _uiState.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Load the REAL offer from the local (SQLCipher) DB.
                val offerEntity = offerDao.getOffer(offerId).firstOrNull()
                val offer = offerEntity?.toDomain()
                if (offer == null) {
                    _uiState.value = UiState.Error(context.getString(R.string.offer_not_found))
                    return@launch
                }

                // Load the REAL peer who created the offer.
                val peerEntity = peerDao.getPeer(offer.creatorPeerId).firstOrNull()
                val peer = peerEntity?.toDomain()

                // Derive the real reputation from the local reputation store.
                val rep = reputationSystem.getReputation(offer.creatorPeerId)
                val score = ReputationScore(
                    score = rep.score,
                    totalTrades = rep.totalTrades
                )

                // Determine whether this offer was created by the current user.
                // The identity may be locked behind device auth (no recent unlock),
                // which must NOT fail the whole detail load — just default to
                // treating the offer as not-owned so the screen still renders.
                val isOwnOffer = try {
                    offer.creatorPeerId == identityManager.getOrCreateIdentity().peerId
                } catch (e: Exception) {
                    false
                }

                _uiState.value = UiState.Success(DetailData(offer, peer, score, isOwnOffer))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(context.getString(R.string.offer_load_failed))
            }
        }
    }

    /** Delete an offer the current user created. Removes it locally and
     *  publishes a NIP-09 deletion event so it is removed on other devices. */
    fun deleteOffer(offer: TradeOffer) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                offerDao.delete(offer.toEntity())

                // Tombstone the deletion so the relay replay of the original
                // offer event can't resurrect it on the next open/update.
                deletedOfferStore.markDeleted(offer.offerId, offer.nostrEventId)

                // Propagate the deletion to the relay so other peers drop this offer too.
                val eventId = offer.nostrEventId
                if (!eventId.isNullOrBlank()) {
                    nostrClient.publishOfferDeletion(eventId)
                }

                _uiState.value = UiState.Error(context.getString(R.string.offer_deleted))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(context.getString(R.string.offer_delete_failed))
            }
        }
    }

    /**
     * Accept a peer's offer: lock it (status=MATCHED) locally and broadcast the
     * status so other devices mark it locked too.
     *
     * Escrow gate: the escrow is ALWAYS funded by the SELLER (the BTC
     * depositor), regardless of who created the offer.
     *
     *  - Acceptor of a BUY offer  → the acceptor IS the seller → create the
     *    escrow right here so they can deposit BTC.
     *  - Acceptor of a SELL offer → the acceptor IS the buyer → just lock the
     *    offer; the SELLER (offer creator) creates & funds the escrow via
     *    [createSellerEscrow] once they see the offer is MATCHED.
     *
     * onAccepted(escrowId) returns the escrow id only when the accepting user
     * is the seller and an escrow was just created (so the UI can navigate to
     * the funding screen); otherwise null (proceed to chat).
     */
    fun acceptOffer(offer: TradeOffer, onAccepted: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                offerDao.updateStatus(offer.offerId, OfferStatus.MATCHED.name)
                val myIdentity = identityManager.getOrCreateIdentity()
                // Broadcast WHO matched so the offer creator can route chat to us.
                nostrClient.publishOfferStatus(offer.offerId, OfferStatus.MATCHED.name, myIdentity.peerId)

                // The escrow is created by the SELLER. For a BUY offer the
                // accepter is the seller, so they create it here. For a SELL
                // offer the accepter is the buyer, so the escrow is created
                // later by the offer creator (seller) via createSellerEscrow.
                val iAmSeller = offer.type == OfferType.BUY

                var escrowId: String? = null
                if (iAmSeller) {
                    val buyerPeerId = offer.creatorPeerId
                    val sellerPeerId = myIdentity.peerId
                    val myPubKey = identityManager.getBitcoinPubKeyHex()
                    val result = escrowService.createEscrow(
                        offer = offer,
                        buyerPeerId = buyerPeerId,
                        sellerPeerId = sellerPeerId,
                        buyerPubKeyHex = myPubKey,
                        sellerPubKeyHex = myPubKey
                    )
                    escrowId = result.getOrNull()?.escrowId
                    if (escrowId != null) {
                        offerDao.updateStatus(offer.offerId, OfferStatus.ESCROWED.name)
                        nostrClient.publishOfferStatus(offer.offerId, OfferStatus.ESCROWED.name)
                    }
                }

                val target = escrowId
                withContext(Dispatchers.Main) { onAccepted(target) }
            } catch (e: Exception) {
                Log.e("OfferDetail", "Accept failed: ${e.message}")
                withContext(Dispatchers.Main) { onAccepted(null) }
            }
        }
    }

    /**
     * Seller-side escrow creation for a SELL offer that a buyer just accepted
     * (status == MATCHED). The seller is the BTC depositor, so they must
     * create the 2-of-3 multisig escrow and fund it before the trade proceeds.
     * Sets the offer to ESCROWED and returns the escrowId (or null on failure).
     */
    fun createSellerEscrow(offer: TradeOffer, onCreated: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (offer.type != OfferType.SELL) {
                    withContext(Dispatchers.Main) { onCreated(null) }
                    return@launch
                }
                val myIdentity = identityManager.getOrCreateIdentity()
                val myPubKey = identityManager.getBitcoinPubKeyHex()
                // For a SELL offer the creator is the SELLER (BTC depositor);
                // the acceptor (buyer) is recorded as the matched peer.
                val buyerPeerId = offer.matchedPeerId?.takeIf { it.isNotBlank() }
                if (buyerPeerId == null) {
                    withContext(Dispatchers.Main) { onCreated(null) }
                    return@launch
                }
                val sellerPeerId = myIdentity.peerId
                val result = escrowService.createEscrow(
                    offer = offer,
                    buyerPeerId = buyerPeerId,
                    sellerPeerId = sellerPeerId,
                    buyerPubKeyHex = myPubKey,
                    sellerPubKeyHex = myPubKey
                )
                val escrowId = result.getOrNull()?.escrowId
                if (escrowId != null) {
                    offerDao.updateStatus(offer.offerId, OfferStatus.ESCROWED.name)
                    nostrClient.publishOfferStatus(offer.offerId, OfferStatus.ESCROWED.name)
                }
                withContext(Dispatchers.Main) { onCreated(escrowId) }
            } catch (e: Exception) {
                Log.e("OfferDetail", "Seller escrow creation failed: ${e.message}")
                withContext(Dispatchers.Main) { onCreated(null) }
            }
        }
    }
}
