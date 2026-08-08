package com.sapphire.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.agent.AgentTemplate
import com.sapphire.domain.agent.AgentTemplates
import com.sapphire.domain.model.AgentFrequency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBuilderScreen(
    onBack: () -> Unit,
    viewModel: AgentBuilderViewModel = hiltViewModel(),
) {
    val palette = LocalSapphirePalette.current
    val form by viewModel.form.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val generateError by viewModel.generateError.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val nameError by viewModel.nameError.collectAsStateWithLifecycle()
    val isEdit = viewModel.isEdit
    var showGallery by remember { mutableStateOf(false) }

    LaunchedEffect(saved) { if (saved) onBack() }
    val canCreate = form.name.isNotBlank() && form.goal.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "EDIT AGENT" else "NEW AGENT", style = SapphireMono.Label, color = palette.OnInk, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel", tint = palette.OnInk) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.Ink, navigationIconContentColor = palette.OnInk, titleContentColor = palette.OnInk),
            )
        },
        bottomBar = { BuilderBottomBar(canCreate, isEdit, viewModel::submit) },
    ) { padding ->
        Column(Modifier.fillMaxSize().background(palette.Ink).padding(padding).verticalScroll(rememberScrollState())) {
            if (!isEdit) TemplateButton(onClick = { showGallery = true })

            FieldLabel("1", "Name", "how it shows in your feed")
            OutlinedTextField(
                value = form.name, onValueChange = { viewModel.setName(it); viewModel.clearNameError() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                placeholder = { Text("e.g. LLM Infra Scanner", color = palette.OnInkFaint) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                isError = nameError != null,
                supportingText = { nameError?.let { Text(it, style = SapphireMono.Label, color = palette.Danger) } },
            )

            FieldLabel("2", "Goal", "what the agent should do")
            OutlinedTextField(
                value = form.goal, onValueChange = viewModel::setGoal,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                placeholder = { Text("e.g. Summarize a random TED talk daily", color = palette.OnInkFaint) },
                minLines = 2, maxLines = 4, shape = RoundedCornerShape(12.dp),
            )
            OutlinedButton(
                onClick = viewModel::generateDirective,
                enabled = !isGenerating && form.goal.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.AccentBright),
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.Accent),
            ) {
                if (isGenerating) { CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = palette.Accent) }
                else { Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(15.dp)) }
                Spacer(Modifier.width(6.dp))
                Text(if (isGenerating) "Generating..." else "Generate structured prompt", style = SapphireMono.Label)
            }
            generateError?.let { Text(it, style = SapphireMono.Label, color = palette.Danger, modifier = Modifier.padding(horizontal = 22.dp)) }

            FieldLabel("3", "Task", "step-by-step procedure")
            PromptField(form.task, viewModel::setTask, "Tap Generate, or write the steps yourself")
            FieldLabel("4", "Format", "how each item looks in your feed")
            PromptField(form.format, viewModel::setFormat, "e.g. Title + 3 bullets + takeaway. Max 200 words.")
            FieldLabel("5", "Rules", "constraints, skips, quality guards")
            PromptField(form.rules, viewModel::setRules, "e.g. Skip musical performances. No fabricated quotes.")

            FieldLabel("6", "Max items", "items per run")
            MaxItemsRow(form.maxItems, viewModel::setMaxItems, Modifier.padding(horizontal = 22.dp))

            FieldLabel("7", "Frequency", "interval or schedule")
            FrequencyRow(form.frequency, viewModel::setFrequency, Modifier.padding(horizontal = 22.dp))
            TimeRow(isHourly = form.frequency.isHourly, time = form.triggerTime, onTimeChange = viewModel::setTriggerTime, modifier = Modifier.padding(horizontal = 22.dp))
            CadenceNote(modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            Text("\u2248 Next run: " + viewModel.nextRunLabel(), style = SapphireMono.Label, color = palette.AccentBright, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))

            BuilderTestRun(form = form, testResult = testResult, isRunning = isRunning, onTestRun = viewModel::testRun)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showGallery) { GallerySheet(onPick = { viewModel.loadTemplate(it); showGallery = false }, onDismiss = { showGallery = false }) }
}

@Composable
private fun PromptField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val palette = LocalSapphirePalette.current
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        placeholder = { Text(placeholder, color = palette.OnInkFaint) },
        minLines = 2, maxLines = 5, shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun SelectChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val palette = LocalSapphirePalette.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = SapphireMono.Label) },
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = palette.InkElevated,
            labelColor = palette.OnInkMuted,
            selectedContainerColor = palette.Accent.copy(alpha = 0.16f),
            selectedLabelColor = palette.AccentBright,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) palette.Accent else palette.InkStroke),
    )
}

@Composable
private fun FieldLabel(num: String, label: String, hint: String) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            num,
            style = SapphireMono.Label,
            color = palette.OnInkFaint,
            modifier = Modifier
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(palette.InkRaised)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(label, style = SapphireMono.Label, color = palette.OnInkFaint, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(hint, style = SapphireMono.Label, color = palette.OnInkFaint)
    }
}

@Composable
private fun TemplateButton(onClick: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.Accent.copy(alpha = 0.10f))
            .border(1.dp, palette.Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = palette.AccentBright, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text("Inspired from template", style = MaterialTheme.typography.titleSmall, color = palette.OnInk, fontWeight = FontWeight.SemiBold)
            Text("12 presets across 4 categories", style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = palette.AccentBright)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FrequencyRow(freq: AgentFrequency, onPick: (AgentFrequency) -> Unit, modifier: Modifier) {
    val hourly = listOf(
        AgentFrequency.HOURLY_1 to "1h", AgentFrequency.HOURLY_2 to "2h",
        AgentFrequency.HOURLY_4 to "4h", AgentFrequency.HOURLY_6 to "6h",
        AgentFrequency.HOURLY_8 to "8h", AgentFrequency.HOURLY_12 to "12h",
    )
    val scheduled = listOf(
        AgentFrequency.DAILY to "Daily", AgentFrequency.WEEKDAY to "Weekdays", AgentFrequency.WEEKLY to "Weekly",
    )
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        hourly.forEach { (f, l) -> SelectChip(freq == f, l) { onPick(f) } }
        Spacer(Modifier.width(4.dp))
        scheduled.forEach { (f, l) -> SelectChip(freq == f, l) { onPick(f) } }
    }
}

@Composable
private fun TimeRow(isHourly: Boolean, time: String, onTimeChange: (String) -> Unit, modifier: Modifier) {
    val palette = LocalSapphirePalette.current
    var expanded by remember { mutableStateOf(false) }
    val slots = remember {
        buildList { for (h in 0..23) for (m in listOf(0, 30)) add("%02d:%02d".format(h, m)) }
    }
    Row(modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (isHourly) "START AT" else "TRIGGER AT",
            style = SapphireMono.Label,
            color = palette.OnInkFaint,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(74.dp),
        )
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(palette.Accent.copy(alpha = 0.12f))
                    .clickable { expanded = true }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(time, style = SapphireMono.Label, color = palette.AccentBright, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Filled.ArrowDropDown, null, tint = palette.AccentBright)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                slots.forEach { slot ->
                    DropdownMenuItem(
                        text = { Text(slot, style = SapphireMono.Label) },
                        onClick = { onTimeChange(slot); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun CadenceNote(modifier: Modifier) {
    val palette = LocalSapphirePalette.current
    Text(
        "Android batches background work for battery — runs fire near this window, not on the minute. Foreground catch-up runs when you open the app.",
        style = SapphireMono.Body,
        color = palette.OnInkFaint,
        modifier = modifier,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MaxItemsRow(maxItems: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MAX/ RUN", style = SapphireMono.Label, color = LocalSapphirePalette.current.OnInkFaint, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(74.dp))
        listOf(1, 3, 5, 10).forEach { n ->
            SelectChip(maxItems == n, n.toString()) { onPick(n) }
        }
    }
}

@Composable
private fun TemplateRow(t: AgentTemplate, onClick: () -> Unit, modifier: Modifier) {
    val palette = LocalSapphirePalette.current
    Column(
        modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(palette.InkElevated)
            .clickable(onClick = onClick)
            .padding(11.dp),
    ) {
        Text(t.name, style = MaterialTheme.typography.titleSmall, color = palette.OnInk, fontWeight = FontWeight.SemiBold)
        Text(t.tagline, style = MaterialTheme.typography.bodySmall, color = palette.AccentBright, modifier = Modifier.padding(top = 1.dp))
        Text(t.goal, style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun BuilderBottomBar(canCreate: Boolean, isEdit: Boolean, onCreate: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.Ink)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Button(
            onClick = onCreate,
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.Accent,
                contentColor = palette.OnInk,
                disabledContainerColor = palette.InkRaised,
                disabledContentColor = palette.OnInkFaint,
            ),
        ) { Text(if (isEdit) "Save agent" else "Create agent", fontWeight = FontWeight.SemiBold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GallerySheet(onPick: (AgentTemplate) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalSapphirePalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = palette.InkElevated) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Template gallery",
                style = MaterialTheme.typography.titleMedium,
                color = palette.OnInk,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
            AgentTemplates.categories.forEach { cat ->
                Text(
                    cat.title.uppercase(),
                    style = SapphireMono.Label,
                    color = palette.OnInk,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 2.dp),
                )
                Text(cat.desc, style = MaterialTheme.typography.bodySmall, color = palette.OnInkFaint, modifier = Modifier.padding(horizontal = 18.dp))
                AgentTemplates.byCategory(cat.id).forEach { t ->
                    TemplateRow(t, onClick = { onPick(t) }, modifier = Modifier.padding(horizontal = 18.dp))
                    HorizontalDivider(color = palette.InkStroke, thickness = androidx.compose.ui.unit.Dp.Hairline, modifier = Modifier.padding(horizontal = 18.dp))
                }
            }
        }
    }
}

@Composable
private fun BuilderTestRun(
    form: BuilderForm,
    testResult: TestRunResult?,
    isRunning: Boolean,
    onTestRun: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(
            onClick = onTestRun,
            enabled = !isRunning && form.goal.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.AccentBright),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.Accent),
        ) {
            if (isRunning) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = palette.Accent)
            } else {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(7.dp))
            Text(if (isRunning) "Running…" else "Test Run (preview result)", fontWeight = FontWeight.SemiBold)
        }
        // Running indicator
        if (isRunning) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(palette.Accent.copy(alpha = 0.08f)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Running — searching & synthesizing…", style = SapphireMono.Label, color = palette.AccentBright)
            }
        }
        // Result panel
        testResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (result.success) palette.Accent.copy(alpha = 0.08f) else palette.Danger.copy(alpha = 0.08f))
                    .verticalScroll(rememberScrollState()).padding(14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (result.success) "✓ SUCCESS" else "✗ FAILED", style = SapphireMono.Label, color = if (result.success) palette.AccentBright else palette.Danger, fontWeight = FontWeight.SemiBold)
                    Text("%.1fs".format(result.durationMs / 1000.0), style = SapphireMono.Label, color = palette.OnInkFaint, fontWeight = FontWeight.SemiBold)
                }
                result.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.Danger, modifier = Modifier.padding(top = 6.dp)) }
                if (result.items.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("${result.itemCount} items synthesized:", style = SapphireMono.Label, color = palette.OnInkMuted, fontWeight = FontWeight.SemiBold)
                    result.items.forEach { item ->
                        Text("• $item", style = MaterialTheme.typography.bodySmall, color = palette.OnInk, modifier = Modifier.padding(top = 4.dp, start = 8.dp))
                    }
                }
            }
        }
    }
}

