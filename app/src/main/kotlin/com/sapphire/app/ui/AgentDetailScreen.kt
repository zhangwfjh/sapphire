package com.sapphire.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono

/**
 * Agent detail (design: `agent-redesign-demo.html` s-detail). Hero with the derived status
 * pill + config chips (folder, cadence, next run), a 4-cell stats strip (items filed, runs,
 * tokens, success rate), Pause/Resume + Run-now + Delete, the run-history timeline, and an
 * overflow menu with Move-to-folder (re-files the agent's source into another drawer folder).
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
    val runResult by viewModel.runResult.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val palette = LocalSapphirePalette.current

    LaunchedEffect(deleted) { if (deleted) onBack() }
    LaunchedEffect(runQueued) {
        if (runQueued) {
            snackbarHostState.showSnackbar("Run complete — items filed to your timeline.")
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
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = palette.OnInk)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Move to folder…") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; showMoveDialog = true },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = palette.Ink,
        contentColor = palette.OnInk,
    ) { padding ->
        val job = state.job ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                DetailHeader(state)
                StatsGrid(state)
                DetailActions(
                    enabled = job.enabled,
                    onToggle = viewModel::toggle,
                    onDelete = { showDeleteDialog = true },
                )
                // Run now — executes AND files items to the feed.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::runNow,
                        enabled = !isRunning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.OnInk),
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.InkStroke),
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = palette.Accent)
                        } else {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(if (isRunning) "Running…" else "Run now", fontWeight = FontWeight.SemiBold)
                    }
                }
                RunResultPanel(
                    runResult = runResult,
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
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete agent?", style = MaterialTheme.typography.titleMedium) },
            text = { Text("This removes the agent and all its filed feed items.", style = MaterialTheme.typography.bodyMedium, color = palette.OnInkMuted) },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false; viewModel.delete() },
                ) { Text("Delete", color = palette.Danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = palette.OnInkMuted)
                }
            },
            containerColor = palette.InkElevated,
        )
    }

    if (showMoveDialog) {
        MoveAgentFolderDialog(
            folders = folders,
            currentCategoryId = state.job?.categoryId,
            onDismiss = { showMoveDialog = false },
            onMove = { categoryId ->
                viewModel.moveToFolder(categoryId)
                showMoveDialog = false
            },
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
            AgentStatusPill(state.status)
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
        // Config chips: folder, cadence, max items, next run.
        PillFlow(
            listOf(
                "📁 ${state.folderLabel}",
                state.cadenceLabel,
                "max ${job.maxItems}/run",
                if (job.enabled) "next: ${state.nextRun}" else "paused",
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PillFlow(pills: List<String>) {
    FlowRow(
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
        Text(text.uppercase(), style = SapphireMono.Label, color = palette.OnInkMuted, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** The 4-cell stats strip: real per-job aggregates + success rate. */
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
        StatCell(Modifier.weight(1f), state.itemsFiled.toString(), "filed")
        StatCell(Modifier.weight(1f), state.totalRuns.toString(), "runs")
        StatCell(Modifier.weight(1f), state.tokensUsed, "tokens")
        StatCell(Modifier.weight(1f), state.successRate, "success")
    }
}

@Composable
private fun StatCell(modifier: Modifier, value: String, label: String) {
    val palette = LocalSapphirePalette.current
    Column(
        modifier.background(palette.InkElevated).padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = palette.OnInk, fontWeight = FontWeight.SemiBold)
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
private fun RunResultPanel(runResult: AgentRunResult?, isRunning: Boolean) {
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
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.AccentBright)
                Text("Running — searching & synthesizing…", style = SapphireMono.Label, color = palette.AccentBright)
            }
        }
    }
    // Result panel — success/fail + timing + items
    runResult?.let { result ->
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

/** Folder picker: ✦ Agents default + every drawer folder; picking moves immediately. */
@Composable
private fun MoveAgentFolderDialog(
    folders: List<AgentFolderOption>,
    currentCategoryId: String?,
    onDismiss: () -> Unit,
    onMove: (categoryId: String?) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.InkElevated,
        title = { Text("Move agent", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                Text(
                    "Filed items follow the agent into the folder you pick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.OnInkMuted,
                )
                LazyColumn(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    items(folders, key = { it.categoryId ?: "default-agents" }) { folder ->
                        val isCurrent = folder.categoryId == currentCategoryId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isCurrent) { onMove(folder.categoryId) }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (folder.categoryId == null) "${folder.label} · default" else folder.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) palette.OnInkFaint else palette.OnInk,
                                modifier = Modifier.weight(1f),
                            )
                            if (isCurrent) {
                                Icon(Icons.Filled.Check, contentDescription = "Current", tint = palette.Accent, modifier = Modifier.size(16.dp))
                            } else {
                                Text("✦", style = SapphireMono.Label, color = palette.AccentBright)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = palette.OnInkMuted) } },
    )
}
