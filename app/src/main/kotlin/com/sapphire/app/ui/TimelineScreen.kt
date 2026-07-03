package com.sapphire.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Markunread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.R
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.design.accentGlow
import com.sapphire.app.ui.design.grainOverlay
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.derivedStateOf

private enum class FeedLayout(val label: String) {
    DENSE("Dense"),
    RICH("Rich"),
}

/**
 * Unified timeline. Two view modes: LIST (dense one-line rows for high-density scanning)
 * and CARD (hero image + title + summary). Switched via a dropdown in the top bar.
 *
 * Read model: an item becomes READ only on explicit action — opening the reader or the
 * manual mark button. Scrolling never marks read.
 *
 * Article batch selection: long-press a card to enter selection mode; select multiple
 * articles for batch mark read / unread / remove.
 *
 * Reader-sheet open overlays this screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(
    viewModel: FeedViewModel = hiltViewModel(),
    onBuildFeed: () -> Unit = {},
    onOpenReader: (String) -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onOpenExplore: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val timeline by viewModel.visibleTimeline.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filterLabel by viewModel.filterLabel.collectAsStateWithLifecycle()
    val hasAnyItems by viewModel.hasAnyItems.collectAsStateWithLifecycle()
    val feedScope by viewModel.feedScope.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val sourcesDrawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val sourcesDrawerScope = rememberCoroutineScope()
    var rightDrawerOpen by rememberSaveable { mutableStateOf(false) }
    val density by viewModel.density.collectAsStateWithLifecycle()
    val layout = if (density.isDense) FeedLayout.DENSE else FeedLayout.RICH
    val translateView by viewModel.translateView.collectAsStateWithLifecycle()
    val themePreference by viewModel.themePreference.collectAsStateWithLifecycle()

    // Show the jump-to-top FAB only once the user has scrolled below the first item.
    val showJumpToTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 400
        }
    }

    // Article selection state: itemId -> selected. Non-empty map = selection mode active.
    val selectedItems = remember { mutableStateMapOf<String, Boolean>() }
    val inSelection = selectedItems.any { it.value }

    // Shared per-item interaction handlers — identical across every layout variant, so the
    // card plumbing is written once and passed to whichever card the active view renders.
    fun itemToggleRead(item: com.sapphire.domain.model.FeedItem): () -> Unit = {
        viewModel.toggleRead(item.hashUuid, item.readState == com.sapphire.domain.model.ReadState.READ)
    }
    fun itemOpen(item: com.sapphire.domain.model.FeedItem, isSelected: Boolean): () -> Unit = {
        if (inSelection) {
            selectedItems[item.hashUuid] = !isSelected
        } else {
            viewModel.markReadOnOpen(item.hashUuid)
            onOpenReader(item.hashUuid)
        }
    }
    fun itemLongPress(item: com.sapphire.domain.model.FeedItem, isSelected: Boolean): () -> Unit = {
        selectedItems[item.hashUuid] = !isSelected
    }

    SourcesDrawer(
        drawerState = sourcesDrawerState,
        query = query,
        onQueryChange = viewModel::setQuery,
        onCategoryClick = { ids, label ->
            viewModel.setCategoryFilter(ids, label)
            sourcesDrawerScope.launch { sourcesDrawerState.close() }
        },
        onSourceGroupClick = { sourceIds, label ->
            viewModel.setSourceGroupFilter(sourceIds, label)
        },
        onSourceClick = { sourceId, label ->
            viewModel.setSourceFilter(sourceId, label)
            sourcesDrawerScope.launch { sourcesDrawerState.close() }
        },
        onClearFilter = {
            viewModel.clearFilter()
            sourcesDrawerScope.launch { sourcesDrawerState.close() }
        },
        onOpenSaved = {
            sourcesDrawerScope.launch { sourcesDrawerState.close() }
            onOpenSaved()
        },
        onOpenExplore = {
            sourcesDrawerScope.launch { sourcesDrawerState.close() }
            onOpenExplore()
        },
        onOpenSettings = {
            sourcesDrawerScope.launch { sourcesDrawerState.close() }
            onOpenSettings()
        },
    ) {

    Scaffold(
        containerColor = LocalSapphirePalette.current.Ink,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TimelineTopBar(
                title = if (inSelection) "${selectedItems.count { it.value }} selected"
                    else filterLabel ?: "All Feeds",
                inSelection = inSelection,
                onOpenLeftDrawer = { sourcesDrawerScope.launch { sourcesDrawerState.open() } },
                onOpenSettings = { rightDrawerOpen = true },
                onClearSelection = { selectedItems.clear() },
                onMarkRead = {
                    viewModel.markReadBatch(selectedItems.filter { it.value }.keys)
                    selectedItems.clear()
                },
                onMarkUnread = {
                    viewModel.markUnreadBatch(selectedItems.filter { it.value }.keys)
                    selectedItems.clear()
                },
                onRemove = {
                    viewModel.deleteItems(selectedItems.filter { it.value }.keys)
                    selectedItems.clear()
                },
            )
        },
        floatingActionButton = {
            if (!inSelection) {
                FloatingActionButton(
                    onClick = onBuildFeed,
                    containerColor = LocalSapphirePalette.current.Accent,
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Curate new topic")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(padding)) {
                ScopeChipsRow(
                    scope = feedScope,
                    onScopeChange = viewModel::setScope,
                    layout = layout,
                    onLayoutChange = { viewModel.setDensity(com.sapphire.domain.settings.UiPrefsStore.FeedDensity(it == FeedLayout.DENSE)) },
                )
            val searching = query.isNotBlank()
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !hasAnyItems -> EmptyTimeline(
                        padding = PaddingValues(0.dp),
                        onRefresh = viewModel::refresh,
                        onBuildFeed = onBuildFeed,
                    )
                    timeline.isEmpty() && searching -> NoSearchMatches(
                        query = query,
                        onClear = { viewModel.setQuery("") },
                    )
                    else -> PullToRefreshBox(
                        // The pull gesture drives a silent streaming refresh; items appear
                        // live as each source completes. Bind isRefreshing so the indicator
                        // rotates for the duration of the pass and dismisses when it ends.
                        isRefreshing = refreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // LIST / CARD — single column; only the card variant differs.
                        val dayGroups = remember(timeline) { groupByDay(timeline) }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            dayGroups.forEach { group ->
                                stickyHeader(key = "header_${group.label}") {
                                    DayHeader(group.label)
                                }
                                items(items = group.items, key = { it.hashUuid }) { item ->
                                    val isSelected = selectedItems[item.hashUuid] == true
                                    val dismissState = rememberSwipeToDismissBoxState(
                                        confirmValueChange = { value ->
                                            when (value) {
                                                SwipeToDismissBoxValue.StartToEnd -> {
                                                    // Swipe right → toggle read/unread + undo
                                                    val wasRead = item.readState == com.sapphire.domain.model.ReadState.READ
                                                    viewModel.toggleRead(item.hashUuid, wasRead)
                                                    sourcesDrawerScope.launch {
                                                        val result = snackbarHostState.showSnackbar(
                                                            message = if (wasRead) "Marked unread" else "Marked read",
                                                            actionLabel = "Undo",
                                                            duration = SnackbarDuration.Short,
                                                        )
                                                        if (result == SnackbarResult.ActionPerformed) {
                                                            viewModel.toggleRead(item.hashUuid, !wasRead)
                                                        }
                                                    }
                                                    false // snap back
                                                }
                                                SwipeToDismissBoxValue.EndToStart -> {
                                                    // Swipe left → toggle save/unsave + undo
                                                    val wasSaved = item.savedLater
                                                    viewModel.toggleSaved(item.hashUuid, wasSaved)
                                                    sourcesDrawerScope.launch {
                                                        val result = snackbarHostState.showSnackbar(
                                                            message = if (wasSaved) "Removed from saved" else "Saved",
                                                            actionLabel = "Undo",
                                                            duration = SnackbarDuration.Short,
                                                        )
                                                        if (result == SnackbarResult.ActionPerformed) {
                                                            viewModel.toggleSaved(item.hashUuid, !wasSaved)
                                                        }
                                                    }
                                                    false // snap back
                                                }
                                                else -> false
                                            }
                                        },
                                    )
                                    SwipeToDismissBox(
                                        state = dismissState,
                                        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
                                        content = {
                                            FeedCardFor(
                                                layout = layout,
                                                item = item,
                                                selected = isSelected,
                                                onToggleRead = itemToggleRead(item),
                                                onOpen = itemOpen(item, isSelected),
                                                onLongPress = itemLongPress(item, isSelected),
                                            )
                                        },
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(96.dp)) }
                        }
                    }
                }

                // Jump-to-top FAB: only when scrolled away from the top.
                JumpToTopFab(
                    visible = showJumpToTop,
                    onJump = {
                        sourcesDrawerScope.launch { listState.animateScrollToItem(0) }
                    },
                )
            }
        }
    }
    }
    RightDrawer(
        visible = rightDrawerOpen,
        onDismiss = { rightDrawerOpen = false },
        density = density,
        onDensityChange = viewModel::setDensity,
        themePreference = themePreference,
        onThemeChange = viewModel::setTheme,
        translateView = translateView,
        onTranslateViewChange = viewModel::setTranslateView,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * Floating "jump to top" button overlaid on the timeline. Fades/scales in only when the
 * user has scrolled away from the top. Wrapped in its own [Box] so the [Alignment] and
 * [AnimatedVisibility] receivers are unambiguous.
 */
@Composable
private fun JumpToTopFab(
    visible: Boolean,
    onJump: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            SmallFloatingActionButton(
                onClick = onJump,
                containerColor = LocalSapphirePalette.current.Accent,
                contentColor = LocalSapphirePalette.current.OnInk,
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Jump to top")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineTopBar(
    title: String,
    inSelection: Boolean,
    onOpenLeftDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearSelection: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    TopAppBar(
        windowInsets = WindowInsets(0, 0, 0, 0),
        navigationIcon = {
            IconButton(onClick = if (inSelection) onClearSelection else onOpenLeftDrawer) {
                Icon(
                    if (inSelection) Icons.Filled.Close else Icons.Filled.Menu,
                    contentDescription = if (inSelection) "Exit selection" else "Sources & search",
                    tint = palette.OnInkMuted,
                )
            }
        },
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.OnInk,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            if (inSelection) {
                IconButton(onClick = onMarkRead) {
                    Icon(Icons.Filled.Markunread, contentDescription = "Mark read", tint = palette.OnInkMuted)
                }
                IconButton(onClick = onMarkUnread) {
                    Icon(Icons.Filled.Drafts, contentDescription = "Mark unread", tint = palette.OnInkMuted)
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove", tint = palette.Danger)
                }
            } else {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Tune, contentDescription = "View & preferences", tint = palette.OnInkMuted)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = palette.Ink,
            titleContentColor = palette.OnInk,
        ),
    )
}

@Composable
private fun EmptyTimeline(
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onBuildFeed: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .grainOverlay(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .accentGlow(palette.Accent, alpha = 0.5f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.InkElevated)
                    .border(1.dp, palette.InkStrokeStrong, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = palette.Accent)
            }
            SectionEyebrow("EMPTY FEED")
            Text(
                stringResource(R.string.timeline_empty_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                color = palette.OnInk,
            )
            Text(
                stringResource(R.string.timeline_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = palette.OnInkMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryActionButton(onRefresh, "Refresh feeds")
                SecondaryActionButton(onBuildFeed, "Curate with AI")
            }
        }
    }
}

@Composable
internal fun PrimaryActionButton(onClick: () -> Unit, text: String, modifier: Modifier = Modifier) {
    val palette = LocalSapphirePalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text.uppercase(),
            style = SapphireMono.Label,
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun SecondaryActionButton(onClick: () -> Unit, text: String, modifier: Modifier = Modifier) {
    val palette = LocalSapphirePalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, palette.InkStrokeStrong, RoundedCornerShape(8.dp))
            .background(palette.InkElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text.uppercase(),
            style = SapphireMono.Label,
            color = palette.OnInk,
            fontWeight = FontWeight.SemiBold,
        )
    }
}


/** Distinct empty state when the timeline has items but the query matched none. */
@Composable
private fun NoSearchMatches(
    query: String,
    onClear: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Box(
        Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "No matches",
                style = MaterialTheme.typography.titleMedium,
                color = palette.OnInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Nothing in your feed matches \"$query\".",
                style = MaterialTheme.typography.bodySmall,
                color = palette.OnInkMuted,
                textAlign = TextAlign.Center,
            )
            SecondaryActionButton(onClick = onClear, text = "Clear search")
        }
    }
}

/**
 * Control row beneath the top bar: scope chips (All / Unread / Saved) on the left, a
 * layout toggle (List / Card) on the right. The layout toggle shares the row with
 * the scope chips rather than living in the top bar, so the whole filter/surface surface
 * is one glance. Custom pills (not Material FilterChip) to match the Sapphire identity.
 */
@Composable
private fun ScopeChipsRow(
    scope: FeedScope,
    onScopeChange: (FeedScope) -> Unit,
    layout: FeedLayout,
    onLayoutChange: (FeedLayout) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val scopeOptions = listOf(
            FeedScope.ALL to "All",
            FeedScope.UNREAD to "Unread",
            FeedScope.SAVED to "Saved",
        )
        // Scope button group — All / Unread / Saved as a compact connected pill row.
        // Built custom (not Material3 SegmentedButton) so the horizontal padding stays
        // tight across three short labels; SegmentedButton's baked ~24dp/side padding
        // makes a 3-segment group run ~80% wider than the 2-segment layout group.
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, palette.InkStroke, RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            scopeOptions.forEachIndexed { index, (value, label) ->
                val active = scope == value
                Text(
                    label,
                    style = SapphireMono.Label,
                    color = if (active) Color.White else palette.OnInkMuted,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .background(if (active) palette.Accent else Color.Transparent)
                        .clickable { onScopeChange(value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // Layout button group — List / Card, same compact connected pill style as scope.
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, palette.InkStroke, RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FeedLayout.entries.forEach { mode ->
                val active = layout == mode
                Text(
                    mode.label,
                    style = SapphireMono.Label,
                    color = if (active) Color.White else palette.OnInkMuted,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .background(if (active) palette.Accent else Color.Transparent)
                        .clickable { onLayoutChange(mode) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}



/**
 * Card dispatcher for the single-column layouts (LIST / CARD).
 * Mosaic renders its own grid cell elsewhere. Keeps the LazyColumn item lambda to one call.
 */
@Composable
private fun FeedCardFor(
    layout: FeedLayout,
    item: com.sapphire.domain.model.FeedItem,
    selected: Boolean,
    onToggleRead: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    when (layout) {
        FeedLayout.DENSE -> DenseFeedCard(item, onToggleRead, onOpen, onLongPress, selected = selected)
        FeedLayout.RICH -> RichFeedCard(item, onToggleRead, onOpen, onLongPress, selected = selected)
    }
}

/**
 * Directional background revealed behind a feed card during a triage swipe.
 * StartToEnd (swipe right) → sapphire accent + "done" glyph (toggle read).
 * EndToStart (swipe left) → amber + bookmark glyph (toggle save).
 */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val palette = LocalSapphirePalette.current
    val bgColor = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> palette.AccentDeep // read toggle — sapphire
        SwipeToDismissBoxValue.EndToStart -> Color(0xFFE8B96A) // save toggle — amber
        else -> Color.Transparent
    }
    val icon = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Icons.Filled.DoneAll
        SwipeToDismissBoxValue.EndToStart -> Icons.Filled.BookmarkBorder
        else -> null
    }
    val alignment = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        else -> Alignment.Center
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

private data class DayGroup(val label: String, val items: List<com.sapphire.domain.model.FeedItem>)

private fun groupByDay(items: List<com.sapphire.domain.model.FeedItem>): List<DayGroup> {
    val now = java.time.LocalDate.now()
    return items
        .groupBy { item ->
            val ts = item.publishedAt ?: return@groupBy 0L
            java.time.Instant.ofEpochMilli(ts)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .toEpochDay()
        }
        .toSortedMap(reverseOrder())
        .map { (epochDay, groupItems) ->
            val date = java.time.LocalDate.ofEpochDay(epochDay)
            val label = when {
                date == now -> "Today"
                date == now.minusDays(1) -> "Yesterday"
                date.isAfter(now.minusDays(7)) ->
                    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                else -> "${date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} ${date.dayOfMonth}"
            }
            DayGroup(label, groupItems)
        }
}

@Composable
private fun DayHeader(label: String) {
    val palette = LocalSapphirePalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(palette.Ink)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = SapphireMono.Label,
            color = palette.OnInkFaint,
        )
    }
}
