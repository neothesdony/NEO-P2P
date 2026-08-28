package com.neop2p.ui.screens.escrow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neop2p.R
import com.neop2p.ui.theme.NeoP2PTheme
import com.neop2p.ui.util.formatIdr
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Max edge for the receipt screenshot before JPEG compression. */
private const val MAX_IMAGE_EDGE = 1600
/** Hard cap for the base64 image carried in the E2EE chat payload. */
private const val MAX_IMAGE_BYTES = 60 * 1024

@Composable
fun ReceiptComposerScreen(
    escrowId: String,
    onBack: () -> Unit,
    onSent: () -> Unit,
    viewModel: ReceiptComposerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(escrowId) { viewModel.load(escrowId) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                viewModel.setImage(compressReceiptImage(context.contentResolver, it))
            }
        }
    }

    NeoP2PTheme {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.escrow_receipt_title)) },
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
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (state.loading) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        return@Column
                    }

                    // Restored-draft banner: the buyer was composing a receipt
                    // for this escrow and left (back-nav) or the app was killed.
                    // Reference + screenshot were restored — offer a fresh start.
                    if (state.hasDraft) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    stringResource(R.string.escrow_receipt_draft_restored),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { viewModel.discardDraft(escrowId) },
                                    enabled = !state.sending
                                ) {
                                    Text(stringResource(R.string.escrow_receipt_draft_discard))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // ── Card 1: reference code ──
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.escrow_receipt_reference_label),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.reference,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { viewModel.regenerateReference() },
                                    enabled = !state.sending
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_refresh),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.escrow_receipt_regenerate))
                                }
                            }
                            Text(
                                stringResource(R.string.escrow_receipt_reference_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Card 2: prefilled amount + method (read-only) ──
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.escrow_receipt_payment_label),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(
                                    R.string.escrow_receipt_amount_idr,
                                    if (state.fiatAmount > 0L) formatIdr(state.fiatAmount) else "—"
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.escrow_receipt_amount_label, state.amountSats),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.escrow_receipt_method_label, state.method.ifEmpty { "—" }),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Image attach row ──
                    OutlinedButton(
                        onClick = { pickImage.launch("image/*") },
                        enabled = !state.sending,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painterResource(id = R.drawable.ic_attach_file),
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.escrow_receipt_attach))
                    }

                    state.imageBase64?.let { b64 ->
                        Spacer(Modifier.height(8.dp))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                val bitmap = remember(b64) { decodeBase64Thumbnail(b64) }
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = stringResource(R.string.escrow_receipt_attach_preview),
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 200.dp)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = { viewModel.setImage(null) },
                                    enabled = !state.sending,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(stringResource(R.string.escrow_receipt_remove_image))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    state.error?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    if (state.imageBase64 == null) {
                        Text(
                            stringResource(R.string.escrow_receipt_attach_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Button(
                        onClick = {
                            viewModel.send(
                                escrowId = escrowId,
                                offerId = state.offerId,
                                peerId = state.sellerPeerId
                            )
                        },
                        enabled = !state.sending && state.offerId.isNotBlank() && state.imageBase64 != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (state.sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.escrow_receipt_send))
                        }
                    }

                    // Navigate back once the receipt is recorded + E2EE-sent.
                    LaunchedEffect(state.sent) {
                        if (state.sent) onSent()
                    }
                }
            }
        )
    }
}

/**
 * Decode + downsample the attached screenshot to ≤1600px and re-encode as a
 * ≤60KB JPEG, then return the base64 string. Compression runs on Dispatchers.IO
 * so large gallery images never jank the UI.
 */
private suspend fun compressReceiptImage(
    resolver: android.content.ContentResolver,
    uri: Uri
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (edge / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@runCatching null
        val out = ByteArrayOutputStream()
        var quality = 85
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        // Tighten quality until we fit the 60KB cap (keeps the E2EE payload small).
        while (out.size() > MAX_IMAGE_BYTES && quality > 30) {
            out.reset()
            quality -= 10
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        val bytes = out.toByteArray()
        bitmap.recycle()
        if (bytes.size > MAX_IMAGE_BYTES) return@runCatching null
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()
}

/** Decode the stored base64 JPEG back into a bitmap for preview. */
private fun decodeBase64Thumbnail(base64: String): Bitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
