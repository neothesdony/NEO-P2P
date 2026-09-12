package com.neop2p.ui.screens.escrow

import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.graphics.BitmapFactory
import com.neop2p.NeoP2PConfig
import com.neop2p.R
import com.neop2p.data.escrow.EscrowService
import com.neop2p.data.escrow.ResolutionGuard
import com.neop2p.data.escrow.RoleAddressAttestation
import com.neop2p.data.local.dao.ArbitratorDisputeDao
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.entity.ArbitratorDisputeEntity
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.domain.model.ResolutionDecision
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Arbitrator Mode dispute feed. Only reachable when the active identity IS
 * the arbitrator (its derived arbitrator key matches
 * [NeoP2PConfig.ARBITRATOR_PUBKEY]).
 *
 * Shows disputes received over the relay (LXMF dispute message), the evidence parties
 * attached (LXMF evidence message), and lets the arbitrator sign + publish a resolution
 * (LXMF resolution message) — the parties then broadcast the payout/refund with the
 * arbitrator's signature (2-of-3).
 */
@Composable
fun DisputeFeedScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: DisputeFeedViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busyEscrowIds by viewModel.busyEscrowIds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isArbitrator by viewModel.isArbitrator.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.arbitrator_feed_title)) },
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
                        is DisputeFeedViewModel.UiState.Loading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        is DisputeFeedViewModel.UiState.Error -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    s.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { viewModel.refresh() }) {
                                    Text(stringResource(R.string.general_retry))
                                }
                            }
                        }
                        is DisputeFeedViewModel.UiState.Success -> {
                            PullToRefreshBox(
                                isRefreshing = isRefreshing,
                                onRefresh = { viewModel.refresh() },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(16.dp)
                                ) {
                                // Phase 4: the relay health banner was removed
                                // (Nostr relays are gone — disputes arrive over
                                // LXMF). Disputes are DB-seeded + LXMF-fed.
                                error?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                if (s.disputes.isEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            painterResource(id = R.drawable.ic_warning),
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            stringResource(R.string.arbitrator_feed_empty),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        if (s.disputes.isEmpty()) {
                                            Text(
                                                stringResource(R.string.arbitrator_feed_empty_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedButton(onClick = { viewModel.refresh() }) {
                                                Text(stringResource(R.string.general_retry))
                                            }
                                        }
                                    }
                                } else {
                                    s.disputes.forEach { d ->
                                        val busy = d.escrowId in busyEscrowIds
                                        DisputeCard(
                                            dispute = d,
                                            evidence = s.evidence[d.escrowId].orEmpty(),
                                            resolved = s.resolved[d.escrowId] ?: false,
                                            busy = busy,
                                            busyThisCard = busy,
                                            onResolve = { decision, notes ->
                                                viewModel.resolve(d.escrowId, d, decision, notes)
                                            },
                                            txOutputs = { viewModel.txOutputs(it) }
                                        )
                                        Spacer(Modifier.height(12.dp))
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

/** A dispute as seen by the arbitrator (from LXMF dispute message + evidence + resolution). */
data class ArbitratorDispute(
    val escrowId: String,
    val openedBy: String,
    val reason: String,
    val openedAt: Long,
    val redeemScriptHex: String?,
    val unsignedTxHex: String?,
    // Pre-built unsigned REFUND tx (dispute from a pre-payout state). The
    // opening party ships it because the arbitrator cannot build the refund
    // themselves (no funding tx/vout on the relay event).
    val refundTxHex: String? = null,
    // BIP-143 (P2WSH) remote signing needs the input value + script type.
    val depositSats: Long? = null,
    val fundingScriptType: String? = null,
    // The seller's BTC refund address (carried by the dispute event) so a
    // REFUND_TO_SELLER resolution pays the SELLER, not whoever applies it.
    val sellerRefundAddress: String? = null,
    // The escrow's parties (v23, 2026-09-02) — the resolution delivery
    // targets. The arbitrator has NO local escrow row, so these are the ONLY
    // way to reach the buyer and seller.
    val buyerPeerId: String? = null,
    val sellerPeerId: String? = null,
    // F2 (2026-09-12): the escrow's role keys + role-signed destination
    // attestations (scope-bound). The arbitrator verifies them before signing
    // a payout/refund so funds can only go to an authorized destination.
    val buyerBtcAddress: String? = null,
    val buyerPubKeyHex: String? = null,
    val sellerPubKeyHex: String? = null,
    val sellerRefundAttestation: String? = null,
    val buyerAddressAttestation: String? = null,
    val offerId: String? = null,
    val tradeSats: Long? = null
)

@Composable
private fun DisputeCard(
    dispute: ArbitratorDispute,
    evidence: List<EvidencePiece>,
    resolved: Boolean,
    busy: Boolean,
    busyThisCard: Boolean,
    onResolve: (ResolutionDecision, String) -> Unit,
    txOutputs: (String) -> List<String>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Text(
                    text = dispute.escrowId,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("NEO-P2P escrowId", dispute.escrowId)
                            )
                            android.widget.Toast.makeText(ctx, R.string.arbitrator_escrow_id_copied, android.widget.Toast.LENGTH_SHORT).show()
                        }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.arbitrator_opened_by, dispute.openedBy.take(12)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (dispute.openedAt > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(dispute.openedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            dispute.depositSats?.let { sats ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.arbitrator_deposit_line, sats, dispute.fundingScriptType?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            dispute.sellerRefundAddress?.takeIf { it.isNotBlank() }?.let { addr ->
                Spacer(Modifier.height(2.dp))
                SelectionContainer {
                    Text(
                        text = stringResource(R.string.arbitrator_refund_line, addr),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            // F2: the arbitrator must see EXACTLY where each decision's tx pays.
            val txForCard = dispute.unsignedTxHex ?: dispute.refundTxHex
            if (!txForCard.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.arbitrator_tx_destinations), style = MaterialTheme.typography.labelMedium)
                val outputs = remember(txForCard) {
                    runCatching { txOutputs(txForCard) }.getOrDefault(emptyList())
                }
                SelectionContainer {
                    Column {
                        outputs.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            if (dispute.reason.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(dispute.reason, style = MaterialTheme.typography.bodyMedium)
            }

            if (dispute.unsignedTxHex.isNullOrBlank() && dispute.refundTxHex.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.arbitrator_missing_tx),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // Evidence attachments
            if (evidence.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.arbitrator_evidence_label, evidence.size),
                    style = MaterialTheme.typography.labelMedium
                )
                evidence.forEach { e ->
                    Spacer(Modifier.height(8.dp))
                    val bitmap = remember(e.imageBase64) {
                        runCatching {
                            val bytes = Base64.decode(e.imageBase64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                        )
                    }
                    if (e.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(e.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (resolved) {
                Text(
                    stringResource(R.string.arbitrator_resolved),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (!dispute.unsignedTxHex.isNullOrBlank() || !dispute.refundTxHex.isNullOrBlank()) {
                var notes by remember(dispute.escrowId) { mutableStateOf("") }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.arbitrator_notes_label)) },
                    placeholder = { Text(stringResource(R.string.arbitrator_notes_placeholder)) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // Resolution buttons are gated on the tx each decision needs:
                // Release signs the payout (psbt_hex), Refund signs the
                // pre-built refund (refund_tx_hex). A pre-payout dispute has
                // no refund tx → refund not arbitrable; a dispute without a
                // payout tx → release not arbitrable. Never show a button the
                // arbitrator cannot sign.
                if (!dispute.refundTxHex.isNullOrBlank() && dispute.unsignedTxHex.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = { onResolve(ResolutionDecision.REFUND_TO_SELLER, notes) },
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busyThisCard) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.arbitrator_resolving))
                        } else {
                            Text(stringResource(R.string.arbitrator_refund_buyer))
                        }
                    }
                } else if (!dispute.unsignedTxHex.isNullOrBlank()) {
                    Row {
                        Button(
                            onClick = { onResolve(ResolutionDecision.RELEASE_TO_BUYER, notes) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (busyThisCard) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.arbitrator_resolving))
                            } else {
                                Text(stringResource(R.string.arbitrator_release_seller))
                            }
                        }
                        if (!dispute.refundTxHex.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onResolve(ResolutionDecision.REFUND_TO_SELLER, notes) },
                                enabled = !busy,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (busyThisCard) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.arbitrator_resolving))
                                } else {
                                    Text(stringResource(R.string.arbitrator_refund_buyer))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class EvidencePiece(
    val escrowId: String,
    val submitter: String,
    val description: String,
    val imageBase64: String
)

@HiltViewModel
class DisputeFeedViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val identityManager: IdentityManager,
    private val escrowService: EscrowService,
    private val arbitratorDisputeDao: ArbitratorDisputeDao,
    private val disputeEvidenceDao: DisputeEvidenceDao,
    private val rnsTransport: com.neop2p.data.p2p.RnsTransport,
    private val pendingArbitrationStore: com.neop2p.data.local.PendingArbitrationStore
) : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(
            val disputes: List<ArbitratorDispute>,
            val evidence: Map<String, List<EvidencePiece>>,
            val resolved: Map<String, Boolean>
        ) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Per-card busy set (Task 4) — allows concurrent resolves, UI spins only the tapped card.
    private val _busyEscrowIds = MutableStateFlow<Set<String>>(emptySet())
    val busyEscrowIds: StateFlow<Set<String>> = _busyEscrowIds.asStateFlow()

    // Legacy single-busy for backward compat in UI (derived).
    @Deprecated("Use busyEscrowIds")
    val busy: StateFlow<Boolean> = _busyEscrowIds.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    @Deprecated("Use busyEscrowIds")
    val busyEscrowId: StateFlow<String?> = _busyEscrowIds.map { it.firstOrNull() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isArbitrator = MutableStateFlow(false)
    val isArbitrator: StateFlow<Boolean> = _isArbitrator.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    // Source-of-truth: DB-merged in-memory maps seeded from Room at init.
    private val disputes = LinkedHashMap<String, ArbitratorDispute>()
    private val evidenceMap = LinkedHashMap<String, MutableList<EvidencePiece>>()
    private val resolvedSet = mutableSetOf<String>()

    init {
        collect()
    }

    fun refresh() {
        viewModelScope.launch {
            _error.value = null
            _refreshing.value = true
            try {
                // Pull-to-refresh must not blank an already-loaded feed: only
                // show the full-screen spinner on the FIRST load.
                if (_uiState.value !is UiState.Success) _uiState.value = UiState.Loading
                // Re-seed from DB (survives prune/reboot) — Task 1 fix: was no-op publishState().
                val dbDisputes = arbitratorDisputeDao.getAll()
                disputes.clear()
                dbDisputes.forEach { e ->
                    disputes[e.escrow_id] = ArbitratorDispute(
                        escrowId = e.escrow_id,
                        openedBy = e.opened_by,
                        reason = e.reason,
                        openedAt = e.opened_at,
                        redeemScriptHex = e.redeem_script_hex,
                        unsignedTxHex = e.psbt_hex,
                        refundTxHex = e.refund_tx_hex,
                        depositSats = e.deposit_sats,
                        fundingScriptType = e.funding_script_type,
                        sellerRefundAddress = e.seller_refund_address,
                        buyerPeerId = e.buyer_peer_id,
                        sellerPeerId = e.seller_peer_id,
                        buyerBtcAddress = e.buyer_btc_address,
                        buyerPubKeyHex = e.buyer_pubkey_hex,
                        sellerPubKeyHex = e.seller_pubkey_hex,
                        sellerRefundAttestation = e.seller_refund_attestation,
                        buyerAddressAttestation = e.buyer_address_attestation,
                        offerId = e.offer_id,
                        tradeSats = e.trade_sats
                    )
                    if (e.resolved) resolvedSet.add(e.escrow_id)
                }
                // Merge evidence from DB (base64-encode stored bytes for UI)
                val dbEvidence = disputeEvidenceDao.getAll()
                evidenceMap.clear()
                dbEvidence.groupBy { it.escrow_id }.forEach { (eid, list) ->
                    evidenceMap[eid] = list.map { ent ->
                        EvidencePiece(
                            escrowId = ent.escrow_id,
                            submitter = ent.submitter_peer_id,
                            description = ent.description,
                            imageBase64 = Base64.encodeToString(ent.image_data, Base64.NO_WRAP)
                        )
                    }.toMutableList()
                }
                Log.d(TAG, "Refresh: seeded ${disputes.size} disputes, ${dbEvidence.size} evidence from DB")
                publishState()
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed: ${e.message}")
                publishState()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun collect() {
        viewModelScope.launch {
            val isArb = runCatching {
                identityManager.getArbitratorPubKeyHex()
                    .equals(NeoP2PConfig.ARBITRATOR_PUBKEY, ignoreCase = true)
            }.getOrDefault(false)
            _isArbitrator.value = isArb
            if (!isArb) {
                _uiState.value = UiState.Error(context.getString(R.string.arbitrator_feed_not_arbitrator))
                Log.w(TAG, "Feed opened by non-arbitrator pub=${runCatching { identityManager.getArbitratorPubKeyHex().take(12) }.getOrDefault("?")} expected=${NeoP2PConfig.ARBITRATOR_PUBKEY.take(12)}")
                return@launch
            }
            // Seed from DB before live LXMF (Task 1)
            try {
                val dbDisputes = arbitratorDisputeDao.getAll()
                dbDisputes.forEach { e ->
                    disputes[e.escrow_id] = ArbitratorDispute(
                        escrowId = e.escrow_id,
                        openedBy = e.opened_by,
                        reason = e.reason,
                        openedAt = e.opened_at,
                        redeemScriptHex = e.redeem_script_hex,
                        unsignedTxHex = e.psbt_hex,
                        refundTxHex = e.refund_tx_hex,
                        depositSats = e.deposit_sats,
                        fundingScriptType = e.funding_script_type,
                        sellerRefundAddress = e.seller_refund_address,
                        buyerPeerId = e.buyer_peer_id,
                        sellerPeerId = e.seller_peer_id,
                        buyerBtcAddress = e.buyer_btc_address,
                        buyerPubKeyHex = e.buyer_pubkey_hex,
                        sellerPubKeyHex = e.seller_pubkey_hex,
                        sellerRefundAttestation = e.seller_refund_attestation,
                        buyerAddressAttestation = e.buyer_address_attestation,
                        offerId = e.offer_id,
                        tradeSats = e.trade_sats
                    )
                    if (e.resolved) resolvedSet.add(e.escrow_id)
                }
                val dbEvidence = disputeEvidenceDao.getAll()
                dbEvidence.groupBy { it.escrow_id }.forEach { (eid, list) ->
                    evidenceMap[eid] = list.map { ent ->
                        EvidencePiece(
                            escrowId = ent.escrow_id,
                            submitter = ent.submitter_peer_id,
                            description = ent.description,
                            imageBase64 = Base64.encodeToString(ent.image_data, Base64.NO_WRAP)
                        )
                    }.toMutableList()
                }
                if (dbDisputes.isNotEmpty()) {
                    Log.d(TAG, "Seeded ${dbDisputes.size} disputes from DB before LXMF")
                    publishState()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to seed from DB: ${e.message}")
            }

            // Also observe DB live (so P2POrchestrator.persisted disputes flow in without LXMF replay)
            launch {
                arbitratorDisputeDao.observeAll().collect { list ->
                    var changed = false
                    list.forEach { e ->
                        val mapped = ArbitratorDispute(
                            escrowId = e.escrow_id,
                            openedBy = e.opened_by,
                            reason = e.reason,
                            openedAt = e.opened_at,
                            redeemScriptHex = e.redeem_script_hex,
                            unsignedTxHex = e.psbt_hex,
                            refundTxHex = e.refund_tx_hex,
                            depositSats = e.deposit_sats,
                            fundingScriptType = e.funding_script_type,
                            sellerRefundAddress = e.seller_refund_address,
                            buyerPeerId = e.buyer_peer_id,
                            sellerPeerId = e.seller_peer_id,
                            buyerBtcAddress = e.buyer_btc_address,
                            buyerPubKeyHex = e.buyer_pubkey_hex,
                            sellerPubKeyHex = e.seller_pubkey_hex,
                            sellerRefundAttestation = e.seller_refund_attestation,
                            buyerAddressAttestation = e.buyer_address_attestation,
                            offerId = e.offer_id,
                            tradeSats = e.trade_sats
                        )
                        if (disputes[e.escrow_id] != mapped) {
                            disputes[e.escrow_id] = mapped
                            changed = true
                        }
                        if (e.resolved) resolvedSet.add(e.escrow_id)
                    }
                    if (changed || list.size != disputes.size) publishState()
                }
            }
            launch {
                disputeEvidenceDao.observeAll().collect { all ->
                    val grouped = all.groupBy { it.escrow_id }
                    var changed = false
                    grouped.forEach { (eid, list) ->
                        val pieces = list.map { ent ->
                            EvidencePiece(
                                escrowId = ent.escrow_id,
                                submitter = ent.submitter_peer_id,
                                description = ent.description,
                                imageBase64 = Base64.encodeToString(ent.image_data, Base64.NO_WRAP)
                            )
                        }
                        if (evidenceMap[eid]?.size != pieces.size) {
                            evidenceMap[eid] = pieces.toMutableList()
                            changed = true
                        }
                    }
                    if (changed) publishState()
                }
            }
        }
        // Initial empty success will be overwritten by DB seed above once isArb check passes
        if (_isArbitrator.value) {
            // Will be populated by DB seed; keep loading until then
        } else {
            _uiState.value = UiState.Success(emptyList(), emptyMap(), emptyMap())
        }
    }

    private fun publishState() {
        // Sorted by openedAt desc (Task 4) — newest disputes first.
        val sorted = disputes.values.sortedByDescending { it.openedAt }
        _uiState.value = UiState.Success(
            sorted,
            evidenceMap.mapValues { it.value.toList() },
            resolvedSet.associateWith { true }
        )
        Log.d(TAG, "publishState: ${sorted.size} disputes, ${evidenceMap.values.sumOf { it.size }} evidence, ${resolvedSet.size} resolved")
    }

    /**
     * Resolve a dispute: sign the payout/refund tx carried in the dispute
     * event with the arbitrator key (derived from THIS admin identity), then
     * publish LXMF resolution message so the winning party can broadcast with 2-of-3.
     */
    fun resolve(
        escrowId: String,
        dispute: ArbitratorDispute,
        decision: ResolutionDecision,
        notes: String
    ) {
        if (escrowId in _busyEscrowIds.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busyEscrowIds.update { it + escrowId }
            _error.value = null
            try {
                val redeem = dispute.redeemScriptHex ?: throw IllegalStateException("No redeem script in dispute")
                // Decision selects WHICH unsigned tx to sign: the payout
                // (psbt_hex) for RELEASE_TO_BUYER, the pre-built refund
                // (refund_tx_hex) for REFUND_TO_SELLER. A pre-payout dispute
                // only carries the refund tx, so Release is impossible there
                // (the UI hides it).
                val txHex = when (decision) {
                    ResolutionDecision.RELEASE_TO_BUYER ->
                        dispute.unsignedTxHex ?: throw IllegalStateException("No unsigned payout tx in dispute")
                    ResolutionDecision.REFUND_TO_SELLER ->
                        // NEVER fall back to the payout tx: signing the payout
                        // as a "refund" would pay the BUYER while the parties
                        // record REFUNDED — a money-path inversion. The
                        // opening party ships refund_tx_hex whenever no payout
                        // exists; a dispute opened from a payout state has no
                        // refund tx by design, so refund is not arbitrable
                        // remotely.
                        dispute.refundTxHex
                            ?: throw IllegalStateException("No unsigned refund tx in dispute — cannot rule a refund")
                }
                // F2: refuse to sign a tx whose destinations are not the attested role
                // destinations. Legacy disputes (no attestations) are refused outright.
                val net = escrowService.networkParameters()
                val guardVerdict = when (decision) {
                    ResolutionDecision.REFUND_TO_SELLER -> {
                        val addr = dispute.sellerRefundAddress
                        if (addr.isNullOrBlank() || dispute.sellerPubKeyHex.isNullOrBlank() ||
                            !RoleAddressAttestation.verify(
                                dispute.sellerPubKeyHex, RoleAddressAttestation.KIND_SELLER_REFUND,
                                escrowId, addr, dispute.sellerRefundAttestation.orEmpty()
                            )
                        ) {
                            throw IllegalStateException("Refund destination is not attested by the seller key — refusing to sign")
                        }
                        ResolutionGuard.validateRefund(
                            org.bitcoinj.core.Transaction(net, hexToBytes(txHex)), net,
                            ResolutionGuard.RefundExpectation(addr, dispute.depositSats ?: 0L, feeCeiling(dispute.depositSats))
                        )
                    }
                    ResolutionDecision.RELEASE_TO_BUYER -> {
                        val buyerAddr = dispute.buyerBtcAddress
                        if (buyerAddr.isNullOrBlank() || dispute.buyerPubKeyHex.isNullOrBlank() || dispute.offerId.isNullOrBlank() ||
                            !RoleAddressAttestation.verify(
                                dispute.buyerPubKeyHex, RoleAddressAttestation.KIND_BUYER_PAYOUT,
                                dispute.offerId, buyerAddr, dispute.buyerAddressAttestation.orEmpty()
                            )
                        ) {
                            throw IllegalStateException("Payout destination is not attested by the buyer key — refusing to sign")
                        }
                        ResolutionGuard.validateRelease(
                            org.bitcoinj.core.Transaction(net, hexToBytes(txHex)), net,
                            ResolutionGuard.ReleaseExpectation(buyerAddr, NeoP2PConfig.FEE_WALLET_ADDRESS, dispute.sellerRefundAddress, dispute.tradeSats ?: 0L)
                        )
                    }
                }
                if (!guardVerdict.ok) throw IllegalStateException("Resolution blocked: ${guardVerdict.reason}")
                // F2 defence in depth: the attested role key must actually be one of
                // the escrow's redeem-script keys (a swapped key in the dispute row
                // would otherwise slip past the attestation check). Skip when the
                // script cannot be parsed — the attestation gate above is primary.
                val roleKey = when (decision) {
                    ResolutionDecision.REFUND_TO_SELLER -> dispute.sellerPubKeyHex
                    ResolutionDecision.RELEASE_TO_BUYER -> dispute.buyerPubKeyHex
                }
                if (roleKey.isNullOrBlank() || !redeemScriptHasKey(redeem, roleKey)) {
                    throw IllegalStateException("Role key is not a key of the escrow redeem script — refusing to sign")
                }
                val arbPriv = identityManager.getArbitratorPrivateKeyHex()
                val sig = escrowService.arbitratorSignTx(
                    txHex, redeem, arbPriv,
                    depositSats = dispute.depositSats,
                    fundingScriptType = dispute.fundingScriptType
                ).getOrThrow()
                // The EXACT final tx the arbitrator signed travels with the
                // resolution so the party broadcasts THIS tx (a locally
                // rebuilt refund would carry a different fee rate and the
                // arbitrator's signature would not verify). The unsigned hex
                // plus the arbitrator's DER sig is sufficient for bitcoinj to
                // assemble the spend on the party side.
                // Phase 4: deliver the resolution to both parties over LXMF
                // (RNS path) so they can broadcast the 2-of-3.
                // v23 (2026-09-02): the arbitrator has NO local escrow row, so
                // the delivery targets come from the DISPUTE ROW (the parties
                // carried by the dispute event) — pre-v23 the targets were
                // derived from escrowService.getEscrow() which returns null on
                // the arbitrator's device, so the resolution was sent to
                // NOBODY and the funds stayed locked in the multisig forever.
                val escrow = escrowService.getEscrow(escrowId)
                val parties = listOfNotNull(
                    escrow?.buyerPeerId,
                    escrow?.sellerPeerId,
                    dispute.buyerPeerId,
                    dispute.sellerPeerId
                ).distinct()
                if (parties.isEmpty()) {
                    _error.value = "No resolution targets — dispute event carried no parties"
                    return@launch
                }
                var delivered = true
                val failedTargets = mutableListOf<String>()
                for (party in parties) {
                    val ok = rnsTransport.sendResolution(
                        toPeerId = party,
                        escrowId = escrowId,
                        decision = decision.name,
                        arbitratorSigHex = sig,
                        notes = notes,
                        sellerRefundAddress = dispute.sellerRefundAddress,
                        signedTxHex = txHex
                    ).isSuccess
                    if (ok) delivered = ok else failedTargets.add(party)
                }
                if (!delivered) {
                    // Slice 3: a resolution that fails at send time must not be
                    // lost — persist it for the 60s sweep retry (the receiving
                    // party skips it once this dispute is marked resolved).
                    if (failedTargets.isNotEmpty()) {
                        pendingArbitrationStore.saveResolution(
                            com.neop2p.data.local.PendingArbitrationStore.PendingResolution(
                                escrowId = escrowId,
                                decision = decision.name,
                                arbitratorSigHex = sig,
                                notes = notes,
                                sellerRefundAddress = dispute.sellerRefundAddress,
                                signedTxHex = txHex,
                                targets = failedTargets
                            )
                        )
                    }
                    // The parties never received the resolution — do NOT mark
                    // it resolved. Surface the failure so the arbitrator can
                    // retry (busy flips false and the card stays actionable).
                    _error.value = "Resolution delivery failed (LXMF) — saved for auto-retry"
                    return@launch
                }
                resolvedSet.add(escrowId)
                try { arbitratorDisputeDao.markResolved(escrowId) } catch (_: Exception) {}
                _error.value = null
                publishState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve dispute $escrowId", e)
                _error.value = e.message ?: "Resolution failed"
            } finally {
                _busyEscrowIds.update { it - escrowId }
            }
        }
    }

    /** F2: a hostile opener must not burn the refund difference into the miner fee. */
    private fun feeCeiling(depositSats: Long?) = maxOf((depositSats ?: 0L) / 100, 5_000L)

    fun txOutputs(txHex: String): List<String> = runCatching {
        val net = escrowService.networkParameters()
        ResolutionGuard.outputSummaries(org.bitcoinj.core.Transaction(net, hexToBytes(txHex)), net)
    }.getOrDefault(emptyList())

    /** F2: x-only form — accepts compressed (33B), uncompressed (65B) or x-only (32B) keys. */
    private fun xOnly(pubHex: String): String {
        val bytes = hexToBytes(pubHex)
        val x = when (bytes.size) {
            33, 65 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> bytes
        }
        return x.joinToString("") { "%02x".format(it) }
    }

    /** F2 defence in depth: is [roleKey] one of the redeem script's keys? Unparseable → skip. */
    private fun redeemScriptHasKey(redeemHex: String, roleKey: String): Boolean = try {
        org.bitcoinj.script.Script(hexToBytes(redeemHex)).pubKeys.any { xOnly(it.publicKeyAsHex) == xOnly(roleKey) }
    } catch (_: Exception) {
        true
    }

    private fun hexToBytes(hex: String): ByteArray {
        val data = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    companion object {
        private const val TAG = "DisputeFeedViewModel"
    }
}
