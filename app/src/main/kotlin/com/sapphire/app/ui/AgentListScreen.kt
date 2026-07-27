package com.sapphire.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.model.AgentJob

/**
 * Agents list (design: `design/agents.html` list view). Hero with summary stats,
 * agent cards (status pill, directive, cadence/next-run meta), FAB → builder.
 * Empty state with the AI orb when no agents exist.
 *
 * Slice A: items-filed/total-runs stats are 0 until Slice B executes runs.
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
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = palette.OnInk)
                    }
                },
                actions = {
                    androidx.compose.material3.IconButton(onClick = { /* Slice B: help sheet */ }) {
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
            items(agents, key = { it.job.id }) { card ->
                AgentCard(
                    card = card,
                    onClick = { onOpen(card.job.id) },
                    onToggle = { viewModel.toggle(card.job.id, !card.job.enabled) },
                )
            }
        }
    }
}

@Composable
private fun AgentHero(stats: AgentListStats) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
        SectionEyebrow("Prompt agents")
        Text(
            "Feeds the AI builds\nfor you.",
            style = MaterialTheme.typography.displaySmall,
            color = palette.OnInk,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("${stats.active}/${stats.total}", "active")
            Stat(stats.itemsFiled.toString(), "items filed")
            Stat(stats.totalRuns.toString(), "total runs")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val palette = LocalSapphirePalette.current
    Column {
        Text(value, style = SapphireMono.Label, color = palette.OnInk, fontWeight = FontWeight.SemiBold)
        Text(
            label.uppercase(),
            style = SapphireMono.Label,
            color = palette.OnInkFaint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun AgentCard(
    card: AgentCardUi,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val job = card.job
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.InkElevated)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Agent glyph tile
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                        .background(palette.Accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✦", color = palette.AccentBright, style = SapphireMono.Label)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.OnInk,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tierLabel(job.modelTier) + " · " + job.searchTool.name,
                        style = SapphireMono.Label,
                        color = palette.OnInkFaint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                StatusPill(enabled = job.enabled)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                job.directive,
                style = MaterialTheme.typography.bodySmall,
                color = palette.OnInkMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            // Meta row: cadence · next-run · (tap card to open; long-press not needed — toggle in detail)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetaText(card.cadenceLabel)
                MetaText(if (job.enabled) "next: " + card.nextRun else card.nextRun, accent = job.enabled)
            }
        }
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
        Text(
            if (enabled) "ACTIVE" else "PAUSED",
            style = SapphireMono.Label,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetaText(text: String, accent: Boolean = false) {
    val palette = LocalSapphirePalette.current
    Text(
        text,
        style = SapphireMono.Label,
        color = if (accent) palette.AccentBright else palette.OnInkFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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

private fun tierLabel(tier: Int) = if (tier == 2) "TIER-2 · GLM-4O" else "TIER-1 · MINI"

@Composable
private fun palette() = LocalSapphirePalette.current
