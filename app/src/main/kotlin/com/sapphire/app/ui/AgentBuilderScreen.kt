package com.sapphire.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.agent.AgentLoopService
import com.sapphire.domain.agent.AgentTemplate
import com.sapphire.domain.agent.AgentTemplates
import com.sapphire.domain.agent.nextRunText
import com.sapphire.domain.agent.runsPerMonth
import com.sapphire.domain.model.AgentFrequency
import android.widget.Toast
import kotlinx.coroutines.delay

private val STEP_NAMES = listOf("Describe", "Tune", "Preview", "Schedule")


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBuilderScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit = {},
    viewModel: AgentBuilderViewModel = hiltViewModel(),
) {
    val palette = LocalSapphirePalette.current
    val context = LocalContext.current
    val form by viewModel.form.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val savedJobId by viewModel.savedJobId.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generateNote by viewModel.generateNote.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val nameError by viewModel.nameError.collectAsStateWithLifecycle()
    val runEvents by viewModel.runEvents.collectAsStateWithLifecycle()
    val isEdit = viewModel.isEdit

    LaunchedEffect(savedJobId) {
        val id = savedJobId ?: return@LaunchedEffect
        Toast.makeText(
            context,
            if (isEdit) "Agent saved · schedule updated" else "Agent created · scheduled · first run queued",
            Toast.LENGTH_SHORT,
        ).show()
        if (isEdit) onBack() else onCreated(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (isEdit) "Edit agent" else "New agent", style = MaterialTheme.typography.titleMedium, color = palette.OnInk, fontWeight = FontWeight.SemiBold)
                        ModePill(if (isEdit) "edit" else "draft")
                    }
                },
                navigationIcon = { IconButtonClose(onBack, palette) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.Ink, navigationIconContentColor = palette.OnInk, titleContentColor = palette.OnInk,
                ),
            )
        },
        bottomBar = {
            WizardFooter(
                step = form.step,
                isEdit = isEdit,
                canAdvance = form.step != 1 || form.goal.isNotBlank(),
                canSubmit = form.name.isNotBlank() && form.goal.isNotBlank(),
                onBack = viewModel::back,
                onNext = viewModel::next,
                onSubmit = viewModel::submit,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().background(palette.Ink).padding(padding)) {
            StepIndicator(form.step)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (form.step) {
                    1 -> DescribeStep(
                        form = form,
                        nameError = nameError,
                        setName = viewModel::setName,
                        setGoal = viewModel::setGoal,
                        loadTemplate = viewModel::loadTemplate,
                    )
                    2 -> TuneStep(
                        form = form,
                        isGenerating = isGenerating,
                        generateNote = generateNote,
                        selectCandidate = viewModel::selectCandidate,
                        startCustom = viewModel::startCustom,
                        setCustom = viewModel::setCustomAnswer,
                        setExtras = viewModel::setExtras,
                        appendPrefix = viewModel::appendExtrasPrefix,
                        toggleTag = viewModel::toggleExtrasTag,
                        refreshWithNotes = viewModel::generate,
                    )
                    3 -> PreviewStep(
                        form = form,
                        isRunning = isRunning,
                        testResult = testResult,
                        runEvents = runEvents,
                    )
                    else -> PlaceStep(
                        form = form,
                        folders = folders,
                        setCategoryId = viewModel::setCategoryId,
                        createFolder = viewModel::createFolder,
                        setFrequency = viewModel::setFrequency,
                        setTriggerTime = viewModel::setTriggerTime,
                        setMaxItems = viewModel::setMaxItems,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

// ---------- shared chrome ----------

@Composable
private fun IconButtonClose(onBack: () -> Unit, palette: com.sapphire.app.ui.theme.SapphirePalette) {
    Icon(
        Icons.Filled.Close, contentDescription = "Cancel",
        tint = palette.OnInkMuted,
        modifier = Modifier
            .padding(start = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onBack)
            .padding(8.dp),
    )
}

@Composable
private fun ModePill(label: String) {
    val palette = LocalSapphirePalette.current
    Text(
        label.uppercase(),
        style = SapphireMono.Eyebrow,
        color = palette.AccentBright,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.Accent.copy(alpha = 0.10f))
            .border(1.dp, palette.Accent.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/** Four-segment progress header: 1 · DESCRIBE / 2 · TUNE / 3 · PREVIEW / 4 · PLACE & RUN. */
@Composable
private fun StepIndicator(step: Int) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        STEP_NAMES.forEachIndexed { i, name ->
            val n = i + 1
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(99.dp))
                        .background(when {
                            n < step -> palette.Accent
                            n == step -> palette.AccentBright
                            else -> palette.InkStroke
                        })
                )
                Text(
                    "$n · ${name.uppercase()}",
                    style = SapphireMono.Label,
                    color = if (n <= step) palette.AccentBright else palette.OnInkFaint,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
        }
    }
}

/** Serif wizard question with the italic accent word, e.g. "Read one *sample*". */
@Composable
private fun QuestionTitle(plain: String, accent: String, trailing: String = "") {
    val palette = LocalSapphirePalette.current
    Text(
        buildAnnotatedString {
            append(plain)
            withStyle(SpanStyle(color = palette.AccentBright, fontStyle = FontStyle.Italic)) { append(accent) }
            if (trailing.isNotEmpty()) append(trailing)
        },
        style = MaterialTheme.typography.displaySmall,
        color = palette.OnInk,
        modifier = Modifier.padding(top = 18.dp),
    )
}

@Composable
private fun StepHint(text: String) {
    val palette = LocalSapphirePalette.current
    Text(text, style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
}

@Composable
private fun FieldLabel(text: String) {
    val palette = LocalSapphirePalette.current
    Text(
        text.uppercase(),
        style = SapphireMono.Eyebrow,
        color = palette.OnInkFaint,
        modifier = Modifier.padding(bottom = 7.dp),
    )
}

@Composable
private fun SelectChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val palette = LocalSapphirePalette.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = SapphireMono.Label) },
        shape = RoundedCornerShape(9.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = palette.InkElevated,
            labelColor = palette.OnInkMuted,
            selectedContainerColor = palette.AccentDeep,
            selectedLabelColor = Color.White,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) palette.Accent else palette.InkStroke),
    )
}

@Composable
private fun WizardFooter(
    step: Int,
    isEdit: Boolean,
    canAdvance: Boolean,
    canSubmit: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSubmit: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier.fillMaxWidth().background(palette.Ink).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (step > 1) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(0.5f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.OnInk),
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.InkStroke),
            ) { Text("Back", fontWeight = FontWeight.SemiBold) }
        }
        Button(
            onClick = if (step == 4) onSubmit else onNext,
            enabled = if (step == 4) canSubmit else canAdvance,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.AccentDeep,
                contentColor = Color.White,
                disabledContainerColor = palette.InkRaised,
                disabledContentColor = palette.OnInkFaint,
            ),
        ) {
            Text(
                when {
                    step == 4 -> if (isEdit) "Save & schedule" else "Create & schedule"
                    else -> "Next · ${STEP_NAMES[step]}"
                },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ---------- step 1 · describe ----------

@Composable
private fun DescribeStep(
    form: BuilderForm,
    nameError: String?,
    setName: (String) -> Unit,
    setGoal: (String) -> Unit,
    loadTemplate: (AgentTemplate) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        QuestionTitle("What should this agent ", "bring you", "?")
        StepHint("One sentence is enough — Next generates the questionnaire; you tune from there.")

        FieldLabel("Name")
        OutlinedTextField(
            value = form.name,
            onValueChange = setName,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. LLM Tracker", color = palette.OnInkFaint) },
            singleLine = true, shape = RoundedCornerShape(12.dp),
            isError = nameError != null,
            supportingText = { nameError?.let { Text(it, style = SapphireMono.Label, color = palette.Danger) } },
        )
        Spacer(Modifier.height(14.dp))

        FieldLabel("Goal")
        OutlinedTextField(
            value = form.goal,
            onValueChange = setGoal,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Track new open-source LLM inference engines and summarize releases", color = palette.OnInkFaint) },
            minLines = 3, maxLines = 5, shape = RoundedCornerShape(12.dp),
        )

        FieldLabel("Start from a template")
        AgentTemplates.all.chunked(2).forEach { rowTemplates ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .height(IntrinsicSize.Min),
            ) {
                rowTemplates.forEach { t ->
                    TemplateCard(
                        t = t,
                        selected = form.templateName == t.name,
                        onClick = { loadTemplate(t) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (rowTemplates.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TemplateCard(t: AgentTemplate, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalSapphirePalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.Accent.copy(alpha = 0.07f) else palette.InkElevated)
            .border(1.dp, if (selected) palette.Accent else palette.InkStroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(11.dp),
    ) {
        Text(t.icon, style = MaterialTheme.typography.titleMedium)
        Text(t.name, style = MaterialTheme.typography.labelLarge, color = palette.OnInk, modifier = Modifier.padding(top = 4.dp))
        Text(t.tagline, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, lineHeight = 12.sp), color = palette.OnInkFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- step 2 · tune ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuneStep(
    form: BuilderForm,
    isGenerating: Boolean,
    generateNote: String?,
    selectCandidate: (BuilderQuestion, Int) -> Unit,
    startCustom: (BuilderQuestion) -> Unit,
    setCustom: (BuilderQuestion, String) -> Unit,
    setExtras: (String) -> Unit,
    appendPrefix: (String) -> Unit,
    toggleTag: (String) -> Unit,
    refreshWithNotes: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        QuestionTitle("Tune the ", "loop")
        StepHint("Pick an answer — it drops into the box below, ready to edit. Or write your own.")

        if (isGenerating || generateNote != null) {
            Row(
                Modifier.padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = palette.AccentBright)
                    Text("generating candidates…", style = SapphireMono.Label, color = palette.AccentBright)
                } else {
                    Text(
                        generateNote ?: "",
                        style = SapphireMono.Label,
                        color = if (generateNote?.startsWith("offline") == true) palette.OnInkMuted else palette.AccentBright,
                    )
                }
            }
        }

        BuilderQuestion.entries.forEach { q ->
            QuestionBlock(
                title = "${q.number}  ${q.title}",
                hint = q.hint,
                candidates = form.candidates(q),
                selection = form.selection(q),
                customText = form.customText(q),
                placeholder = q.placeholder,
                onSelect = { selectCandidate(q, it) },
                onOthers = { startCustom(q) },
                onCustom = { setCustom(q, it) },
            )
        }

        // 04 · anything else?
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 16.dp, bottom = 9.dp)) {
            Text("04", style = SapphireMono.Label, color = palette.AccentBright)
            Text("Anything else?", style = MaterialTheme.typography.titleSmall, color = palette.OnInk)
            Spacer(Modifier.weight(1f))
            Text("requirements the questions didn't cover", style = SapphireMono.Label, color = palette.OnInkFaint)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Exclude:", "Focus on:", "Always include:", "Never:").forEach { prefix ->
                Text(
                    "$prefix…",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.OnInkFaint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.InkElevated)
                        .border(1.dp, palette.InkStrokeStrong, RoundedCornerShape(999.dp))
                        .clickable { appendPrefix(prefix) }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            form.blueprint.extraTags.forEach { tag ->
                val on = extrasHasTag(form.extras, tag)
                Text(
                    (if (on) "✓ " else "+ ") + tag,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (on) Color.White else palette.OnInkMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) palette.AccentDeep else palette.InkRaised)
                        .border(1.dp, if (on) palette.Accent else palette.InkStrokeStrong, RoundedCornerShape(999.dp))
                        .clickable { toggleTag(tag) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        OutlinedTextField(
            value = form.extras,
            onValueChange = setExtras,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("e.g. only projects with >1k stars · always note pricing changes · skip weekend noise", color = palette.OnInkFaint) },
            minLines = 2, maxLines = 5, shape = RoundedCornerShape(10.dp),
        )
        if (form.extras.isNotBlank()) {
            Row(
                Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = refreshWithNotes).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Refresh, null, tint = palette.AccentBright, modifier = Modifier.size(13.dp))
                Text("Refresh candidates with your notes", style = SapphireMono.Label, color = palette.AccentBright)
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    title: String,
    hint: String,
    candidates: List<String>,
    selection: Int,
    customText: String,
    placeholder: String,
    onSelect: (Int) -> Unit,
    onOthers: () -> Unit,
    onCustom: (String) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 10.dp, bottom = 9.dp)) {
            val parts = title.split("  ", limit = 2)
            Text(parts[0], style = SapphireMono.Label, color = palette.AccentBright)
            Text(parts.getOrElse(1) { title }, style = MaterialTheme.typography.titleSmall, color = palette.OnInk)
            Spacer(Modifier.weight(1f))
            Text(hint, style = SapphireMono.Label, color = palette.OnInkFaint)
        }
        candidates.forEachIndexed { i, text ->
            val on = selection == i
            Row(
                Modifier.fillMaxWidth().padding(bottom = 7.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) palette.Accent.copy(alpha = 0.07f) else palette.InkElevated)
                    .border(1.dp, if (on) palette.Accent else palette.InkStroke, RoundedCornerShape(12.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    ('A' + i).toString(),
                    style = SapphireMono.Label,
                    color = if (on) palette.AccentBright else palette.OnInkFaint,
                    modifier = Modifier
                        .border(1.dp, if (on) palette.Accent else palette.InkStrokeStrong, RoundedCornerShape(7.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (on) palette.OnInk else palette.OnInkMuted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val unselected = selection == BuilderQuestion.UNSELECTED
        if (unselected) {
            // Nothing chosen yet — offer the write-your-own escape hatch; no answer box.
            Row(
                Modifier.fillMaxWidth().padding(bottom = 7.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, palette.InkStrokeStrong, RoundedCornerShape(12.dp))
                    .clickable { onOthers() }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("✎", style = SapphireMono.Label, color = palette.OnInkFaint)
                Text(
                    "Others — I'll write my own",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.OnInkMuted,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // ✎ your answer — the tapped candidate lands here; edit freely
            val custom = selection == BuilderQuestion.CUSTOM
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "✎ YOUR ANSWER — EDIT FREELY",
                    style = SapphireMono.Eyebrow,
                    color = if (custom) palette.AccentBright else palette.OnInkFaint,
                    modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 5.dp),
                )
                OutlinedTextField(
                    value = customText,
                    onValueChange = onCustom,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder, color = palette.OnInkFaint) },
                    minLines = 2, maxLines = 6,
                    shape = RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.Accent,
                        unfocusedBorderColor = if (custom) palette.Accent else palette.InkStrokeStrong,
                        focusedContainerColor = palette.Ink,
                        unfocusedContainerColor = palette.Ink,
                    ),
                )
            }
        }
    }
}

// ---------- step 3 · preview ----------


private sealed interface SampleBlock {
    data class Heading(val text: String) : SampleBlock
    data class Para(val text: String) : SampleBlock
    data class Bullet(val text: String) : SampleBlock
}

private val SAMPLE_BODY_TOKEN = Regex("<h2>(.*?)</h2>|<p>(.*?)</p>|<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)

private fun parseSampleBody(html: String?): List<SampleBlock> {
    if (html.isNullOrBlank()) return emptyList()
    return SAMPLE_BODY_TOKEN.findAll(html).mapNotNull { m ->
        val strip = { s: String -> s.replace(Regex("<[^>]+>"), "").trim() }
        when {
            m.groups[1] != null -> SampleBlock.Heading(strip(m.groupValues[1]))
            m.groups[2] != null -> SampleBlock.Para(strip(m.groupValues[2]))
            m.groups[3] != null -> SampleBlock.Bullet(strip(m.groupValues[3]))
            else -> null
        }
    }.toList()
}

@Composable
private fun PreviewStep(
    form: BuilderForm,
    isRunning: Boolean,
    testResult: TestRunResult?,
    runEvents: List<RunEvent>,
) {
    val palette = LocalSapphirePalette.current
    val agentName = form.name.ifBlank { "new agent" }

    Column(Modifier.fillMaxWidth()) {
        QuestionTitle("Read one ", "sample")
        StepHint("The real loop, live — built from your answers. Nothing is filed.")

        when {
            isRunning -> DryRunWaiting(runEvents)
            testResult != null -> DryRunDone(result = testResult, agentName = agentName)
            else -> DryRunWaiting(runEvents)
        }
    }
}

@Composable
private fun ArticleBody(blocks: List<SampleBlock>) {
    val palette = LocalSapphirePalette.current
    Column {
        blocks.forEach { b ->
            when (b) {
                is SampleBlock.Heading -> Text(
                    b.text,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.OnInk,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                is SampleBlock.Para -> Text(
                    b.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.OnInkMuted,
                    modifier = Modifier.padding(bottom = 11.dp),
                )
                is SampleBlock.Bullet -> Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("·", style = MaterialTheme.typography.bodyMedium, color = palette.OnInkFaint)
                    Text(b.text, style = MaterialTheme.typography.bodyMedium, color = palette.OnInkMuted)
                }
            }
        }
    }
}

@Composable
private fun SourcesBlock(domains: List<String>) {
    val palette = LocalSapphirePalette.current
    if (domains.isEmpty()) return
    Column(Modifier.padding(top = 13.dp)) {
        HorizontalDivider(color = palette.InkStrokeStrong, thickness = androidx.compose.ui.unit.Dp.Hairline)
        Text("SOURCES · ${domains.size}", style = SapphireMono.Eyebrow, color = palette.AccentBright, modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))
        Text(domains.joinToString(" · "), style = SapphireMono.Body, color = palette.AccentBright)
    }
}

@Composable
private fun PreviewFooter(note: String, right: @Composable () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier.fillMaxWidth().background(palette.InkElevated).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(note, style = SapphireMono.Label, color = palette.OnInkFaint, modifier = Modifier.weight(1f))
        right()
    }
}

@Composable
private fun DryRunDone(result: TestRunResult, agentName: String) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.InkElevated).border(1.dp, palette.InkStroke, RoundedCornerShape(14.dp)).padding(15.dp)) {
            val article = result.article
            if (article == null) {
                Text(
                    result.error ?: if (result.itemCount == 0) "No new signal after dedupe — a valid outcome." else "Nothing synthesized.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.error != null) palette.Danger else palette.OnInkMuted,
                )
            } else {
                Text(article.title, style = MaterialTheme.typography.headlineSmall, color = palette.OnInk)
                Text(
                    "✦ $agentName  ·  ${article.sources.size} sources  ·  ${"%.0fs".format(result.durationMs / 1000.0)}",
                    style = SapphireMono.Label, color = palette.OnInkFaint,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                val blocks = remember(article.bodyHtml) {
                    com.sapphire.data.reader.JsoupRichContentParser().parse(article.bodyHtml)
                }
                RichBlockList(blocks = blocks)
                article.summary?.takeIf { blocks.isEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.OnInkMuted)
                }
                SourcesBlock(article.sources)
            }
        }
        if (result.items.size > 1) {
            Text(
                "Also synthesized: " + result.items.drop(1).joinToString(" · "),
                style = SapphireMono.Body, color = palette.OnInkFaint,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    PreviewFooter(
        note = "${result.itemCount} item${if (result.itemCount == 1) "" else "s"} · ${result.tokensUsed} tok · ${"%.0fs".format(result.durationMs / 1000.0)} — nothing filed until you create",
    ) {
        Text("", style = SapphireMono.Label)
    }
}

@Composable
private fun DryRunWaiting(runEvents: List<RunEvent>) {
    val palette = LocalSapphirePalette.current
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); elapsed++ }
    }
    val pulse = rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "scale",
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(54.dp).scale(pulse.value).clip(RoundedCornerShape(17.dp)).background(palette.AccentDeep),
            contentAlignment = Alignment.Center,
        ) { Text("✦", color = Color.White, style = MaterialTheme.typography.titleLarge) }
        Text("Agent is running", style = MaterialTheme.typography.titleMedium, color = palette.OnInk, modifier = Modifier.padding(top = 14.dp))
        Text("NOTHING IS FILED", style = SapphireMono.Label, color = palette.OnInkFaint, modifier = Modifier.padding(top = 5.dp, bottom = 22.dp))
        Column(Modifier.fillMaxWidth()) {
            if (runEvents.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = palette.AccentBright)
                    Text("Planning queries…", style = MaterialTheme.typography.titleSmall, color = palette.OnInk)
                }
            } else {
                runEvents.forEachIndexed { i, e ->
                    val isLast = i == runEvents.lastIndex
                    val label = when (e.phase) {
                        AgentLoopService.EVENT_SEARCH -> "Searching"
                        AgentLoopService.EVENT_FETCH -> "Reading"
                        AgentLoopService.EVENT_WRITE -> "Writing"
                        else -> e.phase
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 9.dp).alpha(if (isLast) 1f else 0.6f),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(22.dp).padding(top = 4.dp), contentAlignment = Alignment.Center) {
                            if (isLast) {
                                CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp, color = palette.AccentBright)
                            } else {
                                Text("✓", style = SapphireMono.Label, color = palette.OnInkFaint)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleSmall, color = palette.OnInk)
                            Text(
                                e.detail,
                                style = SapphireMono.Label,
                                color = if (isLast) palette.AccentBright else palette.OnInkFaint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Text("elapsed ${elapsed}s", style = SapphireMono.Label, color = palette.OnInkFaint, modifier = Modifier.padding(top = 18.dp))
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceStep(
    form: BuilderForm,
    folders: List<FolderOption>,
    setCategoryId: (String?) -> Unit,
    createFolder: (String) -> Unit,
    setFrequency: (AgentFrequency) -> Unit,
    setTriggerTime: (String) -> Unit,
    setMaxItems: (Int) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        QuestionTitle("Schedule ", "it")
        StepHint("Agents live beside your feeds — pick or create the folder it files into.")

        FieldLabel("Folder")
        FolderDropdown(form = form, folders = folders, onPick = setCategoryId, onCreateFolder = createFolder)
        Spacer(Modifier.height(14.dp))

        FieldLabel("Cadence")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(
                AgentFrequency.HOURLY_1 to "1h", AgentFrequency.HOURLY_2 to "2h",
                AgentFrequency.HOURLY_4 to "4h", AgentFrequency.HOURLY_6 to "6h",
                AgentFrequency.HOURLY_8 to "8h", AgentFrequency.HOURLY_12 to "12h",
            ).forEach { (f, l) -> SelectChip(form.frequency == f, l) { setFrequency(f) } }
            listOf(
                AgentFrequency.DAILY to "Daily", AgentFrequency.WEEKDAY to "Weekdays", AgentFrequency.WEEKLY to "Weekly",
            ).forEach { (f, l) -> SelectChip(form.frequency == f, l) { setFrequency(f) } }
        }
        Spacer(Modifier.height(14.dp))

        FieldLabel("Trigger time")
        TimeSlider(time = form.triggerTime, onTimeChange = setTriggerTime)
        Spacer(Modifier.height(14.dp))

        FieldLabel("Max items per run")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(1, 2, 4, 8).forEach { n -> SelectChip(form.maxItems == n, n.toString()) { setMaxItems(n) } }
        }
        Spacer(Modifier.height(16.dp))

        SchedNote(
            icon = "⏱",
            content = { Text(buildAnnotatedString {
                append("Next run ")
                withStyle(SpanStyle(color = palette.AccentBright, fontWeight = FontWeight.SemiBold)) {
                    append(nextRunText(form.frequency, form.triggerTime, System.currentTimeMillis()))
                }
                append(" · ≈${runsPerMonth(form.frequency)} runs/mo")
            }, style = SapphireMono.Body, color = palette.OnInkMuted) },
        )
        Spacer(Modifier.height(10.dp))
        SchedNote(
            icon = "🔋",
            dashed = false,
            content = { Text("Scheduled via WorkManager · batches on battery; defers when offline", style = SapphireMono.Body, color = palette.OnInkMuted) },
        )
    }
}
@Composable
private fun SchedNote(icon: String, dashed: Boolean = true, content: @Composable () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.InkElevated)
            .border(1.dp, if (dashed) palette.InkStrokeStrong else palette.InkStroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(icon)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderDropdown(
    form: BuilderForm,
    folders: List<FolderOption>,
    onPick: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    var expanded by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val selected = folders.firstOrNull { it.categoryId == form.categoryId }
        ?: FolderOption(null, "✦ Agents", "default · dedicated folder")
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        Row(
            Modifier.fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .clip(RoundedCornerShape(12.dp))
                .background(if (expanded) palette.InkRaised else palette.InkElevated)
                .border(1.dp, if (expanded) palette.Accent else palette.InkStroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("📁 ${selected.label}", style = MaterialTheme.typography.titleSmall, color = palette.OnInk, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.ArrowDropDown, null,
                tint = palette.OnInkFaint,
                modifier = Modifier.rotateDown(expanded),
            )
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            folders.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(option.label, style = MaterialTheme.typography.titleSmall, color = if (option.categoryId == form.categoryId) palette.AccentBright else palette.OnInk)
                            Spacer(Modifier.weight(1f))
                            Text(option.desc, style = SapphireMono.Label, color = palette.OnInkFaint)
                            if (option.categoryId == form.categoryId) Text("✓", style = SapphireMono.Label, color = palette.AccentBright)
                        }
                    },
                    onClick = { onPick(option.categoryId); expanded = false },
                )
            }
            HorizontalDivider(color = palette.InkStroke, modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("＋ New folder…", style = MaterialTheme.typography.titleSmall, color = palette.AccentBright) },
                trailingIcon = { Icon(Icons.Filled.CreateNewFolder, null, tint = palette.AccentBright, modifier = Modifier.size(16.dp)) },
                onClick = { expanded = false; newFolderName = ""; showCreate = true },
            )
        }
    }
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            containerColor = palette.InkElevated,
            title = { Text("New folder", style = MaterialTheme.typography.titleMedium, color = palette.OnInk) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. Infra", color = palette.OnInkFaint) },
                    singleLine = true, shape = RoundedCornerShape(10.dp),
                )
            },
            confirmButton = {
                Text(
                    "Create",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.AccentBright,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = newFolderName.isNotBlank()) {
                            onCreateFolder(newFolderName)
                            showCreate = false
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.OnInkMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showCreate = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            },
        )
    }
}

/** Chevron that rotates 180° while the dropdown is open. */
private fun Modifier.rotateDown(expanded: Boolean): Modifier =
    if (expanded) this.rotate(180f) else this

/** 24h dial: 15-minute steps, ticks at 0/6/12/18/24, big mono readout. */
@Composable
private fun TimeSlider(time: String, onTimeChange: (String) -> Unit) {
    val palette = LocalSapphirePalette.current
    val minutes = remember(time) {
        val (h, m) = time.split(":").map { it.toIntOrNull() ?: 0 }
        (h * 60 + m).coerceIn(0, 1410)
    }
    Column {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Text(time, style = SapphireMono.Label.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold), color = palette.OnInk)
            Text("next run today (or on charge) · drag the dial", style = SapphireMono.Label, color = palette.OnInkFaint)
        }
        Slider(
            value = minutes.toFloat(),
            onValueChange = { v ->
                val snapped = (v / 15).toInt() * 15
                onTimeChange("%02d:%02d".format(snapped / 60, snapped % 60))
            },
            valueRange = 0f..1410f,
            steps = (1410 / 15) - 1,
            colors = SliderDefaults.colors(
                thumbColor = palette.AccentBright,
                activeTrackColor = palette.Accent,
                inactiveTrackColor = palette.InkStrokeStrong,
            ),
        )
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
                Text(it, style = SapphireMono.Label.copy(fontSize = 8.5.sp), color = palette.OnInkFaint)
            }
        }
    }
}
