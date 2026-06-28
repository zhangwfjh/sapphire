package com.sapphire.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.model.Source
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.source.SourceFolderNode
import com.sapphire.domain.source.SourceTreeNode
// ---- Dialog models (kept here; drawer-local) ----

private sealed interface DrawerDialog {
    data class EditSource(val source: Source) : DrawerDialog
    data class MoveSource(val source: Source) : DrawerDialog
    data class AddSource(val categoryId: String) : DrawerDialog
    data class RenameCategory(val id: String, val name: String) : DrawerDialog
    data object AddTopLevelFolder : DrawerDialog
    data class ConfirmDeleteCategory(val id: String, val name: String) : DrawerDialog
    data class MergeCategory(val id: String, val name: String) : DrawerDialog
    data class BatchMoveSources(val ids: Set<String>) : DrawerDialog
    data class ConfirmBatchDeleteSources(val ids: Set<String>) : DrawerDialog
    data class ConfirmDeleteSource(val id: String, val title: String) : DrawerDialog
}

/**
 * Sources drawer. A [ModalNavigationDrawer] over the timeline; opens from the top-left
 * menu button. Renders the single-level folder list (folders → sources). Tapping a folder
 * or source filters the timeline; "All Feeds" clears it. Read Later is a drawer destination.
 *
 * Source row gestures: long-press or swipe-right opens a context menu (Edit / Move / Select
 * / Remove); swipe-left marks all of the source's items as read, surfaced with an Undo
 * snackbar. Folders keep their inline Add/Rename/Delete buttons and show an unread badge.
 * Counts shown everywhere are unread-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesDrawer(
    viewModel: SourcesDrawerViewModel = hiltViewModel(),
    drawerState: androidx.compose.material3.DrawerState = rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed,
    ),
    onCategoryClick: (categoryIds: Set<String>, label: String) -> Unit = { _, _ -> },
    onSourceGroupClick: (sourceIds: Set<String>, label: String) -> Unit = { _, _ -> },
    onSourceClick: (sourceId: String, label: String) -> Unit = { _, _ -> },
    onClearFilter: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onOpenExplore: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val conflict by viewModel.conflict.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(conflict) {
        conflict?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissConflict()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.markReadEvents.collect { event ->
            val result = snackbarHostState.showSnackbar(
                message = "Marked ${event.count} as read in \"${event.label}\".",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoMarkRead(event.itemIds)
            }
        }
    }

    val importState by viewModel.importState.collectAsStateWithLifecycle()
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

    val context = androidx.compose.ui.platform.LocalContext.current
    val exportXml by viewModel.exportXml.collectAsStateWithLifecycle()

    // SAF: pick an .opml file to import.
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                viewModel.importOpml(stream)
            }
        }
    }

    // SAF: choose a destination to write the OPML export.
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

    // When export XML is ready, prompt the user for a save location.
    LaunchedEffect(exportXml) {
        if (exportXml != null) {
            exportLauncher.launch("sapphire-sources.opml")
        }
    }

    var dialog by remember { mutableStateOf<DrawerDialog?>(null) }
    val selectedSources = remember { mutableStateMapOf<String, Boolean>() }
    val inSourceSelection = selectedSources.any { it.value }

    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerSheetContent(
                    tree = tree,
                    selectedSources = selectedSources,
                    inSelection = inSourceSelection,
                    onToggleSelectSource = { id -> selectedSources[id] = selectedSources[id] != true },
                    onClearSelection = { selectedSources.clear() },
                    onBatchMove = { dialog = DrawerDialog.BatchMoveSources(selectedSources.filter { it.value }.keys.toSet()) },
                    onBatchDelete = { dialog = DrawerDialog.ConfirmBatchDeleteSources(selectedSources.filter { it.value }.keys.toSet()) },
                    onAddSource = { catId -> dialog = DrawerDialog.AddSource(catId) },
                    onAddTopLevelFolder = { dialog = DrawerDialog.AddTopLevelFolder },
                    onRenameCategory = { id, name -> dialog = DrawerDialog.RenameCategory(id, name) },
                    onMergeCategory = { id, name -> dialog = DrawerDialog.MergeCategory(id, name) },
                    onDeleteCategory = { id, name -> dialog = DrawerDialog.ConfirmDeleteCategory(id, name) },
                    onEditSource = { dialog = DrawerDialog.EditSource(it) },
                    onMoveSource = { dialog = DrawerDialog.MoveSource(it) },
                    onDeleteSource = { id, title -> dialog = DrawerDialog.ConfirmDeleteSource(id, title) },
                    onMarkAllReadInSource = { sourceId, label -> viewModel.markAllReadInSource(sourceId, label) },
                    onMarkAllReadInCategory = { categoryId, label -> viewModel.markAllReadInCategory(categoryId, label) },
                    onMarkAllReadInSourceGroup = { sourceIds, label -> viewModel.markAllReadInSourceGroup(sourceIds, label) },
                    onCategoryClick = onCategoryClick,
                    onSourceGroupClick = onSourceGroupClick,
                    onSourceClick = onSourceClick,
                    onClearFilter = onClearFilter,
                    onOpenSaved = onOpenSaved,
                    onOpenExplore = onOpenExplore,
                    onImportOpml = { importLauncher.launch(arrayOf("application/xml", "text/xml", "*/*")) },
                    onExportOpml = { viewModel.exportOpml() },
                )
            },
        ) {
            content()
        }

        // Single host, drawn last → topmost z-order. Sits above both the timeline and the
        // open drawer sheet, so the undo toast is never masked by the sidebar.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }

    when (val d = dialog) {
        is DrawerDialog.EditSource -> EditSourceDialog(
            source = d.source,
            onDismiss = { dialog = null },
            onSave = { title, url, kind ->
                viewModel.updateSource(d.source.id, title, url, kind)
                dialog = null
            },
        )
        is DrawerDialog.MoveSource -> MoveSourceDialog(
            source = d.source,
            tree = tree,
            onDismiss = { dialog = null },
            onMove = { targetCatId ->
                viewModel.moveSource(d.source.id, targetCatId)
                dialog = null
            },
        )
        is DrawerDialog.AddSource -> AddSourceDialog(
            onDismiss = { dialog = null },
            onAdd = { title, url, kind ->
                viewModel.addSource(d.categoryId, title, url, kind)
                dialog = null
            },
        )
        is DrawerDialog.RenameCategory -> RenameCategoryDialog(
            initial = d.name,
            onDismiss = { dialog = null },
            onRename = { name ->
                viewModel.renameCategory(d.id, name)
                dialog = null
            },
        )
        DrawerDialog.AddTopLevelFolder -> AddTopLevelFolderDialog(
            onDismiss = { dialog = null },
            onAdd = { name ->
                viewModel.addTopLevelCategory(name)
                dialog = null
            },
        )
        is DrawerDialog.BatchMoveSources -> MoveSourceDialog(
            source = null,
            tree = tree,
            onDismiss = { dialog = null; selectedSources.clear() },
            onMove = { targetCatId ->
                viewModel.moveSources(d.ids, targetCatId)
                selectedSources.clear()
                dialog = null
            },
        )
        is DrawerDialog.ConfirmDeleteCategory -> ConfirmDialog(
            title = "Delete \"${d.name}\"?",
            body = "This removes the folder and everything under it (sources and their feed items).",
            confirmLabel = "Delete",
            onDismiss = { dialog = null },
            onConfirm = {
                viewModel.deleteCategory(d.id)
                dialog = null
            },
        )
        is DrawerDialog.MergeCategory -> MergeCategoryDialog(
            fromId = d.id,
            fromName = d.name,
            tree = tree,
            onDismiss = { dialog = null },
            onMerge = { targetId ->
                viewModel.mergeCategory(d.id, targetId)
                dialog = null
            },
        )
        is DrawerDialog.ConfirmDeleteSource -> ConfirmDialog(
            title = "Remove \"${d.title}\"?",
            body = "The source and all items fetched from it will be deleted.",
            confirmLabel = "Remove",
            onDismiss = { dialog = null },
            onConfirm = {
                viewModel.deleteSource(d.id)
                dialog = null
            },
        )
        is DrawerDialog.ConfirmBatchDeleteSources -> ConfirmDialog(
            title = "Remove ${d.ids.size} source(s)?",
            body = "The selected sources and all items fetched from them will be deleted.",
            confirmLabel = "Remove",
            onDismiss = { dialog = null; selectedSources.clear() },
            onConfirm = {
                viewModel.deleteSources(d.ids)
                selectedSources.clear()
                dialog = null
            },
        )
        null -> Unit
    }
}

// ---- Drawer sheet ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerSheetContent(
    tree: List<SourceFolderNode>,
    selectedSources: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    inSelection: Boolean,
    onToggleSelectSource: (String) -> Unit,
    onClearSelection: () -> Unit,
    onBatchMove: () -> Unit,
    onBatchDelete: () -> Unit,
    onAddSource: (categoryId: String) -> Unit,
    onAddTopLevelFolder: () -> Unit,
    onRenameCategory: (id: String, name: String) -> Unit,
    onMergeCategory: (id: String, name: String) -> Unit,
    onDeleteCategory: (id: String, name: String) -> Unit,
    onEditSource: (Source) -> Unit,
    onMoveSource: (Source) -> Unit,
    onDeleteSource: (id: String, title: String) -> Unit,
    onMarkAllReadInSource: (sourceId: String, label: String) -> Unit,
    onMarkAllReadInCategory: (categoryId: String, label: String) -> Unit,
    onMarkAllReadInSourceGroup: (sourceIds: Set<String>, label: String) -> Unit,
    onCategoryClick: (categoryIds: Set<String>, label: String) -> Unit,
    onSourceGroupClick: (sourceIds: Set<String>, label: String) -> Unit,
    onSourceClick: (sourceId: String, label: String) -> Unit,
    onClearFilter: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenExplore: () -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }
    ModalDrawerSheet(
        drawerContainerColor = palette.Ink,
        drawerContentColor = palette.OnInk,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.InkElevated)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (inSelection) "${selectedSources.count { it.value }} SELECTED" else "FOLDERS",
                style = SapphireMono.Label,
                color = palette.Accent,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (inSelection) {
                IconButton(onClick = onBatchMove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.DriveFileMove, contentDescription = "Move selected", tint = palette.Accent, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onBatchDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove selected", tint = palette.Danger, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClearSelection, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = "Done", tint = palette.OnInkMuted, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(onClick = onImportOpml, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.FileDownload,
                        contentDescription = "Import OPML",
                        tint = palette.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onExportOpml, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.FileUpload,
                        contentDescription = "Export OPML",
                        tint = palette.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onAddTopLevelFolder, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.CreateNewFolder,
                        contentDescription = "New folder",
                        tint = palette.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Divider(color = palette.InkStroke, thickness = 1.dp)

        if (tree.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No sources yet. Curate a topic to populate the list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.OnInkMuted,
                )
            }
            return@ModalDrawerSheet
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(bottom = 24.dp)) {
            item(key = "explore") {
                ExploreSourcesRow(onClick = onOpenExplore)
            }
            item(key = "all-sources") {
                AllFeedsRow(onClick = onClearFilter)
            }
            item(key = "read-later") {
                ReadLaterRow(onClick = onOpenSaved)
            }
            tree.forEach { folder ->
                val isExpanded = expandedFolders[folder.category.id] == true
                item(key = "folder-${folder.category.id}") {
                    val folderUnread = folder.sources.sumOf { it.counts.unread } +
                        folder.children.sumOf { c -> c.sources.sumOf { it.counts.unread } }
                    FolderHeader(
                        node = folder,
                        unreadCount = folderUnread,
                        expanded = isExpanded,
                        onToggleExpand = {
                            expandedFolders[folder.category.id] = !isExpanded
                        },
                        onRename = { onRenameCategory(folder.category.id, folder.category.name) },
                        onDelete = { onDeleteCategory(folder.category.id, folder.category.name) },
                        onAddSource = { onAddSource(folder.category.id) },
                        onMerge = { onMergeCategory(folder.category.id, folder.category.name) },
                        onMarkAllRead = {
                            onMarkAllReadInCategory(folder.category.id, folder.category.name)
                        },
                        onFilter = { onCategoryClick(setOf(folder.category.id), folder.category.name) },
                    )
                }
                if (isExpanded) {
                    // Interleaved contents: name-sorted mix of direct sources and virtual
                    // domain-group sub-folders, so a group sits among singletons by name.
                    folder.entries.forEach { entry ->
                        when (entry) {
                            is SourceTreeNode.Leaf -> {
                                val node = entry.node
                                item(key = "src-${node.source.id}") {
                                    SourceRow(
                                        source = node.source,
                                        counts = node.counts,
                                        selected = selectedSources[node.source.id] == true,
                                        inSelection = inSelection,
                                        onEdit = { onEditSource(node.source) },
                                        onMove = { onMoveSource(node.source) },
                                        onDelete = { onDeleteSource(node.source.id, node.source.title ?: node.source.url) },
                                        onMarkAllRead = {
                                            onMarkAllReadInSource(node.source.id, node.source.title ?: node.source.url)
                                        },
                                        onClick = {
                                            if (inSelection) onToggleSelectSource(node.source.id)
                                            else onSourceClick(node.source.id, node.source.title ?: node.source.url)
                                        },
                                        onLongPress = { onToggleSelectSource(node.source.id) },
                                    )
                                }
                            }
                            is SourceTreeNode.Group -> {
                                val child = entry.folder
                                val isVirtual = child.category.id.startsWith("domain:")
                                val childExpanded = expandedFolders[child.category.id] == true
                                val groupSourceIds = child.sources.map { it.source.id }.toSet()
                                item(key = "folder-${child.category.id}") {
                                    if (isVirtual) {
                                        DomainGroupHeader(
                                            name = child.category.name,
                                            unreadCount = child.sources.sumOf { it.counts.unread },
                                            expanded = childExpanded,
                                            onToggleExpand = {
                                                expandedFolders[child.category.id] = !childExpanded
                                            },
                                            onMarkAllRead = {
                                                onMarkAllReadInSourceGroup(groupSourceIds, child.category.name)
                                            },
                                            onFilter = {
                                                onSourceGroupClick(groupSourceIds, child.category.name)
                                            },
                                        )
                                    } else {
                                        FolderHeader(
                                            node = child,
                                            unreadCount = child.sources.sumOf { it.counts.unread },
                                            expanded = childExpanded,
                                            onToggleExpand = {
                                                expandedFolders[child.category.id] = !childExpanded
                                            },
                                            onRename = { onRenameCategory(child.category.id, child.category.name) },
                                            onDelete = { onDeleteCategory(child.category.id, child.category.name) },
                                            onAddSource = { onAddSource(child.category.id) },
                                            onMerge = { onMergeCategory(child.category.id, child.category.name) },
                                            onMarkAllRead = {
                                                onMarkAllReadInCategory(child.category.id, child.category.name)
                                            },
                                            onFilter = {
                                                onCategoryClick(setOf(child.category.id), child.category.name)
                                            },
                                            indent = 20,
                                        )
                                    }
                                }
                                if (childExpanded) {
                                    child.sources.forEach { node ->
                                        item(key = "src-${node.source.id}") {
                                            SourceRow(
                                                source = node.source,
                                                counts = node.counts,
                                                selected = selectedSources[node.source.id] == true,
                                                inSelection = inSelection,
                                                onEdit = { onEditSource(node.source) },
                                                onMove = { onMoveSource(node.source) },
                                                onDelete = { onDeleteSource(node.source.id, node.source.title ?: node.source.url) },
                                                onMarkAllRead = {
                                                    onMarkAllReadInSource(node.source.id, node.source.title ?: node.source.url)
                                                },
                                                onClick = {
                                                    if (inSelection) onToggleSelectSource(node.source.id)
                                                    else onSourceClick(node.source.id, node.source.title ?: node.source.url)
                                                },
                                                onLongPress = { onToggleSelectSource(node.source.id) },
                                                indent = 36,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllFeedsRow(onClick: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = palette.Accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "All Feeds",
            style = MaterialTheme.typography.labelLarge,
            color = palette.Accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Divider(color = palette.InkStroke, thickness = 0.5.dp)
}

@Composable
private fun ExploreSourcesRow(onClick: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = palette.Accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Explore",
            style = MaterialTheme.typography.labelLarge,
            color = palette.Accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ReadLaterRow(onClick: () -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.BookmarkBorder,
            contentDescription = null,
            tint = palette.OnInkMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Read Later",
            style = MaterialTheme.typography.labelLarge,
            color = palette.OnInk,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Divider(color = palette.InkStroke, thickness = 0.5.dp)
}

@Composable
private fun FolderHeader(
    node: SourceFolderNode,
    unreadCount: Int,
    expanded: Boolean,
    isVirtual: Boolean = false,
    onToggleExpand: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddSource: () -> Unit,
    onMerge: () -> Unit,
    onMarkAllRead: () -> Unit,
    onFilter: () -> Unit,
    indent: Int = 0,
) {
    val palette = LocalSapphirePalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (4 + indent).dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = palette.OnInkMuted,
                modifier = Modifier.size(20.dp).then(
                    // Rotate 90° CCW when collapsed so the chevron points right.
                    if (!expanded) Modifier.rotate(-90f) else Modifier,
                ),
            )
        }
        Icon(Icons.Filled.Folder, contentDescription = null, tint = palette.OnInkFaint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(2.dp))
        Column(modifier = Modifier.weight(1f).clickable(onClick = onFilter)) {
            Text(
                node.category.name,
                style = if (indent > 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                color = palette.OnInk,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unreadCount > 0) {
            Text(unreadCount.toString(), style = SapphireMono.Label, color = palette.Accent)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onMarkAllRead, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.DoneAll, contentDescription = "Mark all as read", tint = palette.Accent, modifier = Modifier.size(18.dp))
            }
        }
        // Overflow: Add source / Rename / Merge into… / Delete — de-clutters the row.
        // Virtual (domain-group) folders are presentation-only, so they get no menu.
        if (!isVirtual) {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Folder actions", tint = palette.OnInkMuted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Add source") }, onClick = { menuOpen = false; onAddSource() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text("Merge into…") }, onClick = { menuOpen = false; onMerge() })
                DropdownMenuItem(
                    text = { Text("Delete", color = palette.Danger) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
    Divider(color = palette.InkStroke, thickness = 0.5.dp)
}

/**
 * Virtual domain-group sub-folder header. A distinct visual tier between bare L1 folder
 * headers and filled source cards: a subtle accent-tinted rounded container with a globe
 * icon, the domain name in mono, a source-count chip, and a thin accent left rail that
 * visually connects it to its child sources when expanded. No management menu — these are
 * presentation-only groupings computed by observeTree().
 *
 * Visual hierarchy in the drawer:
 *  - L1 folder: bare header, no container (section divider role)
 *  - Domain group: this — accent-tinted card (collection role)
 *  - Source: neutral filled card (leaf role)
 */
@Composable
private fun DomainGroupHeader(
    name: String,
    unreadCount: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onMarkAllRead: () -> Unit,
    onFilter: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.Accent.copy(alpha = 0.07f))
            .clickable(onClick = onFilter)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron toggle.
        IconButton(onClick = onToggleExpand, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = palette.AccentBright,
                modifier = Modifier.size(18.dp).then(
                    if (!expanded) Modifier.rotate(-90f) else Modifier,
                ),
            )
        }
        // Globe icon — signals "domain" not "folder".
        Icon(
            Icons.Filled.Language,
            contentDescription = null,
            tint = palette.AccentBright.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        // Domain name in mono — reads as a hostname, not a human folder name.
        Text(
            name,
            style = SapphireMono.Label,
            color = palette.OnInk,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Unread count + mark-all-read — only when there are unread items.
        if (unreadCount > 0) {
            Spacer(Modifier.width(6.dp))
            Text(unreadCount.toString(), style = SapphireMono.Label, color = palette.AccentBright)
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onMarkAllRead, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.DoneAll, contentDescription = "Mark all as read", tint = palette.AccentBright, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Source row. Inline action buttons are gone — per-source actions live in a context menu
 * (long-press, or swipe right past the threshold). Swipe LEFT past the threshold marks all
 * of the source's items as read (the drawer-wide undo snackbar follows).
 *
 * The swipe threshold is a sixth of the row width — a short, deliberate gesture. The
 * revealed space shows the action's icon + label ("Mark read" / "Menu") so the user sees
 * what releasing will do before they commit.
 *
 * When [inSelection] is true a checkbox replaces the feed icon and tap toggles selection;
 * swipes are disabled. The context menu's "Select" entry is the way into batch mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRow(
    source: Source,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onMarkAllRead: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    counts: com.sapphire.domain.source.SourceCounts? = null,
    selected: Boolean = false,
    inSelection: Boolean = false,
    indent: Int = 0,
) {
    val palette = LocalSapphirePalette.current
    val title = source.title ?: source.url
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    var menuOpen by remember { mutableStateOf(false) }

    // An eighth of the row width, measured once laid out. Falls back to a fixed px value
    // until the first measurement so the gesture still works before composition settles.
    var rowWidthPx by remember { mutableFloatStateOf(560f) }
    val threshold = (rowWidthPx / 8f).coerceAtLeast(48f)
    val maxOffset = threshold

    val animatedOffset by animateDpAsState(
        targetValue = if (swipeOffset == 0f) 0.dp else swipeOffset.dp,
        label = "swipe-snap",
    )

    // Progress of the swipe toward its threshold — drives the reveal alpha (0..1, clamped).
    val leftProgress = if (swipeOffset < 0f) (-swipeOffset / threshold).coerceIn(0f, 1f) else 0f
    val rightProgress = if (swipeOffset > 0f) (swipeOffset / threshold).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .padding(start = (40 + indent).dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            .onSizeChanged { rowWidthPx = it.width.toFloat() },
    ) {
        // Action surfaces revealed by swipe. The row slides toward the swipe direction,
        // so the label sits in the gap that opens on the OPPOSITE side:
        //  - swipe left (mark read)  → row moves left   → gap + label on the RIGHT
        //  - swipe right (menu)      → row moves right  → gap + label on the LEFT
        if (leftProgress > 0f) {
            SwipeActionSurface(
                background = palette.Accent.copy(alpha = 0.28f * leftProgress),
                tint = palette.Accent,
                icon = Icons.Filled.DoneAll,
                label = "Mark read",
                contentAlignment = Alignment.CenterEnd,
                labelFirst = false,
            )
        }
        if (rightProgress > 0f) {
            SwipeActionSurface(
                background = palette.InkRaised.copy(alpha = 0.30f * rightProgress),
                tint = palette.OnInkMuted,
                icon = Icons.Filled.MoreVert,
                label = "Menu",
                contentAlignment = Alignment.CenterStart,
                labelFirst = true,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = if (swipeOffset != 0f) swipeOffset.dp else animatedOffset)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.InkElevated)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (!inSelection) menuOpen = true else onLongPress() },
                )
                .pointerInput(source.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                swipeOffset <= -threshold -> onMarkAllRead()
                                swipeOffset >= threshold -> menuOpen = true
                            }
                            swipeOffset = 0f
                        },
                    ) { _, dragAmount ->
                        if (!inSelection) {
                            swipeOffset = (swipeOffset + dragAmount).coerceIn(-maxOffset, maxOffset)
                        }
                    }
                }
                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inSelection) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
            } else {
                Icon(
                    Icons.Outlined.RssFeed,
                    contentDescription = null,
                    tint = palette.OnInkMuted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = palette.OnInk,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (source.healthState == com.sapphire.domain.model.HealthState.FAILED) {
                Box(Modifier.size(5.dp).clip(androidx.compose.foundation.shape.CircleShape).background(palette.Danger))
                Spacer(Modifier.width(4.dp))
            }
            if (!inSelection && counts != null && counts.unread > 0) {
                Text(formatCounts(counts), style = SapphireMono.Label, color = palette.Accent)
                Spacer(Modifier.width(4.dp))
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Mark all as read") },
                onClick = { menuOpen = false; onMarkAllRead() },
            )
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = { menuOpen = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text("Move") },
                onClick = { menuOpen = false; onMove() },
            )
            DropdownMenuItem(
                text = { Text("Select") },
                onClick = { menuOpen = false; onLongPress() },
            )
            DropdownMenuItem(
                text = { Text("Remove", color = palette.Danger) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

/**
 * The colored surface revealed behind a row as it's swiped. Shows the action's icon plus a
 * short label so the user can see what releasing will commit. The caller pre-bakes the
 * background alpha from the swipe progress; declared as a [BoxScope] extension so it can
 * [BoxScope.matchParentSize] against the row [Box].
 */
@Composable
private fun BoxScope.SwipeActionSurface(
    background: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentAlignment: Alignment,
    labelFirst: Boolean,
) {
    Box(
        Modifier
            .matchParentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(background),
        contentAlignment = contentAlignment,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            if (labelFirst) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
                Text(label, style = SapphireMono.Label, color = tint)
            } else {
                Text(label, style = SapphireMono.Label, color = tint)
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ---- Dialogs ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSourceDialog(
    source: Source,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, kind: SourceKind) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    var title by rememberSaveable { mutableStateOf(source.title ?: "") }
    var url by rememberSaveable { mutableStateOf(source.url) }
    var kind by remember { mutableStateOf(source.kind) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.InkElevated,
        titleContentColor = palette.OnInk,
        title = { Text("Edit source", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                DrawerTextField(title, "Title") { title = it }
                Spacer(Modifier.height(8.dp))
                DrawerTextField(url, "URL") { url = it }
                Spacer(Modifier.height(8.dp))
                KindPicker(kind) { kind = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, url, kind) }) {
                Text("Save", color = palette.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = palette.OnInkMuted) } },
    )
}

@Composable
private fun MoveSourceDialog(
    source: Source?,
    tree: List<SourceFolderNode>,
    onDismiss: () -> Unit,
    onMove: (targetCategoryId: String) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.InkElevated,
        title = {
            val t = source?.let { it.title ?: it.url }
            Text(if (t != null) "Move \"$t\"" else "Move selected", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                tree.forEach { folder ->
                    item(key = "move-${folder.category.id}") {
                        CategoryPickerRow(
                            label = folder.category.name,
                            categoryId = folder.category.id,
                            currentId = source?.categoryId ?: "",
                            onPick = onMove,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = palette.OnInkMuted) } },
    )
}

/**
 * Folder merge picker: choose a target folder to merge [fromId] into. Excludes the source
 * folder itself and its descendants (can't merge into self/child). Lists L1 folders and
 * their L2 children as targets.
 */
@Composable
private fun MergeCategoryDialog(
    fromId: String,
    fromName: String,
    tree: List<SourceFolderNode>,
    onDismiss: () -> Unit,
    onMerge: (targetCategoryId: String) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.InkElevated,
        title = { Text("Merge \"$fromName\" into", style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                // Only L1 folders are valid merge targets — L2 domain groups are
                // presentation-only and recompute automatically after the move.
                tree.forEach { folder ->
                    if (folder.category.id != fromId) {
                        item(key = "merge-${folder.category.id}") {
                            CategoryPickerRow(
                                label = folder.category.name,
                                categoryId = folder.category.id,
                                currentId = fromId,
                                onPick = onMerge,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = palette.OnInkMuted) } },
    )
}

@Composable
private fun CategoryPickerRow(
    label: String,
    categoryId: String,
    currentId: String,
    onPick: (String) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val isCurrent = categoryId == currentId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isCurrent) { onPick(categoryId) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isCurrent -> palette.OnInkFaint
                else -> palette.OnInk
            },
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Icon(Icons.Filled.Check, contentDescription = "Current", tint = palette.Accent, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onAdd: (title: String, url: String, kind: SourceKind) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    var title by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var kind by remember { mutableStateOf(SourceKind.RSS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.InkElevated,
        title = { Text("Add source", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                DrawerTextField(title, "Title") { title = it }
                Spacer(Modifier.height(8.dp))
                DrawerTextField(url, "URL") { url = it }
                Spacer(Modifier.height(8.dp))
                KindPicker(kind) { kind = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(title, url, kind) }, enabled = title.isNotBlank() && url.isNotBlank()) {
                Text("Add", color = palette.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = palette.OnInkMuted) } },
    )
}

@Composable
private fun RenameCategoryDialog(
    initial: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.InkElevated,
        title = { Text("Rename folder", style = MaterialTheme.typography.titleMedium) },
        text = { DrawerTextField(name, "Name") { name = it } },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text("Save", color = palette.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = palette.OnInkMuted) } },
    )
}

@Composable
private fun AddTopLevelFolderDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.InkElevated,
        title = { Text("New folder", style = MaterialTheme.typography.titleMedium) },
        text = { DrawerTextField(name, "Name") { name = it } },
        confirmButton = {
            TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank()) {
                Text("Create", color = palette.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = palette.OnInkMuted) } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.InkElevated,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = palette.OnInkMuted) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = palette.Danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = palette.OnInkMuted) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerTextField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = palette.InkRaised,
            unfocusedContainerColor = palette.InkRaised,
            focusedIndicatorColor = palette.Accent,
            unfocusedIndicatorColor = palette.InkStroke,
            focusedTextColor = palette.OnInk,
            unfocusedTextColor = palette.OnInk,
            focusedLabelColor = palette.Accent,
            unfocusedLabelColor = palette.OnInkFaint,
            cursorColor = palette.Accent,
        ),
    )
}

@Composable
private fun KindPicker(selected: SourceKind, onSelect: (SourceKind) -> Unit) {
    val palette = LocalSapphirePalette.current
    val editable = listOf(SourceKind.RSS, SourceKind.ATOM, SourceKind.JSON, SourceKind.RSSHUB)
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(palette.InkRaised)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected.name,
                style = SapphireMono.Label,
                color = palette.OnInk,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.Check, contentDescription = null, tint = palette.OnInkMuted, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            editable.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.name) },
                    onClick = { onSelect(k); open = false },
                )
            }
        }
    }
}

/** Unread count compact label per source, e.g. "3". Callers hide it when zero. */
private fun formatCounts(counts: com.sapphire.domain.source.SourceCounts): String =
    counts.unread.toString()
