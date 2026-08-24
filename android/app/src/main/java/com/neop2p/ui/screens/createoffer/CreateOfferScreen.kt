package com.neop2p.ui.screens.createoffer

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.local.*
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.p2p.IdentityLockedException
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.p2p.NostrClient
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateOfferScreen(
    onOfferCreated: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffer: TradeOffer? = null,
    onEditSaved: ((String) -> Unit)? = null
) {
    val viewModel: CreateOfferViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showConfirmDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface create/update failures instead of swallowing them.
    val currentError = state.error
    LaunchedEffect(currentError) {
        currentError?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeError()
        }
    }

    // EDIT mode: pre-fill the form from the offer being edited.
    val isEditMode = initialOffer != null

    // P0-4: when the identity seed is locked behind device auth (unlock window
    // expired), surface a BiometricPrompt so the user can re-authorize the
    // Keystore key (biometric or PIN). After success we retry the pending op.
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val identityLocked = state.identityLocked
    LaunchedEffect(identityLocked) {
        if (identityLocked && activity is FragmentActivity) {
            viewModel.consumeIdentityLocked()
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        // Re-arm the auth-gated key; retry the pending op.
                        if (isEditMode) {
                            initialOffer?.let {
                                viewModel.updateOffer(it.offerId) { id -> onEditSaved?.invoke(id) ?: onOfferCreated(id) }
                            }
                        } else {
                            viewModel.createOffer(onOfferCreated)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            Log.w("CreateOffer", "Unlock prompt failed: $errString")
                        }
                    }
                }
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.offer_unlock_title))
                .setSubtitle(activity.getString(R.string.offer_unlock_subtitle))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(promptInfo)
        }
    }


    LaunchedEffect(initialOffer?.offerId) {
        if (isEditMode) {
            initialOffer?.let { viewModel.loadOfferForEdit(it) }
        }
    }

    NeoP2PTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(if (isEditMode) R.string.edit_offer_title else R.string.home_create_offer)) },
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
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Create-offer is sell-only: buyers shop from the offer list.
                    Text(stringResource(R.string.offer_type_label), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.offer_create_sell_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Amount (BTC) — you are selling BTC
                    Text(stringResource(R.string.offer_amount_sell_label), style = MaterialTheme.typography.titleMedium)
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

                    // Summary line — you are selling BTC, you receive IDR
                    if (state.btcAmount.toDoubleOrNull() != null && state.pricePerBtc.toDoubleOrNull() != null) {
                        val total = state.totalFiat
                        val btc = state.btcAmount
                        Text(
                            text = stringResource(R.string.offer_summary_sell, total, btc),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Fee breakdown
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.offer_fee_breakdown), style = MaterialTheme.typography.labelMedium)

                            // ── SELL offer: you are the seller, you deposit BTC ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.offer_trade_amount))
                                Text(state.btcAmountFormatted)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.offer_fee_seller))
                                Text(state.sellerFeeFormatted)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.offer_total_deposit))
                                Text(state.totalDepositFormatted)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.offer_you_receive_idr),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = state.tradeFiatFormatted,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.offer_fee_wallet_format, NeoP2PConfig.FEE_WALLET_ADDRESS.take(12)),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    // Fiat Methods
                    Text(stringResource(R.string.offer_payment_methods), style = MaterialTheme.typography.titleMedium)
                    NeoP2PConfig.FIAT_METHODS.forEach { method ->
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

                            // When selected, collect the recipient's payment details.
                            // As the SELLER you receive the fiat, so you supply your
                            // bank/account info for each selected method.
                            if (isSelected) {
                                val isCash = method.id == "cash"
                                OutlinedTextField(
                                    value = details?.accountNumber.orEmpty(),
                                    onValueChange = { viewModel.updateMethodAccountNumber(method.id, it) },
                                    label = { Text(if (isCash) stringResource(R.string.offer_cash_contact) else stringResource(R.string.offer_account_number_format, method.displayNameId)) },
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

                    // Submit
                    Button(
                        onClick = { showConfirmDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = state.canSubmit
                    ) {
                        Text(stringResource(if (isEditMode) R.string.edit_offer_save else R.string.offer_create_sell))
                    }
                }
            }
        )
    }

    // Confirmation dialog showing the transaction summary before publishing.
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.offer_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfirmRow(stringResource(R.string.offer_type_label), stringResource(R.string.trade_sell))
                    ConfirmRow(stringResource(R.string.offer_amount), state.btcAmountFormatted)
                    ConfirmRow(stringResource(R.string.offer_total_deposit), state.totalDepositFormatted)
                    ConfirmRow(stringResource(R.string.offer_fee_seller), state.sellerFeeFormatted)
                    ConfirmRow(stringResource(R.string.offer_payment_methods), state.selectedMethods.joinToString { id ->
                        NeoP2PConfig.FIAT_METHODS.firstOrNull { it.id == id }?.displayNameId ?: id
                    })
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        if (isEditMode) {
                            initialOffer?.let {
                                viewModel.updateOffer(
                                    initialOfferId = it.offerId,
                                    onUpdated = { id -> onEditSaved?.invoke(id) ?: onOfferCreated(id) }
                                )
                            }
                        } else {
                            viewModel.createOffer(onOfferCreated)
                        }
                    },
                    enabled = !state.isSubmitting
                ) {
                    Text(stringResource(if (isEditMode) R.string.edit_offer_save else R.string.offer_confirm_publish))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

@HiltViewModel
class CreateOfferViewModel @Inject constructor(
    private val identityManager: IdentityManager,
    private val nostrClient: NostrClient,
    private val offerDao: OfferDao,
    private val marketPriceService: com.neop2p.data.market.MarketPriceService
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfferFormState())
    val uiState: StateFlow<OfferFormState> = _uiState.asStateFlow()

    init {
        // Pre-fill "Price per BTC" with the current market price (editable).
        // Start with the static fallback so the field is never empty, then
        // overwrite it with the live price once fetched.
        _uiState.update { it.copy(pricePerBtc = NeoP2PConfig.DEFAULT_BTC_MARKET_PRICE_IDR.toString()) }
        viewModelScope.launch(Dispatchers.IO) {
            val livePrice = marketPriceService.getBtcPriceIdr()
            // Only set the live price if the user hasn't already typed a value.
            if (_uiState.value.pricePerBtc == NeoP2PConfig.DEFAULT_BTC_MARKET_PRICE_IDR.toString()) {
                _uiState.update { it.copy(pricePerBtc = livePrice.toString()) }
            }
        }
    }

    data class OfferFormState(
        val offerType: OfferType = OfferType.SELL,
        val btcAmount: String = "",
        val pricePerBtc: String = "",
        // BTC receive address — required when the creator is the BUYER (BTC recipient).
        val btcReceiveAddress: String = "",
        val selectedMethods: Set<String> = emptySet(),
        // Per-method payment details (account number, holder name, etc.) keyed by method id
        val methodDetails: Map<String, MethodDetails> = emptyMap(),
        val isSubmitting: Boolean = false,
        // Non-null when a create/update attempt failed; shown to the user via snackbar.
        val error: String? = null,
        // True when the identity seed is locked behind device auth (P0-4) and
        // the user must unlock (biometric / PIN) before the offer can be signed.
        val identityLocked: Boolean = false
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

        // New fee model: the seller pays the full 0.3% fee; the buyer pays
        // nothing and receives the full crypto amount. Mirrors TradeOffer.
        val computedBuyerFeeSats: Long
            get() = 0

        val computedSellerFeeSats: Long
            get() = computedFeeSats

        // Total the seller must deposit = trade amount + full 0.3% fee (100.3%).
        val computedTotalSats: Long
            get() {
                val sats = (btcAmount.toDoubleOrNull() ?: 0.0) * 100_000_000
                return sats.toLong() + computedSellerFeeSats
            }

        // ── Fiat (IDR) perspective ──────────────────────────────
        // The buyer pays the trade value in IDR with no fee.
        val tradeFiat: Long
            get() {
                val btc = btcAmount.toDoubleOrNull() ?: 0.0
                val price = pricePerBtc.toDoubleOrNull() ?: 0.0
                return (btc * price).toLong()
            }

        // The buyer is not charged a fee.
        val buyerFeeFiat: Long
            get() = 0

        // Total IDR the buyer pays = trade value (no fee).
        val totalFiatPayable: Long
            get() = tradeFiat

        val tradeFiatFormatted: String
            get() = "Rp ${String.format("%,.0f", tradeFiat.toDouble())}"

        val buyerFeeFiatFormatted: String
            get() = "Rp ${String.format("%,.0f", buyerFeeFiat.toDouble())}"

        val totalFiatPayableFormatted: String
            get() = "Rp ${String.format("%,.0f", totalFiatPayable.toDouble())}"

        val btcAmountFormatted: String
            get() = String.format("%.8f BTC", btcAmount.toDoubleOrNull() ?: 0.0)

        val totalDepositFormatted: String
            get() = String.format("%.8f BTC", computedTotalSats / 100_000_000.0)

        val buyerFeeFormatted: String
            get() = String.format("%.8f BTC", computedBuyerFeeSats / 100_000_000.0)

        val sellerFeeFormatted: String
            get() = String.format("%.8f BTC", computedSellerFeeSats / 100_000_000.0)

        val canSubmit: Boolean
            get() {
                val hasAmount = btcAmount.toDoubleOrNull() != null && pricePerBtc.toDoubleOrNull() != null
                val hasMethod = selectedMethods.isNotEmpty()
                // SELL offer: the seller receives the fiat, so every selected
                // method must have complete account details.
                return hasAmount && hasMethod && selectedMethods.all { methodId ->
                    val d = methodDetails[methodId]
                    d != null && d.isComplete
                }
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

    fun updateBtcReceiveAddress(address: String) {
        _uiState.update { it.copy(btcReceiveAddress = address) }
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

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    fun consumeIdentityLocked() {
        _uiState.update { it.copy(identityLocked = false) }
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
                    btcReceiveAddress = state.btcReceiveAddress,
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
                // Persist locally FIRST so the offer always shows on our own feed,
                // regardless of relay echo latency or connectivity.
                offerDao.upsert(offer.toEntity())

                // Publish to Nostr as best-effort with a hard timeout. The relay
                // handshake/send can hang on a slow/unreachable host, so cap it
                // and ALWAYS return to the list. The offer is already saved locally.
                withTimeoutOrNull(5_000L) {
                    try {
                        nostrClient.publishTradeOffer(
                            privateKeyHex = tradeKey.privateKeyHex,
                            pubkeyHex = nostrPubkey,
                            offerJson = offerJson
                        )
                    } catch (e: Exception) {
                        Log.w("CreateOffer", "Publish to relay failed (offer kept locally): ${e.message}")
                    }
                }

                _uiState.update { it.copy(isSubmitting = false) }
                // NavController.popBackStack() (wired via onCreated) must run on
                // the main thread; this coroutine is on Dispatchers.IO.
                withContext(Dispatchers.Main) { onCreated(offer.offerId) }
            } catch (e: IdentityLockedException) {
                // P0-4: identity is gated behind device auth (unlock window expired).
                // Surface the unlock prompt; do NOT overwrite with a generic error.
                _uiState.update { it.copy(isSubmitting = false, identityLocked = true) }
                Log.w("CreateOffer", "Identity locked; prompting unlock: ${e.message}")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSubmitting = false, error = "Failed to create offer: ${e.message}")
                }
                Log.e("CreateOffer", "Create offer failed: ${e.message}")
            }
        }
    }

    /** EDIT mode: pre-fill the form from an existing offer so the user can
     *  review and modify its values before saving back to the same offerId. */
    fun loadOfferForEdit(offer: TradeOffer) {
        val methodDetails = offer.fiatMethods.associateWith { methodId ->
            MethodDetails()
        }
        _uiState.update {
            it.copy(
                offerType = offer.type,
                btcAmount = String.format("%.8f", offer.cryptoAmountSats / 100_000_000.0).trimEnd('0').trimEnd('.', ','),
                pricePerBtc = formatDouble(offer.pricePerUnit),
                btcReceiveAddress = offer.btcReceiveAddress,
                selectedMethods = offer.fiatMethods.toSet(),
                methodDetails = methodDetails
            )
        }
    }

    /** EDIT mode: persist edits back to the SAME offer row (same offerId,
     *  same creatorPeerId/status/nostrEventId) and best-effort re-publish to
     *  Nostr without blocking navigation. */
    fun updateOffer(initialOfferId: String, onUpdated: (String) -> Unit) {
        val state = _uiState.value
        if (!state.canSubmit) return

        _uiState.update { it.copy(isSubmitting = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = offerDao.getOfferSync(initialOfferId)?.toDomain()
                    ?: throw IllegalStateException("Offer not found for edit")

                val btcSats = (state.btcAmount.toDouble() * 100_000_000).toLong()
                val fiatAmount = (btcSats.toDouble() / 100_000_000.0) * state.pricePerBtc.toDouble()

                val updated = existing.copy(
                    cryptoAmountSats = btcSats,
                    fiatAmount = fiatAmount.toLong(),
                    pricePerUnit = state.pricePerBtc.toDouble(),
                    feeSats = (btcSats * NeoP2PConfig.FEE_PERCENT).toLong(),
                    fiatMethods = state.selectedMethods.toList(),
                    btcReceiveAddress = state.btcReceiveAddress
                )

                // Persist locally FIRST (same offerId, REPLACE on conflict).
                offerDao.upsert(updated.toEntity())

                // Best-effort re-publish to Nostr with a hard timeout. Editing
                // must NOT block navigation even if the relay is unreachable.
                withTimeoutOrNull(5_000L) {
                    try {
                        val tradeKey = identityManager.getNextTradeNostrKeyPair()
                        val offerJson = buildJsonObject {
                            put("offer_id", updated.offerId)
                            put("creator_peer_id", updated.creatorPeerId)
                            put("type", updated.type.name)
                            put("fiat_amount", updated.fiatAmount)
                            put("crypto_amount_sats", updated.cryptoAmountSats)
                            put("price_per_unit", updated.pricePerUnit)
                            put("fee_percent", updated.feePercent)
                            putJsonArray("fiat_methods") {
                                updated.fiatMethods.forEach { add(it) }
                            }
                            put("status", updated.status.name)
                            put("created_at", updated.createdAt)
                        }
                        val result = nostrClient.publishTradeOffer(
                            privateKeyHex = tradeKey.privateKeyHex,
                            pubkeyHex = tradeKey.publicKeyHex,
                            offerJson = offerJson
                        )
                        // Persist the freshly-published event id if the relay
                        // accepted it, so the new event is tracked locally.
                        result.getOrNull()?.let { newEventId ->
                            if (newEventId.isNotBlank()) {
                                offerDao.upsert(updated.copy(nostrEventId = newEventId).toEntity())
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("CreateOffer", "Re-publish after edit failed (offer kept locally): ${e.message}")
                    }
                }

                _uiState.update { it.copy(isSubmitting = false) }
                // Same main-thread requirement as createOffer.
                withContext(Dispatchers.Main) { onUpdated(updated.offerId) }
            } catch (e: IdentityLockedException) {
                // P0-4: unlock window expired — surface the unlock prompt and retry.
                _uiState.update { it.copy(isSubmitting = false, identityLocked = true) }
                Log.w("CreateOffer", "Identity locked on edit; prompting unlock: ${e.message}")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSubmitting = false, error = "Failed to update offer: ${e.message}")
                }
                Log.e("CreateOffer", "Edit offer failed: ${e.message}")
            }
        }
    }

    private fun formatDouble(value: Double): String {
        val s = String.format("%.2f", value)
        return if (s.endsWith(".0") || s.endsWith(".00")) s else s.trimEnd('0').trimEnd('.')
    }
}
