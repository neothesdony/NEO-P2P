package com.neop2p.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neop2p.R
import com.neop2p.domain.model.Offer
import com.neop2p.state.HomeUiState
import com.neop2p.state.HomeViewModel
import com.neop2p.ui.theme.NeoP2pTheme
import com.neop2p.ui.util.LoadingState
import com.neop2p.ui.util.showErrorSnackbar

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(),
    onCreateOfferClick: () -> Unit = {},
    onOfferClick: (Offer) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scaffoldState = rememberScaffoldState()
    val scope = rememberCoroutineScope()

    when (uiState) {
        is HomeUiState.Loading -> {
            HomeContent(
                offers = emptyList(),
                onCreateOfferClick = onCreateOfferClick,
                onOfferClick = onOfferClick,
                isLoading = true
            )
        }
        is HomeUiState.Success -> {
            HomeContent(
                offers = uiState.offers,
                onCreateOfferClick = onCreateOfferClick,
                onOfferClick = onOfferClick,
                isLoading = false
            )
        }
        is HomeUiState.Error -> {
            HomeContent(
                offers = emptyList(),
                onCreateOfferClick = onCreateOfferClick,
                onOfferClick = onOfferClick,
                isLoading = false
            )
            // Show error snackbar
            LaunchedEffect(uiState) {
                scope.launch {
                    delay(100)
                    showErrorSnackbar(scaffoldState.snackbarHostState, uiState.message)
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    offers: List<Offer>,
    onCreateOfferClick: () -> Unit,
    onOfferClick: (Offer) -> Unit,
    isLoading: Boolean
) {
    NeoP2pTheme {
        Scaffold(
            scaffoldState = scaffoldState,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(text = "NEO-P2P") }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = onCreateOfferClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    tonalElevation = 4.dp
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create Offer"
                    )
                }
            }
        ) { padding ->
            HomeBodyContent(
                padding = padding,
                offers = offers,
                onOfferClick = onOfferClick,
                isLoading = isLoading
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HomeBodyContent(
    padding: PaddingValues,
    offers: List<Offer>,
    onOfferClick: (Offer) -> Unit,
    isLoading: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        if (isLoading) {
            LoadingState()
        } else if (offers.isEmpty()) {
            EmptyState()
        } else {
            OffersList(
                offers = offers,
                onOfferClick = onOfferClick
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OffersList(
    offers: List<Offer>,
    onOfferClick: (Offer) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(offers) { offer ->
            OfferItem(
                offer = offer,
                onClick = { onOfferClick(offer) }
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OfferItem(
    offer: Offer,
    onClick: () -> Unit
) {
    val isBuying = offer.isBuying
    val actionText = if (isBuying) "Buying" else "Selling"
    val actionColor = if (isBuying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${offer.asset} ${offer.amount.format("%.8f")}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelLarge,
                    color = actionColor
                )
            }
            Divider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "IDR ${offer.price.format("%,.0f")}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Total: IDR ${offer.total.format("%,.0f")}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Payment: ${offer.paymentMethods.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Explore,
            contentDescription = "No offers",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No active offers",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create your first offer to start trading",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}