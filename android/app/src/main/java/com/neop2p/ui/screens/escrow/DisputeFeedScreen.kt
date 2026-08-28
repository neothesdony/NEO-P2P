package com.neop2p.ui.screens.escrow

import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.data.p2p.NostrClient
import com.neop2p.domain.model.ResolutionDecision
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.inject.Inject

/**
 * Arbitrator Mode dispute feed. Only reachable when the active identity IS
 * the arbitrator (its derived arbitrator key matches
 * [NeoP2PConfig.ARBITRATOR_PUBKEY]).
 *
 * Shows disputes received over the relay (kind:33386), the evidence parties
 * attached (kind:33387), and lets the arbitrator sign + publish a resolution
 * (kind:33388) — the parties then broadcast the payout/refund with the
 * arbitrator's signature (2-of-3).
 */
@Composable
fun DisputeFeedScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: DisputeFeedViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val busyEscrowId by viewModel.busyEscrowId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

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
                            if (s.disputes.isEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(16.dp)
                                ) {
                                    error?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    s.disputes.forEach { d ->
                                        DisputeCard(
                                            dispute = d,
                                            evidence = s.evidence[d.escrowId].orEmpty(),
                                            resolved = s.resolved[d.escrowId] ?: false,
                                            busy = busy,
                                            busyThisCard = busyEscrowId == d.escrowId,
                                            onResolve = { decision, notes ->
                                                viewModel.resolve(d.escrowId, d, decision, notes)
                                            }
                                        )
                                        Spacer(Modifier.height(12.dp))
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

/** A dispute as seen by the arbitrator (from kind:33386 + evidence + resolution). */
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
    val sellerRefundAddress: String? = null
)

@Composable
private fun DisputeCard(
    dispute: ArbitratorDispute,
    evidence: List<EvidencePiece>,
    resolved: Boolean,
    busy: Boolean,
    busyThisCard: Boolean,
    onResolve: (ResolutionDecision, String) -> Unit,
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
                Text(
                    text = dispute.escrowId,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.arbitrator_opened_by, dispute.openedBy.take(12)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    private val nostrClient: NostrClient,
    private val identityManager: IdentityManager,
    private val escrowService: EscrowService
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

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // Which dispute is currently being resolved (per-card spinner). The
    // global _busy still gates re-entry; this lets the UI spin only the
    // card whose button was tapped instead of every card.
    private val _busyEscrowId = MutableStateFlow<String?>(null)
    val busyEscrowId: StateFlow<String?> = _busyEscrowId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // In-memory arbitration state: disputes, evidence, resolutions received
    // since the feed opened. The relay replays the last 200 arbitration events
    // on connect, so the feed self-populates from the network (no DB table).
    private val disputes = LinkedHashMap<String, ArbitratorDispute>()
    private val evidenceMap = LinkedHashMap<String, MutableList<EvidencePiece>>()
    private val resolvedSet = mutableSetOf<String>()

    init {
        collect()
    }

    fun refresh() {
        _uiState.value = UiState.Loading
        _error.value = null
        publishState()
    }

    private fun collect() {
        viewModelScope.launch {
            // Only the arbitrator should be able to open this screen, but
            // double-check at the data layer too.
            val isArb = runCatching {
                identityManager.getArbitratorPubKeyHex()
                    .equals(NeoP2PConfig.ARBITRATOR_PUBKEY, ignoreCase = true)
            }.getOrDefault(false)
            if (!isArb) {
                _uiState.value = UiState.Error("This identity is not the arbitrator")
                return@launch
            }

            // Disputes
            nostrClient.disputes.collect { obj ->
                val escrowId = obj["escrow_id"]?.jsonPrimitive?.content ?: return@collect
                disputes[escrowId] = ArbitratorDispute(
                    escrowId = escrowId,
                    openedBy = obj["opened_by"]?.jsonPrimitive?.content ?: "",
                    reason = obj["reason"]?.jsonPrimitive?.content ?: "",
                    openedAt = obj["opened_at"]?.jsonPrimitive?.long ?: 0L,
                    redeemScriptHex = obj["redeem_script_hex"]?.jsonPrimitive?.content,
                    unsignedTxHex = obj["psbt_hex"]?.jsonPrimitive?.content,
                    refundTxHex = obj["refund_tx_hex"]?.jsonPrimitive?.content,
                    depositSats = obj["deposit_sats"]?.jsonPrimitive?.long,
                    fundingScriptType = obj["funding_script_type"]?.jsonPrimitive?.content,
                    sellerRefundAddress = obj["seller_refund_address"]?.jsonPrimitive?.content
                )
                publishState()
            }
        }
        viewModelScope.launch {
            nostrClient.evidence.collect { evp ->
                val escrowId = evp["escrow_id"]?.jsonPrimitive?.content ?: return@collect
                val item = EvidencePiece(
                    escrowId = escrowId,
                    submitter = evp["submitter"]?.jsonPrimitive?.content ?: "",
                    description = evp["description"]?.jsonPrimitive?.content ?: "",
                    imageBase64 = evp["image_base64"]?.jsonPrimitive?.content ?: ""
                )
                evidenceMap.getOrPut(escrowId) { mutableListOf() }.add(item)
                publishState()
            }
        }
        viewModelScope.launch {
            nostrClient.resolutions.collect { evp ->
                val escrowId = evp["escrow_id"]?.jsonPrimitive?.content ?: return@collect
                resolvedSet.add(escrowId)
                publishState()
            }
        }
        _uiState.value = UiState.Success(emptyList(), emptyMap(), emptyMap())
    }

    private fun publishState() {
        _uiState.value = UiState.Success(
            disputes.values.toList(),
            evidenceMap.mapValues { it.value.toList() },
            resolvedSet.associateWith { true }
        )
    }

    /**
     * Resolve a dispute: sign the payout/refund tx carried in the dispute
     * event with the arbitrator key (derived from THIS admin identity), then
     * publish kind:33388 so the winning party can broadcast with 2-of-3.
     */
    fun resolve(
        escrowId: String,
        dispute: ArbitratorDispute,
        decision: ResolutionDecision,
        notes: String
    ) {
        if (_busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _busyEscrowId.value = escrowId
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
                val published = nostrClient.publishResolution(
                    escrowId = escrowId,
                    decision = decision.name,
                    arbitratorSigHex = sig,
                    notes = notes,
                    sellerRefundAddress = dispute.sellerRefundAddress,
                    signedTxHex = txHex
                )
                if (published.isFailure) {
                    // The parties never received the resolution — do NOT mark
                    // it resolved. Surface the failure so the arbitrator can
                    // retry (busy flips false and the card stays actionable).
                    _error.value = published.exceptionOrNull()?.message
                        ?: "Resolution publish failed"
                    return@launch
                }
                resolvedSet.add(escrowId)
                _error.value = null
                publishState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve dispute $escrowId", e)
                _error.value = e.message ?: "Resolution failed"
            } finally {
                _busy.value = false
                _busyEscrowId.value = null
            }
        }
    }

    companion object {
        private const val TAG = "DisputeFeedViewModel"
    }
}
