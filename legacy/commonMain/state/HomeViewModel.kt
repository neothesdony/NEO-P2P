package com.neop2p.state

import com.neop2p.domain.model.Offer
import com.neop2p.domain.usecase.GetActiveOffersUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val getActiveOffersUseCase: GetActiveOffersUseCase
) {
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadOffers()
    }

    private fun loadOffers() {
        viewModelScope.launch {
            try {
                // Emit loading state immediately
                _uiState.value = HomeUiState.Loading

                // Collect the flow of offers and update the UI state
                getActiveOffersUseCase.invoke().collect { offers ->
                    _uiState.value = HomeUiState.Success(offers)
                }
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    // For simplicity, we are not implementing a refresh function here, but it could be added.
    fun refresh() {
        loadOffers()
    }
}

// UI State for the Home screen
sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(val offers: List<Offer>) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}