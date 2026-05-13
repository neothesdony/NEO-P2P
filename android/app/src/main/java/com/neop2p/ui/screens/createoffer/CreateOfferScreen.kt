package com.neop2p.ui.screens.createoffer

import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.p2p.NostrClient
import com.neop2p.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateOfferScreen(
    onOfferCreated: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: CreateOfferViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Create Offer") },
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Offer Type
                    Text("Offer Type", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.offerType == OfferType.BUY,
                            onClick = { viewModel.updateOfferType(OfferType.BUY) },
                            label = { Text("Buy BTC") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_trending_down),
                                    contentDescription = "Buy"
                                )
                            }
                        )
                        FilterChip(
                            selected = state.offerType == OfferType.SELL,
                            onClick = { viewModel.updateOfferType(OfferType.SELL) },
                            label = { Text("Sell BTC") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_trending_up),
                                    contentDescription = "Sell"
                                )
                            }
                        )
                    }

                    // Amount (BTC)
                    Text("Amount (BTC)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.btcAmount,
                        onValueChange = { viewModel.updateBtcAmount(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("0.01") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )

                    // Price per BTC (IDR)
                    Text("Price per BTC (IDR)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.pricePerBtc,
                        onValueChange = { viewModel.updatePrice(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("1,500,000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )

                    // Total fiat amount
                    Text(
                        text = "Total: Rp ${state.totalFiat}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )

                    // Fee breakdown
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Fee Breakdown", style = MaterialTheme.typography.labelMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Trade amount")
                                Text("${state.btcAmount.toDoubleOrNull()?.times(100_000_000)?.toLong() ?: 0} sats")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("NEO-P2P fee (1%)")
                                Text("${state.computedFeeSats} sats")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Total deposit")
                                Text("${state.computedTotalSats} sats")
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Fee wallet: ${NeoP2PConfig.FEE_WALLET_ADDRESS.take(12)}...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    // Fiat Methods
                    Text("Payment Methods", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(
                        modifier = Modifier.height(200.dp)
                    ) {
                        items(NeoP2PConfig.FIAT_METHODS) { method ->
                            val isSelected = method.id in state.selectedMethods
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleMethod(method.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { viewModel.toggleMethod(method.id) }
                                )
                                Text(
                                    text = method.displayNameId,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Submit
                    Button(
                        onClick = { viewModel.createOffer(onOfferCreated) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = state.canSubmit
                    ) {
                        Text(if (state.offerType == OfferType.BUY) "Create Buy Offer" else "Create Sell Offer")
                    }
                }
            }
        )
    }
}

@HiltViewModel
class CreateOfferViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val nostrClient: NostrClient,
    private val escrowService: EscrowService
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfferFormState())
    val uiState: StateFlow<OfferFormState> = _uiState.asStateFlow()

    data class OfferFormState(
        val offerType: OfferType = OfferType.BUY,
        val btcAmount: String = "",
        val pricePerBtc: String = "",
        val selectedMethods: Set<String> = emptySet(),
        val isSubmitting: Boolean = false
    ) {
        val totalFiat: String
            get() {
                val btc = btcAmount.toDoubleOrNull() ?: 0.0
                val price = pricePerBtc.toDoubleOrNull() ?: 0.0
                return "Rp ${String.format("%,.0f", btc * price)}"
            }

        val computedFeeSats: Long
            get() {
                val sats = (btcAmount.toDoubleOrNull() ?: 0.0) * 100_000_000
                return (sats * NeoP2PConfig.FEE_PERCENT).toLong()
            }

        val computedTotalSats: Long
            get() {
                val sats = (btcAmount.toDoubleOrNull() ?: 0.0) * 100_000_000
                return sats.toLong() + computedFeeSats
            }

        val canSubmit: Boolean
            get() = btcAmount.toDoubleOrNull() != null &&
                    pricePerBtc.toDoubleOrNull() != null &&
                    selectedMethods.isNotEmpty()
    }

    fun updateOfferType(type: OfferType) {
        _uiState.update { it.copy(offerType = type) }
    }

    fun updateBtcAmount(amount: String) {
        _uiState.update { it.copy(btcAmount = amount) }
    }

    fun updatePrice(price: String) {
        _uiState.update { it.copy(pricePerBtc = price) }
    }

    fun toggleMethod(methodId: String) {
        _uiState.update { state ->
            val updated = if (methodId in state.selectedMethods)
                state.selectedMethods - methodId
            else
                state.selectedMethods + methodId
            state.copy(selectedMethods = updated)
        }
    }

    fun createOffer(onCreated: (String) -> Unit) {
        val state = _uiState.value
        if (!state.canSubmit) return

        _uiState.update { it.copy(isSubmitting = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val identity = identityManager.getOrCreateIdentity()
                val btcSats = (state.btcAmount.toDouble() * 100_000_000).toLong()
                val fiatAmount = (btcSats.toDouble() / 100_000_000.0) * state.pricePerBtc.toDouble()

                val offer = TradeOffer(
                    offerId = "offer_${System.currentTimeMillis()}",
                    creatorPeerId = identity.peerId,
                    type = state.offerType,
                    cryptoAmountSats = btcSats,
                    fiatAmount = fiatAmount.toLong(),
                    pricePerUnit = state.pricePerBtc.toDouble(),
                    feeSats = (btcSats * NeoP2PConfig.FEE_PERCENT).toLong(),
                    fiatMethods = state.selectedMethods.toList(),
                    status = OfferStatus.OPEN
                )

                // Publish to Nostr
                val nostrPubkey = identity.nostrPubkeyHex
                val result = nostrClient.publishTradeOffer(
                    privateKeyHex = "placeholder_key",
                    pubkeyHex = nostrPubkey,
                    offerJson = kotlinx.serialization.json.Json.encodeToJsonElement(offer).jsonObject
                )

                _uiState.update { it.copy(isSubmitting = false) }
                onCreated(offer.offerId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isSubmitting = false) }
                // TODO: Show error
            }
        }
    }
}
