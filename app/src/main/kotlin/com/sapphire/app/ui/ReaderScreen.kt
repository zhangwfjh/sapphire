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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Markunread
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.sapphire.app.ui.design.ShimmerBlock
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.model.ReadState
import com.sapphire.domain.reader.toPlainParagraphs
import com.sapphire.domain.settings.TranslateViewMode
import com.sapphire.domain.settings.UiPrefsStore

/**
 * PRD §3.4 Full-Screen Reader.
 *
 * Full navigation route (promoted from the old ReaderSheet overlay). The action row carries
 * read/unread, save, share, open-in-browser, and preferences — and auto-hides on scroll-down
 * / shows on scroll-up. The back chevron stays pinned and always tappable. Translate auto-fires
 * when the view mode is BILINGUAL or TRANSLATION.
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
        showDensity = false,
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
 * Reader action row — read/unread toggle, save, share, open-in-browser, preferences.
 * Auto-hides on scroll-down, shows on scroll-up. The persistent back chevron lives
 * outside this row in [ReaderContent] so it stays tappable at all scroll positions.
 */
@Composable
private fun ReaderTopBar(
    state: ReaderUiState.Open,
    viewModel: ReaderViewModel,
    onOpenRightDrawer: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalSapphirePalette.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        val title = state.item.title
        IconButton(onClick = {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    android.content.Intent.EXTRA_TEXT,
                    if (url.isNullOrBlank()) title else "$title $url",
                )
            }
            runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
        }) {
            Icon(Icons.Filled.Share, contentDescription = "Share", tint = palette.OnInkMuted)
        }
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
                    // Reading-time estimate (200 wpm) from the resolved article body, or
                    // the feed body while extraction is still in flight. Hidden when <1 min.
                    val readingMinutes by remember(state.item.hashUuid, state.articleBlocks, state.blocks) {
                        derivedStateOf {
                            val paras = (state.articleBlocks ?: state.blocks).toPlainParagraphs()
                            val words = paras.sumOf { it.split(WS_REGEX).count { w -> w.isNotBlank() } }
                            (words + READING_WPM - 1) / READING_WPM // ceil
                        }
                    }
                    if (readingMinutes >= 1) {
                        Text("· $readingMinutes min", style = SapphireMono.Label, color = palette.OnInkFaint)
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
        // Auto-hiding action row overlay (read/unread, save, share, open, tune)
        AnimatedVisibility(
            visible = topBarVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(Modifier.fillMaxWidth().background(palette.ReaderPaper)) {
                ReaderTopBar(state, viewModel, onOpenRightDrawer)
            }
        }

        // Persistent back chevron — always tappable, pinned top-start outside the auto-hide
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.OnInk)
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

private const val READING_WPM = 200
private val WS_REGEX = Regex("\\s+")
