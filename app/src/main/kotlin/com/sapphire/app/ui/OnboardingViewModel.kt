package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.onboarding.CurateTaxonomyUseCase
import com.sapphire.domain.onboarding.OnboardingRepository
import com.sapphire.domain.review.ReviewEdit
import com.sapphire.domain.review.ReviewEditApplier
import com.sapphire.domain.review.ReviewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI-facing states for the onboarding flow. Error carries a user-facing message. */
sealed interface OnboardingUiState {
    data object Idle : OnboardingUiState
    data object Loading : OnboardingUiState
    data class Review(val model: ReviewModel) : OnboardingUiState
    data class Committing(val model: ReviewModel) : OnboardingUiState
    data class Committed(val topicId: String) : OnboardingUiState
    data class Error(val message: String) : OnboardingUiState
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val curate: CurateTaxonomyUseCase,
    private val repository: OnboardingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Idle)
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun generateFeed(phrase: String) {
        _state.value = OnboardingUiState.Loading
        viewModelScope.launch {
            when (val outcome = curate(phrase)) {
                is LlmOutcome.Err -> _state.value = OnboardingUiState.Error(outcome.error.userMessage())
                is LlmOutcome.Ok -> _state.value = OnboardingUiState.Review(outcome.value)
            }
        }
    }

    fun applyEdit(edit: ReviewEdit) {
        _state.update { current ->
            if (current is OnboardingUiState.Review) {
                OnboardingUiState.Review(ReviewEditApplier.apply(current.model, edit))
            } else current
        }
    }

    fun dismissError() { _state.value = OnboardingUiState.Idle }

    fun approve() {
        val current = _state.value
        if (current !is OnboardingUiState.Review) return
        _state.value = OnboardingUiState.Committing(current.model)
        viewModelScope.launch {
            val topicId = repository.commitReview(current.model)
            _state.value = OnboardingUiState.Committed(topicId)
        }
    }
}
