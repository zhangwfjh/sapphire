package com.sapphire.app.ui

import androidx.core.net.toUri
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.sapphire.app.ui.design.shimmerSweep
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateMapOf
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
 * Explore — browse curated catalog rails, search feeds via keyless live-web harvest
 * (or paste a URL for an instant, free result), peek a feed before subscribing, then
 * subscribe into an existing or new folder. Reached from the Sources drawer's "Explore
 * sources" row. Styled dark-first per the Sapphire palette; mono labels for the
 * research-terminal accent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onBack: () -> Unit,
    onBuildAgent: () -> Unit = {},
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
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val exportXml by viewModel.exportXml.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var pickingFeed by remember { mutableStateOf<ExploreFeed?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // SAF: OPML import
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                viewModel.importOpml(stream)
            }
        }
    }

    // SAF: OPML export
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/xml"),
    ) { uri ->
        val xml = exportXml
        if (uri != null && xml != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(xml.toByteArray(Charsets.UTF_8))
            }
            viewModel.consumeExport()
        }
    }

    LaunchedEffect(importState) {
        when (val s = importState) {
            is ImportState.Done -> {
                snackbarHostState.showSnackbar("Imported ${s.sourcesImported} sources.")
                viewModel.dismissImportState()
            }
            is ImportState.Error -> {
                snackbarHostState.showSnackbar("Import failed: ${s.message}")
                viewModel.dismissImportState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(exportXml) {
        if (exportXml != null) {
            exportLauncher.launch("sapphire-sources.opml")
        }
    }

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
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/xml", "text/xml", "*/*")) }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Import OPML", tint = palette.OnInkMuted)
                    }
                    IconButton(onClick = { viewModel.exportOpml() }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "Export OPML", tint = palette.OnInkMuted)
                    }
                    IconButton(onClick = onBuildAgent) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "Build an agent", tint = palette.Accent)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = palette.Ink,
        contentColor = palette.OnInk,
    ) { padding ->
        ExploreBody(
            query = query,
            onQueryChange = { query = it },
            onSubmit = { viewModel.search(query) },
            onClear = { query = ""; viewModel.clearSearch() },
            onPasteUrl = { query = it },
            onImportOpml = { importLauncher.launch(arrayOf("application/xml", "text/xml", "*/*")) },
            onBuildAgent = onBuildAgent,
            searchState = searchState,
            searchResults = searchResults,
            searchError = searchError,
            sections = sections,
            onPreview = viewModel::preview,
            onSubscribe = { pickingFeed = it },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
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
private fun ExploreBody(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onPasteUrl: (String) -> Unit,
    onImportOpml: () -> Unit,
    onBuildAgent: () -> Unit,
    searchState: SearchState,
    searchResults: List<ExploreFeedUi>,
    searchError: String?,
    sections: List<ExploreSectionUi>,
    onPreview: (ExploreFeed) -> Unit,
    onSubscribe: (ExploreFeed) -> Unit,
    modifier: Modifier = Modifier,
) {
    val collapsed = remember(sections) { mutableStateMapOf<String, Boolean>() }
    val toggle: (String) -> Unit = { title ->
        val default = sections.indexOfFirst { it.title == title } < 2
        collapsed[title] = !(collapsed[title] ?: default)
    }
    val searching = query.isNotBlank()
    LazyColumn(modifier.fillMaxSize()) {
        item(key = "hero") {
            HeroSearch(
                query = query,
                onQueryChange = onQueryChange,
                onSubmit = onSubmit,
                onClear = onClear,
            )
        }
        if (!searching) {
            // Browse view: mode tiles (URL / OPML / featured agent) then the domain rails.
            item(key = "modes") {
                ModeTiles(
                    onPasteUrl = onPasteUrl,
                    onImportOpml = onImportOpml,
                    onBuildAgent = onBuildAgent,
                )
            }
            domainRails(
                sections = sections,
                collapsed = collapsed,
                onToggle = toggle,
                onPreview = onPreview,
                onSubscribe = onSubscribe,
            )
        } else {
            // Search view.
            when (searchState) {
                SearchState.LOADING -> item(key = "loading") { LoadingState() }
                SearchState.ERROR -> item(key = "error") { MessageState(searchError ?: "Search failed.") }
                SearchState.EMPTY -> item(key = "empty") {
                    MessageState("No feeds found for \"$query\" — try a broader term or paste a URL.")
                }
                SearchState.RESULTS -> items(searchResults, key = { it.feed.url }) { feedUi ->
                    FeedCard(
                        feedUi = feedUi,
                        onPreview = { onPreview(feedUi.feed) },
                        onSubscribe = { onSubscribe(feedUi.feed) },
                        expanded = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
                SearchState.IDLE -> Unit
            }
        }
    }
}

/**
 * Hero block: eyebrow + editorial headline + accent glow + search field with the
 * "AI search · URLs preview instantly" hint. Ported from `design/explore.html` `.hero`.
 */
@Composable
private fun HeroSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
        SectionEyebrow("Explore sources")
        Text(
            buildAnnotatedString {
                append("Find a feed for ")
                withStyle(SpanStyle(color = palette.AccentBright, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append("anything")
                }
                append("\nworth reading.")
            },
            style = MaterialTheme.typography.displaySmall,
            color = palette.OnInk,
            modifier = Modifier.padding(top = 9.dp),
        )
        Text(
            "Browse a curated newsstand, search any topic across the live web, or drop in a URL.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.OnInkMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search a topic — or paste a feed URL", color = palette.OnInkFaint) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = palette.Accent) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = palette.OnInkMuted)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = palette.AccentBright,
                modifier = Modifier.size(12.dp),
            )
            Text("Live web search · any topic", style = SapphireMono.Label, color = palette.AccentBright, fontWeight = FontWeight.SemiBold)
            Text("/", style = SapphireMono.Label, color = palette.OnInkFaint)
            Text("URLs preview instantly", style = SapphireMono.Label, color = palette.OnInkFaint)
        }
    }
}

/**
 * Mode tiles. First row: two compact tiles (Paste a URL, Import OPML) sharing width.
 * Second row: the full-width featured "Build an agent" tile with accent tint + arrow.
 * Ported from `design/explore.html` `.modes.wrap` + `.mode.featured`.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModeTiles(
    onPasteUrl: (String) -> Unit,
    onImportOpml: () -> Unit,
    onBuildAgent: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactModeTile(
                icon = Icons.Filled.Link,
                index = "01",
                title = "Paste a URL",
                desc = "Instant preview, no key needed.",
                onClick = { onPasteUrl("https://") },
                modifier = Modifier.weight(1f),
            )
            CompactModeTile(
                icon = Icons.Filled.FileDownload,
                index = "02",
                title = "Import OPML",
                desc = "Bulk-add from another reader.",
                onClick = onImportOpml,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Featured full-width tile: accent-tinted, arrow affordance, deep-links to Agents.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.Accent.copy(alpha = 0.10f))
                .border(1.dp, palette.Accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .clickable(onClick = onBuildAgent)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(palette.Accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = palette.AccentBright,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Build an agent", style = MaterialTheme.typography.titleMedium, color = palette.OnInk, fontWeight = FontWeight.SemiBold)
                Text(
                    "Hand the AI a prompt + a schedule. It searches, synthesizes, and files results into your feed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.OnInkMuted,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open Agents", tint = palette.AccentBright)
        }
    }
}

@Composable
private fun CompactModeTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    index: String,
    title: String,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSapphirePalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.InkElevated)
            .border(1.dp, palette.InkStroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                    .background(palette.Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = palette.AccentBright, modifier = Modifier.size(16.dp))
            }
            Text(index, style = SapphireMono.Label, color = palette.OnInkFaint, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = palette.OnInk, fontWeight = FontWeight.SemiBold)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted, modifier = Modifier.padding(top = 1.dp))
    }
}

/**
 * Domain rails. LazyColumn-sourced item sequence: for each domain a clickable header
 * (code chip + name + count + blurb + chevron), then — if open — a horizontal rail of
 * feed cards. The first two domains start expanded so the catalog reads as a newsstand,
 * not a table of contents. Ported from `design/explore.html` `.domain` / `.rail`.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.domainRails(
    sections: List<ExploreSectionUi>,
    collapsed: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onToggle: (String) -> Unit,
    onPreview: (ExploreFeed) -> Unit,
    onSubscribe: (ExploreFeed) -> Unit,
) {
    sections.forEachIndexed { i, section ->
        item(key = "header-${section.title}") {
            val open = collapsed[section.title] ?: (i < 2)
            DomainHeader(section = section, open = open) { onToggle(section.title) }
        }
        val open = collapsed[section.title] ?: (i < 2)
        if (open) {
            item(key = "rail-${section.title}") {
                DomainRail(
                    feeds = section.feeds,
                    onPreview = { onPreview(it) },
                    onSubscribe = { onSubscribe(it) },
                )
            }
        }
    }
}

@Composable
private fun DomainHeader(section: ExploreSectionUi, open: Boolean, onClick: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Mono 2-letter code chip — the per-domain identity mark.
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
                .background(palette.InkElevated)
                .border(1.dp, palette.InkStrokeStrong, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                domainCode(section.title),
                style = SapphireMono.Label,
                color = palette.OnInkMuted,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleSmall, color = palette.OnInk, fontWeight = FontWeight.SemiBold)
                Text(
                    "%02d feeds".format(section.feeds.size),
                    style = SapphireMono.Label,
                    color = palette.OnInkFaint,
                )
            }
            Text(
                section.kind.name.lowercase(),
                style = SapphireMono.Label,
                color = palette.OnInkFaint,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Collapse ${section.title}" else "Expand ${section.title}",
            tint = palette.OnInkFaint,
            modifier = Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f },
        )
    }
}

@Composable
private fun DomainRail(
    feeds: List<ExploreFeedUi>,
    onPreview: (ExploreFeed) -> Unit,
    onSubscribe: (ExploreFeed) -> Unit,
) {
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.foundation.lazy.LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(feeds, key = { it.feed.url }) { feedUi ->
            FeedCard(
                feedUi = feedUi,
                onPreview = { onPreview(feedUi.feed) },
                onSubscribe = { onSubscribe(feedUi.feed) },
                expanded = false,
                modifier = Modifier.width(204.dp).padding(vertical = 2.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

/**
 * 2-letter identity code for a section title (the `.codechip` in explore.html).
 * Deterministic from the title so it's stable across recompositions.
 */
private fun domainCode(title: String): String {
    val letters = title.uppercase().filter { it.isLetter() }
    return if (letters.length >= 2) letters.take(2) else letters.padEnd(2, 'X')
}

/**
 * Feed card. Wide (row layout, full width) for search results; compact (column layout,
 * fixed 204dp) for browse rails. Tapping anywhere except the +/✓ opens preview.
 * Subscribed → check glyph; unsubscribed → + button. Ported from `design/explore.html`
 * `.card` / `.card.wide`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedCard(
    feedUi: ExploreFeedUi,
    onPreview: () -> Unit,
    onSubscribe: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSapphirePalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.InkElevated)
            .border(1.dp, palette.InkStroke, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onPreview),
    ) {
        if (expanded) {
            // Wide: favicon + body + trailing action.
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                Favicon(url = feedUi.feed.url)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    CardMeta(feedUi)
                    Spacer(Modifier.height(5.dp))
                    Text(feedUi.feed.title, style = MaterialTheme.typography.titleSmall, color = palette.OnInk, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    feedUi.feed.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                CardAction(feedUi, onSubscribe)
            }
        } else {
            // Compact: stacked column for the horizontal rail.
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                CardMeta(feedUi)
                Spacer(Modifier.height(8.dp))
                Text(feedUi.feed.title, style = MaterialTheme.typography.titleSmall, color = palette.OnInk, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                feedUi.feed.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = palette.OnInkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CardAction(feedUi, onSubscribe)
                }
            }
        }
    }
}

@Composable
private fun CardMeta(feedUi: ExploreFeedUi) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PlatformBadge(tag = feedUi.feed.kind.name, read = false)
        feedUi.feed.language?.takeIf { it.isNotBlank() }?.let { lang ->
            Text(lang.uppercase(), style = SapphireMono.Label, color = palette().OnInkFaint)
        }
    }
}

@Composable
private fun CardAction(feedUi: ExploreFeedUi, onSubscribe: () -> Unit) {
    val palette = LocalSapphirePalette.current
    if (feedUi.subscribed) {
        Icon(Icons.Filled.Check, contentDescription = "Added", tint = palette.OnInkMuted, modifier = Modifier.padding(top = 2.dp))
    } else {
        IconButton(onClick = onSubscribe, modifier = Modifier.padding(top = 2.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Subscribe", tint = palette.Accent)
        }
    }
}

@Composable
private fun palette() = LocalSapphirePalette.current

/**
 * Site favicon with an RssFeed glyph fallback. The DuckDuckGo icon service is reliable
 * and key-less; the fallback icon is painted behind the [AsyncImage] so a failed load
 * reads as the glyph rather than a blank box.
 */
@Composable
private fun Favicon(url: String) {
    val palette = LocalSapphirePalette.current
    val favUrl = remember(url) {
        val host = runCatching { url.toUri().host }.getOrNull()
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
                // A topic is required for folders; OPML import is the path to a first
                // topic. Guide the user there instead of dead-ending.
                Text(
                    "Folders live under a topic. Import an OPML file to create your first topic and start subscribing.",
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
                HorizontalDivider(color = palette.InkStroke, thickness = 0.5.dp)
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
