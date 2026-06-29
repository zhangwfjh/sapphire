package com.sapphire.app.ui

import java.util.Locale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.BuildConfig
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.settings.ThemePreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val palette = LocalSapphirePalette.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbar.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbar) {
        snackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeSnackbar()
        }
    }

    var showKey by remember { mutableStateOf(false) }
    var confirmDialog by remember { mutableStateOf<ClearAction?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = SapphireMono.Label,
                        color = palette.OnInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.OnInk)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.Ink,
                    titleContentColor = palette.OnInk,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = palette.Ink,
        contentColor = palette.OnInk,
    ) { padding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // ── LLM Config ──
            SectionEyebrow("LLM CONFIGURATION")
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::setApiKey,
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showKey) "Hide key" else "Show key",
                            tint = palette.OnInkMuted,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            LlmField("Base URL", state.baseUrl, viewModel::setBaseUrl)
            LlmField("Tier-1 Model (fast)", state.tier1, viewModel::setTier1)
            LlmField("Tier-2 Model (deep)", state.tier2, viewModel::setTier2)

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::testConnection, enabled = connTest !is ConnectionTestState.Testing) {
                    Text("Test connection")
                }
                Spacer(Modifier.height(0.dp))
                when (val ct = connTest) {
                    is ConnectionTestState.Testing -> {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp, color = palette.Accent)
                    }
                    is ConnectionTestState.Ok -> {
                        Spacer(Modifier.width(12.dp))
                        Text("✓ Connected", color = palette.Accent, style = MaterialTheme.typography.bodyMedium)
                    }
                    is ConnectionTestState.Err -> {
                        Spacer(Modifier.width(12.dp))
                        Text("✗ ${ct.message}", color = palette.Danger, style = MaterialTheme.typography.bodyMedium)
                    }
                    ConnectionTestState.Idle -> Unit
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Retention ──
            SectionEyebrow("RETENTION WINDOW")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 14, 30, 60, 90).forEach { days ->
                    FilterChip(
                        selected = state.retentionDays == days,
                        onClick = { viewModel.setRetention(days) },
                        label = { Text("$days days") },
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Theme ──
            SectionEyebrow("THEME")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePreference.entries.forEach { pref ->
                    FilterChip(
                        selected = state.theme == pref,
                        onClick = { viewModel.setTheme(pref) },
                        label = { Text(pref.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Data ──
            SectionEyebrow("DATA")
            Spacer(Modifier.height(8.dp))
            val bd by viewModel.breakdown.collectAsStateWithLifecycle()
            Text(
                "Database: ${formatBytes(bd.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.OnInkMuted,
            )
            ClearRow("Feed items", "${bd.feedItems} · ${formatBytes(bd.feedItemsBytes)}", "Remove all feed items? Sources and saved items are kept.") { confirmDialog = ClearAction.FEED_ITEMS }
            ClearRow("Reader cache", "${bd.readerCache} · ${formatBytes(bd.readerCacheBytes)}", "Remove all extracted article bodies and LLM caches?") { confirmDialog = ClearAction.READER_CACHE }
            ClearRow("Saved items", "${bd.savedItems} · ${formatBytes(bd.savedItemsBytes)}", "Remove all saved items? Feed items are kept.") { confirmDialog = ClearAction.SAVED }
            ClearRow("Reset all data", formatBytes(bd.totalBytes), "Erase everything and re-seed defaults? This cannot be undone.") { confirmDialog = ClearAction.ALL }

            Spacer(Modifier.height(32.dp))

            // ── About ──
            SectionEyebrow("ABOUT")
            Spacer(Modifier.height(12.dp))
            Text("Sapphire", style = MaterialTheme.typography.titleMedium, color = palette.OnInk)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted)
            Spacer(Modifier.height(4.dp))
            Text("Anonymous · local-first · AI-curated", style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted)
            Spacer(Modifier.height(32.dp))
        }
    }

    confirmDialog?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmDialog = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        ClearAction.FEED_ITEMS -> viewModel.clearFeedItems()
                        ClearAction.READER_CACHE -> viewModel.clearReaderCache()
                        ClearAction.SAVED -> viewModel.clearSaved()
                        ClearAction.ALL -> viewModel.clearAll()
                    }
                    confirmDialog = null
                }) { Text("Confirm", color = palette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDialog = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LlmField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ClearRow(title: String, countLabel: String, message: String, onClick: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.OnInk)
            Spacer(Modifier.width(8.dp))
            Text(countLabel, style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted)
        }
        TextButton(onClick = onClick) { Text("Clear", color = palette.Danger) }
    }
}

private enum class ClearAction(val title: String, val message: String) {
    FEED_ITEMS("Clear feed items", "Remove all feed items? Sources and saved items are kept."),
    READER_CACHE("Clear reader cache", "Remove all extracted article bodies and LLM caches?"),
    SAVED("Clear saved items", "Remove all saved items? Feed items are kept."),
    ALL("Reset all data", "Erase everything and re-seed defaults? This cannot be undone."),
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

