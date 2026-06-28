package com.sapphire.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Markunread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.derivedStateOf

private enum class FeedLayout(val label: String) {
    LIST("List"),
    MAGAZINE("Card"),
}

/**
 * Unified timeline. Two view modes: LIST (dense one-line rows for high-density scanning)
 * and MAGAZINE (thumbnail + title + summary). Switched via a dropdown in the top bar.
 *
 * Read model: an item becomes READ only on explicit action — opening the reader or the
 * manual mark button. Scrolling never marks read.
 *
 * Article batch selection: long-press a card to enter selection mode; select multiple
 * articles for batch mark read / unread / remove.
 *
 * Reader-sheet open overlays this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    viewModel: FeedViewModel = hiltViewModel(),
    onBuildFeed: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onOpenExplore: () -> Unit = {},
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
    var layout by rememberSaveable { mutableStateOf(FeedLayout.LIST) }

    // Show the jump-to-top FAB only once the user has scrolled below the first item.
    val showJumpToTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 400
        }
    }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    var openItemId by rememberSaveable { mutableStateOf<String?>(null) }

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
            openItemId = item.hashUuid
        }
    }
    fun itemLongPress(item: com.sapphire.domain.model.FeedItem, isSelected: Boolean): () -> Unit = {
        selectedItems[item.hashUuid] = !isSelected
    }

    SourcesDrawer(
        drawerState = sourcesDrawerState,
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
    ) {

    LaunchedEffect(searchExpanded) {
        if (!searchExpanded) viewModel.setQuery("")
    }

    Scaffold(
        containerColor = LocalSapphirePalette.current.Ink,
        topBar = {
            TimelineTopBar(
                title = if (inSelection) "${selectedItems.count { it.value }} selected"
                    else filterLabel ?: "All Feeds",
                inSelection = inSelection,
                searchExpanded = searchExpanded,
                onToggleSearch = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) viewModel.setQuery("")
                },
                onMarkAllRead = viewModel::markAllVisibleRead,
                onBuildFeed = onBuildFeed,
                onOpenSources = { sourcesDrawerScope.launch { sourcesDrawerState.open() } },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                ScopeChipsRow(
                    scope = feedScope,
                    onScopeChange = viewModel::setScope,
                    layout = layout,
                    onLayoutChange = { layout = it },
                )
            if (searchExpanded && hasAnyItems && !inSelection) {
                SearchRow(
                    query = query,
                    onQueryChange = viewModel::setQuery,
                    onClear = {
                        viewModel.setQuery("")
                    },
                )
            }
            val searching = searchExpanded && query.isNotBlank()
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !hasAnyItems -> EmptyTimeline(
                        padding = PaddingValues(0.dp),
                        onRefresh = viewModel::refresh,
                        onBuildFeed = onBuildFeed,
                    )
                    timeline.isEmpty() && searching -> NoSearchMatches(
                        query = query,
                        onClear = { viewModel.setQuery(""); searchExpanded = false },
                    )
                    else -> PullToRefreshBox(
                        // The pull gesture drives a silent streaming refresh; items appear
                        // live as each source completes. Bind isRefreshing so the indicator
                        // rotates for the duration of the pass and dismisses when it ends.
                        isRefreshing = refreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // LIST / MAGAZINE — single column; only the card variant differs.
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(items = timeline, key = { it.hashUuid }) { item ->
                                val isSelected = selectedItems[item.hashUuid] == true
                                FeedCardFor(
                                    layout = layout,
                                    item = item,
                                    selected = isSelected,
                                    onToggleRead = itemToggleRead(item),
                                    onOpen = itemOpen(item, isSelected),
                                    onLongPress = itemLongPress(item, isSelected),
                                )
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

    openItemId?.let { itemId ->
        val readerViewModel: ReaderViewModel = hiltViewModel()
        LaunchedEffect(itemId) { readerViewModel.open(itemId) }
        ReaderSheet(viewModel = readerViewModel, onDismiss = { openItemId = null })
    }
    }
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
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    onMarkAllRead: () -> Unit,
    onBuildFeed: () -> Unit,
    onOpenSources: () -> Unit,
    onClearSelection: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = if (inSelection) onClearSelection else onOpenSources) {
                Icon(
                    if (inSelection) Icons.Filled.Close else Icons.Filled.Menu,
                    contentDescription = if (inSelection) "Exit selection" else "Sources",
                    tint = palette.OnInkMuted,
                )
            }
        },
        title = {
            Text(
                title,
                style = if (inSelection) SapphireMono.Label else MaterialTheme.typography.titleMedium,
                color = palette.OnInk,
                fontWeight = if (inSelection) FontWeight.SemiBold else FontWeight.Normal,
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
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove", tint = palette.Danger)
                }
            } else {
                IconButton(onClick = onToggleSearch) {
                    val icon = if (searchExpanded) Icons.Filled.Close else Icons.Outlined.Search
                    val desc = if (searchExpanded) "Close search" else "Search feed"
                    Icon(icon, contentDescription = desc, tint = palette.OnInkMuted)
                }
                IconButton(onClick = onMarkAllRead) {
                    Icon(Icons.Filled.DoneAll, contentDescription = "Mark all as read", tint = palette.OnInkMuted)
                }
                IconButton(onClick = onBuildFeed) {
                    Icon(Icons.Filled.Add, contentDescription = "Curate new topic", tint = palette.OnInkMuted)
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


/**
 * Collapsible in-feed search field. Rendered beneath the top bar when the search toggle
 * is active. Styling follows the Sapphire dark-first identity.
 */
@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        placeholder = {
            Text("Search title · summary · author", style = SapphireMono.Label, color = palette.OnInkFaint)
        },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = palette.OnInkMuted)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = palette.OnInkMuted)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = palette.InkRaised,
            unfocusedContainerColor = palette.InkElevated,
            cursorColor = palette.Accent,
            focusedIndicatorColor = palette.Accent,
            unfocusedIndicatorColor = palette.InkStroke,
            focusedTextColor = palette.OnInk,
            unfocusedTextColor = palette.OnInk,
        ),
    )
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
 * layout toggle (List / Magazine) on the right. The layout toggle shares the row with
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
 * Card dispatcher for the single-column layouts (LIST / EXPANDED / MAGAZINE / NEWSPAPER).
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
        FeedLayout.LIST -> ListFeedCard(item, onToggleRead, onOpen, onLongPress, selected = selected)
        FeedLayout.MAGAZINE -> MagazineFeedCard(item, onToggleRead, onOpen, onLongPress, selected = selected)
    }
}
