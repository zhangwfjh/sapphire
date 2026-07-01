package com.sapphire.app.ui

import androidx.core.net.toUri
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Box(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
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
                Text("· " + formatRelativeTime(it), style = SapphireMono.Label, color = palette.OnInkFaint)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.ReaderInk,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 33.sp,
        )

        // Action row (PRD §3.4 tools) — pinned near the top for instant reach
        Spacer(Modifier.height(14.dp))
        ActionRow(state, viewModel)

        // Macro slot — shimmer while classifying, chips once done (PRD §3.5)
        Spacer(Modifier.height(16.dp))
        MacroSlot(state)

        // Summary block pinned beneath header once produced (PRD §3.4)
        state.summary?.let { sum ->
            Spacer(Modifier.height(16.dp))
            SummaryBlock(sum)
        }
        // Brief — the original feed body, always visible (PRD §3.4).
        Spacer(Modifier.height(20.dp))
        BriefBlock(state)

        // Full article — extracted body appended below the brief behind a divider, sitting
        // directly above the custom prompt field. Rendered only once the article resolves;
        // omitted while fetching (the brief is the focus then) and on no-URL/extraction fail.
        if (state.articleBlocks != null) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = palette.InkStrokeStrong.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))
            ArticleBlock(state)
        }

        // Custom prompt field (PRD §3.5 — interactive from launch)
        Spacer(Modifier.height(12.dp))
        CustomPromptField()
        Spacer(Modifier.height(20.dp))
    }
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
            is SummaryState.Streaming -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sum.bullets.forEach { bullet -> SummaryBullet(bullet) }
                if (sum.current.isNotEmpty()) SummaryBullet(sum.current, streaming = true)
            }
            is SummaryState.Done -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sum.bullets.forEach { bullet -> SummaryBullet(bullet) }
            }
        }
    }
}

@Composable
private fun SummaryBullet(text: String, streaming: Boolean = false) {
    val palette = LocalSapphirePalette.current
    // Blink the caret only while the bullet is still being typed.
    val caretAlpha by if (streaming) {
        val transition = rememberInfiniteTransition(label = "summary-caret")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
            label = "caret-alpha",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("→", color = palette.Accent, style = MaterialTheme.typography.bodyMedium)
        Text(
            buildAnnotatedString {
                append(text)
                if (streaming) withStyle(SpanStyle(color = palette.Accent.copy(alpha = caretAlpha))) { append(" ▏") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = palette.ReaderInk,
        )
    }
}

@Composable
private fun BriefBlock(state: ReaderUiState.Open) {
    // The brief is the translation target only when no full article is present; otherwise
    // translation applies to the article (see [ArticleBlock]).
    val translate = state.translate
    val isTranslateTarget = state.articleBlocks == null
    if (isTranslateTarget) TranslateStatus(translate, state.translateVisible)
    if (isTranslateTarget && state.translateVisible && translate is TranslateState.Done) {
        RichBlockList(
            blocks = state.blocks,
            translateTargets = translate.response.paragraphs.map { it.target },
        )
    } else {
        RichBlockList(blocks = state.blocks)
    }
}

@Composable
private fun ArticleBlock(state: ReaderUiState.Open) {
    val palette = LocalSapphirePalette.current
    val article = state.articleBlocks ?: return
    val translate = state.translate
    // Collapsed by default — the full article only renders once the user taps the toggle.
    // The state is keyed on the item id so it resets when the reader opens a different item.
    var expanded by remember(state.item.hashUuid) { mutableStateOf(false) }
    // If the user triggers translate while collapsed, auto-expand so the result is visible;
    // translate runs on the full article body (articleBlocks != null).
    val showTranslate = state.translateVisible
    LaunchedEffect(showTranslate) { if (showTranslate) expanded = true }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Collapsible toggle (PRD §3.4) — "Show full article" / "Hide full article".
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.Accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                if (expanded) "Hide full article" else "Show full article",
                style = SapphireMono.Label,
                color = palette.Accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (expanded) {
            TranslateStatus(translate, state.translateVisible)
            if (state.translateVisible && translate is TranslateState.Done) {
                RichBlockList(
                    blocks = article,
                    translateTargets = translate.response.paragraphs.map { it.target },
                )
            } else {
                RichBlockList(blocks = article)
            }
        }
    }
}

/** Inline translate loading/error indicator; rendered once by whichever block is the active translate target. */
@Composable
private fun TranslateStatus(translate: TranslateState?, visible: Boolean) {
    if (!visible) return
    val palette = LocalSapphirePalette.current
    when (translate) {
        is TranslateState.Loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = palette.Accent)
            Text("Translating…", style = SapphireMono.Body, color = palette.OnInkMuted)
        }
        is TranslateState.Error -> Text(translate.message, color = palette.Danger, style = MaterialTheme.typography.bodySmall)
        else -> {}
    }
}

@Composable
private fun ActionRow(state: ReaderUiState.Open, viewModel: ReaderViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        val url = state.item.url
        if (!url.isNullOrBlank()) {
            ToolButton(
                onClick = { openInAppBrowser(context, url) },
                enabled = true,
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                label = "Open",
            )
        }
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
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Run",
                    tint = if (prompt.isNotBlank()) Color.White else palette.OnInkFaint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Launches the article's canonical URL in an in-app browser (Chrome Custom Tabs) — overlays
 * the app with a themed, dismissible browser session so the user stays in-task. Falls back
 * silently if no browser is available to handle the intent.
 */
private fun openInAppBrowser(context: android.content.Context, url: String) {
    val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    runCatching {
        customTabsIntent.launchUrl(context, url.toUri())
    }
}

/** Compact relative-time formatter for the reader header (e.g. "3h", "2d"). */
private fun formatRelativeTime(epochMs: Long): String {
    val mins = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        mins < 24 * 60 -> "${mins / 60}h"
        mins < 30 * 24 * 60 -> "${mins / (24 * 60)}d"
        else -> "${mins / (30 * 24 * 60)}mo"
    }
}
