package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.model.SavedItemDetails
import com.sapphire.domain.save.SavedItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * S07 Saved Later screen state. Backed by [SavedItemRepository.observeDetails] — a live
 * JOIN of saved items with their feed-item display columns. Unsave is exposed so the list
 * can drop an item without navigating to the reader.
 */
@HiltViewModel
class SavedItemsViewModel @Inject constructor(
    private val savedItems: SavedItemRepository,
) : ViewModel() {

    val items: StateFlow<List<SavedItemDetails>> = savedItems.observeDetails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun unsave(itemId: String) {
        viewModelScope.launch { savedItems.unsave(itemId) }
    }
}
