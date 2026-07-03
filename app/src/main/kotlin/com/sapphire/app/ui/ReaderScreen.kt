package com.sapphire.app.ui

import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Markunread
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.design.PlatformBadge
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.design.ShimmerBlock
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.model.ReadState
import com.sapphire.domain.settings.TranslateViewMode
import com.sapphire.domain.settings.UiPrefsStore

/**
 * PRD §3.4 Full-Screen Reader + §3.5 Context-Aware Dynamic AI Operations.
 *
 * Full navigation route (promoted from the old ReaderSheet overlay). The top toolbar carries
 * back, read/unread, save, open-in-browser, AI summarize, and preferences — and auto-hides
 * on scroll-down / shows on scroll-up. Translate auto-fires when the view mode is BILINGUAL
 * or TRANSLATION. A search-style custom prompt sits at the end of the scrolling body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    itemId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val palette = LocalSapphirePalette.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val translateViewMode by viewModel.translateViewMode.collectAsStateWithLifecycle()
    val themePreference by viewModel.themePreference.collectAsStateWithLifecycle()
    var rightDrawerOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(itemId) { viewModel.open(itemId) }

    when (val s = state) {
        is ReaderUiState.Idle, is ReaderUiState.Loading -> {
            Box(Modifier.fillMaxSize().background(palette.ReaderPaper)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.OnInk)
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = palette.Accent)
                }
            }
        }
        is ReaderUiState.Error -> {
            Box(Modifier.fillMaxSize().background(palette.ReaderPaper)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.OnInk)
                    }
                }
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text(s.message, color = palette.Danger, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is ReaderUiState.Open -> ReaderContent(
            s, viewModel, onBack, translateViewMode,
            onOpenRightDrawer = { rightDrawerOpen = true },
        )
    }
    RightDrawer(
        visible = rightDrawerOpen,
        onDismiss = { rightDrawerOpen = false },
        density = UiPrefsStore.FeedDensity(true),
        onDensityChange = {},
        themePreference = themePreference,
        onThemeChange = viewModel::setTheme,
        translateView = translateViewMode,
        onTranslateViewChange = viewModel::setTranslateView,
        onOpenSettings = {},
    )
}

/**
 * Reader top toolbar — back, read/unread toggle, save, open-in-browser, AI summarize,
 * preferences. Auto-hides on scroll-down, shows on scroll-up.
 */
@Composable
private fun ReaderTopBar(
    state: ReaderUiState.Open,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenRightDrawer: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalSapphirePalette.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.OnInk)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = viewModel::toggleRead) {
            Icon(
                if (state.item.readState == ReadState.READ) Icons.Filled.Drafts else Icons.Filled.Markunread,
                contentDescription = if (state.item.readState == ReadState.READ) "Mark unread" else "Mark read",
                tint = palette.OnInkMuted,
            )
        }
        IconButton(onClick = viewModel::toggleSave) {
            Icon(
                if (state.savedLater) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (state.savedLater) "Saved" else "Save",
                tint = palette.OnInkMuted,
            )
        }
        val url = state.item.url
        if (!url.isNullOrBlank()) {
            IconButton(onClick = { openInAppBrowser(context, url) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open", tint = palette.OnInkMuted)
            }
        }
        IconButton(onClick = onOpenRightDrawer) {
            Icon(Icons.Filled.Tune, contentDescription = "View & preferences", tint = palette.OnInkMuted)
        }
    }
}

@Composable
private fun ReaderContent(
    state: ReaderUiState.Open,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    translateViewMode: TranslateViewMode,
    onOpenRightDrawer: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val item = state.item
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val effectiveTranslateVisible = state.translateVisible && translateViewMode != TranslateViewMode.ORIGIN
    val hideOriginals = translateViewMode == TranslateViewMode.TRANSLATION && effectiveTranslateVisible

    // Auto-hide top bar on scroll-down, show on scroll-up
    var prevScroll by remember { mutableStateOf(0) }
    var topBarVisible by remember { mutableStateOf(true) }
    LaunchedEffect(scrollState.value) {
        val delta = scrollState.value - prevScroll
        if (scrollState.value <= 0) {
            topBarVisible = true
        } else if (delta > 12) {
            topBarVisible = false
        } else if (delta < -12) {
            topBarVisible = true
        }
        prevScroll = scrollState.value
    }

    Box(Modifier.fillMaxSize().background(palette.ReaderPaper)) {
        // Scrollable body
        Box(
            Modifier.fillMaxSize().verticalScroll(scrollState),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 56.dp, bottom = 28.dp),
            ) {
                // Header metadata
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val platformTag = item.platformTag
                    if (!platformTag.isNullOrBlank()) {
                        PlatformBadge(platformTag, read = false)
                    }
                    item.authorHandle?.takeIf { it.isNotBlank() }?.let { author ->
                        Text("@$author", style = SapphireMono.Label, color = palette.OnInkMuted)
                    }
                    item.publishedAt?.let {
                        Text("· " + formatRelativeTime(it), style = SapphireMono.Label, color = palette.OnInkFaint)
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Title (with translate-view handling)
                val tFrame = (state.translate as? TranslateState.Done)?.frame
                    ?: (state.translate as? TranslateState.Streaming)?.frame
                val translatedTitle = tFrame?.title?.takeIf { it.isNotEmpty() }
                if (!(hideOriginals && translatedTitle != null)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.ReaderInk,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 33.sp,
                    )
                }
                if (effectiveTranslateVisible && translatedTitle != null) {
                    Spacer(Modifier.height(4.dp))
                    if (hideOriginals) {
                        Text(translatedTitle, style = MaterialTheme.typography.headlineMedium,
                            color = palette.ReaderInk, fontWeight = FontWeight.SemiBold, lineHeight = 33.sp)
                    } else {
                        Text(translatedTitle, style = MaterialTheme.typography.titleMedium,
                            fontStyle = FontStyle.Italic, color = palette.AccentBright)
                    }
                }

                // Translate indicator
                TranslateStatus(state.translate, effectiveTranslateVisible)

                // Summary
                state.summary?.let { sum ->
                    Spacer(Modifier.height(16.dp))
                    SummaryBlock(
                        sum = sum,
                        summaryTargets = if (effectiveTranslateVisible) {
                            (state.translate as? TranslateState.Done)?.frame?.summary
                                ?: (state.translate as? TranslateState.Streaming)?.frame?.summary
                        } else null,
                        hideOriginals = hideOriginals,
                    )
                }

                // Brief
                Spacer(Modifier.height(20.dp))
                BriefBlock(state, effectiveTranslateVisible, hideOriginals)

                // Full article
                if (state.articleBlocks != null) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = palette.InkStrokeStrong.copy(alpha = 0.5f))
                    Spacer(Modifier.height(16.dp))
                    ArticleBlock(state, effectiveTranslateVisible, hideOriginals)
                }
                Spacer(Modifier.height(80.dp))
            }
        }
        // Auto-hiding top bar overlay
        AnimatedVisibility(
            visible = topBarVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(Modifier.fillMaxWidth().background(palette.ReaderPaper)) {
                ReaderTopBar(state, viewModel, onBack, onOpenRightDrawer)
            }
        }

        // Jump-to-top / jump-to-bottom (bottom-right)
        ReaderJumpButtons(
            scrollState = scrollState,
            onJumpToTop = { scope.launch { scrollState.animateScrollBy(-scrollState.value.toFloat()) } },
            onJumpToBottom = { scope.launch { scrollState.animateScrollBy((scrollState.maxValue - scrollState.value).toFloat()) } },
        )

        // Floating AI button — bottom-start, shows a popup menu
        var aiMenuExpanded by remember { mutableStateOf(false) }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 24.dp),
        ) {
            FloatingActionButton(
                onClick = { aiMenuExpanded = true },
                containerColor = palette.Accent,
                contentColor = Color.White,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "AI ops")
            }
            DropdownMenu(
                expanded = aiMenuExpanded,
                onDismissRequest = { aiMenuExpanded = false },
                modifier = Modifier.background(palette.Ink),
            ) {
                // Context ops as menu items
                when (state.classification) {
                    is ClassificationState.Loading -> {
                        DropdownMenuItem(
                            text = { Text("Analyzing…", color = palette.OnInkFaint, style = MaterialTheme.typography.bodySmall) },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    is ClassificationState.Error -> {
                        DropdownMenuItem(
                            text = { Text("Classification unavailable", color = palette.OnInkFaint) },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    is ClassificationState.Done -> {
                        if (state.macros.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(state.classification.label.uppercase(), color = palette.OnInkMuted, style = SapphireMono.Label) },
                                onClick = {},
                                enabled = false,
                            )
                        } else {
                            state.macros.forEach { macro ->
                                DropdownMenuItem(
                                    text = { Text(macro.label, color = palette.OnInk) },
                                    onClick = { aiMenuExpanded = false },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = palette.InkStroke)
                // Ask AI bar
                CustomPromptField()
            }
        }
    }
}

/**
 * Floating jump-to-top / jump-to-bottom controls overlaid on the reader body. Each fades
 * in only when reachable: "to top" once scrolled away from the head, "to bottom" once a
 * gap remains below. Short articles that fit without scrolling show neither (maxValue == 0).
 */
@Composable
private fun ReaderJumpButtons(
    scrollState: ScrollState,
    onJumpToTop: () -> Unit,
    onJumpToBottom: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val showTop by remember { derivedStateOf { scrollState.value > 0 } }
    val showBottom by remember { derivedStateOf { scrollState.value < scrollState.maxValue } }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            AnimatedVisibility(
                visible = showTop,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                SmallFloatingActionButton(
                    onClick = onJumpToTop,
                    containerColor = palette.Accent,
                    contentColor = palette.OnInk,
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Jump to top") }
            }
            AnimatedVisibility(
                visible = showBottom,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                SmallFloatingActionButton(
                    onClick = onJumpToBottom,
                    containerColor = palette.InkRaised,
                    contentColor = palette.OnInk,
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to bottom") }
            }
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
                Text("Classification unavailable", style = SapphireMono.Body, color = palette.OnInkFaint)
            }
            is ClassificationState.Done -> {
                if (state.macros.isEmpty()) {
                    Text(state.classification.label.uppercase(), style = SapphireMono.Label, color = palette.OnInkMuted)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.macros.forEach { macro -> MacroChip(label = macro.label) }
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
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(palette.Accent.copy(alpha = 0.12f))
            .border(1.dp, palette.Accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .clickable {}
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = palette.AccentBright, modifier = Modifier.size(12.dp))
        Text(label, style = SapphireMono.Label, color = palette.AccentBright, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SummaryBlock(sum: SummaryState, summaryTargets: List<String>? = null, hideOriginals: Boolean = false) {
    val palette = LocalSapphirePalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
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
                sum.bullets.forEachIndexed { i, bullet ->
                    val target = summaryTargets?.getOrNull(i)?.takeIf { it.isNotEmpty() }
                    if (hideOriginals && target != null) {
                        SummaryBullet(target)
                    } else {
                        SummaryBullet(bullet)
                        target?.let { SummaryTranslation(it) }
                    }
                }
                if (sum.current.isNotEmpty()) SummaryBullet(sum.current, streaming = true)
            }
            is SummaryState.Done -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sum.bullets.forEachIndexed { i, bullet ->
                    val target = summaryTargets?.getOrNull(i)?.takeIf { it.isNotEmpty() }
                    if (hideOriginals && target != null) {
                        SummaryBullet(target)
                    } else {
                        SummaryBullet(bullet)
                        target?.let { SummaryTranslation(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryTranslation(text: String) {
    val palette = LocalSapphirePalette.current
    Text(text, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = palette.AccentBright)
}

@Composable
private fun SummaryBullet(text: String, streaming: Boolean = false) {
    val palette = LocalSapphirePalette.current
    val caretAlpha by if (streaming) {
        val transition = rememberInfiniteTransition(label = "summary-caret")
        transition.animateFloat(
            initialValue = 1f, targetValue = 0f,
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
private fun BriefBlock(
    state: ReaderUiState.Open,
    effectiveTranslateVisible: Boolean,
    hideOriginals: Boolean,
) {
    val briefTargets = if (effectiveTranslateVisible) {
        (state.translate as? TranslateState.Done)?.frame?.brief
            ?: (state.translate as? TranslateState.Streaming)?.frame?.brief
    } else null
    RichBlockList(blocks = state.blocks, translateTargets = briefTargets, hideOriginals = hideOriginals)
}

@Composable
private fun ArticleBlock(
    state: ReaderUiState.Open,
    effectiveTranslateVisible: Boolean,
    hideOriginals: Boolean,
) {
    val palette = LocalSapphirePalette.current
    val article = state.articleBlocks ?: return
    var expanded by remember(state.item.hashUuid) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = palette.Accent, modifier = Modifier.size(16.dp))
            Text(
                if (expanded) "Hide full article" else "Show full article",
                style = SapphireMono.Label, color = palette.Accent, fontWeight = FontWeight.SemiBold,
            )
        }
        if (expanded) {
            val articleTargets = if (effectiveTranslateVisible) {
                (state.translate as? TranslateState.Done)?.frame?.article
                    ?: (state.translate as? TranslateState.Streaming)?.frame?.article
            } else null
            RichBlockList(blocks = article, translateTargets = articleTargets, hideOriginals = hideOriginals)
        }
    }
}

@Composable
private fun TranslateStatus(translate: TranslateState?, visible: Boolean) {
    if (!visible) return
    val palette = LocalSapphirePalette.current
    when (translate) {
        is TranslateState.Loading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TRANSLATING", style = SapphireMono.Label, color = palette.Accent, fontWeight = FontWeight.SemiBold)
            }
            ShimmerBlock(width = 220.dp, height = 14.dp)
            ShimmerBlock(width = 160.dp, height = 14.dp)
        }
        is TranslateState.Streaming -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StreamingCaretLabel(text = "TRANSLATING", color = palette.Accent)
        }
        is TranslateState.Error -> Text(translate.message, color = palette.Danger, style = MaterialTheme.typography.bodySmall)
        else -> {}
    }
}

@Composable
private fun StreamingCaretLabel(text: String, color: Color) {
    val caretAlpha by rememberInfiniteTransition(label = "stream-caret").animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "stream-caret-alpha",
    )
    Text(
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = color.copy(alpha = caretAlpha))) { append(" ▏") }
        },
        style = SapphireMono.Label, color = color, fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Search-style AI prompt field — pill-shaped, sparkle leading icon, send button.
 */
@Composable
private fun CustomPromptField() {
    val palette = LocalSapphirePalette.current
    var prompt by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(palette.InkElevated)
            .border(1.dp, palette.InkStroke, RoundedCornerShape(24.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = palette.OnInkFaint,
            modifier = Modifier.padding(start = 12.dp).size(18.dp),
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = {
                Text("Ask AI about this article…", style = MaterialTheme.typography.bodySmall, color = palette.OnInkFaint)
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
            Modifier.size(36.dp).clip(RoundedCornerShape(50))
                .background(if (prompt.isNotBlank()) palette.Accent else palette.InkRaised)
                .clickable(enabled = prompt.isNotBlank()) { prompt = ""; keyboard?.hide() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (prompt.isNotBlank()) Color.White else palette.OnInkFaint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun openInAppBrowser(context: android.content.Context, url: String) {
    val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    runCatching { customTabsIntent.launchUrl(context, url.toUri()) }
}

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
