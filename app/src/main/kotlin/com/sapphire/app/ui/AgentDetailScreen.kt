package com.sapphire.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono

/**
 * Agent detail (design: `design/agents.html` detail view). Header with config pills,
 * Pause/Resume + Run-now + Delete actions, a 3-up stats grid, and the run-history timeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: AgentDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val runQueued by viewModel.runQueued.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val palette = LocalSapphirePalette.current

    LaunchedEffect(deleted) { if (deleted) onBack() }
    LaunchedEffect(runQueued) {
        if (runQueued) {
            snackbarHostState.showSnackbar("Run queued — check back in a moment.")
            viewModel.consumeRunQueued()
        }
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AGENT", style = SapphireMono.Label, color = palette.OnInk, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = palette.OnInk)
                    }
                },
                actions = {
                    IconButton(onClick = { state.job?.let { onEdit(it.id) } }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit agent", tint = palette.OnInk)
                    }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        containerColor = palette.Ink,
        contentColor = palette.OnInk,
    ) { padding ->
        val job = state.job ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                DetailHeader(state)
                DetailActions(
                    enabled = job.enabled,
                    onToggle = viewModel::toggle,
                    onDelete = { showDeleteDialog = true },
                )
                // Test Run button — verifies agent settings without filing to feed.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::testRun,
                        enabled = !isRunning,
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
                        Text(if (isRunning) "Running…" else "Test Run", fontWeight = FontWeight.SemiBold)
                    }
                }
                // Run now — executes AND files items to the feed.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    OutlinedButton(
                        onClick = viewModel::runNow,
                        enabled = !isRunning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.OnInk),
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.InkStroke),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Run now (files to feed)", fontWeight = FontWeight.SemiBold)
                    }
                }
                RunResultPanel(
                    testResult = testResult,
                    isRunning = isRunning,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionEyebrow("Run history")
                    Text("${state.totalRuns}", style = SapphireMono.Label, color = palette.OnInkFaint)
                }
            }
            items(state.runs) { row -> RunTimelineRow(row) }
        }
    }

    if (showDeleteDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete agent?", style = MaterialTheme.typography.titleMedium) },
            text = { Text("This removes the agent and all its filed feed items.", style = MaterialTheme.typography.bodyMedium, color = palette.OnInkMuted) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDeleteDialog = false; viewModel.delete() },
                ) { Text("Delete", color = palette.Danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = palette.OnInkMuted)
                }
            },
            containerColor = palette.InkElevated,
        )
    }
}

@Composable
private fun DetailHeader(state: AgentDetailUi) {
    val palette = LocalSapphirePalette.current
    val job = state.job ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(palette.Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text("✦", color = palette.AccentBright, style = MaterialTheme.typography.titleMedium) }
            StatusPill(enabled = job.enabled)
        }
        Text(
            job.name,
            style = MaterialTheme.typography.headlineSmall,
            color = palette.OnInk,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            job.directive,
            style = MaterialTheme.typography.bodySmall,
            color = palette.OnInkMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        // Config pills
        PillFlow(
            listOf(
                state.cadenceLabel,
                state.recencyLabel,
                state.styleLabel,
                state.langLabel,
                if (job.enabled) "next: ${state.nextRun}" else "paused",
            ),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PillFlow(pills: List<String>) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        pills.forEach { MetaPill(it) }
    }
}

@Composable
private fun MetaPill(text: String) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.InkRaised)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text.uppercase(), style = SapphireMono.Label, color = palette.OnInkMuted, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusPill(enabled: Boolean) {
    val palette = LocalSapphirePalette.current
    val color = if (enabled) palette.Accent else palette.OnInkFaint
    Row(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(1.dp)).background(color))
        Text(if (enabled) "ACTIVE" else "PAUSED", style = SapphireMono.Label, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailActions(enabled: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onToggle,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (enabled) palette.OnInk else palette.AccentBright),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) palette.InkStroke else palette.Accent),
        ) {
            if (enabled) Icon(Icons.Filled.Pause, null, modifier = Modifier.size(15.dp)) else Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (enabled) "Pause" else "Resume")
        }
        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.Danger),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.Danger.copy(alpha = 0.4f)),
        ) {
            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text("Delete")
        }
    }
}

@Composable
private fun StatsGrid(state: AgentDetailUi) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.InkStroke),
    ) {
        StatCell(Modifier.weight(1f), state.itemsFiled.toString(), "items filed")
        StatCell(Modifier.weight(1f), state.totalRuns.toString(), "total runs")
        StatCell(Modifier.weight(1f), state.tokensUsed, "tokens used")
    }
}

@Composable
private fun StatCell(modifier: Modifier, value: String, label: String) {
    val palette = LocalSapphirePalette.current
    Column(
        modifier.background(palette.InkElevated).padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = SapphireMono.Label, color = palette.OnInk, fontWeight = FontWeight.SemiBold)
        Text(label.uppercase(), style = SapphireMono.Label, color = palette.OnInkFaint, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun RunTimelineRow(row: RunRow) {
    val palette = LocalSapphirePalette.current
    val dotColor = when (row.status) {
        "OK" -> palette.Accent
        "FAILED" -> palette.Danger
        else -> palette.OnInkFaint
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 12.dp)) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(dotColor))
            Box(Modifier.width(2.dp).height(28.dp).background(palette.InkStroke))
        }
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyMedium, color = palette.OnInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(row.whenLabel, style = SapphireMono.Label, color = palette.OnInkFaint)
                Text(row.meta, style = SapphireMono.Label, color = if (row.status == "OK") palette.Accent else palette.OnInkFaint)
            }
        }
    }
}

@Composable
private fun RunResultPanel(testResult: TestRunResult?, isRunning: Boolean) {
    val palette = LocalSapphirePalette.current
    // Running indicator — spinner, no FAILED badge
    if (isRunning) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.Accent.copy(alpha = 0.08f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.AccentBright)
                Text("Running — searching & synthesizing…", style = SapphireMono.Label, color = palette.AccentBright)
            }
        }
    }
    // Result panel — success/fail + timing + items
    testResult?.let { result ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (result.success) palette.Accent.copy(alpha = 0.08f) else palette.Danger.copy(alpha = 0.08f))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (result.success) "✓ SUCCESS" else "✗ FAILED",
                        style = SapphireMono.Label,
                        color = if (result.success) palette.AccentBright else palette.Danger,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "%.1fs".format(result.durationMs / 1000.0),
                        style = SapphireMono.Label,
                        color = palette.OnInkFaint,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                result.error?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = palette.Danger, modifier = Modifier.padding(top = 6.dp))
                }
                if (result.items.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${result.itemCount} items synthesized:",
                        style = SapphireMono.Label,
                        color = palette.OnInkMuted,
                        fontWeight = FontWeight.SemiBold,
                    )
                    result.items.forEach { item ->
                        Text("• $item", style = MaterialTheme.typography.bodySmall, color = palette.OnInk, modifier = Modifier.padding(top = 4.dp, start = 8.dp))
                    }
                }
            }
        }
    }
}
