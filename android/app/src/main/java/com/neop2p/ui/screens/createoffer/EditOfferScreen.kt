package com.neop2p.ui.screens.createoffer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toDomain
import com.neop2p.domain.model.TradeOffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Loads an existing offer and routes into CreateOfferScreen in EDIT mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditOfferScreen(
    offerId: String,
    onBack: () -> Unit,
    onEditSaved: (String) -> Unit
) {
    val viewModel: EditOfferViewModel = hiltViewModel()
    val offer by viewModel.offer.collectAsStateWithLifecycle()

    LaunchedEffect(offerId) {
        viewModel.load(offerId)
    }

    val current = offer
    if (current != null) {
        CreateOfferScreen(
            onOfferCreated = onEditSaved,
            onBack = onBack,
            initialOffer = current,
            onEditSaved = onEditSaved
        )
    } else {
        // Loading state while the offer is being fetched (or not found).
        Box(Modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).padding(24.dp))
        }
    }
}

@HiltViewModel
class EditOfferViewModel @Inject constructor(
    private val offerDao: OfferDao
) : ViewModel() {

    private val _offer = MutableStateFlow<TradeOffer?>(null)
    val offer: StateFlow<TradeOffer?> = _offer

    fun load(offerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _offer.value = offerDao.getOfferSync(offerId)?.toDomain()
        }
    }
}
