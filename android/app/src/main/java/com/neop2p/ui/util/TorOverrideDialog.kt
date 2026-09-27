package com.neop2p.ui.util

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.neop2p.R
import com.neop2p.data.network.TorState
import com.neop2p.data.network.TorVerdict

/** Pure: should a blocked HTTP action offer the explicit direct override? */
object TorBlockDecision {
    fun shouldOfferOverride(enabled: Boolean, state: TorState): Boolean =
        com.neop2p.data.network.TorGate.verdict(enabled, state, override = false) == TorVerdict.BLOCK
}

@Composable
fun TorOverrideDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tor_override_title)) },
        text = { Text(stringResource(R.string.tor_override_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.tor_override_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tor_override_cancel)) } },
    )
}
