package com.sapphire.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.agent.AgentTemplate
import com.sapphire.domain.agent.AgentTemplates
import com.sapphire.domain.model.AgentFrequency
import com.sapphire.domain.model.AgentRecency
import com.sapphire.domain.model.AgentStyle
import com.sapphire.domain.model.OutputLanguage
import com.sapphire.domain.model.SearchTool

/**
 * Agent builder (design: `design/agents.html` builder view). Six fields, live cost,
 * dynamic preview, 3 quick-starts, and a "Browse all" gallery bottom sheet.
 * Edit mode loads the existing job (jobId arg) into the form.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AgentBuilderScreen(
    onBack: () -> Unit,
    viewModel: AgentBuilderViewModel = hiltViewModel(),
) {
    val palette = LocalSapphirePalette.current
    val form by viewModel.form.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var showGallery by remember { mutableStateOf(false) }

    LaunchedEffect(saved) { if (saved) onBack() }

    val cost = viewModel.cost()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (viewModel.isEdit) "EDIT AGENT" else "NEW AGENT",
                        style = SapphireMono.Label,
                        color = palette.OnInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel", tint = palette.OnInk)
                    }
                },
            )
        },
        bottomBar = {
            BuilderBottomBar(cost = cost, isEdit = viewModel.isEdit, onCreate = viewModel::submit)
        },
        containerColor = palette.Ink,
        contentColor = palette.OnInk,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            BuilderHero(isEdit = viewModel.isEdit)

            FieldLabel("1", "Name", "how it shows in your feed")
            OutlinedTextField(
                value = form.name,
                onValueChange = viewModel::setName,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                placeholder = { Text("e.g. LLM Infra Scanner", color = palette.OnInkFaint) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            FieldLabel("2", "Directive", "what it should synthesize")
            OutlinedTextField(
                value = form.directive,
                onValueChange = viewModel::setDirective,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                placeholder = { Text("Describe what the agent should search for and synthesize…", color = palette.OnInkFaint) },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(12.dp),
            )

            FieldLabel("3", "Search tool", "where it looks")
            ChipRow(modifier = Modifier.padding(horizontal = 22.dp)) {
                SearchTool.entries.forEach { tool ->
                    SelectChip(
                        selected = form.searchTool == tool,
                        label = toolLabel(tool),
                        onClick = { viewModel.setTool(tool) },
                    )
                }
            }

            FieldLabel("4", "Frequency", "interval or schedule")
            FrequencyRow(form.frequency, viewModel::setFrequency, Modifier.padding(horizontal = 22.dp))
            // Trigger/Start time — always shown; label swaps for hourly vs scheduled.
            TimeRow(
                isHourly = form.frequency.isHourly,
                time = form.triggerTime,
                onTimeChange = viewModel::setTriggerTime,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            CadenceNote(modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            Text(
                "≈ Next run: ${viewModel.nextRunLabel()}",
                style = SapphireMono.Label,
                color = palette.AccentBright,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
            )

            FieldLabel("5", "Output & scope", "time, language, voice")
            ScopeRows(form, viewModel, Modifier.padding(horizontal = 22.dp))

            // Quick-start templates
            Text(
                "Or start from a template",
                style = SapphireMono.Label,
                color = palette.OnInkFaint,
                modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 8.dp),
            )
            AgentTemplates.quickStartIndices.forEach { i ->
                val t = AgentTemplates.all[i]
                TemplateRow(t, onClick = { viewModel.loadTemplate(t) }, modifier = Modifier.padding(horizontal = 22.dp))
            }
            Text(
                "BROWSE ALL TEMPLATES",
                style = SapphireMono.Label,
                color = palette.OnInkMuted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 22.dp, top = 4.dp)
                    .clickable { showGallery = true },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showGallery) {
        GallerySheet(onPick = { viewModel.loadTemplate(it); showGallery = false }, onDismiss = { showGallery = false })
    }
}

@Composable
private fun BuilderHero(isEdit: Boolean) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
        SectionEyebrow(if (isEdit) "Edit agent" else "Prompt agent · §3.7")
        Text(
            if (isEdit) "Refine the agent." else "Describe a feed\nthat doesn't exist yet.",
            style = MaterialTheme.typography.headlineSmall,
            color = palette.OnInk,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
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
private fun ScopeRows(form: BuilderForm, viewModel: AgentBuilderViewModel, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentRecency.entries.forEach { SelectChip(form.recency == it, recencyLabel(it)) { viewModel.setRecency(it) } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputLanguage.entries.forEach { SelectChip(form.outputLanguage == it, langLabel(it)) { viewModel.setLanguage(it) } }
        }
        // Style as a dropdown (6 options).
        StyleDropdown(form.style, viewModel::setStyle)
    }
}

@Composable
private fun StyleDropdown(style: AgentStyle, onPick: (AgentStyle) -> Unit) {
    val palette = LocalSapphirePalette.current
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("STYLE", style = SapphireMono.Label, color = palette.OnInkFaint, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(74.dp))
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
                Text(styleLabel(style), style = SapphireMono.Label, color = palette.AccentBright, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Filled.ArrowDropDown, null, tint = palette.AccentBright)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AgentStyle.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(styleLabel(s), style = SapphireMono.Label) }, onClick = { onPick(s); expanded = false })
                }
            }
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
        Text(t.directive, style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun BuilderBottomBar(cost: BuilderCost, isEdit: Boolean, onCreate: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.Ink)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(cost.perRunLabel, style = SapphireMono.Body, color = palette.OnInkFaint)
        Text(cost.perMonthLabel, style = SapphireMono.Body, color = palette.OnInkFaint)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onCreate,
            enabled = cost.canCreate,
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

private fun toolLabel(t: SearchTool) = when (t) {
    SearchTool.TAVILY -> "Tavily"; SearchTool.DDG -> "DuckDuckGo"
}
private fun recencyLabel(r: AgentRecency) = when (r) {
    AgentRecency.H24 -> "24h"; AgentRecency.WEEK -> "Week"; AgentRecency.MONTH -> "Month"
    AgentRecency.YEAR -> "Year"; AgentRecency.ALL -> "All"
}
private fun langLabel(l: OutputLanguage) = when (l) {
    OutputLanguage.EN -> "EN"; OutputLanguage.ZH -> "中文"; OutputLanguage.MATCH_SOURCE -> "Match source"
}
private fun styleLabel(s: AgentStyle) = when (s) {
    AgentStyle.BRIEF -> "Analyst brief"; AgentStyle.BULLETED -> "Bulleted"
    AgentStyle.CONVERSATIONAL -> "Conversational"; AgentStyle.ACADEMIC -> "Academic"
    AgentStyle.HOTTAKE -> "Hot take"; AgentStyle.EXPLAINER -> "Explainer"
}
