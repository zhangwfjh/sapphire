package com.sapphire.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Markunread
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sapphire.app.ui.design.AIAgentBadge
import com.sapphire.app.ui.design.PlatformBadge
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.model.FeedItem
import com.sapphire.domain.model.ReadState

/**
 * Universal Content Card — research-density single-column variant (X-like).
 *
 * Layout (top→bottom):
 * - Metadata row: platform/agent badge · author · relative time · trailing read toggle.
 * - Serif title (Newsreader) — bold when unread, regular/muted when read.
 * - Plex Sans summary snippet, 3-line clamp.
 *
 * Visual state matrix drives title weight, container alpha, and badge color via
 * [FeedCardState]. Read transitions animate alpha so a batch swept by scroll fades as a
 * group rather than snapping.
 *
 * Read model: opening the reader marks read; the manual toggle button flips read state.
 * In selection mode, the card shows a selection affordance and tap toggles selection
 * instead of opening the reader.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FeedCard(
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
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            MetaRow(
                item = item,
                read = state.isRead,
                onToggleRead = onToggleRead,
            )
            Spacer(Modifier.height(8.dp))
            Title(
                text = item.title,
                read = state.isRead,
                maxLines = 3,
            )
            val summary = item.summary
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Masonry variant — image-forward 2-column waterfall card (XHS-like) for visual streams.
 * Media fills the top with a gradient scrim; title overlays the lower third; metadata
 * collapses to a compact row beneath. Same read-state semantics as [FeedCard].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MasonryFeedCard(
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
        Column {
            val mediaUrl = item.mediaUrl
            if (!mediaUrl.isNullOrBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.78f)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
                ) {
                    AsyncImage(
                        model = mediaUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ScrimTitleOverlay(item.title, state.isRead)
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    ) {
                        val agentTag = item.agentTag
                        val platformTag = item.platformTag
                        if (!agentTag.isNullOrBlank()) {
                            AIAgentBadge(agentTag)
                        } else if (!platformTag.isNullOrBlank()) {
                            PlatformBadge(platformTag, read = state.isRead)
                        }
                    }
                }
            } else {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    MetaRow(item, read = state.isRead, onToggleRead = onToggleRead)
                    Spacer(Modifier.height(6.dp))
                    Title(item.title, read = state.isRead, maxLines = 4)
                }
            }
            // Compact meta footer under the media.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.authorHandle?.takeIf { it.isNotBlank() }?.let { author ->
                    Text(
                        "@$author",
                        style = SapphireMono.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                item.publishedAt?.let {
                    Text(
                        "· ${relativeTime(it)}",
                        style = SapphireMono.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaRow(
    item: FeedItem,
    read: Boolean,
    onToggleRead: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val agentTag = item.agentTag
        val platformTag = item.platformTag
        if (!agentTag.isNullOrBlank()) {
            AIAgentBadge(agentTag)
        } else if (!platformTag.isNullOrBlank()) {
            PlatformBadge(platformTag, read = read)
        }
        item.authorHandle?.takeIf { it.isNotBlank() }?.let { author ->
            Text(
                "@$author",
                style = SapphireMono.Label,
                color = if (read) palette.OnInkFaint else palette.OnInkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (item.authorHandle.isNullOrBlank()) Spacer(Modifier.weight(1f))
        item.publishedAt?.let {
            Text(
                relativeTime(it),
                style = SapphireMono.Label,
                color = palette.OnInkFaint,
                maxLines = 1,
            )
        }
        IconButton(onClick = onToggleRead, modifier = Modifier.size(28.dp)) {
            val icon = if (read) Icons.Outlined.Drafts else Icons.Outlined.Markunread
            val desc = if (read) "Mark unread" else "Mark read"
            Icon(
                icon,
                contentDescription = desc,
                tint = if (read) palette.OnInkFaint else palette.AccentBright,
                modifier = Modifier.size(16.dp),
            )
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
private fun ScrimTitleOverlay(title: String, read: Boolean) {
    val palette = LocalSapphirePalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.InkElevated)
            .drawWithCache {
                val grad = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xF00B0F14)),
                    startY = size.height * 0.30f,
                    endY = size.height,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(grad)
                }
            },
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (read) palette.OnInkMuted else palette.OnInk,
            fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        )
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
