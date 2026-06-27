package com.sapphire.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.ui.design.PlatformLabels
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import java.text.DateFormat
import java.util.Date

/**
 * S07 Saved Later repository (PRD §3.4 `[📁 Save Later]`). Lists items promoted from the
 * reader, newest-save first. Each row shows the title, author/platform, save folder, and
 * a remove (unsave) action. Items here survive the 30-day retention purge (architecture §7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedItemsScreen(
    viewModel: SavedItemsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val palette = LocalSapphirePalette.current
    val items by viewModel.items.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = palette.Ink,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "SAVED LATER",
                        style = SapphireMono.Label,
                        color = palette.OnInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = palette.OnInkMuted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.Ink),
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptySaved(padding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp,
                ),
            ) {
                items(items, key = { it.itemId }) { item ->
                    SavedRow(item = item, onUnsave = viewModel::unsave)
                }
            }
        }
    }
}

@Composable
private fun SavedRow(
    item: com.sapphire.domain.model.SavedItemDetails,
    onUnsave: (String) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.InkElevated)
            .border(1.dp, palette.InkStroke, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.title,
                style = SapphireMono.Body,
                color = palette.OnInk,
                fontWeight = FontWeight.SemiBold,
            )
            val meta = buildString {
                item.authorHandle?.takeIf { it.isNotBlank() }?.let { append(it) }
                item.platformTag?.takeIf { it.isNotBlank() }?.let {
                    val label = PlatformLabels.forTag(it)
                    if (isEmpty()) append(label) else append(" · $label")
                }
            }
            if (meta.isNotBlank()) {
            Text(meta, style = SapphireMono.Label, color = palette.OnInkMuted)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderChip(item.folder)
                Text(
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.savedAt)),
                    style = SapphireMono.Label,
                    color = palette.OnInkFaint,
                )
            }
        }
        IconButton(onClick = { onUnsave(item.itemId) }) {
            Icon(
                Icons.Filled.BookmarkRemove,
                contentDescription = "Remove from saved",
                tint = palette.OnInkFaint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun FolderChip(folder: String) {
    val palette = LocalSapphirePalette.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(palette.InkRaised)
            .border(1.dp, palette.InkStroke, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            folder,
            style = SapphireMono.Label,
            color = palette.AccentBright,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptySaved(padding: PaddingValues) {
    val palette = LocalSapphirePalette.current
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Nothing saved yet",
                style = SapphireMono.Body,
                color = palette.OnInkMuted,
            )
            Text(
                "Tap Save in the reader to keep an item past the 30-day purge.",
                style = SapphireMono.Label,
                color = palette.OnInkFaint,
            )
        }
    }
}
