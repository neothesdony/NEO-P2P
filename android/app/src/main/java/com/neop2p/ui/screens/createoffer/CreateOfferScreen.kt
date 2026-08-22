package com.neop2p.ui.screens.createoffer

import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.p2p.NostrClient
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
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
                    title = { Text(stringResource(R.string.home_create_offer)) },
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Offer Type
                    Text(stringResource(R.string.offer_type_label), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.offerType == OfferType.BUY,
                            onClick = { viewModel.updateOfferType(OfferType.BUY) },
                            label = { Text(stringResource(R.string.offer_buy_btc)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_trending_down),
                                    contentDescription = stringResource(R.string.trade_buy)
                                )
                            }
                        )
                        FilterChip(
                            selected = state.offerType == OfferType.SELL,
                            onClick = { viewModel.updateOfferType(OfferType.SELL) },
                            label = { Text(stringResource(R.string.offer_sell_btc)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_trending_up),
                                    contentDescription = stringResource(R.string.trade_sell)
                                )
                            }
                        )
                    }

                    // Amount (BTC)
                    Text(stringResource(R.string.offer_amount_label), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.btcAmount,
                        onValueChange = { viewModel.updateBtcAmount(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.offer_amount_placeholder)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )

                    // Price per BTC (IDR)
                    Text(stringResource(R.string.offer_price_label), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.pricePerBtc,
                        onValueChange = { viewModel.updatePrice(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.offer_price_placeholder)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )

                    // Total fiat amount
                    Text(
                        text = stringResource(R.string.offer_total_format, state.totalFiat),
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
                            Text(stringResource(R.string.offer_fee_breakdown), style = MaterialTheme.typography.labelMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.offer_trade_amount))
                                Text(stringResource(R.string.common_sats, state.btcAmount.toDoubleOrNull()?.times(100_000_000)?.toLong() ?: 0))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.offer_fee))
                                Text(stringResource(R.string.common_sats, state.computedFeeSats))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.offer_total_deposit))
                                Text(stringResource(R.string.common_sats, state.computedTotalSats))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.offer_fee_wallet_format, NeoP2PConfig.FEE_WALLET_ADDRESS.take(12)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    // Fiat Methods
                    Text(stringResource(R.string.offer_payment_methods), style = MaterialTheme.typography.titleMedium)
                    LazyColumn(
                        modifier = Modifier.height(320.dp)
                    ) {
                        items(NeoP2PConfig.FIAT_METHODS) { method ->
                            val isSelected = method.id in state.selectedMethods
                            val details = state.methodDetails[method.id]
                            Column(modifier = Modifier.fillMaxWidth()) {
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

                                // When selected, collect the recipient's payment details
                                if (isSelected) {
                                    val isCash = method.id == "cash"
                                    OutlinedTextField(
                                        value = details?.accountNumber.orEmpty(),
                                        onValueChange = { viewModel.updateMethodAccountNumber(method.id, it) },
                                        label = { Text(if (isCash) stringResource(R.string.offer_cash_contact) else "${method.displayNameId} Account Number") },
                                        placeholder = { Text(if (isCash) stringResource(R.string.offer_cash_placeholder) else stringResource(R.string.offer_bank_placeholder)) },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = if (isCash) KeyboardType.Text else KeyboardType.Number
                                        )
                                    )
                                    OutlinedTextField(
                                        value = details?.accountHolder.orEmpty(),
                                        onValueChange = { viewModel.updateMethodAccountHolder(method.id, it) },
                                        label = { Text(if (isCash) stringResource(R.string.offer_name_label) else stringResource(R.string.offer_holder_label)) },
                                        placeholder = { Text(stringResource(R.string.offer_account_name_placeholder)) },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        singleLine = true
                                    )
                                }
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
                        Text(if (state.offerType == OfferType.BUY) stringResource(R.string.offer_create_buy) else stringResource(R.string.offer_create_sell))
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

    init {
        // Pre-fill "Price per BTC" with the current market reference price.
        _uiState.update { it.copy(pricePerBtc = NeoP2PConfig.DEFAULT_BTC_MARKET_PRICE_IDR.toString()) }
    }

    data class OfferFormState(
        val offerType: OfferType = OfferType.BUY,
        val btcAmount: String = "",
        val pricePerBtc: String = "",
        val selectedMethods: Set<String> = emptySet(),
        // Per-method payment details (account number, holder name, etc.) keyed by method id
        val methodDetails: Map<String, MethodDetails> = emptyMap(),
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
                    selectedMethods.isNotEmpty() &&
                    // Every selected method must have complete payment details
                    selectedMethods.all { methodId ->
                        val d = methodDetails[methodId]
                        d != null && d.isComplete
                    }
    }

    /** Payment details required for a fiat method (e.g. bank account). */
    data class MethodDetails(
        val accountNumber: String = "",
        val accountHolder: String = ""
    ) {
        val isComplete: Boolean
            get() = accountNumber.isNotBlank() && accountHolder.isNotBlank()
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
            // Initialize (or drop) the details entry when selection changes.
            val updatedDetails = if (methodId in updated) {
                state.methodDetails + (methodId to (state.methodDetails[methodId] ?: MethodDetails()))
            } else {
                state.methodDetails - methodId
            }
            state.copy(selectedMethods = updated, methodDetails = updatedDetails)
        }
    }

    fun updateMethodAccountNumber(methodId: String, value: String) {
        _uiState.update { state ->
            val current = state.methodDetails[methodId] ?: MethodDetails()
            state.copy(methodDetails = state.methodDetails + (methodId to current.copy(accountNumber = value)))
        }
    }

    fun updateMethodAccountHolder(methodId: String, value: String) {
        _uiState.update { state ->
            val current = state.methodDetails[methodId] ?: MethodDetails()
            state.copy(methodDetails = state.methodDetails + (methodId to current.copy(accountHolder = value)))
        }
    }

    fun createOffer(onCreated: (String) -> Unit) {
        val state = _uiState.value
        if (!state.canSubmit) return

        _uiState.update { it.copy(isSubmitting = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val identity = identityManager.getOrCreateIdentity()
                // P0-3: sign the offer with a fresh per-trade key so offers and
                // trade messages cannot be linked to the identity key.
                val tradeKey = identityManager.getNextTradeNostrKeyPair()
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
                val nostrPubkey = tradeKey.publicKeyHex
                val offerJson = buildJsonObject {
                    put("offer_id", offer.offerId)
                    put("creator_peer_id", offer.creatorPeerId)
                    put("type", offer.type.name)
                    put("fiat_amount", offer.fiatAmount)
                    put("crypto_amount_sats", offer.cryptoAmountSats)
                    put("price_per_unit", offer.pricePerUnit)
                    put("fee_percent", offer.feePercent)
                    putJsonArray("fiat_methods") {
                        offer.fiatMethods.forEach { add(it) }
                    }
                    // SECURITY: payment account details (bank number, holder name)
                    // are deliberately NOT published here. Nostr relays are public
                    // and immutable — account numbers must only be exchanged AFTER
                    // a taker commits, inside an encrypted channel (see P0-1).
                    put("status", offer.status.name)
                    put("created_at", offer.createdAt)
                }
                val result = nostrClient.publishTradeOffer(
                    privateKeyHex = tradeKey.privateKeyHex,
                    pubkeyHex = nostrPubkey,
                    offerJson = offerJson
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
