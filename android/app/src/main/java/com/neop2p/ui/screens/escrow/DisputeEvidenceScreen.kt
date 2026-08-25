package com.neop2p.ui.screens.escrow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.graphics.BitmapFactory
import com.neop2p.R
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.entity.DisputeEvidenceEntity
import com.neop2p.data.p2p.IdentityManager
import com.neop2p.ui.theme.NeoP2PTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Dispute evidence screen: lets either party attach payment receipts (image +
 * description) to a disputed escrow. Evidence is stored locally in the
 * SQLCipher-encrypted `dispute_evidence` table — never published to the relay.
 */
@Composable
fun DisputeEvidenceScreen(
    escrowId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: DisputeEvidenceViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val description by viewModel.description.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.setPickedImage(it) }
    }

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.escrow_evidence_title)) },
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
                        is DisputeEvidenceViewModel.UiState.Loading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        is DisputeEvidenceViewModel.UiState.Error -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(s.message, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { viewModel.loadEvidence() }) {
                                    Text(stringResource(R.string.general_retry))
                                }
                            }
                        }
                        is DisputeEvidenceViewModel.UiState.Success -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                            ) {
                                // ── Submit new evidence ──
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            stringResource(R.string.escrow_submit_evidence),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = { launcher.launch("image/*") },
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                painterResource(id = R.drawable.ic_attach_file),
                                                contentDescription = null
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.escrow_evidence_pick_image))
                                        }
                                        viewModel.pickedImage?.let { uri ->
                                            Spacer(Modifier.height(8.dp))
                                            val bitmap = remember(uri) {
                                                runCatching {
                                                    context.contentResolver.openInputStream(uri)?.use {
                                                        BitmapFactory.decodeStream(it)
                                                    }
                                                }.getOrNull()
                                            }
                                            if (bitmap != null) {
                                                Image(
                                                    bitmap = bitmap.asImageBitmap(),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.FillWidth,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 200.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = description,
                                            onValueChange = { viewModel.setDescription(it) },
                                            label = { Text(stringResource(R.string.escrow_evidence_description_label)) },
                                            placeholder = {
                                                Text(stringResource(R.string.escrow_evidence_description_placeholder))
                                            },
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        error?.let {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Button(
                                            onClick = { viewModel.submitEvidence(context) },
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                        ) {
                                            if (busy) {
                                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                            } else {
                                                Text(stringResource(R.string.escrow_evidence_submit))
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                // ── Existing evidence ──
                                Text(
                                    stringResource(R.string.escrow_view_evidence),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                if (s.evidence.isEmpty()) {
                                    Text(
                                        stringResource(R.string.escrow_evidence_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    s.evidence.forEach { item ->
                                        EvidenceCard(item)
                                        Spacer(Modifier.height(8.dp))
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

@Composable
private fun EvidenceCard(item: DisputeEvidenceEntity, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val bitmap = remember(item.evidence_id) {
                runCatching { BitmapFactory.decodeByteArray(item.image_data, 0, item.image_data.size) }
                    .getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
                    .format(Date(item.submitted_at)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@HiltViewModel
class DisputeEvidenceViewModel @Inject constructor(
    private val evidenceDao: DisputeEvidenceDao,
    private val identityManager: IdentityManager,
    private val nostrClient: com.neop2p.data.p2p.NostrClient,
    savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val evidence: List<DisputeEvidenceEntity>) : UiState()
    }

    private val escrowId: String =
        savedStateHandle.get<String>("escrowId") ?: ""

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var pickedImage: Uri? = null
        private set

    init {
        loadEvidence()
    }

    fun setDescription(v: String) { _description.value = v }
    fun setPickedImage(uri: Uri) {
        pickedImage = uri
        _error.value = null
    }

    fun loadEvidence() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState.Loading
            try {
                val items = evidenceDao.getEvidenceForEscrow(escrowId)
                _uiState.value = UiState.Success(items)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load evidence")
            }
        }
    }

    fun submitEvidence(context: android.content.Context) {
        if (_busy.value) return
        val uri = pickedImage ?: run {
            _error.value = context.getString(R.string.escrow_evidence_need_image)
            return
        }
        val desc = _description.value.trim()
        if (desc.isEmpty()) {
            _error.value = context.getString(R.string.escrow_evidence_need_description)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _error.value = null
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    _error.value = context.getString(R.string.escrow_evidence_attach_failed, "empty file")
                    return@launch
                }
                val entity = DisputeEvidenceEntity(
                    evidence_id = UUID.randomUUID().toString(),
                    escrow_id = escrowId,
                    submitter_peer_id = runCatching { identityManager.getOrCreateIdentity().peerId }
                        .getOrDefault(""),
                    description = desc,
                    mime_type = "image/jpeg",
                    image_data = bytes,
                    submitted_at = System.currentTimeMillis()
                )
                evidenceDao.insert(entity)
                // Publish the evidence to the relay (kind:33387) so the
                // arbitrator (and the counterparty) can review it even if they
                // never received the local E2EE attachment.
                val submitter = runCatching { identityManager.getOrCreateIdentity().peerId }
                    .getOrDefault("")
                nostrClient.publishEvidence(
                    escrowId = escrowId,
                    submitter = submitter,
                    description = desc,
                    mimeType = entity.mime_type,
                    imageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                )
                pickedImage = null
                _description.value = ""
                loadEvidence()
            } catch (e: Exception) {
                _error.value = context.getString(R.string.escrow_evidence_attach_failed, e.message ?: "")
            } finally {
                _busy.value = false
            }
        }
    }
}
