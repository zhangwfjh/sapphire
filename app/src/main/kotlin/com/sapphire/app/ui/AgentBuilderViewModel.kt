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
    val modelTier: Int = 1,
    val recency: AgentRecency = AgentRecency.WEEK,
    val outputLanguage: OutputLanguage = OutputLanguage.EN,
    val style: AgentStyle = AgentStyle.BRIEF,
)

/** Live cost estimate (the bottom bar). */
data class BuilderCost(val perRunLabel: String, val perMonthLabel: String, val canCreate: Boolean)

/** The dynamic preview pane — title + body rendered for the chosen (style, language). */
data class BuilderPreview(val title: String, val body: String, val badge: String)

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
                        modelTier = job.modelTier,
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
    fun setTier(v: Int) = _form.update { it.copy(modelTier = v) }
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
            modelTier = t.modelTier,
            recency = t.recency,
            outputLanguage = t.outputLanguage,
            style = t.style,
        )
    }

    fun nextRunLabel(): String = nextRunText(_form.value.frequency, _form.value.triggerTime, System.currentTimeMillis())

    fun cost(): BuilderCost {
        val f = _form.value
        val mult = STYLE_MULT[f.style] ?: 1.0
        val baseTok = if (f.modelTier == 2) 2400 else 1200
        val baseUsd = if (f.modelTier == 2) 0.0048 else 0.0012
        val perRun = (baseTok * mult).roundToInt()
        val perRunUsd = baseUsd * mult
        val rpm = runsPerMonth(f.frequency)
        return BuilderCost(
            perRunLabel = "~${perRun} tok / run · \$${"%.3f".format(perRunUsd)}",
            perMonthLabel = "${freqLabel(f.frequency)} × ${styleLabel(f.style)} ≈ \$${"%.2f".format(perRunUsd * rpm)} / month",
            canCreate = f.name.isNotBlank() && f.directive.isNotBlank(),
        )
    }

    fun preview(): BuilderPreview {
        val f = _form.value
        val lang = if (f.outputLanguage == OutputLanguage.MATCH_SOURCE) OutputLanguage.EN else f.outputLanguage
        val story = PREVIEW_STORY[lang] ?: PREVIEW_STORY.getValue(OutputLanguage.EN)
        val body = renderStyle(f.style, story, lang)
        val badgeLang = if (f.outputLanguage == OutputLanguage.MATCH_SOURCE) "AUTO" else langLabel(lang)
        return BuilderPreview(story.title, body, "${styleLabel(f.style)} · $badgeLang")
    }

    fun submit() {
        val f = _form.value
        if (f.name.isBlank() || f.directive.isBlank()) return
        viewModelScope.launch {
            val input = AgentJobInput(
                f.name, f.directive, f.searchTool, f.frequency, f.triggerTime,
                f.modelTier, f.recency, f.outputLanguage, f.style,
            )
            if (isEdit) repository.update(editJobId, input) else repository.create(input)
            _saved.value = true
        }
    }

    private fun renderStyle(style: AgentStyle, s: Story, lang: OutputLanguage): String = when (style) {
        AgentStyle.BRIEF -> s.core
        AgentStyle.BULLETED -> "• ${s.core}"
        AgentStyle.CONVERSATIONAL -> when (lang) {
            OutputLanguage.EN -> "Here's the move: ${s.core}"
            OutputLanguage.ZH -> "简单说：${s.core}"
            OutputLanguage.MATCH_SOURCE -> s.core
        }
        AgentStyle.ACADEMIC -> when (lang) {
            OutputLanguage.EN -> "Method. ${s.core} Per our benchmark protocol (n=8, 99% CI), the gain holds at 4096-token prompts."
            OutputLanguage.ZH -> "方法。${s.core} 在我们的基准协议下（n=8，99% 置信区间），该增益在 4096 token 时依然成立。"
            OutputLanguage.MATCH_SOURCE -> s.core
        }
        AgentStyle.HOTTAKE -> when (lang) {
            OutputLanguage.EN -> "Everyone's obsessing over context length. Wrong lever — prefill is where the money is. ${s.core}"
            OutputLanguage.ZH -> "所有人都在卷上下文长度。方向错了——钱在预填充里。${s.core}"
            OutputLanguage.MATCH_SOURCE -> s.core
        }
        AgentStyle.EXPLAINER -> when (lang) {
            OutputLanguage.EN -> "What it is: prefill is the first-pass computation over your prompt. ${s.core} Why it matters: prefill is the bottleneck on long prompts — splitting it out means you can scale it independently and stop blocking decode."
            OutputLanguage.ZH -> "是什么：预填充是对提示词的首次计算。${s.core} 为什么重要：预填充是长提示的瓶颈——独立出来后可单独扩容，不再阻塞解码。"
            OutputLanguage.MATCH_SOURCE -> s.core
        }
    }

    private data class Story(val title: String, val core: String)

    companion object {
        private val STYLE_MULT = mapOf(
            AgentStyle.BRIEF to 1.0,
            AgentStyle.BULLETED to 0.85,
            AgentStyle.CONVERSATIONAL to 1.15,
            AgentStyle.ACADEMIC to 1.6,
            AgentStyle.HOTTAKE to 1.1,
            AgentStyle.EXPLAINER to 1.7,
        )
        private val PREVIEW_STORY = mapOf(
            OutputLanguage.EN to Story(
                "vLLM 0.7 ships disaggregated prefill",
                "Separates prefill from decode across GPU pools — 2.3× throughput on long prompts.",
            ),
            OutputLanguage.ZH to Story(
                "vLLM 0.7 发布解耦预填充",
                "将预填充与解码分配到不同 GPU 池——长提示吞吐量提升 2.3 倍。",
            ),
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
        private fun langLabel(l: OutputLanguage) = when (l) {
            OutputLanguage.EN -> "EN"; OutputLanguage.ZH -> "中文"; OutputLanguage.MATCH_SOURCE -> "Match"
        }
    }
}
