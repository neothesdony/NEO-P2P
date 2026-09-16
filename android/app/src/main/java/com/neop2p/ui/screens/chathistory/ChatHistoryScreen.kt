package com.neop2p.ui.screens.chathistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neop2p.R
import com.neop2p.ui.components.NeoEmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Home "Chats" list: every conversation this device has messages for,
 * newest first, including finished trades — whose chat has no other UI path
 * (a terminal escrow opens the escrow detail, not the chat).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    onOpenChat: (String, String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: ChatHistoryViewModel = hiltViewModel()
    val threads by viewModel.threads.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.chats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.general_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (threads.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                NeoEmptyState(
                    painter = painterResource(id = R.drawable.ic_send),
                    title = stringResource(R.string.chats_empty),
                    modifier = Modifier.fillMaxSize().align(Alignment.Center)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(threads, key = { it.offerId }) { thread ->
                    ChatThreadRow(
                        thread = thread,
                        onClick = { onOpenChat(thread.offerId, thread.peerId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatThreadRow(thread: ChatThread, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = shortenPeerId(thread.peerId),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(threadStatusLabel(thread.status)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatThreadDate(thread.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (thread.unread > 0) {
                Badge {
                    Text(if (thread.unread > 99) "99+" else thread.unread.toString())
                }
            }
        }
    }
}

private fun threadStatusLabel(status: String): Int = when (status) {
    "COMPLETED" -> R.string.history_success
    "CANCELLED" -> R.string.history_cancelled
    else -> R.string.history_active
}

private fun shortenPeerId(peerId: String): String =
    if (peerId.length > 16) "${peerId.take(8)}…${peerId.takeLast(6)}" else peerId

private fun formatThreadDate(epochMillis: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))
