package com.neop2p.ui.screens.legal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.neop2p.R

enum class LegalDocument { TERMS, PRIVACY }

/**
 * Static, in-app legal text (Phase 3, 2026-09-23). No external hosting — the
 * user decision was to bundle the text so it is always available offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(
    doc: LegalDocument,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when (doc) {
        LegalDocument.TERMS -> R.string.legal_terms_title
        LegalDocument.PRIVACY -> R.string.legal_privacy_title
    }
    val body = when (doc) {
        LegalDocument.TERMS -> R.string.terms_body
        LegalDocument.PRIVACY -> R.string.legal_privacy_body
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(title),
                        modifier = Modifier.semantics { heading() }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.general_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}
