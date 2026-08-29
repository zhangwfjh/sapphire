package com.sapphire.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * Agents hub (design: `agent-redesign-demo.html` s-hub). Hero stat strip (active, filed
 * 24h, total runs, needs-attention), bulk Run-all / Pause-all row, and per-agent cards:
 * folder chip, cadence, status pill, last/next run, items filed, and a sparkline of the
 * last 8 runs. Card tap → detail; per-card Run-now and Pause/Resume; FAB → builder.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun AgentListScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: AgentListViewModel = hiltViewModel(),
) {
    val palette = LocalSapphirePalette.current
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "AGENTS",
                        style = SapphireMono.Label,
                        color = palette.OnInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = palette.OnInk)
                    }
                },
                actions = {
                    IconButton(onClick = { /* Help sheet — post-redesign */ }) {
                        Icon(Icons.Outlined.Info, "How agents work", tint = palette.OnInkMuted)
                    }
                },
            )
        },
        floatingActionButton = {
            if (agents.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    containerColor = palette.Accent,
                    contentColor = palette.OnInk,
                    icon = { Icon(Icons.Filled.Add, "New agent") },
                    text = { Text("New agent") },
                )
            }
        },
        containerColor = palette.Ink,
        contentColor = palette.OnInk,
    ) { padding ->
        if (agents.isEmpty()) {
            EmptyAgents(modifier = Modifier.padding(padding), onNew = onNew)
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { AgentHero(stats) }
            item {
                BulkRow(
                    anyEnabled = agents.any { it.job.enabled },
                    onRunAll = viewModel::runAllNow,
                    onPauseAll = viewModel::pauseAll,
                )
            }
            items(agents, key = { it.job.id }) { card ->
                AgentCard(
                    card = card,
                    onClick = { onOpen(card.job.id) },
                    onRun = { viewModel.runNow(card.job) },
                    onToggle = { viewModel.toggle(card.job.id, !card.job.enabled, card.job.frequency, card.job.triggerTime) },
                )
            }
        }
    }
}

/** Hero strip: the four cross-agent stats in a 2×2 grid of cells. */
@Composable
private fun AgentHero(stats: AgentListStats) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        SectionEyebrow("Agents hub")
        Text(
            "Cross-agent\nhealth.",
            style = MaterialTheme.typography.displaySmall,
            color = palette.OnInk,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCell(Modifier.weight(1f), "${stats.active}/${stats.total}", "ACTIVE", valueColor = palette.OnInk)
            StatCell(Modifier.weight(1f), stats.filed24h.toString(), "FILED 24H", valueColor = if (stats.filed24h > 0) palette.AccentBright else palette.OnInk)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCell(Modifier.weight(1f), stats.totalRuns.toString(), "RUNS", valueColor = palette.OnInk)
            StatCell(Modifier.weight(1f), stats.attention.toString(), "ATTENTION", valueColor = if (stats.attention > 0) palette.Danger else palette.OnInkFaint)
        }
    }
}

@Composable
private fun StatCell(modifier: Modifier, value: String, label: String, valueColor: Color) {
    val palette = LocalSapphirePalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(palette.InkElevated)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = SapphireMono.Label,
            color = palette.OnInkFaint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Bulk ops row: run every enabled agent now, or pause them all. */
@Composable
private fun BulkRow(anyEnabled: Boolean, onRunAll: () -> Unit, onPauseAll: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onRunAll,
            enabled = anyEnabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.OnInk),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.InkStroke),
        ) {
            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Run all now", fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        OutlinedButton(
            onClick = onPauseAll,
            enabled = anyEnabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.OnInkMuted),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.InkStroke),
        ) {
            Icon(Icons.Filled.Pause, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Pause all", fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun AgentCard(
    card: AgentCardUi,
    onClick: () -> Unit,
    onRun: () -> Unit,
    onToggle: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val job = card.job
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(palette.InkElevated)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.OnInk,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AgentStatusPill(card.status)
            }
            Row(
                Modifier.padding(top = 6.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                FolderChip(card.folderLabel)
                Text("·", style = SapphireMono.Label, color = palette.InkStrokeStrong)
                Text(shortCadenceLabel(card.cadenceLabel), style = SapphireMono.Label, color = palette.OnInkMuted, maxLines = 1)
                Text("·", style = SapphireMono.Label, color = palette.InkStrokeStrong)
                Text("✦ agent", style = SapphireMono.Label, color = palette.OnInkFaint)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    CardMetric("LAST RUN", card.lastRunLabel, valueColor = if (card.status == AgentStatusUi.FAILED) palette.Danger else palette.OnInk)
                    Spacer(Modifier.height(7.dp))
                    CardMetric("NEXT", card.nextRun)
                    Spacer(Modifier.height(7.dp))
                    CardMetric("FILED", card.itemsFiled.toString())
                }
                if (card.spark.isNotEmpty()) Sparkline(card.spark)
            }
            Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRun,
                    enabled = card.status != AgentStatusUi.RUNNING && card.status != AgentStatusUi.PAUSED,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.AccentBright),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.Accent.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    if (card.status == AgentStatusUi.RUNNING) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = palette.AccentBright,
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(13.dp))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(if (card.status == AgentStatusUi.RUNNING) "Running" else "Run now", style = SapphireMono.Label, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.OnInkMuted),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.InkStroke),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(
                        if (job.enabled) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(if (job.enabled) "Pause" else "Resume", style = SapphireMono.Label, maxLines = 1)
                }
            }
        }
    }
}


@Composable
private fun CardMetric(label: String, value: String, valueColor: Color? = null) {
    val palette = LocalSapphirePalette.current
    Column {
        Text(label, style = SapphireMono.Label, color = palette.OnInkFaint)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor ?: palette.OnInk,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun FolderChip(label: String) {
    val palette = LocalSapphirePalette.current
    Text(
        label,
        style = SapphireMono.Label,
        color = palette.OnInkMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(palette.Ink)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        maxLines = 1,
    )
}

/** Mini bar chart of the last 8 runs' itemsFiled (oldest → newest, accent bars). */
@Composable
private fun Sparkline(values: List<Int>) {
    val palette = LocalSapphirePalette.current
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.height(26.dp),
    ) {
        values.forEach { v ->
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight(if (v == 0) 0.18f else (v.toFloat() / max).coerceIn(0.25f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (v == 0) palette.Danger.copy(alpha = 0.6f) else palette.Accent),
            )
        }
    }
}

@Composable
private fun EmptyAgents(modifier: Modifier, onNew: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(28.dp))
                .background(palette.Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Text("✦", color = palette.AccentBright, style = MaterialTheme.typography.headlineMedium) }
        Spacer(Modifier.height(14.dp))
        Text(
            "No agents yet",
            style = MaterialTheme.typography.headlineSmall,
            color = palette.OnInk,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Agents turn a prompt and a schedule into a feed. They search the web and file items into your timeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.OnInkMuted,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        androidx.compose.material3.Button(
            onClick = onNew,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = palette.Accent),
        ) { Text("Build your first agent", color = palette.OnInk) }
    }
}
