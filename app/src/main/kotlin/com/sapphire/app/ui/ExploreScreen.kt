package com.sapphire.app.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.sapphire.app.ui.design.shimmerSweep
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.sapphire.app.ui.design.PlatformBadge
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.design.ShimmerBlock
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.explore.ExploreFeed

/**
 * Explore — browse curated catalog rails, search feeds via the Tier-1 LLM (or paste a
 * URL for an instant, free result), peek a feed before subscribing, then subscribe into
 * an existing or new folder. Reached from the Sources drawer's "Explore sources" row.
 * Styled dark-first per the Sapphire palette; mono labels for the research-terminal accent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    onCurateTopic: () -> Unit,
    viewModel: ExploreViewModel = hiltViewModel(),
) {
    val palette = LocalSapphirePalette.current
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val hasTopic by viewModel.hasTopic.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchError by viewModel.searchError.collectAsStateWithLifecycle()
    val subscribeResult by viewModel.subscribeResult.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var pickingFeed by remember { mutableStateOf<ExploreFeed?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(subscribeResult) {
        when (val r = subscribeResult) {
            is SubscribeResult.Added -> {
                snackbarHostState.showSnackbar("Added to \"${r.folder}\".")
                viewModel.consumeSubscribeResult()
            }
            is SubscribeResult.Conflict -> {
                snackbarHostState.showSnackbar("Already in that folder.")
                viewModel.consumeSubscribeResult()
            }
            null -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Explore",
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
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = palette.Ink,
        contentColor = palette.OnInk,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchRow(
                query = query,
                onQueryChange = { query = it },
                onSubmit = { viewModel.search(query) },
                onClear = { query = ""; viewModel.clearSearch() },
            )

            when (searchState) {
                SearchState.LOADING -> LoadingState()
                SearchState.ERROR -> MessageState(searchError ?: "Search failed.")
                SearchState.EMPTY -> MessageState("No feeds found for \"$query\" — try a broader term or paste a URL.")
                SearchState.RESULTS -> SearchResultsList(
                    results = searchResults,
                    onPreview = viewModel::preview,
                    onSubscribe = { pickingFeed = it },
                )
                SearchState.IDLE -> BrowseRails(
                    sections = sections,
                    onPreview = viewModel::preview,
                    onSubscribe = { pickingFeed = it },
                )
            }
        }
    }

    val feed = pickingFeed
    if (feed != null) {
        CategoryPickerSheet(
            feed = feed,
            categories = categories,
            hasTopic = hasTopic,
            onDismiss = { pickingFeed = null },
            onPick = { categoryId, label ->
                viewModel.subscribe(feed, categoryId, label)
                pickingFeed = null
            },
            onCreateFolder = { folderName ->
                viewModel.subscribeIntoNewFolder(feed, folderName)
                pickingFeed = null
            },
            onCurateTopic = {
                pickingFeed = null
                onCurateTopic()
            },
        )
    }

    if (previewState !is PreviewState.Idle) {
        FeedPreviewSheet(
            state = previewState,
            onDismiss = viewModel::clearPreview,
            onSubscribe = { feedToPick ->
                viewModel.clearPreview()
                pickingFeed = feedToPick
            },
        )
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search a topic or paste a feed URL", color = palette.OnInkFaint) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = palette.Accent) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = palette.OnInkMuted)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

@Composable
private fun BrowseRails(
    sections: List<ExploreSectionUi>,
    onPreview: (ExploreFeed) -> Unit,
    onSubscribe: (ExploreFeed) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    if (sections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Browse curated feeds or search for more.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.OnInkMuted,
            )
        }
        return
    }
    // Single-column list: each section renders as an eyebrow header followed by its
    // feeds as full-width rows (no horizontal rail). Keeps one LazyColumn so scroll
    // is linear and cards reflow at any width.
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            item(key = "header-${section.title}") {
                SectionEyebrow(text = section.title.uppercase())
            }
            items(
                items = section.feeds,
                key = { feedUi -> "${section.title}-${feedUi.feed.url}" },
            ) { feedUi ->
                FeedCard(
                    feedUi = feedUi,
                    onPreview = { onPreview(feedUi.feed) },
                    onSubscribe = { onSubscribe(feedUi.feed) },
                    expanded = true,
                )
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<ExploreFeedUi>,
    onPreview: (ExploreFeed) -> Unit,
    onSubscribe: (ExploreFeed) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(results, key = { it.feed.url }) { feedUi ->
            FeedCard(
                feedUi = feedUi,
                onPreview = { onPreview(feedUi.feed) },
                onSubscribe = { onSubscribe(feedUi.feed) },
                expanded = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedCard(
    feedUi: ExploreFeedUi,
    onPreview: () -> Unit,
    onSubscribe: () -> Unit,
    expanded: Boolean = false,
) {
    val palette = LocalSapphirePalette.current
    val width = if (expanded) Modifier.fillMaxWidth() else Modifier.width(240.dp)
    // Box + combinedClickable — the proven click pattern from FeedCardSurface. The card
    // is a visible tappable surface (elevated ink, hairline border, rounded corners) so
    // the user sees an affordance; tapping anywhere except the +/✓ button opens preview.
    Box(
        modifier = width
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.InkElevated)
            .border(1.dp, palette.InkStroke, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onPreview),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Favicon(url = feedUi.feed.url)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformBadge(tag = feedUi.feed.kind.name, read = false)
                    feedUi.feed.language?.takeIf { it.isNotBlank() }?.let { lang ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            lang.uppercase(),
                            style = SapphireMono.Label,
                            color = palette.OnInkFaint,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    feedUi.feed.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.OnInk,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                feedUi.feed.description?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.OnInkMuted,
                        maxLines = if (expanded) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (feedUi.subscribed) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Added",
                    tint = palette.OnInkMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                IconButton(onClick = onSubscribe, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Subscribe",
                        tint = palette.Accent,
                    )
                }
            }
        }
    }
}

/**
 * Site favicon with an RssFeed glyph fallback. The DuckDuckGo icon service is reliable
 * and key-less; the fallback icon is painted behind the [AsyncImage] so a failed load
 * reads as the glyph rather than a blank box.
 */
@Composable
private fun Favicon(url: String) {
    val palette = LocalSapphirePalette.current
    val favUrl = remember(url) {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        if (host.isNullOrBlank()) null else "https://icons.duckduckgo.com/ip3/$host.ico"
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.InkRaised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.RssFeed, contentDescription = null, tint = palette.OnInkFaint, modifier = Modifier.size(18.dp))
        if (favUrl != null) {
            AsyncImage(
                model = favUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoadingState() {
    val palette = LocalSapphirePalette.current
    // Shimmer skeletons mirroring the FeedCard layout: favicon + title + description,
    // repeated so the loading reads as "feeds are coming" rather than an inert spinner.
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(5) {
            Row(verticalAlignment = Alignment.Top) {
                ShimmerBlock(width = 36.dp, height = 36.dp, modifier = Modifier.clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShimmerBlock(width = 96.dp, height = 14.dp)
                    ShimmerBlock(width = 220.dp, height = 16.dp)
                    ShimmerBlock(width = 180.dp, height = 12.dp)
                }
            }
        }
    }
}

@Composable
private fun MessageState(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalSapphirePalette.current.OnInkMuted,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    feed: ExploreFeed,
    categories: List<CategoryOption>,
    hasTopic: Boolean,
    onDismiss: () -> Unit,
    onPick: (categoryId: String, label: String) -> Unit,
    onCreateFolder: (folderName: String) -> Unit,
    onCurateTopic: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val sheetState = rememberModalBottomSheetState()
    var newFolderName by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Add \"${feed.title}\" to…",
                style = MaterialTheme.typography.titleMedium,
                color = palette.OnInk,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            if (!hasTopic) {
                // Explore must never dead-end: a topic is required for folders, so route
                // the user to onboarding instead of leaving them stuck in the picker.
                Text(
                    "Folders live under a curated topic. Curate one to start subscribing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.OnInkMuted,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onCurateTopic,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.Accent),
                ) { Text("Curate a topic") }
            } else {
                // New-folder affordance: inline name + create button. Shown whenever a
                // topic exists, even when no folders do yet — that's exactly the moment a
                // user needs to create the first one.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null, tint = palette.Accent)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("New folder", color = palette.OnInkFaint) },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onCreateFolder(newFolderName) },
                        enabled = newFolderName.isNotBlank(),
                    ) {
                        Text("Create", color = if (newFolderName.isNotBlank()) palette.Accent else palette.OnInkFaint)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Divider(color = palette.InkStroke, thickness = 0.5.dp)
                Spacer(Modifier.height(4.dp))

                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(category.id, category.name) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = palette.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(category.name, color = palette.OnInk)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedPreviewSheet(
    state: PreviewState,
    onDismiss: () -> Unit,
    onSubscribe: (ExploreFeed) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val sheetState = rememberModalBottomSheetState()
    val feed: ExploreFeed = (state as? PreviewState.Loading)?.feed
        ?: (state as? PreviewState.Loaded)?.feed
        ?: (state as? PreviewState.Empty)?.feed
        ?: (state as? PreviewState.Failed)?.feed
        ?: return

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Favicon(url = feed.url)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformBadge(tag = feed.kind.name, read = false)
                        feed.language?.takeIf { it.isNotBlank() }?.let { lang ->
                            Spacer(Modifier.width(6.dp))
                            Text(lang.uppercase(), style = SapphireMono.Label, color = palette.OnInkFaint)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        feed.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.OnInk,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            feed.description?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.OnInkMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            SectionEyebrow(text = "RECENT")
            Spacer(Modifier.height(8.dp))

            when (state) {
                is PreviewState.Loading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        repeat(3) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .shimmerSweep(),
                            )
                        }
                    }
                }
                is PreviewState.Loaded -> {
                    state.items.forEach { item ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.OnInk,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.summary?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.OnInkMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                is PreviewState.Empty -> Text(
                    "This feed has no recent items.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.OnInkMuted,
                )
                is PreviewState.Failed -> Text(
                    "Couldn't load this feed — it may be down or unsupported. You can still subscribe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.OnInkMuted,
                )
                PreviewState.Idle -> Unit
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSubscribe(feed) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = palette.Accent),
            ) { Text("Subscribe") }
        }
    }
}
