package com.sapphire.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sapphire.domain.agent.AgentBlueprint
import com.sapphire.domain.agent.AgentBlueprintHeuristics
import com.sapphire.domain.agent.AgentJobInput
import com.sapphire.domain.agent.AgentRepository
import com.sapphire.domain.agent.AgentRunService
import com.sapphire.domain.agent.AgentTemplate
import com.sapphire.domain.agent.EnhanceDirectiveService
import com.sapphire.domain.source.SourceFolderNode
import com.sapphire.domain.source.SourceRepository
import com.sapphire.domain.llm.LlmOutcome
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentJob
import com.sapphire.domain.model.AgentRunStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The three Tune questions, in wizard order — drives the questionnaire UI generically.
 * Selection semantics per question: `>=0` = candidate index, [CUSTOM] (-1) = free-text
 * answer, [UNSELECTED] (-2) = nothing chosen yet (candidates shown, answer box hidden).
 */
enum class BuilderQuestion(val number: String, val title: String, val hint: String, val placeholder: String) {
    TASK("01", "Task", "what it does each run", "Describe the task in your own words…"),
    FORMAT("02", "Output format", "what each filed item looks like", "Describe the output format…"),
    RULES("03", "Rules", "hard constraints it must respect", "One rule per line…"),
    ;

    companion object {
        const val CUSTOM = -1
        const val UNSELECTED = -2
    }
}

/**
 * Wizard state across all four steps. Per question, the selection indexes the candidate
 * row (A/B/C, 0-based), is [BuilderQuestion.CUSTOM] for the free-text "your answer"
 * ([BuilderForm.taskCustom] etc.), or [BuilderQuestion.UNSELECTED] before anything is
 * chosen. The effective answer falls back to custom text whenever the selection has no
 * matching variant, so a swapped blueprint can never leave a stale answer behind.
 */
data class BuilderForm(
    val name: String = "",
    val goal: String = "",
    val maxItems: Int = 4,
    val frequency: AgentFrequency = AgentFrequency.DAILY,
    val triggerTime: String = "07:00",
    /** Drawer folder for the agent's source; null = the shared "✦ Agents" folder. */
    val categoryId: String? = null,
    val blueprint: AgentBlueprint = AgentBlueprint.EMPTY,
    val taskSelection: Int = BuilderQuestion.UNSELECTED,
    val taskCustom: String = "",
    val formatSelection: Int = BuilderQuestion.UNSELECTED,
    val formatCustom: String = "",
    val rulesSelection: Int = BuilderQuestion.UNSELECTED,
    val rulesCustom: String = "",
    val extras: String = "",
    /** Wizard step 1..4 (Describe / Tune / Preview / Schedule). */
    val step: Int = 1,
    /** Template currently preloading the form, for the gallery's selected state. */
    val templateName: String? = null,
) {
    val task: String get() = answer(taskSelection, blueprint.taskVariants, taskCustom)
    val format: String get() = answer(formatSelection, blueprint.formatVariants, formatCustom)
    val rules: String get() = answer(rulesSelection, blueprint.ruleVariants, rulesCustom)

    fun selection(q: BuilderQuestion): Int = when (q) {
        BuilderQuestion.TASK -> taskSelection
        BuilderQuestion.FORMAT -> formatSelection
        BuilderQuestion.RULES -> rulesSelection
    }

    fun candidates(q: BuilderQuestion): List<String> = when (q) {
        BuilderQuestion.TASK -> blueprint.taskVariants
        BuilderQuestion.FORMAT -> blueprint.formatVariants
        BuilderQuestion.RULES -> blueprint.ruleVariants
    }

    fun customText(q: BuilderQuestion): String = when (q) {
        BuilderQuestion.TASK -> taskCustom
        BuilderQuestion.FORMAT -> formatCustom
        BuilderQuestion.RULES -> rulesCustom
    }

    private fun answer(selection: Int, variants: List<String>, custom: String): String =
        variants.getOrNull(selection) ?: custom
}

/** One folder option in the Schedule dropdown; [categoryId] null = "✦ Agents" default. */
data class FolderOption(val categoryId: String?, val label: String, val desc: String)

/** The dry-run article shown on Preview — first synthesized item, reader-ready. */
data class DryArticle(
    val title: String,
    val summary: String?,
    val bodyHtml: String?,
    val sources: List<String>,
)

/** One live loop action for the Preview's waiting feed ("Searching — 'rust releases'"). */
data class RunEvent(val phase: String, val detail: String, val atMs: Long)

data class TestRunResult(
    val success: Boolean,
    val durationMs: Long,
    val tokensUsed: Int,
    val itemCount: Int,
    val items: List<String>,
    val article: DryArticle?,
    val error: String?,
)

@HiltViewModel
class AgentBuilderViewModel @Inject constructor(
    private val repository: AgentRepository,
    private val scheduler: com.sapphire.data.agent.AgentScheduler,
    private val runService: AgentRunService,
    private val enhanceService: EnhanceDirectiveService,
    private val sourceRepository: SourceRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val editJobId: String = savedStateHandle["jobId"] ?: "new"
    val isEdit: Boolean = editJobId != "new"

    private val _form = MutableStateFlow(BuilderForm())
    val form: StateFlow<BuilderForm> = _form.asStateFlow()

    /** Id of the saved job once create/update lands — drives the toast + navigation. */
    private val _savedJobId = MutableStateFlow<String?>(null)
    val savedJobId: StateFlow<String?> = _savedJobId.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** Post-generate status line ("ready ✓" / "offline draft ✓ — …"). */
    private val _generateNote = MutableStateFlow<String?>(null)
    val generateNote: StateFlow<String?> = _generateNote.asStateFlow()

    private val _testResult = MutableStateFlow<TestRunResult?>(null)
    val testResult: StateFlow<TestRunResult?> = _testResult.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Live loop actions while a dry run is in flight — the Preview's waiting feed. */
    private val _runEvents = MutableStateFlow<List<RunEvent>>(emptyList())
    val runEvents: StateFlow<List<RunEvent>> = _runEvents.asStateFlow()
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    private val _nameError = MutableStateFlow<String?>(null)
    val nameError: StateFlow<String?> = _nameError.asStateFlow()

    /** Real folders from the sources tree, "✦ Agents" default first. */
    val folders: StateFlow<List<FolderOption>> = sourceRepository.observeTree()
        .map { tree -> listOf(defaultFolder) + tree.flatMap(::flattenFolders) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        if (isEdit) {
            viewModelScope.launch {
                repository.observeJob(editJobId).first()?.let { job ->
                    _form.value = BuilderForm(
                        name = job.name, goal = job.goal,
                        taskSelection = BuilderQuestion.CUSTOM,
                        formatSelection = BuilderQuestion.CUSTOM,
                        rulesSelection = BuilderQuestion.CUSTOM,
                        taskCustom = job.task, formatCustom = job.format, rulesCustom = job.rules,
                        maxItems = job.maxItems, frequency = job.frequency, triggerTime = job.triggerTime,
                        categoryId = job.categoryId,
                    )
                }
            }
        }
    }

    // ---------- step navigation ----------

    /** Key of the goal+extras the current blueprint was generated for (skip redundant regens). */
    private var generatedForKey: String? = null

    fun goToStep(step: Int) {
        val from = _form.value.step
        val to = step.coerceIn(1, 4)
        _form.update { it.copy(step = to) }
        when {
            // Describe → Tune: generate the questionnaire (LLM; heuristics on failure).
            from == 1 && to == 2 -> maybeGenerate()
            // Tune → Preview: run the real loop with the user's answers; re-run after any edit.
            to == 3 && _testResult.value == null -> runDryTest()
            else -> Unit
        }
    }

    fun next() = goToStep(_form.value.step + 1)
    fun back() = goToStep(_form.value.step - 1)

    /** Goal gate on step 1; answered-all gate on Tune (each question needs an answer). */
    fun canAdvance(): Boolean {
        val f = _form.value
        return when (f.step) {
            1 -> f.goal.isNotBlank()
            2 -> f.task.isNotBlank() && f.format.isNotBlank() && f.rules.isNotBlank()
            else -> true
        }
    }

    // ---------- edits (any edit invalidates the dry result) ----------

    fun setName(v: String) {
        _nameError.value = null
        mutate { it.copy(name = v) }
    }

    fun clearNameError() { _nameError.value = null }

    fun setGoal(v: String) {
        _generateNote.value = null
        generatedForKey = null
        mutate { it.copy(goal = v, blueprint = AgentBlueprint.EMPTY) }
    }

    fun setMaxItems(v: Int) = mutate { it.copy(maxItems = v) }
    fun setFrequency(v: AgentFrequency) = mutate { it.copy(frequency = v) }
    fun setTriggerTime(v: String) = mutate { it.copy(triggerTime = v) }
    fun setCategoryId(v: String?) = mutate { it.copy(categoryId = v) }

    /** Tap a candidate: selects it AND drops its text into the answer box for free editing. */
    fun selectCandidate(q: BuilderQuestion, index: Int) {
        val text = _form.value.candidates(q).getOrNull(index) ?: return
        mutate { form ->
            when (q) {
                BuilderQuestion.TASK -> form.copy(taskSelection = index, taskCustom = text)
                BuilderQuestion.FORMAT -> form.copy(formatSelection = index, formatCustom = text)
                BuilderQuestion.RULES -> form.copy(rulesSelection = index, rulesCustom = text)
            }
        }
    }

    /** "Others — I'll write my own": reveal the answer box, empty, ready to type. */
    fun startCustom(q: BuilderQuestion) = mutate { form ->
        when (q) {
            BuilderQuestion.TASK -> form.copy(taskSelection = BuilderQuestion.CUSTOM)
            BuilderQuestion.FORMAT -> form.copy(formatSelection = BuilderQuestion.CUSTOM)
            BuilderQuestion.RULES -> form.copy(rulesSelection = BuilderQuestion.CUSTOM)
        }
    }

    fun setCustomAnswer(q: BuilderQuestion, text: String) = mutate { form ->
        when (q) {
            BuilderQuestion.TASK -> form.copy(taskSelection = BuilderQuestion.CUSTOM, taskCustom = text)
            BuilderQuestion.FORMAT -> form.copy(formatSelection = BuilderQuestion.CUSTOM, formatCustom = text)
            BuilderQuestion.RULES -> form.copy(rulesSelection = BuilderQuestion.CUSTOM, rulesCustom = text)
        }
    }

    fun setExtras(v: String) = mutate { it.copy(extras = v) }
    fun appendExtrasPrefix(prefix: String) = mutate { it.copy(extras = appendExtraPrefix(it.extras, prefix)) }
    fun toggleExtrasTag(tag: String) = mutate { it.copy(extras = toggleExtraTag(it.extras, tag)) }

    private fun mutate(block: (BuilderForm) -> BuilderForm) {
        _form.update(block)
        _testResult.value = null
    }

    // ---------- templates & generate ----------

    fun loadTemplate(t: AgentTemplate) {
        val f = _form.value
        val templateNames = com.sapphire.domain.agent.AgentTemplates.all.map { it.name }
        _form.value = f.copy(
            templateName = t.name,
            name = if (f.name.isBlank() || f.name in templateNames) t.name else f.name,
            goal = t.goal,
            maxItems = t.maxItems, frequency = t.frequency, triggerTime = t.triggerTime,
            blueprint = AgentBlueprint.EMPTY,
            taskSelection = BuilderQuestion.UNSELECTED,
            formatSelection = BuilderQuestion.UNSELECTED,
            rulesSelection = BuilderQuestion.UNSELECTED,
            taskCustom = "", formatCustom = "", rulesCustom = "", extras = "",
        )
        generatedForKey = null
        _generateNote.value = null
        _testResult.value = null
    }

    /** Generate only when the goal/answers changed since the last blueprint (back-nav is free). */
    private fun maybeGenerate() {
        val f = _form.value
        val key = "${f.goal}|${f.extras}"
        if (f.blueprint.isUsable && generatedForKey == key) return
        generate()
    }

    /**
     * Generate / ↻Refresh-candidates-with-notes — one Tier-1 call; extras ride along as
     * user requirements. Candidates come from the LLM only (no pre-generation seed); on
     * any failure — no key, network, malformed — the heuristic twin fills in so Tune is
     * never stuck. Never throws.
     */
    fun generate() {
        val f = _form.value
        if (f.goal.isBlank()) return
        generatedForKey = "${f.goal}|${f.extras}"
        _form.update { it.copy(blueprint = AgentBlueprint.EMPTY) }
        _isGenerating.value = true
        _generateNote.value = null
        viewModelScope.launch {
            val (blueprint, note) = try {
                when (val outcome = enhanceService.enhance(f.goal, f.maxItems, f.extras)) {
                    is LlmOutcome.Ok -> if (outcome.value.isUsable) {
                        outcome.value to "ready ✓"
                    } else {
                        AgentBlueprintHeuristics.blueprint(f.goal, f.maxItems, f.extras) to "offline draft ✓ — heuristics"
                    }
                    is LlmOutcome.Err -> {
                        AgentBlueprintHeuristics.blueprint(f.goal, f.maxItems, f.extras) to "offline draft ✓ — ${outcome.error.userMessage()}"
                    }
                }
            } catch (e: Exception) {
                AgentBlueprintHeuristics.blueprint(f.goal, f.maxItems, f.extras) to "offline draft ✓ — ${e.message ?: "generation failed"}"
            }
            _form.update { it.copy(blueprint = blueprint) }
            _testResult.value = null
            _generateNote.value = note
            _isGenerating.value = false
        }
    }

    // ---------- create folder (Schedule) ----------

    /** Create a drawer folder and select it for this agent. No-op when the name is blank. */
    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val topicId = sourceRepository.currentTopicId() ?: return@launch
            val categoryId = sourceRepository.addCategory(topicId, trimmed)
            mutate { it.copy(categoryId = categoryId) }
        }
    }
    fun runDryTest() {
        val f = _form.value
        if (f.goal.isBlank() || _isRunning.value) return
        _testResult.value = null
        _runEvents.value = emptyList()
        _isRunning.value = true
        viewModelScope.launch {
            try {
                val onEvent: (String, String) -> Unit = { phase, detail ->
                    _runEvents.update { (it + RunEvent(phase, detail, System.currentTimeMillis())).takeLast(6) }
                }
                val tempJob = AgentJob(
                    id = "preview", name = f.name.ifBlank { "Preview" },
                    goal = f.goal, task = f.task, format = f.format, rules = mergedRules(f),
                    maxItems = f.maxItems, frequency = f.frequency, triggerTime = f.triggerTime,
                    categoryId = f.categoryId, enabled = true, nextRunIntentEpochMs = null, createdAt = 0L,
                )
                val outcome = runService.run(tempJob, AgentRunService.RunMode.DRY, onEvent)
                val first = outcome.items.firstOrNull()
                _testResult.value = TestRunResult(
                    success = outcome.status != AgentRunStatus.FAILED,
                    durationMs = outcome.durationMs,
                    tokensUsed = outcome.tokensUsed,
                    itemCount = outcome.items.size,
                    items = outcome.items.map { it.title + (it.summary?.let { s -> " — $s" } ?: "") },
                    article = first?.let {
                        DryArticle(
                            title = it.title, summary = it.summary, bodyHtml = it.body,
                            sources = it.sources.map { ref -> hostOf(ref.url) },
                        )
                    },
                    error = if (outcome.status == AgentRunStatus.FAILED) outcome.message ?: "Dry run failed" else null,
                )
            } finally {
                _isRunning.value = false
            }
        }
    }

    // ---------- create / update ----------

    fun submit() {
        val f = _form.value
        if (_isSaving.value) return
        if (f.name.isBlank() || f.goal.isBlank()) return
        _nameError.value = null
        _isSaving.value = true
        viewModelScope.launch {
            try {
                val existing = repository.observeJobs().first()
                val clash = existing.any { it.name.equals(f.name.trim(), ignoreCase = true) && it.id != editJobId }
                if (clash) {
                    // Shown under the Name field (step 1) and as a banner on Schedule (step 4)
                    // so the rejection is never silent.
                    _nameError.value = "An agent with this name already exists"
                    return@launch
                }
                val input = AgentJobInput(
                    name = f.name.trim(), goal = f.goal.trim(),
                    task = f.task, format = f.format, rules = mergedRules(f),
                    maxItems = f.maxItems, frequency = f.frequency, triggerTime = f.triggerTime,
                    categoryId = f.categoryId,
                )
                val id = if (isEdit) {
                    repository.update(editJobId, input)
                    scheduler.schedule(editJobId, input.frequency, input.triggerTime)
                    editJobId
                } else {
                    val newId = repository.create(input)
                    scheduler.schedule(newId, input.frequency, input.triggerTime)
                    newId
                }
                _savedJobId.value = id
            } finally {
                _isSaving.value = false
            }
        }
    }

    /** Extras fold into the persisted rules (once) — the directive carries them on every run. */
    private fun mergedRules(f: BuilderForm): String {
        val extras = f.extras.trim().trimEnd('·').trim()
        if (extras.isBlank() || f.rules.contains(extras)) return f.rules
        return if (f.rules.isBlank()) extras else f.rules.trimEnd() + "\n" + extras
    }

    private companion object {
        val defaultFolder = FolderOption(null, "✦ Agents", "default · dedicated folder")
    }
}

// ---------- folder tree → dropdown options ----------

private fun flattenFolders(node: SourceFolderNode): List<FolderOption> =
    listOf(node.toFolderOption()) + node.children.flatMap(::flattenFolders)

private fun SourceFolderNode.toFolderOption(): FolderOption = FolderOption(
    categoryId = category.id,
    label = category.name,
    desc = "${sources.size} sources · ${sources.sumOf { it.counts.unread }} unread",
)

// ---------- extras string algebra (shared with the Tune UI) ----------

/** True when [tag] is present in [extras] as a standalone `·`-part or after a bare prefix. */
fun extrasHasTag(extras: String, tag: String): Boolean =
    extras.split(Regex("\\s*·\\s*")).any { val p = it.trim(); p == tag || p.endsWith(": $tag") }

/** Toggle [tag] in the `·`-separated extras list; merges right after a trailing bare prefix. */
fun toggleExtraTag(extras: String, tag: String): String {
    val parts = extras.split(Regex("\\s*·\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    val idx = parts.indexOfFirst { it == tag || it.endsWith(": $tag") }
    if (idx >= 0) {
        val p = parts[idx]
        if (p == tag) parts.removeAt(idx) else parts[idx] = p.removeSuffix(" $tag").trimEnd()
    } else {
        val last = parts.lastOrNull()
        if (!last.isNullOrEmpty() && last.length > 1 && last.endsWith(":")) {
            parts[parts.size - 1] = "$last $tag"
        } else {
            parts.add(tag)
        }
    }
    return parts.joinToString(" · ")
}

/** Append a starter prefix ("Exclude:", "Focus on:", …) to extras, separated when needed. */
fun appendExtraPrefix(extras: String, prefix: String): String {
    val base = extras.trimEnd()
    if (base.isEmpty()) return prefix
    val sep = if (base.endsWith(";") || base.endsWith("·")) " " else " · "
    return base + sep + prefix
}

private fun hostOf(url: String): String {
    val host = url.substringAfter("://", url).substringBefore('/')
    return host.ifBlank { url }
}
