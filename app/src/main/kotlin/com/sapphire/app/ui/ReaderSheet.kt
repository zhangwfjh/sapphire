package com.sapphire.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.design.PlatformBadge
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.design.ShimmerBlock
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono

/**
 * PRD §3.4 Full-Screen Reader Sheet + §3.5 Context-Aware Dynamic AI Operations.
 *
 * The reading surface: warm paper-on-charcoal body, serif headline, a macro slot that
 * shimmers while Tier-1 classification runs (PRD §3.5), on-demand summary/translate
 * tools, and an always-interactive custom-prompt chat field at the base.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSheet(
    viewModel: ReaderViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = LocalSapphirePalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.dismissError()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = palette.ReaderPaper,
        dragHandle = null,
    ) {
        when (val s = state) {
            is ReaderUiState.Idle, is ReaderUiState.Loading -> Box(
                Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = palette.Accent)
            }
            is ReaderUiState.Error -> Column(Modifier.padding(24.dp)) {
                Text(s.message, color = palette.Danger, style = MaterialTheme.typography.bodyMedium)
            }
            is ReaderUiState.Open -> ReaderContent(s, viewModel)
        }
    }
}

@Composable
private fun ReaderContent(state: ReaderUiState.Open, viewModel: ReaderViewModel) {
    val palette = LocalSapphirePalette.current
    val item = state.item
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        // Grabber
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.InkStrokeStrong)
                .align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(16.dp))

        // Header metadata
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val platformTag = item.platformTag
            if (!platformTag.isNullOrBlank()) {
                PlatformBadge(platformTag, read = false)
            }
            item.authorHandle?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    "@$author",
                    style = SapphireMono.Label,
                    color = palette.OnInkMuted,
                )
            }
            item.publishedAt?.let {
                Text("· now", style = SapphireMono.Label, color = palette.OnInkFaint)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.ReaderInk,
            fontWeight = FontWeight.SemiBold,
        )

        // Macro slot — shimmer while classifying, chips once done (PRD §3.5)
        Spacer(Modifier.height(16.dp))
        MacroSlot(state)

        // Summary block pinned beneath header once produced (PRD §3.4)
        state.summary?.let { sum ->
            Spacer(Modifier.height(14.dp))
            SummaryBlock(sum)
        }

        // Body — original or interleaved translate stream
        Spacer(Modifier.height(18.dp))
        BodyBlock(state)

        // Action row (PRD §3.4 tools; Save Later lands in S07)
        Spacer(Modifier.height(18.dp))
        ActionRow(state, viewModel)

        // Custom prompt field (PRD §3.5 — interactive from launch)
        Spacer(Modifier.height(16.dp))
        CustomPromptField()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MacroSlot(state: ReaderUiState.Open) {
    val palette = LocalSapphirePalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionEyebrow("CONTEXT OPS")
        when (state.classification) {
            is ClassificationState.Loading -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShimmerBlock(width = 140.dp)
                    ShimmerBlock(width = 100.dp)
                }
            }
            is ClassificationState.Error -> {
                Text(
                    "Classification unavailable",
                    style = SapphireMono.Body,
                    color = palette.OnInkFaint,
                )
            }
            is ClassificationState.Done -> {
                if (state.macros.isEmpty()) {
                    Text(
                        state.classification.label.uppercase(),
                        style = SapphireMono.Label,
                        color = palette.OnInkMuted,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.macros.forEach { macro ->
                            MacroChip(label = macro.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MacroChip(label: String) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.Accent.copy(alpha = 0.12f))
            .border(1.dp, palette.Accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .clickable {}
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = palette.AccentBright, modifier = Modifier.size(12.dp))
        Text(
            label,
            style = SapphireMono.Label,
            color = palette.AccentBright,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SummaryBlock(sum: SummaryState) {
    val palette = LocalSapphirePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.Accent.copy(alpha = 0.07f))
            .border(1.dp, palette.Accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = palette.Accent, modifier = Modifier.size(14.dp))
            Text("SUMMARY", style = SapphireMono.Label, color = palette.Accent, fontWeight = FontWeight.SemiBold)
        }
        when (sum) {
            is SummaryState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = palette.Accent)
                Text("Summarizing…", style = SapphireMono.Body, color = palette.OnInkMuted)
            }
            is SummaryState.Error -> Text(sum.message, style = MaterialTheme.typography.bodySmall, color = palette.Danger)
            is SummaryState.Done -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sum.bullets.forEach { bullet ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("→", color = palette.Accent, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            bullet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.ReaderInk,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyBlock(state: ReaderUiState.Open) {
    val palette = LocalSapphirePalette.current
    val translate = state.translate
    if (state.translateVisible && translate is TranslateState.Done) {
        translate.response.paragraphs.forEach { p ->
            Text(p.original, style = MaterialTheme.typography.bodyLarge, color = palette.ReaderInk)
            Spacer(Modifier.height(4.dp))
            Text(
                p.target,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = palette.AccentBright,
            )
            Spacer(Modifier.height(14.dp))
        }
    } else {
        translate?.let {
            if (it is TranslateState.Loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = palette.Accent)
                    Text("Translating…", style = SapphireMono.Body, color = palette.OnInkMuted)
                }
            } else if (it is TranslateState.Error) {
                Text(it.message, color = palette.Danger, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.paragraphs.forEach { p ->
            Text(p, style = MaterialTheme.typography.bodyLarge, color = palette.ReaderInk)
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ActionRow(state: ReaderUiState.Open, viewModel: ReaderViewModel) {
    val palette = LocalSapphirePalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolButton(
            onClick = viewModel::summarize,
            enabled = state.summary !is SummaryState.Loading,
            icon = Icons.Filled.AutoAwesome,
            label = "Summary",
        )
        ToolButton(
            onClick = viewModel::translate,
            enabled = state.translate !is TranslateState.Loading,
            icon = Icons.Filled.Language,
            label = "Translate",
        )
        ToolButton(
            onClick = viewModel::toggleSave,
            enabled = true,
            icon = if (state.savedLater) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
            label = if (state.savedLater) "Saved" else "Save",
        )
    }
}

@Composable
private fun ToolButton(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) palette.InkRaised else palette.InkElevated)
            .border(1.dp, palette.InkStroke, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) palette.Accent else palette.OnInkFaint, modifier = Modifier.size(14.dp))
        Text(
            label.uppercase(),
            style = SapphireMono.Label,
            color = if (enabled) palette.OnInk else palette.OnInkFaint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CustomPromptField() {
    val palette = LocalSapphirePalette.current
    var prompt by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionEyebrow("CUSTOM INSTRUCTION")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.InkElevated)
                .border(1.dp, palette.InkStroke, RoundedCornerShape(10.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = {
                    Text(
                        "Instruct AI to run a custom operation on this text…",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.OnInkFaint,
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.ReaderInk),
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = palette.Accent,
                ),
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (prompt.isNotBlank()) palette.Accent else palette.InkRaised)
                    .clickable(enabled = prompt.isNotBlank()) {
                        prompt = ""
                        keyboard?.hide()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Run",
                    tint = if (prompt.isNotBlank()) Color.White else palette.OnInkFaint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
