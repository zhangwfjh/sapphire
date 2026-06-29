package com.sapphire.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.llm.LlmClient
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.llm.LlmTier
import com.sapphire.domain.settings.DataClearUseCase
import com.sapphire.domain.settings.LlmConfigStore
import com.sapphire.domain.settings.RetentionConfigStore
import com.sapphire.domain.settings.ThemeConfigStore
import com.sapphire.domain.settings.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/**
 * Settings screen state. Store flows are now hot (backed by MutableStateFlow), so the
 * initial load collects them once and subsequent edits propagate reactively.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val llmStore: LlmConfigStore,
    private val retentionStore: RetentionConfigStore,
    private val themeStore: ThemeConfigStore,
    private val dataClear: DataClearUseCase,
    private val llmClient: LlmClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _connectionTest = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    private val _breakdown = MutableStateFlow(com.sapphire.domain.settings.DataBreakdown(0, 0, 0, 0L, 0L, 0L, 0L))
    val breakdown: StateFlow<com.sapphire.domain.settings.DataBreakdown> = _breakdown.asStateFlow()

    init {
        viewModelScope.launch {
            val llm = llmStore.observe().first()
            val key = llmStore.observeApiKey().first()
            val retention = retentionStore.observe().first()
            val theme = themeStore.observe().first()
            _state.value = SettingsUiState(
                apiKey = key,
                baseUrl = llm.baseUrl,
                tier1 = llm.tier1Model,
                tier2 = llm.tier2Model,
                retentionDays = retention,
                theme = theme,
                loaded = true,
            )
        }
        refreshStorageUsage()
    }

    private fun refreshStorageUsage() {
        viewModelScope.launch {
            val b = dataClear.breakdown()
            _storageBytes.value = b.totalBytes
            _breakdown.value = b
        }
    }

    fun setApiKey(v: String) {
        _state.value = _state.value.copy(apiKey = v)
        viewModelScope.launch { llmStore.setApiKey(v) }
    }

    fun setBaseUrl(v: String) {
        _state.value = _state.value.copy(baseUrl = v)
        viewModelScope.launch { llmStore.setBaseUrl(v) }
    }

    fun setTier1(v: String) {
        _state.value = _state.value.copy(tier1 = v)
        viewModelScope.launch { llmStore.setTier1Model(v) }
    }

    fun setTier2(v: String) {
        _state.value = _state.value.copy(tier2 = v)
        viewModelScope.launch { llmStore.setTier2Model(v) }
    }

    fun setRetention(days: Int) {
        _state.value = _state.value.copy(retentionDays = days)
        viewModelScope.launch { retentionStore.setDays(days) }
    }

    fun setTheme(p: ThemePreference) {
        _state.value = _state.value.copy(theme = p)
        viewModelScope.launch { themeStore.set(p) }
    }

    /** Fires a minimal Tier-1 ping to validate the current LLM config. */
    fun testConnection() {
        viewModelScope.launch {
            _connectionTest.value = ConnectionTestState.Testing
            val outcome = llmClient.completeStructured(
                tier = LlmTier.TIER1_FAST,
                systemPrompt = "Respond with exactly: {\"ok\":\"pong\"}",
                userPrompt = "ping",
                outputSerializer = PingResult.serializer(),
            )
            _connectionTest.value = when (outcome) {
                is LlmOutcome.Ok -> ConnectionTestState.Ok
                is LlmOutcome.Err -> ConnectionTestState.Err(outcome.error.userMessage())
            }
        }
    }

    fun clearFeedItems() = viewModelScope.launch {
        val n = dataClear.clearFeedItems()
        _snackbar.value = "Cleared $n feed items"
        refreshStorageUsage()
    }

    fun clearReaderCache() = viewModelScope.launch {
        val n = dataClear.clearReaderCache()
        _snackbar.value = "Cleared $n cached entries"
        refreshStorageUsage()
    }

    fun clearSaved() = viewModelScope.launch {
        val n = dataClear.clearSaved()
        _snackbar.value = "Cleared $n saved items"
        refreshStorageUsage()
    }

    fun clearAll() = viewModelScope.launch {
        dataClear.clearAll()
        _snackbar.value = "Reset complete — defaults re-seed on next open"
        refreshStorageUsage()
    }

    fun consumeSnackbar() {
        _snackbar.value = null
    }

    @Serializable
    private data class PingResult(val ok: String = "pong")
}

data class SettingsUiState(
    val apiKey: String = "",
    val baseUrl: String = "",
    val tier1: String = "",
    val tier2: String = "",
    val retentionDays: Int = 30,
    val theme: ThemePreference = ThemePreference.DARK,
    val loaded: Boolean = false,
)

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data object Ok : ConnectionTestState
    data class Err(val message: String) : ConnectionTestState
}
