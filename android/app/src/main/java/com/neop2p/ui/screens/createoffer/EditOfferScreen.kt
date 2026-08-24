package com.neop2p.ui.screens.createoffer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.neop2p.R
import com.neop2p.data.local.dao.OfferDao
import com.neop2p.data.local.toDomain
import com.neop2p.domain.model.TradeOffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(offerId) {
        viewModel.load(offerId)
    }

    when (val state = uiState) {
        is EditOfferViewModel.UiState.Loading -> {
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).padding(24.dp))
            }
        }
        is EditOfferViewModel.UiState.Error -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.message,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Button(onClick = { viewModel.load(offerId) }) {
                    Text(stringResource(R.string.general_retry))
                }
            }
        }
        is EditOfferViewModel.UiState.Success -> {
            CreateOfferScreen(
                onOfferCreated = onEditSaved,
                onBack = onBack,
                initialOffer = state.offer,
                onEditSaved = onEditSaved
            )
        }
    }
}

@HiltViewModel
class EditOfferViewModel @Inject constructor(
    private val offerDao: OfferDao
) : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        data class Error(val message: String) : UiState()
        data class Success(val offer: TradeOffer) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState

    fun load(offerId: String) {
        if (offerId.isBlank()) {
            _uiState.value = UiState.Error("Invalid offer ID")
            return
        }
        _uiState.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val offer = offerDao.getOfferSync(offerId)?.toDomain()
                _uiState.value = if (offer != null) {
                    UiState.Success(offer)
                } else {
                    UiState.Error("Offer not found")
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to load offer: ${e.message}")
            }
        }
    }
}
