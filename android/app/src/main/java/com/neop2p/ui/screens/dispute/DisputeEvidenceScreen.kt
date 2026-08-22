package com.neop2p.ui.screens.dispute

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neop2p.R
import com.neop2p.data.local.AppDatabase
import com.neop2p.data.local.dao.DisputeEvidenceDao
import com.neop2p.data.local.entity.DisputeEvidenceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ─── Evidence Screen ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisputeEvidenceScreen(
    escrowId: String,
    submitterPeerId: String,
    onBack: () -> Unit,
    viewModel: DisputeEvidenceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(escrowId) {
        viewModel.loadEvidence(escrowId)
    }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImageSelected(it, context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dispute_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.general_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ─── Upload Section ─────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.dispute_submit_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dispute_upload_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    // Image preview
                    uiState.selectedImageUri?.let { uri ->
                        val previewBitmap = remember(uri) {
                            try {
                                val inputStream = context.contentResolver.openInputStream(uri)
                                val bitmap = BitmapFactory.decodeStream(inputStream)
                                inputStream?.close()
                                bitmap
                            } catch (_: Exception) { null }
                        }
                        if (previewBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                Image(
                                    bitmap = previewBitmap.asImageBitmap(),
                                    contentDescription = stringResource(R.string.dispute_cd_selected),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // Description field
                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = viewModel::onDescriptionChanged,
                        label = { Text(stringResource(R.string.dispute_desc_label)) },
                        placeholder = { Text(stringResource(R.string.dispute_desc_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                    Spacer(Modifier.height(12.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.dispute_choose_photo))
                        }
                        Button(
                            onClick = {
                                viewModel.submitEvidence(
                                    escrowId = escrowId,
                                    submitterPeerId = submitterPeerId,
                                    context = context
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = uiState.selectedImageUri != null && uiState.description.isNotBlank()
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.dispute_submit))
                        }
                    }
                }
            }

            // ─── Submitted Evidence List ────────────────────────
            Text(
                text = stringResource(R.string.dispute_submitted_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.evidenceList.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.dispute_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.evidenceList, key = { it.evidence_id }) { evidence ->
                            val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.forLanguageTag("id-ID")) }
                            EvidenceCard(
                                evidence = evidence,
                                dateFormat = dateFormat,
                                context = context
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Evidence Card ────────────────────────────────────────────

@Composable
private fun EvidenceCard(
    evidence: DisputeEvidenceEntity,
    dateFormat: SimpleDateFormat,
    context: android.content.Context
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = evidence.description,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.dispute_cd_toggle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = dateFormat.format(Date(evidence.submitted_at)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded && evidence.image_data.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val bitmap = remember(evidence.image_data) {
                    BitmapFactory.decodeByteArray(evidence.image_data, 0, evidence.image_data.size)
                }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = evidence.description,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}

// ─── ViewModel ────────────────────────────────────────────────

@HiltViewModel
class DisputeEvidenceViewModel @Inject constructor(
    private val db: AppDatabase,
    @ApplicationContext private val appContext: android.content.Context
) : ViewModel() {
    private val evidenceDao: DisputeEvidenceDao = db.disputeEvidenceDao()

    data class UiState(
        val isLoading: Boolean = false,
        val evidenceList: List<DisputeEvidenceEntity> = emptyList(),
        val selectedImageUri: Uri? = null,
        val description: String = "",
        val uploadError: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun loadEvidence(escrowId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val list = evidenceDao.getEvidenceForEscrow(escrowId)
                _uiState.update { it.copy(isLoading = false, evidenceList = list) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onImageSelected(uri: Uri, context: android.content.Context) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    fun onDescriptionChanged(text: String) {
        _uiState.update { it.copy(description = text) }
    }

    fun submitEvidence(escrowId: String, submitterPeerId: String, context: android.content.Context) {
        val uri = _uiState.value.selectedImageUri ?: return
        val desc = _uiState.value.description.trim()
        if (desc.isBlank()) return

        viewModelScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val imageBytes = inputStream?.readBytes()
                inputStream?.close()

                if (imageBytes == null || imageBytes.isEmpty()) {
                    _uiState.update { it.copy(uploadError = "Failed to read image") }
                    return@launch
                }

                val entity = DisputeEvidenceEntity(
                    evidence_id = "ev_${escrowId}_${System.currentTimeMillis()}",
                    escrow_id = escrowId,
                    submitter_peer_id = submitterPeerId,
                    description = desc,
                    mime_type = context.contentResolver.getType(uri) ?: "image/jpeg",
                    image_data = imageBytes,
                    submitted_at = System.currentTimeMillis()
                )

                evidenceDao.insert(entity)

                // Clear form & refresh
                val list = evidenceDao.getEvidenceForEscrow(escrowId)
                _uiState.update {
                    it.copy(
                        evidenceList = list,
                        selectedImageUri = null,
                        description = "",
                        uploadError = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(uploadError = "Upload failed: ${e.message}") }
            }
        }
    }
}
