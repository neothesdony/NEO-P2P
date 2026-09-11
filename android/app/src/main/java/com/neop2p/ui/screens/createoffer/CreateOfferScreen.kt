package com.neop2p.ui.screens.createoffer

import android.util.Log
import android.content.Context
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
import com.neop2p.domain.model.*
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.util.formatIdr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import dagger.hilt.android.qualifiers.ApplicationContext
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

                    // EDIT mode with a live taker: changing price/amount/rails
                    // mid-handshake can break the pending agreement — warn
                    // before the seller commits the change.
                    if (isEditMode && initialOffer?.status == com.neop2p.domain.model.OfferStatus.MATCHED) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.edit_offer_live_taker_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // Amount (BTC) — you are selling BTC
                    Text(stringResource(R.string.offer_amount_sell_label), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.btcAmount,
                        onValueChange = { viewModel.updateBtcAmount(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.offer_amount_placeholder)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = state.amountInvalid || state.amountOutOfBounds,
                        supportingText = when {
                            state.amountOutOfBounds -> {
                                { Text(stringResource(R.string.offer_amount_out_of_bounds)) }
                            }
                            state.amountInvalid -> {
                                { Text(stringResource(R.string.offer_amount_invalid)) }
                            }
                            else -> null
                        }
                    )

                    // Price per BTC (IDR)
                    Text(stringResource(R.string.offer_price_label), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.pricePerBtc,
                        onValueChange = { viewModel.updatePrice(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.offer_price_placeholder)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = state.priceInvalid,
                        supportingText = if (state.priceInvalid) {
                            { Text(stringResource(R.string.offer_price_invalid)) }
                        } else null
                    )
                    if (!state.priceInvalid && state.pricePerBtc.isBlank()) {
                        Text(
                            text = stringResource(R.string.offer_price_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

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
                                Text(stringResource(R.string.offer_network_fee))
                                Text(state.networkFeeFormatted)
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
                    // Saved-method quick-fill (T10): one tap prefills the
                    // account number + holder from Settings → Metode Pembayaran.
                    val saved = viewModel.savedMethods()
                    if (saved.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            saved.forEach { (methodId, _) ->
                                FilterChip(
                                    selected = false,
                                    onClick = { viewModel.applySavedMethod(methodId) },
                                    label = { Text(methodId.uppercase()) }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
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
                                // QRIS rail: the seller supplies their static
                                // QRIS string (NMID-based) the buyer scans.
                                if (method.id == "qris") {
                                    OutlinedTextField(
                                        value = details?.qrisString.orEmpty(),
                                        onValueChange = { viewModel.updateMethodQrisString(method.id, it) },
                                        label = { Text(stringResource(R.string.offer_qris_label)) },
                                        placeholder = { Text(stringResource(R.string.offer_qris_placeholder)) },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        singleLine = true
                                    )
                                }
                            }
                        }
                    }

                    // Offer lifetime (TTL) — BasicSwap "Offer valid (hrs)" /
                    // RoboSats order-box expiry pattern: creator picks how long
                    // the offer stays claimable; stale offers stay visible but
                    // cannot be accepted.
                    Text(stringResource(R.string.offer_ttl_label), style = MaterialTheme.typography.titleMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            6L * 60 * 60 * 1000 to R.string.offer_ttl_6h,
                            12L * 60 * 60 * 1000 to R.string.offer_ttl_12h,
                            24L * 60 * 60 * 1000 to R.string.offer_ttl_24h,
                            48L * 60 * 60 * 1000 to R.string.offer_ttl_48h
                        ).forEach { (millis, labelRes) ->
                            FilterChip(
                                selected = state.ttlMillis == millis,
                                onClick = { viewModel.setTtl(millis) },
                                label = { Text(stringResource(labelRes)) },
                                modifier = Modifier.weight(1f)
                            )
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
                    if (!state.canSubmit) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.offer_incomplete_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    ConfirmRow(stringResource(R.string.offer_fee_seller), state.sellerFeeFormatted)
                    ConfirmRow(stringResource(R.string.offer_network_fee), state.networkFeeFormatted)
                    ConfirmRow(stringResource(R.string.offer_total_deposit), state.totalDepositFormatted)
                    ConfirmRow(stringResource(R.string.offer_payment_methods), state.selectedMethods.joinToString { id ->
                        NeoP2PConfig.FIAT_METHODS.firstOrNull { it.id == id }?.displayNameId ?: id
                    })
                    ConfirmRow(
                        stringResource(R.string.offer_ttl_label),
                        stringResource(ttlLabelRes(state.ttlMillis))
                    )
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

@Composable
private fun ttlLabelRes(ttlMillis: Long?): Int = when (ttlMillis) {
    null -> R.string.offer_ttl_never
    6L * 60 * 60 * 1000 -> R.string.offer_ttl_6h
    12L * 60 * 60 * 1000 -> R.string.offer_ttl_12h
    24L * 60 * 60 * 1000 -> R.string.offer_ttl_24h
    else -> R.string.offer_ttl_48h
}

/**
 * Map an offer's persisted payment details into form MethodDetails for the
 * edit pre-fill. MUST carry qrisString — dropping it (the pre-fix bug)
 * made every edit of a QRIS offer wipe the QRIS string from the row.
 */
internal fun methodDetailsFromOffer(offer: TradeOffer): Map<String, CreateOfferViewModel.MethodDetails> =
    offer.fiatMethods.associateWith { methodId ->
        val saved = offer.paymentDetails[methodId]
        CreateOfferViewModel.MethodDetails(
            accountNumber = saved?.accountNumber.orEmpty(),
            accountHolder = saved?.accountHolder.orEmpty(),
            qrisString = saved?.qrisString.orEmpty()
        )
    }

@HiltViewModel
class CreateOfferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityManager: IdentityManager,
    private val offerDao: OfferDao,
    private val marketPriceService: com.neop2p.data.market.MarketPriceService,
    private val chainMonitor: com.neop2p.data.escrow.ChainMonitor,
    private val peerDao: com.neop2p.data.local.dao.PeerDao,
    private val savedPaymentMethods: com.neop2p.data.local.SavedPaymentMethodsStore,
    private val rnsTransport: com.neop2p.data.p2p.RnsTransport
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfferFormState())
    val uiState: StateFlow<OfferFormState> = _uiState.asStateFlow()

    init {
        // H3: prefill with the live market price when available; otherwise
        // leave the field EMPTY with a hint — never prefill a stale number.
        viewModelScope.launch(Dispatchers.IO) {
            val livePrice = marketPriceService.getBtcPriceIdr()
            if (livePrice != null && _uiState.value.pricePerBtc.isBlank()) {
                _uiState.update { it.copy(pricePerBtc = livePrice.toString()) }
            }
        }
        // Surface the estimated on-chain network fee (payout tx) so the seller's
        // "Total deposit" reflects the real amount they must fund. Matches the
        // EscrowService calculation: feeRate × PAYOUT_APPROX_VSIZE (220 vB).
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val feeRate = chainMonitor.estimateFees().fastest
                val networkFee = feeRate * 220L
                _uiState.update { it.copy(estimatedNetworkFeeSats = networkFee) }
            } catch (e: Exception) {
                // Non-fatal: deposit falls back to crypto + 0.5% fee only.
                android.util.Log.w("CreateOffer", "Network fee estimate failed: ${e.message}")
            }
        }
    }

    data class OfferFormState(
        val offerType: OfferType = OfferType.SELL,
        val btcAmount: String = "",
        val pricePerBtc: String = "",
        val selectedMethods: Set<String> = emptySet(),
        // Per-method payment details (account number, holder name, etc.) keyed by method id
        val methodDetails: Map<String, MethodDetails> = emptyMap(),
        val isSubmitting: Boolean = false,
        // Offer lifetime in millis. The user picks how long the offer stays
        // claimable (6h / 12h / 24h / 48h). NULL = never expires (legacy edit).
        val ttlMillis: Long? = 24L * 60 * 60 * 1000,
        // Estimated network (miner) fee the on-chain escrow payout will pay,
        // surfaced so the seller knows the FULL deposit (crypto + fee + network).
        val estimatedNetworkFeeSats: Long = 0L,
        // Non-null when a create/update attempt failed; shown to the user via snackbar.
        val error: String? = null,
        // True when the identity seed is locked behind device auth (P0-4) and
        // the user must unlock (biometric / PIN) before the offer can be signed.
        val identityLocked: Boolean = false
    ) {
        val totalFiat: String
            get() = formatIdr(tradeFiat)

        val computedFeeSats: Long
            get() {
                val sats = btcSatsExact() ?: return 0L
                return maxOf((sats * NeoP2PConfig.FEE_NUM) / NeoP2PConfig.FEE_DEN, NeoP2PConfig.MIN_FEE_SATS)
            }

        val computedSellerFeeSats: Long
            get() = computedFeeSats

        // Total the seller must deposit = trade amount + full 0.5% fee (100.5%).
        // The on-chain escrow adds a network (miner) fee for the payout tx; we
        // surface the estimated network fee so the seller knows the FULL amount
        // they must fund (crypto + fee + network fee).
        val computedTotalSats: Long
            get() {
                val sats = btcSatsExact() ?: 0L
                return sats + computedSellerFeeSats + estimatedNetworkFeeSats
            }

        val totalDepositFormatted: String
            get() = String.format("%.8f BTC", computedTotalSats / 100_000_000.0)

        val networkFeeFormatted: String
            get() = String.format("%.8f BTC", estimatedNetworkFeeSats / 100_000_000.0)

        // ── Fiat (IDR) perspective ──────────────────────────────
        // The buyer pays the trade value in IDR with no fee.
        val tradeFiat: Long
            get() {
                val sats = btcSatsExact()
                val price = priceIdrExact()
                if (sats == null || price == null) {
                    val btc = btcAmount.toDoubleOrNull() ?: 0.0
                    val p = pricePerBtc.toDoubleOrNull() ?: 0.0
                    return (btc * p).toLong()
                }
                return (sats * price) / 100_000_000L
            }

        val tradeFiatFormatted: String
            get() = formatIdr(tradeFiat)

        val btcAmountFormatted: String
            get() = String.format("%.8f BTC", btcAmount.toDoubleOrNull() ?: 0.0)

        val sellerFeeFormatted: String
            get() = String.format("%.8f BTC", computedSellerFeeSats / 100_000_000.0)

        val canSubmit: Boolean
            get() {
                val hasAmount = btcAmount.toDoubleOrNull() != null && pricePerBtc.toDoubleOrNull() != null
                val hasMethod = selectedMethods.isNotEmpty()
                // SELL offer: the seller receives the fiat, so every selected
                // method must have complete account details.
                return hasAmount && hasMethod && !amountOutOfBounds && selectedMethods.all { methodId ->
                    val d = methodDetails[methodId]
                    d != null && d.isComplete && (methodId != "qris" || d.qrisString.isNotBlank())
                }
            }

        /** True when the user typed something that is not a valid BTC amount. */
        val amountInvalid: Boolean
            get() = btcAmount.isNotBlank() && btcSatsExact() == null

        /** True when the amount is valid but outside the ingest bounds (peers would reject it). */
        val amountOutOfBounds: Boolean
            get() {
                val sats = btcSatsExact() ?: return false
                if (sats < NeoP2PConfig.MIN_OFFER_SATS || sats > NeoP2PConfig.MAX_OFFER_SATS) return true
                // The real floor is the fiat trade value: peers reject offers
                // under Rp 5M (MIN_OFFER_FIAT_IDR), so the form must too.
                val price = priceIdrExact() ?: return false
                val fiat = (sats * price) / 100_000_000L
                return fiat < NeoP2PConfig.MIN_OFFER_FIAT_IDR
            }

        /** True when the user typed something that is not a whole-number IDR price. */
        val priceInvalid: Boolean
            get() = pricePerBtc.isNotBlank() && priceIdrExact() == null

        /** Parsed whole-satoshi amount (exact). Null when the input is invalid. */
        fun btcSatsExact(): Long? {
            val btc = btcAmount.trim().toBigDecimalOrNull() ?: return null
            if (btc <= java.math.BigDecimal.ZERO) return null
            // Exact decimal math: 0.29 → 29_000_000, never 28_999_999 (the old
            // Double path lost 1 sat). toLong() truncates toward zero, so
            // sub-satoshi input still never rounds up — matches parseBtcToSats.
            return btc.multiply(java.math.BigDecimal(100_000_000)).toLong()
        }

        /** Parsed whole-rupiah price per BTC (exact). Null when invalid/zero. */
        fun priceIdrExact(): Long? =
            CreateOfferViewModel.parseIdrToLong(pricePerBtc)?.takeIf { it > 0L }
    }

    /** Payment details required for a fiat method (e.g. bank account). */
    data class MethodDetails(
        val accountNumber: String = "",
        val accountHolder: String = "",
        val qrisString: String = ""
    ) {
        val isComplete: Boolean
            get() = accountNumber.isNotBlank() && accountHolder.isNotBlank()
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

    /**
     * Prefill the selected method's account details from the saved payment
     * methods store (T10 — Peach "add payment method before first trade"
     * pattern). Only fills when the field is still blank so a manual edit is
     * never overwritten.
     */
    fun applySavedMethod(methodId: String) {
        val saved = savedPaymentMethods.get(methodId) ?: return
        _uiState.update { state ->
            val current = state.methodDetails[methodId] ?: MethodDetails()
            val merged = current.copy(
                accountNumber = current.accountNumber.ifBlank { saved.accountNumber },
                accountHolder = current.accountHolder.ifBlank { saved.accountHolder },
                qrisString = current.qrisString.ifBlank { saved.qrisString }
            )
            state.copy(
                methodDetails = state.methodDetails + (methodId to merged),
                selectedMethods = state.selectedMethods + methodId
            )
        }
    }

    /** All saved methods (for the "use saved" chips row in the form). */
    fun savedMethods(): Map<String, PaymentDetails> = savedPaymentMethods.all()

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

    fun updateMethodQrisString(methodId: String, value: String) {
        _uiState.update { state ->
            val current = state.methodDetails[methodId] ?: MethodDetails()
            state.copy(methodDetails = state.methodDetails + (methodId to current.copy(qrisString = value)))
        }
    }

    fun setTtl(millis: Long) {
        _uiState.update { it.copy(ttlMillis = millis) }
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
                // G.M.01: integer-only money. btcSats is whole satoshis, the
                // fiat amount is whole rupiah computed exactly, the 0.5% fee is
                // sats*5/1000. No float round-trips on the money path.
                val btcSats = state.btcSatsExact() ?: 0L
                val priceIdr = state.priceIdrExact() ?: 0L
                val fiatAmount = if (btcSats > 0L && priceIdr > 0L) {
                    (btcSats * priceIdr) / 100_000_000L
                } else {
                    ((state.btcAmount.toDoubleOrNull() ?: 0.0) * (state.pricePerBtc.toDoubleOrNull() ?: 0.0)).toLong()
                }

                val offer = TradeOffer(
                    offerId = "offer_${System.currentTimeMillis()}",
                    creatorPeerId = identity.peerId,
                    // C1 (2026-09-11): the creator's secp256k1 pubkey rides the
                    // canonical offer JSON so the taker can build a REAL 2-of-3.
                    // Without this, every escrow fails the Task 4 key gate.
                    creatorPubKeyHex = identityManager.getBitcoinPubKeyHex(),
                    type = state.offerType,
                    cryptoAmountSats = btcSats,
                    fiatAmount = fiatAmount,
                    pricePerUnit = (state.pricePerBtc.toDoubleOrNull() ?: 0.0),
                    feeSats = maxOf((btcSats * NeoP2PConfig.FEE_NUM) / NeoP2PConfig.FEE_DEN, NeoP2PConfig.MIN_FEE_SATS),
                    fiatMethods = state.selectedMethods.toList(),
                    status = OfferStatus.OPEN,
                    // Persist the per-method bank account + holder so the seller
                    // can share them via E2EE chat once a buyer accepts (P0-1).
                    paymentDetails = state.methodDetails.mapValues { (_, d) ->
                        com.neop2p.domain.model.PaymentDetails(
                            accountNumber = d.accountNumber,
                            accountHolder = d.accountHolder,
                            qrisString = d.qrisString
                        )
                    },
                    // Offer lifetime: creator-picked TTL. NULL = never expires.
                    expiresAt = state.ttlMillis?.let { System.currentTimeMillis() + it }
                )

                // Persist locally FIRST so the offer always shows on our own feed.
                offerDao.upsert(offer.toEntity())

                // Persist the entered details as saved methods (the T10 write
                // path was dead — only get/all/remove were wired, so users
                // could never actually save a method). Blank entries are
                // skipped; a later manual edit is never overwritten because
                // applySavedMethod only fills blank fields.
                state.methodDetails.forEach { (methodId, d) ->
                    if (d.accountNumber.isNotBlank() || d.accountHolder.isNotBlank() || d.qrisString.isNotBlank()) {
                        savedPaymentMethods.save(
                            methodId,
                            com.neop2p.domain.model.PaymentDetails(
                                accountNumber = d.accountNumber,
                                accountHolder = d.accountHolder,
                                qrisString = d.qrisString
                            )
                        )
                    }
                }

                // Upsert MY OWN peer row so the card shows my nickname without
                // waiting for the feed to echo my offer back (OfferRouter also
                // upserts the creator peer on ingest, covering the buyer side).
                runCatching {
                    val myId = offer.creatorPeerId
                    val existing = peerDao.getPeerSync(myId)
                    if (existing == null || existing.nickname.isBlank() || existing.nickname != identity.nickname) {
                        peerDao.upsert(
                            com.neop2p.data.local.entity.PeerEntity(
                                peer_id = myId,
                                nickname = identity.nickname,
                                nostr_pubkey = existing?.nostr_pubkey ?: "",
                                ln_node_id = existing?.ln_node_id ?: "",
                                created_at = existing?.created_at ?: System.currentTimeMillis(),
                                reputation_score = existing?.reputation_score ?: 0f,
                                total_trades = existing?.total_trades ?: 0,
                                last_seen = System.currentTimeMillis(),
                                relay_hints = existing?.relay_hints ?: "[]",
                                multiaddrs = existing?.multiaddrs ?: "[]"
                            )
                        )
                    }
                }.onFailure { Log.w("CreateOffer", "Failed to upsert own peer row: ${it.message}") }

                // Phase 4: register the offer digest with the paced RNS
                // re-announce loop (neop2p/offers). The loop announces one
                // digest per 2.5s tick — the one-shot publishOffer was removed
                // (Bug B, 2026-09-01): it duplicated the loop's announce within
                // the same 30s window, risking the fork's same-second /
                // 16-per-30s per-destination drops. A newly tracked digest is
                // announced on the next tick (~2.5s).
                rnsTransport.trackOfferDigest(
                    com.neop2p.data.p2p.RnsOfferDigest.encode(offer, identity.nickname)
                )

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
                    it.copy(isSubmitting = false, error = context.getString(R.string.offer_create_failed, e.message ?: ""))
                }
                Log.e("CreateOffer", "Create offer failed: ${e.message}")
            }
        }
    }

    /** EDIT mode: pre-fill the form from an existing offer so the user can
     *  review and modify its values before saving back to the same offerId. */
    fun loadOfferForEdit(offer: TradeOffer) {
        _uiState.update {
            it.copy(
                offerType = offer.type,
                btcAmount = String.format("%.8f", offer.cryptoAmountSats / 100_000_000.0).trimEnd('0').trimEnd('.', ','),
                pricePerBtc = formatDouble(offer.pricePerUnit),
                selectedMethods = offer.fiatMethods.toSet(),
                methodDetails = methodDetailsFromOffer(offer),
                // Reconstruct the TTL OPTION (6/12/24/48h), not the remaining
                // time — remaining time left no chip selected and mislabeled
                // the confirm dialog. NULL stays "never".
                ttlMillis = ttlFromDeadline(offer.createdAt, offer.expiresAt)
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
                // A MATCHED/ESCROWED offer's terms are a live agreement with the
                // buyer — silently changing amount/price/rails after the match
                // would make the buyer pay different terms than they accepted.
                if (!isOfferEditable(existing.status)) {
                    _uiState.update { it.copy(isSubmitting = false) }
                    _uiState.update { it.copy(error = context.getString(R.string.offer_cannot_edit_locked)) }
                    return@launch
                }

                // An expired offer is dead: the accept gate rejects it, and
                // re-saving would resurrect it with a fresh deadline (the
                // pre-fix bug: remaining time < 0 → null → "never expires").
                if (existing.expiresAt != null && existing.expiresAt <= System.currentTimeMillis()) {
                    _uiState.update { it.copy(isSubmitting = false) }
                    _uiState.update { it.copy(error = context.getString(R.string.offer_cannot_edit_expired)) }
                    return@launch
                }

                val btcSats = state.btcSatsExact() ?: 0L
                val priceIdr = state.priceIdrExact() ?: 0L
                val fiatAmount = if (btcSats > 0L && priceIdr > 0L) {
                    (btcSats * priceIdr) / 100_000_000L
                } else {
                    ((state.btcAmount.toDoubleOrNull() ?: 0.0) * (state.pricePerBtc.toDoubleOrNull() ?: 0.0)).toLong()
                }

                val updated = existing.copy(
                    cryptoAmountSats = btcSats,
                    fiatAmount = fiatAmount,
                    pricePerUnit = (state.pricePerBtc.toDoubleOrNull() ?: 0.0),
                    feeSats = maxOf((btcSats * NeoP2PConfig.FEE_NUM) / NeoP2PConfig.FEE_DEN, NeoP2PConfig.MIN_FEE_SATS),
                    fiatMethods = state.selectedMethods.toList(),
                    paymentDetails = state.methodDetails.mapValues { (_, d) ->
                        com.neop2p.domain.model.PaymentDetails(
                            accountNumber = d.accountNumber,
                            accountHolder = d.accountHolder,
                            qrisString = d.qrisString
                        )
                    },
                    // Re-picked TTL replaces the original deadline. NULL = never.
                    expiresAt = state.ttlMillis?.let { System.currentTimeMillis() + it }
                )

                // Persist locally FIRST (same offerId, REPLACE on conflict).
                offerDao.upsert(updated.toEntity())

                // Same saved-method write-through as createOffer: editing an
                // offer is the natural moment to (re)save the rails the seller
                // actually uses. Blank entries are skipped.
                state.methodDetails.forEach { (methodId, d) ->
                    if (d.accountNumber.isNotBlank() || d.accountHolder.isNotBlank() || d.qrisString.isNotBlank()) {
                        savedPaymentMethods.save(
                            methodId,
                            com.neop2p.domain.model.PaymentDetails(
                                accountNumber = d.accountNumber,
                                accountHolder = d.accountHolder,
                                qrisString = d.qrisString
                            )
                        )
                    }
                }

                // Phase 4: replace the tracked digest in the paced RNS
                // re-announce loop (same offer id) so peers see the updated
                // card on the next tick. The one-shot publishOffer was removed
                // (Bug B, 2026-09-01) — the loop owns all feed announces.
                rnsTransport.trackOfferDigest(
                    com.neop2p.data.p2p.RnsOfferDigest.encode(updated, identityManager.getOrCreateIdentity().nickname)
                )

                _uiState.update { it.copy(isSubmitting = false) }
                // Same main-thread requirement as createOffer.
                withContext(Dispatchers.Main) { onUpdated(updated.offerId) }
            } catch (e: IdentityLockedException) {
                // P0-4: unlock window expired — surface the unlock prompt and retry.
                _uiState.update { it.copy(isSubmitting = false, identityLocked = true) }
                Log.w("CreateOffer", "Identity locked on edit; prompting unlock: ${e.message}")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSubmitting = false, error = context.getString(R.string.offer_update_failed, e.message ?: ""))
                }
                Log.e("CreateOffer", "Edit offer failed: ${e.message}")
            }
        }
    }

    private fun formatDouble(value: Double): String {
        val s = String.format("%.2f", value)
        return if (s.endsWith(".0") || s.endsWith(".00")) s else s.trimEnd('0').trimEnd('.')
    }

    companion object {
        val TTL_OPTIONS_MILLIS: List<Long> = listOf(6L, 12L, 24L, 48L).map { it * 60 * 60 * 1000 }
        const val DEFAULT_TTL_MILLIS: Long = 24L * 60 * 60 * 1000

        /**
         * Reconstruct the TTL option from a stored deadline. Offers are created
         * with expiresAt = createdAt + ttl, so the option is recoverable exactly.
         * A deadline matching no option (legacy/imported row) falls back to the
         * default so the form always shows a selected chip; null stays "never".
         */
        internal fun ttlFromDeadline(createdAt: Long, expiresAt: Long?): Long? =
            expiresAt?.let { deadline ->
                TTL_OPTIONS_MILLIS.firstOrNull { createdAt + it == deadline } ?: DEFAULT_TTL_MILLIS
            }

        /** Parse a user-typed IDR price to a whole-rupiah Long. */
        internal fun parseIdrToLong(input: String): Long? {
            val cleaned = input.trim()
            if (cleaned.isEmpty()) return null
            val groups = cleaned.split('.', ',')
            val validThousands = groups.size == 1 ||
                (groups.drop(1).all { it.length == 3 } && groups.first().isNotEmpty())
            if (!validThousands) return null
            val digits = cleaned.replace(".", "").replace(",", "")
            if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
            return digits.toLongOrNull()
        }
    }
}
