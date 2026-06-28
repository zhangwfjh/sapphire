package com.sapphire.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.model.ReadState


/**
 * Compact list variant — Inoreader-style dense one-line-per-item row for high-density
 * scanning. Two lines only: a single-line title (semibold unread / regular-muted read) over
 * a monospace meta line (origin label · relative time). The unread accent rail from
 * [FeedCardSurface] carries read state; no per-row toggle button keeps the row tight.
 * Same selection / open semantics as [FeedCard].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ListFeedCard(
    item: FeedItem,
    onToggleRead: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val state = rememberFeedCardState(item.readState)
    FeedCardSurface(
        read = state.isRead,
        alpha = state.containerAlpha,
        selected = selected,
        onClick = onOpen,
        onLongClick = onLongPress,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                ListTitle(item.title, read = state.isRead)
                Spacer(Modifier.height(3.dp))
                ListMeta(item, read = state.isRead)
            }
        }
    }
}

/**
 * Magazine variant — Inoreader "Magazine" view: a leading thumbnail (96dp square) beside
 * a stacked title + 2-line summary. Horizontal layout; falls back to a text-only column
 * when the item has no media. The classic "card with thumbnail" scan pattern.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MagazineFeedCard(
    item: FeedItem,
    onToggleRead: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val state = rememberFeedCardState(item.readState)
    FeedCardSurface(
        read = state.isRead,
        alpha = state.containerAlpha,
        selected = selected,
        onClick = onOpen,
        onLongClick = onLongPress,
        modifier = modifier,
    ) {
        Row(Modifier.padding(12.dp)) {
            item.mediaUrl?.takeIf { it.isNotBlank() }?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Title(item.title, read = state.isRead, maxLines = 2)
                val summary = item.summary
                if (!summary.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                ListMeta(item, read = state.isRead)
            }
        }
    }
}


@Composable
private fun Title(text: String, read: Boolean, maxLines: Int) {
    val palette = LocalSapphirePalette.current
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = if (read) palette.OnInkMuted else palette.OnInk,
        fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ListTitle(text: String, read: Boolean) {
    val palette = LocalSapphirePalette.current
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = if (read) palette.OnInkMuted else palette.OnInk,
        fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ListMeta(item: FeedItem, read: Boolean) {
    val palette = LocalSapphirePalette.current
    val origin = item.agentTag
        ?: item.platformTag?.let { com.sapphire.app.ui.design.PlatformLabels.forTag(it) }
        ?: item.authorHandle?.let { "@$it" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!origin.isNullOrBlank()) {
            Text(
                origin,
                style = SapphireMono.Label,
                color = if (read) palette.OnInkFaint else palette.OnInkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.publishedAt?.let {
            if (!origin.isNullOrBlank()) {
                Text(
                    " · ",
                    style = SapphireMono.Label,
                    color = palette.OnInkFaint,
                )
            }
            Text(
                relativeTime(it),
                style = SapphireMono.Label,
                color = palette.OnInkFaint,
                maxLines = 1,
            )
        }
    }
}


/**
 * Card container surface — hairline 1px stroke on elevated ink, subtle accent left-rule
 * for unread items (a 2dp accent bar pinned to the leading edge signals "new"). Alpha
 * animates on read transitions to soften batch sweeps. Selected cards get an accent border.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FeedCardSurface(
    read: Boolean,
    alpha: Float,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val animatedAlpha by animateFloatAsState(
        targetValue = alpha,
        animationSpec = tween(durationMillis = 220),
        label = "card-alpha",
    )
    Box(
        modifier
            .alpha(animatedAlpha)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.InkElevated)
            .border(
                1.dp,
                if (selected) palette.Accent else palette.InkStroke,
                RoundedCornerShape(14.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (!read) {
            // Unread accent rail — a quiet 2dp sapphire bar on the leading edge.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(2.dp)
                    .fillMaxSize()
                    .background(palette.Accent),
            )
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(18.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(palette.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
        content()
    }
}

@Composable
private fun rememberFeedCardState(readState: ReadState): FeedCardState {
    val isRead = readState == ReadState.READ
    return FeedCardState(
        isRead = isRead,
        containerAlpha = if (isRead) READ_ALPHA else 1f,
    )
}

private data class FeedCardState(val isRead: Boolean, val containerAlpha: Float)

private const val READ_ALPHA = 0.75f

/** Crude relative-time formatter — enough for the card. */
private fun relativeTime(epochMs: Long): String {
    val delta = System.currentTimeMillis() - epochMs
    val mins = delta / 60_000
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        mins < 24 * 60 -> "${mins / 60}h"
        mins < 30 * 24 * 60 -> "${mins / (24 * 60)}d"
        else -> "${mins / (30 * 24 * 60)}mo"
    }
}
