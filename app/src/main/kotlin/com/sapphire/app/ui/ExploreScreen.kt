package com.sapphire.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.Divider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.explore.ExploreFeed

/**
 * Explore — browse curated catalog rails, search feeds via the Tier-1 LLM (or paste a
 * URL for an instant, free result), and subscribe into an existing category. Reached
 * from the Sources drawer's "Explore sources" row. Styled dark-first per the Sapphire
 * palette; mono labels for the research-terminal accent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onBack: () -> Unit,
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
                SearchState.RESULTS -> SearchResultsList(searchResults) { pickingFeed = it }
                SearchState.IDLE -> BrowseRails(sections) { pickingFeed = it }
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
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
    )
}

@Composable
private fun BrowseRails(
    sections: List<ExploreSectionUi>,
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
                FeedCard(feedUi = feedUi, onSubscribe = { onSubscribe(feedUi.feed) }, expanded = true)
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<ExploreFeedUi>,
    onSubscribe: (ExploreFeed) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(results, key = { it.feed.url }) { feedUi ->
            FeedCard(feedUi = feedUi, onSubscribe = { onSubscribe(feedUi.feed) }, expanded = true)
        }
    }
}

@Composable
private fun FeedCard(
    feedUi: ExploreFeedUi,
    onSubscribe: () -> Unit,
    expanded: Boolean = false,
) {
    val palette = LocalSapphirePalette.current
    val width = if (expanded) Modifier.fillMaxWidth() else Modifier.width(240.dp)
    Row(
        modifier = width.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.RssFeed, contentDescription = null, tint = palette.Accent, modifier = Modifier.height(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    feedUi.feed.kind.name.lowercase(),
                    style = SapphireMono.Label,
                    color = palette.Accent,
                )
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

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalSapphirePalette.current.Accent)
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
                Text(
                    "Curate a topic first — folders live under a topic.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.OnInkMuted,
                )
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
