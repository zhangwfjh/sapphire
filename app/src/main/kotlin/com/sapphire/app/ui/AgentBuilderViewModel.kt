package com.sapphire.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.agent.AgentJobInput
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentTemplate
import com.sapphire.domain.agent.nextRunText
import com.sapphire.domain.agent.runsPerMonth
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage
import com.sapphire.domain.model.SearchTool
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** The builder form state (the HTML `B` object). */
data class BuilderForm(
    val name: String = "",
    val directive: String = "",
    val searchTool: SearchTool = SearchTool.TAVILY,
    val frequency: AgentFrequency = AgentFrequency.DAILY,
    val triggerTime: String = "07:00",
    val recency: AgentRecency = AgentRecency.WEEK,
    val outputLanguage: OutputLanguage = OutputLanguage.EN,
    val style: AgentStyle = AgentStyle.BRIEF,
)

/** Live cost estimate (the bottom bar). */
data class BuilderCost(val perRunLabel: String, val perMonthLabel: String, val canCreate: Boolean)


/**
 * Builder form. In edit mode (jobId != "new") the existing job loads into the form on
 * init. Cost + preview derive from [BuilderForm] so they update on every field change.
 * [saved] flips true once create/update completes — the screen observes it to navigate back.
 */
@HiltViewModel
class AgentBuilderViewModel @Inject constructor(
    private val repository: AgentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Mirrors Routes.agentBuilder("{jobId}") arg name.
    private val editJobId: String = savedStateHandle["jobId"] ?: "new"
    val isEdit: Boolean = editJobId != "new"

    private val _form = MutableStateFlow(BuilderForm())
    val form: StateFlow<BuilderForm> = _form.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        if (isEdit) {
            viewModelScope.launch {
                repository.observeJob(editJobId).first()?.let { job ->
                    _form.value = BuilderForm(
                        name = job.name,
                        directive = job.directive,
                        searchTool = job.searchTool,
                        frequency = job.frequency,
                        triggerTime = job.triggerTime,
                        recency = job.recency,
                        outputLanguage = job.outputLanguage,
                        style = job.style,
                    )
                }
            }
        }
    }

    fun setName(v: String) = _form.update { it.copy(name = v) }
    fun setDirective(v: String) = _form.update { it.copy(directive = v) }
    fun setTool(v: SearchTool) = _form.update { it.copy(searchTool = v) }
    fun setFrequency(v: AgentFrequency) = _form.update { it.copy(frequency = v) }
    fun setTriggerTime(v: String) = _form.update { it.copy(triggerTime = v) }
    fun setRecency(v: AgentRecency) = _form.update { it.copy(recency = v) }
    fun setLanguage(v: OutputLanguage) = _form.update { it.copy(outputLanguage = v) }
    fun setStyle(v: AgentStyle) = _form.update { it.copy(style = v) }

    fun loadTemplate(t: AgentTemplate) {
        _form.value = BuilderForm(
            name = t.name,
            directive = t.directive,
            searchTool = t.searchTool,
            frequency = t.frequency,
            triggerTime = t.triggerTime,
            recency = t.recency,
            outputLanguage = t.outputLanguage,
            style = t.style,
        )
    }

    fun nextRunLabel(): String = nextRunText(_form.value.frequency, _form.value.triggerTime, System.currentTimeMillis())

    fun cost(): BuilderCost {
        val f = _form.value
        val mult = STYLE_MULT[f.style] ?: 1.0
        val baseTok = 1200
        val baseUsd = 0.0012
        val perRun = (baseTok * mult).roundToInt()
        val perRunUsd = baseUsd * mult
        val rpm = runsPerMonth(f.frequency)
        return BuilderCost(
            perRunLabel = "~${perRun} tok / run · \$${"%.3f".format(perRunUsd)}",
            perMonthLabel = "${freqLabel(f.frequency)} × ${styleLabel(f.style)} ≈ \$${"%.2f".format(perRunUsd * rpm)} / month",
            canCreate = f.name.isNotBlank() && f.directive.isNotBlank(),
        )
    }

    fun submit() {
        val f = _form.value
        if (f.name.isBlank() || f.directive.isBlank()) return
        viewModelScope.launch {
            val input = AgentJobInput(
                f.name, f.directive, f.searchTool, f.frequency, f.triggerTime,
                f.recency, f.outputLanguage, f.style,
            )
            if (isEdit) repository.update(editJobId, input) else repository.create(input)
            _saved.value = true
        }
    }

    companion object {
        private val STYLE_MULT = mapOf(
            AgentStyle.BRIEF to 1.0,
            AgentStyle.BULLETED to 0.85,
            AgentStyle.CONVERSATIONAL to 1.15,
            AgentStyle.ACADEMIC to 1.6,
            AgentStyle.HOTTAKE to 1.1,
            AgentStyle.EXPLAINER to 1.7,
        )

        private fun freqLabel(f: AgentFrequency) = when (f) {
            AgentFrequency.HOURLY_1 -> "Every 1h"; AgentFrequency.HOURLY_2 -> "Every 2h"
            AgentFrequency.HOURLY_4 -> "Every 4h"; AgentFrequency.HOURLY_6 -> "Every 6h"
            AgentFrequency.HOURLY_8 -> "Every 8h"; AgentFrequency.HOURLY_12 -> "Every 12h"
            AgentFrequency.DAILY -> "Daily"; AgentFrequency.WEEKDAY -> "Weekdays"
            AgentFrequency.WEEKLY -> "Weekly"
        }
        private fun styleLabel(s: AgentStyle) = when (s) {
            AgentStyle.BRIEF -> "Analyst brief"; AgentStyle.BULLETED -> "Bulleted"
            AgentStyle.CONVERSATIONAL -> "Conversational"; AgentStyle.ACADEMIC -> "Academic"
            AgentStyle.HOTTAKE -> "Hot take"; AgentStyle.EXPLAINER -> "Explainer"
        }

    }
}
