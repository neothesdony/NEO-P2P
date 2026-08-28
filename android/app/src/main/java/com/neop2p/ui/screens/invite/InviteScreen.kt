package com.neop2p.ui.screens.invite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.neop2p.R
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.util.ErrorCodes
import com.neop2p.ui.util.generateQrCode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: InviteViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) } // 0 = show QR, 1 = scan / paste
    var pastedLink by remember { mutableStateOf("") }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            viewModel.onScanCancelled()
        } else {
            viewModel.parseAndConnect(contents)
        }
    }

    fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("NEO-P2P invite", text)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(clip)
    }

    NeoP2PTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.invite_title)) },
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
                        .verticalScroll(rememberScrollState())
                ) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = { Text(stringResource(R.string.invite_show_qr)) }
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text(stringResource(R.string.invite_scan)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    when (tab) {
                        0 -> ShowQrTab(
                            link = viewModel.myInviteLink(),
                            onCopy = {
                                copyToClipboard(viewModel.myInviteLink())
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.invite_link_copied))
                                }
                            }
                        )
                        else -> ScanPasteTab(
                            pastedLink = pastedLink,
                            onPastedLinkChange = { pastedLink = it },
                            onConnect = { viewModel.parseAndConnect(pastedLink) },
                            onScan = {
                                val options = ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt(context.getString(R.string.invite_scan_prompt))
                                    setBeepEnabled(false)
                                }
                                scanLauncher.launch(options)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Result states
                    when (val s = state) {
                        is InviteViewModel.UiState.Validating -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(stringResource(R.string.invite_connecting))
                            }
                        }
                        is InviteViewModel.UiState.Success -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(
                                            if (s.alreadyKnown) R.string.invite_already_connected
                                            else R.string.invite_success,
                                            s.peerId.take(16)
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                        is InviteViewModel.UiState.Error -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = stringResource(s.messageRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    if (s.messageRes == R.string.invite_invalid_qr) {
                                        Text(
                                            text = stringResource(R.string.error_code_line, ErrorCodes.ERR_INVALID_QR),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        )
    }
}

@Composable
private fun ShowQrTab(link: String, onCopy: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.invite_show_qr_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        val bitmap: Bitmap? = remember(link) { generateQrCode(link) }
        if (bitmap != null) {
            Card(
                modifier = Modifier.size(260.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.invite_qr_description),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                )
            }
        } else {
            Text(stringResource(R.string.invite_qr_failed))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = link,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onCopy) {
            Text(stringResource(R.string.invite_copy_link))
        }
    }
}

@Composable
private fun ScanPasteTab(
    pastedLink: String,
    onPastedLinkChange: (String) -> Unit,
    onConnect: () -> Unit,
    onScan: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = pastedLink,
            onValueChange = onPastedLinkChange,
            label = { Text(stringResource(R.string.invite_paste_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(
                onClick = onConnect,
                enabled = pastedLink.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.invite_connect))
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.invite_scan))
            }
        }
    }
}
