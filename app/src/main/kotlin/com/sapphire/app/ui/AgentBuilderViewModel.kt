package com.sapphire.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.agent.AgentJobInput
import com.sapphire.domain.agent.AgentRunService
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentTemplate
import com.sapphire.domain.agent.EnhanceDirectiveService
import com.sapphire.domain.agent.nextRunText
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.model.AgentFrequency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BuilderForm(
    val name: String = "",
    val goal: String = "",
    val task: String = "",
    val format: String = "",
    val rules: String = "",
    val maxItems: Int = 1,
    val frequency: AgentFrequency = AgentFrequency.DAILY,
    val triggerTime: String = "07:00",
)

@HiltViewModel
class AgentBuilderViewModel @Inject constructor(
    private val repository: AgentRepository,
    private val scheduler: com.sapphire.data.agent.AgentScheduler,
    private val runService: AgentRunService,
    private val enhanceService: EnhanceDirectiveService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editJobId: String = savedStateHandle["jobId"] ?: "new"
    val isEdit: Boolean = editJobId != "new"

    private val _form = MutableStateFlow(BuilderForm())
    val form: StateFlow<BuilderForm> = _form.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _generateError = MutableStateFlow<String?>(null)
    val generateError: StateFlow<String?> = _generateError.asStateFlow()

    private val _testResult = MutableStateFlow<TestRunResult?>(null)
    val testResult: StateFlow<TestRunResult?> = _testResult.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _nameError = MutableStateFlow<String?>(null)
    val nameError: StateFlow<String?> = _nameError.asStateFlow()

    fun clearNameError() { _nameError.value = null }
    fun clearGenerateError() { _generateError.value = null }

    init {
        if (isEdit) {
            viewModelScope.launch {
                repository.observeJob(editJobId).first()?.let { job ->
                    _form.value = BuilderForm(
                        name = job.name, goal = job.goal, task = job.task,
                        format = job.format, rules = job.rules,
                        maxItems = job.maxItems, frequency = job.frequency, triggerTime = job.triggerTime,
                    )
                }
            }
        }
    }

    fun setName(v: String) = _form.update { it.copy(name = v) }
    fun setGoal(v: String) = _form.update { it.copy(goal = v) }
    fun setTask(v: String) = _form.update { it.copy(task = v) }
    fun setFormat(v: String) = _form.update { it.copy(format = v) }
    fun setRules(v: String) = _form.update { it.copy(rules = v) }
    fun setMaxItems(v: Int) = _form.update { it.copy(maxItems = v) }
    fun setFrequency(v: AgentFrequency) = _form.update { it.copy(frequency = v) }
    fun setTriggerTime(v: String) = _form.update { it.copy(triggerTime = v) }

    fun loadTemplate(t: AgentTemplate) {
        _form.value = BuilderForm(
            name = t.name, goal = t.goal, maxItems = t.maxItems, frequency = t.frequency, triggerTime = t.triggerTime,
        )
    }

    fun nextRunLabel(): String = nextRunText(_form.value.frequency, _form.value.triggerTime, System.currentTimeMillis())

    fun generateDirective() {
        val f = _form.value
        if (f.goal.isBlank()) return
        _generateError.value = null
        _isGenerating.value = true
        viewModelScope.launch {
            try {
                when (val outcome = enhanceService.enhance(f.goal, f.maxItems)) {
                    is LlmOutcome.Ok -> {
                        val g = outcome.value
                        _form.update { it.copy(task = g.task, format = g.format, rules = g.rules) }
                    }
                    is LlmOutcome.Err -> _generateError.value = outcome.error.userMessage()
                }
            } catch (e: Exception) { _generateError.value = e.message }
            finally { _isGenerating.value = false }
        }
    }

    fun testRun() {
        val f = _form.value
        if (f.goal.isBlank()) return
        _testResult.value = null
        _isRunning.value = true
        viewModelScope.launch {
            try {
                val tempJob = com.sapphire.domain.model.AgentJob(
                    id = "preview", name = f.name.ifBlank { "Preview" },
                    goal = f.goal, task = f.task, format = f.format, rules = f.rules,
                    maxItems = f.maxItems, frequency = f.frequency, triggerTime = f.triggerTime,
                    enabled = true, nextRunIntentEpochMs = null, createdAt = 0L,
                )
                val outcome = runService.run(tempJob, AgentRunService.RunMode.DRY)
                _testResult.value = TestRunResult(
                    success = outcome.status != com.sapphire.domain.model.AgentRunStatus.FAILED,
                    durationMs = outcome.durationMs,
                    itemCount = outcome.items.size,
                    items = outcome.items.map { it.title + (it.summary?.let { s -> " - $s" } ?: "") },
                    error = if (outcome.status != com.sapphire.domain.model.AgentRunStatus.OK) outcome.message else null,
                )
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun submit() {
        val f = _form.value
        if (f.name.isBlank() || f.goal.isBlank()) return
        _nameError.value = null
        viewModelScope.launch {
            val existing = repository.observeJobs().first()
            val clash = existing.any { it.name.equals(f.name, ignoreCase = true) && it.id != editJobId }
            if (clash) { _nameError.value = "An agent with this name already exists"; return@launch }
            val input = AgentJobInput(f.name, f.goal, f.task, f.format, f.rules, f.maxItems, f.frequency, f.triggerTime)
            if (isEdit) { repository.update(editJobId, input); scheduler.schedule(editJobId, input.frequency, input.triggerTime) }
            else { val newId = repository.create(input); scheduler.schedule(newId, input.frequency, input.triggerTime) }
            _saved.value = true
        }
    }
}

data class TestRunResult(val success: Boolean, val durationMs: Long, val itemCount: Int, val items: List<String>, val error: String?)
